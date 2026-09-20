/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.dsh.settings;

import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshPortMode;
import org.jackhuang.hmcl.dsh.DshPorts;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import javafx.scene.image.Image;
import org.jackhuang.hmcl.ui.construct.LineComponent;
import org.jackhuang.hmcl.dsh.DshInstanceIcons;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.dsh.InstanceIconDialog;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.NumberValidator;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;


import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The settings tab of an instance's management page.
///
/// Only the settings that exist for a DeepSeek Harness instance are offered.
/// The port is the one with real consequences: the browser interface keys
/// session state by origin, so an instance must keep one port for its whole
/// life, and two instances sharing a history through different ports would let
/// two writers corrupt it.
@NotNullByDefault
public final class InstanceSettingsPage extends ScrollPane {
    /// The instance being edited.
    private final DshInstance instance;

    /// Called after a change is written back.
    private final Runnable onChanged;

    /// Creates the settings tab.
    ///
    /// @param instance  the instance being edited
    /// @param onChanged run after a successful write
    public InstanceSettingsPage(DshInstance instance, Runnable onChanged) {
        this.instance = instance;
        this.onChanged = onChanged;

        setFitToWidth(true);

        ComponentList list = new ComponentList();

        LineTextPane iconHeader = new LineTextPane();
        iconHeader.setTitle(i18n("dsh.instance.icon"));
        iconHeader.getStyleClass().add("section-header");
        list.getContent().add(iconHeader);
        list.getContent().add(buildIconRow());

        LineTextPane portHeader = new LineTextPane();
        portHeader.setTitle(i18n("dsh.instance.port"));
        portHeader.getStyleClass().add("section-header");
        list.getContent().add(portHeader);

        list.getContent().add(buildPortModeRow());
        list.getContent().add(buildPortRow());
        list.getContent().add(buildPortNote());

        VBox root = new VBox(list);
        root.setPadding(new Insets(10));
        setContent(root);

        // Must run after the content is installed: smooth scrolling binds to the
        // content node and throws on a null content.
        FXUtils.smoothScrolling(this);
    }

    /// Builds the instance icon row.
    ///
    /// The mechanism is HMCL's: the row shows the current icon and opens a
    /// dialog of tiles, because the choice is visual and a list of names would
    /// make the user read what they are meant to recognise.
    ///
    /// @return the row
    private LineButton buildIconRow() {
        LineButton row = new LineButton();
        row.setTitle(i18n("dsh.instance.icon"));
        row.setSubtitle(i18n("dsh.instance.icon.hint"));
        applyLeadingIcon(row, instance);
        row.setOnAction(event -> Controllers.dialog(new InstanceIconDialog(instance, () -> {
            if (onChanged != null) {
                onChanged.run();
            }
        })));
        return row;
    }

    /// Shows an instance's icon as a row's leading graphic.
    ///
    /// @param row      the row to decorate
    /// @param instance the instance whose icon to show
    private static void applyLeadingIcon(LineComponent row, DshInstance instance) {
        Image image = DshInstanceIcons.load(instance);
        if (image != null) {
            row.setLeading(image, 24);
        }
    }

    /// Builds the automatic-versus-fixed choice.
    ///
    /// @return the row
    private LineSelectButton<DshPortMode> buildPortModeRow() {
        LineSelectButton<DshPortMode> row = new LineSelectButton<>();
        row.setTitle(i18n("dsh.instance.port.mode"));
        row.setSubtitle(i18n("dsh.instance.port.mode.hint"));
        row.setItems(DshPortMode.AUTO, DshPortMode.FIXED);
        row.setValue(instance.portModeOrDefault());
        // The control runs its converter the moment one is installed, before a
        // value need exist, so the null-safe form is required here.
        row.setNullSafeConverter(mode -> i18n("dsh.instance.port.mode." + mode.id()));
        row.valueProperty().addListener((observable, was, mode) -> {
            if (mode != null && mode != was) {
                write(instance.withPortPolicy(mode, instance.port()));
            }
        });
        return row;
    }

    /// Builds the fixed-port entry.
    ///
    /// @return the row
    private LineTextPane buildPortRow() {
        boolean fixed = instance.portModeOrDefault() == DshPortMode.FIXED;

        LineTextPane row = new LineTextPane();
        row.setTitle(i18n("dsh.instance.port.fixed"));
        row.setSubtitle(fixed
                ? i18n("dsh.instance.port.fixed.hint")
                : i18n("dsh.instance.port.auto.current", portDescription()));

        // Only editable in fixed mode; in automatic mode the launcher decides.
        var field = new com.jfoenix.controls.JFXTextField();
        field.setPrefWidth(80);
        field.setDisable(!fixed);
        field.setText(fixed ? Integer.toString(instance.portOrDefault()) : "");
        field.setValidators(new NumberValidator(i18n("dsh.instance.port.invalid"), false));
        field.setOnAction(event -> applyPort(field.getText()));
        field.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                applyPort(field.getText());
            }
        });
        row.setRowTrailing(field);
        return row;
    }

    /// Describes the port an automatic instance is currently bound to.
    ///
    /// @return the port as text, or a note that none has been chosen yet
    private String portDescription() {
        int port = instance.portOrDefault();
        return port > 0 ? Integer.toString(port) : i18n("dsh.instance.port.auto.none");
    }

    /// Writes a typed port back.
    ///
    /// @param text the field text
    private void applyPort(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Integer port;
        try {
            port = Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return;
        }
        String complaint = DshPorts.validate(port);
        if (complaint != null) {
            Controllers.dialog(complaint, i18n("dsh.instance.port.invalid"), MessageType.ERROR);
            return;
        }
        if (port == instance.portOrDefault()) {
            return;
        }
        write(instance.withPortPolicy(DshPortMode.FIXED, port));
    }

    /// Explains what the two modes do.
    ///
    /// @return the note row
    private LineTextPane buildPortNote() {
        LineTextPane note = new LineTextPane();
        note.setText(i18n("dsh.instance.port.note"));
        return note;
    }

    /// Persists a modified instance and notifies the caller.
    ///
    /// @param updated the instance to store
    private void write(DshInstance updated) {
        try {
            DshInstanceManager.update(updated);
            if (onChanged != null) {
                onChanged.run();
            }
        } catch (DshException e) {
            LOG.warning("Failed to save instance settings", e);
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
        }
    }
}
