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
import java.util.List;
import java.util.Locale;
import java.nio.file.Path;
import java.util.ArrayList;
import org.jackhuang.hmcl.dsh.NodeRuntimeManager;
import org.jackhuang.hmcl.dsh.NodeRuntime;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshInstanceSettings;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshPortMode;
import org.jackhuang.hmcl.dsh.DshPorts;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.dsh.VersionPickerPage;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.FXUtils;
import javafx.scene.image.Image;
import org.jackhuang.hmcl.dsh.DshInstanceIcons;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.dsh.InstanceIconDialog;
import org.jackhuang.hmcl.ui.construct.ImagePickerItem;
import org.jackhuang.hmcl.dsh.DshInstanceIcon;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.NumberValidator;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;


import static org.jackhuang.hmcl.setting.SettingsManager.settings;
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
    /// The instance being edited, refreshed from storage after every write.
    private DshInstance instance;

    /// The icon row, kept so its image can follow a change.
    private final ImagePickerItem iconRow = new ImagePickerItem();

    /// The port row, kept so its hint can follow the mode.
    private final LineTextPane portRow = new LineTextPane();

    /// The port number field, kept so it can be offered or taken away with the mode.
    private final com.jfoenix.controls.JFXTextField portField = new com.jfoenix.controls.JFXTextField();

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
        setFitToHeight(true);

        ComponentList iconList = new ComponentList();
        iconList.getContent().add(buildIconRow());

        ComponentList environmentList = new ComponentList();
        environmentList.getContent().add(buildNodeRuntimeRow());
        environmentList.getContent().add(buildHomeModeRow());

        ComponentList portList = new ComponentList();
        portList.getContent().add(buildPortModeRow());
        portList.getContent().add(buildPortRow());

        // The original's card list: the padding and the spacing between sections
        // come from the class rather than from numbers chosen here, so a page
        // built from the same parts sits the same way.
        // What version of DeepSeek Harness the instance runs is not here: choosing
        // it installs a runtime, and the auto-install page is where the original
        // offers that — beside the boot library and the plugins, which are the
        // same kind of thing.
        VBox root = new VBox(
                ComponentList.createComponentListTitle(i18n("dsh.instance.icon")), iconList,
                ComponentList.createComponentListTitle(i18n("dsh.settings.environment")), environmentList,
                ComponentList.createComponentListTitle(i18n("dsh.instance.port")), portList,
                ComponentList.createComponentListTitle(i18n("dsh.settings.build_scripts")), buildScriptsList());
        root.getStyleClass().add("card-list");
        setContent(root);

        // Must run after the content is installed: smooth scrolling binds to the
        // content node and throws on a null content.
        FXUtils.smoothScrolling(this);
    }

    /// Builds the row about install scripts.
    ///
    /// The same three answers the launcher offers, plus following it, which is what
    /// an instance starts at: the launcher's answer is the default, and an instance
    /// states its own only when it has a reason to.
    ///
    /// @return the list
    private ComponentList buildScriptsList() {
        // The entries are the policies with a null in front, which is what
        // "follow the launcher" is.
        java.util.List<org.jackhuang.hmcl.dsh.DshBuildScriptPolicy> choices = new java.util.ArrayList<>();
        choices.add(null);
        choices.addAll(java.util.List.of(org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.AUTO,
                org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.MANUAL,
                org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.NEVER));

        LineSelectButton<org.jackhuang.hmcl.dsh.DshBuildScriptPolicy> row = new LineSelectButton<>();
        row.setTitle(i18n("dsh.settings.build_scripts.approve"));
        row.setItems(choices);
        row.setConverter(policy -> i18n(policy == null
                ? "dsh.settings.build_scripts.follow"
                : "dsh.settings.build_scripts." + policy.id()));
        row.setValue(org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.of(
                DshInstanceSettings.buildScriptPolicy(instance)));
        row.valueProperty().addListener((observable, was, value) -> {
            try {
                DshInstanceSettings.setBuildScriptPolicy(instance,
                        value == null ? null : value.id());
            } catch (DshException e) {
                LOG.warning("Failed to store the build script policy", e);
            }
        });

        ComponentList list = new ComponentList();
        list.getContent().add(row);
        return list;
    }

    /// Builds the Node runtime row.
    ///
    /// The first entry follows the launcher, which is what HMCL's game settings
    /// offer for Java: an instance states its own only when it has a reason to.
    ///
    /// @return the row
    private LineSelectButton<String> buildNodeRuntimeRow() {
        List<String> choices = new ArrayList<>();
        choices.add(DshNodeRuntime.GLOBAL);
        choices.add(DshNodeRuntime.SYSTEM);
        for (NodeRuntime runtime : NodeRuntimeManager.listInstalled()) {
            choices.add(runtime.version());
        }

        LineSelectButton<String> row = new LineSelectButton<>();
        row.setTitle(i18n("dsh.node.title"));
        row.setItems(choices);
        row.setNullSafeConverter(selection -> {
            if (DshNodeRuntime.GLOBAL.equals(selection)) {
                return i18n("dsh.instance.follow_global") + " (" + describeGlobalRuntime() + ")";
            }
            return DshNodeRuntime.SYSTEM.equals(selection)
                    ? i18n("dsh.install.node.system")
                    : selection;
        });
        row.setValue(instance.nodeRuntime() == null ? DshNodeRuntime.GLOBAL : instance.nodeRuntime());
        row.valueProperty().addListener((observable, was, value) -> {
            if (value != null && !value.equals(was)) {
                write(instance.withNodeRuntime(DshNodeRuntime.GLOBAL.equals(value) ? null : value));
            }
        });
        return row;
    }

    /// Describes what following the launcher currently resolves to.
    ///
    /// @return the runtime the launcher is set to
    private String describeGlobalRuntime() {
        String value = settings().defaultNodeRuntimeProperty().get();
        return DshNodeRuntime.SYSTEM.equals(value) ? i18n("dsh.install.node.system") : value;
    }

    /// Builds the DSH_HOME policy row.
    ///
    /// @return the row
    private LineSelectButton<DshHomeMode> buildHomeModeRow() {
        LineSelectButton<DshHomeMode> row = new LineSelectButton<>();
        row.setTitle(i18n("dsh.install.home"));
        row.setSubtitle(i18n("dsh.instance.home.hint"));
        row.setItems(DshHomeMode.GLOBAL, DshHomeMode.ISOLATED, DshHomeMode.VERSION_SHARED, DshHomeMode.CUSTOM);
        row.setNullSafeConverter(mode -> DshHomeMode.GLOBAL.equals(mode)
                ? i18n("dsh.instance.follow_global") + " ("
                        + i18n("dsh.instance.home."
                                + settings().defaultHomeModeProperty().get().name().toLowerCase(Locale.ROOT)) + ")"
                : i18n("dsh.instance.home." + mode.name().toLowerCase(Locale.ROOT)));
        row.setValue(instance.homeMode());
        row.valueProperty().addListener((observable, was, mode) -> {
            if (mode == null || mode == was) {
                return;
            }
            if (mode == DshHomeMode.CUSTOM) {
                javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
                chooser.setTitle(i18n("dsh.install.home"));
                Path chosen = Controllers.showDialog(chooser);
                if (chosen == null) {
                    row.setValue(was);
                    return;
                }
                write(instance.withHome(DshHomeMode.CUSTOM, chosen));
            } else {
                write(instance.withHome(mode, null));
            }
        });
        return row;
    }

    /// Builds the instance icon row.
    ///
    /// HMCL's icon row is an `ImagePickerItem`: the image sits on the trailing
    /// edge with an edit button beside it and a reset button after that, rather
    /// than the whole row being clickable. The distinction matters because the
    /// row is a setting like any other — the controls say what can be done to
    /// it — and because resetting to the default has to be reachable without
    /// opening the chooser.
    ///
    /// @return the row
    private ImagePickerItem buildIconRow() {
        iconRow.setTitle(i18n("dsh.instance.icon"));
        iconRow.setImage(DshInstanceIcons.load(instance));
        iconRow.setOnSelectButtonClicked(event -> Controllers.dialog(
                new InstanceIconDialog(instance, this::reloadFromStorage)));
        iconRow.setOnDeleteButtonClicked(event -> resetIcon());
        return iconRow;
    }

    /// Re-reads the instance after a change made outside this pane.
    ///
    /// The icon chooser writes to storage itself, so the pane cannot rely on
    /// having produced the change and has to pick it up afterwards.
    private void reloadFromStorage() {
        DshInstance stored = DshInstanceManager.find(instance.id());
        if (stored != null) {
            instance = stored;
        }
        iconRow.setImage(DshInstanceIcons.load(instance));
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /// Restores the default icon.
    private void resetIcon() {
        write(instance.withIcon(DshInstanceIcon.DEFAULT).withNoIconFile());
    }

    /// Builds the automatic-versus-fixed choice.
    ///
    /// @return the row
    private LineSelectButton<DshPortMode> buildPortModeRow() {
        LineSelectButton<DshPortMode> row = new LineSelectButton<>();
        row.setTitle(i18n("dsh.instance.port.mode"));
        row.setSubtitle(i18n("dsh.instance.port.mode.hint"));
        row.setItems(DshPortMode.GLOBAL, DshPortMode.AUTO, DshPortMode.FIXED);
        // What it follows is the launcher's policy, and an instance that has not
        // chosen one shows that rather than the policy it happens to resolve to.
        row.setValue(instance.hasOwnPortMode() ? instance.portMode() : DshPortMode.GLOBAL);
        // The control runs its converter the moment one is installed, before a
        // value need exist, so the null-safe form is required here.
        row.setNullSafeConverter(mode -> i18n("dsh.instance.port.mode." + mode.id()));
        row.valueProperty().addListener((observable, was, mode) -> {
            if (mode != null && mode != was) {
                write(withPortMode(mode));
            }
        });
        return row;
    }

    /// Returns the instance under a new port policy.
    ///
    /// An instance that has no port yet is given one here, so that choosing to
    /// name the port offers a number the instance can actually use instead of a
    /// zero. That is what makes the reserved port the starting point of the
    /// choice rather than something to look up elsewhere.
    ///
    /// @param mode the policy to record
    /// @return the instance to store
    private DshInstance withPortMode(DshPortMode mode) {
        if (instance.portOrDefault() > 0) {
            return instance.withPortPolicy(mode, instance.port());
        }
        try {
            return instance.withPortPolicy(mode, DshPorts.reserve(instance.id()));
        } catch (DshException e) {
            LOG.warning("Could not reserve a port for " + instance.id(), e);
            return instance.withPortPolicy(mode, instance.port());
        }
    }

    /// Builds the fixed-port entry.
    ///
    /// The field and the hint are drawn from the stored instance rather than
    /// decided once here, because the control is built before the user has
    /// chosen a mode and is never built again: deciding here is what left the
    /// field disabled after the mode had already been switched to a fixed port,
    /// with nothing to type the port into.
    ///
    /// @return the row
    private LineTextPane buildPortRow() {
        portRow.setTitle(i18n("dsh.instance.port.fixed"));

        portField.setPrefWidth(80);
        portField.setValidators(new NumberValidator(i18n("dsh.instance.port.invalid"), false));
        portField.setOnAction(event -> applyPort(portField.getText()));
        portField.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                applyPort(portField.getText());
            }
        });
        portRow.setRowTrailing(portField);

        syncPortRow();
        return portRow;
    }

    /// Draws the port row from the stored instance.
    ///
    /// Called after every write, so that switching the mode and naming a port
    /// take effect on the control the user is looking at.
    private void syncPortRow() {
        boolean fixed = instance.hasOwnPortMode() && instance.portMode() == DshPortMode.FIXED;
        portRow.setSubtitle(fixed
                ? i18n("dsh.instance.port.fixed.hint")
                : i18n("dsh.instance.port.auto.current", portDescription()));

        // Only editable in fixed mode; in automatic mode the launcher decides.
        portField.setDisable(!fixed);
        portField.setText(fixed ? Integer.toString(instance.portOrDefault()) : "");
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

    /// Persists a modified instance and notifies the caller.
    ///
    /// @param updated the instance to store
    private void write(DshInstance updated) {
        try {
            DshInstanceManager.update(updated);
            // Re-read rather than trusting the copy passed in: the stored
            // instance is what the rest of the interface will show.
            DshInstance stored = DshInstanceManager.find(updated.id());
            if (stored != null) {
                instance = stored;
            }
            iconRow.setImage(DshInstanceIcons.load(instance));
            syncPortRow();
            if (onChanged != null) {
                onChanged.run();
            }
        } catch (DshException e) {
            LOG.warning("Failed to save instance settings", e);
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
        }
    }
}
