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
package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshInstanceIcon;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The icon chooser for an instance.
///
/// This mirrors HMCL's `GameInstanceIconDialog`: a row of square tiles that
/// apply on click, preceded by an add tile that picks an image file. A dialog of
/// tiles is what the original uses because the choice is visual — a dropdown of
/// names would make the user read what they are meant to recognise.
@NotNullByDefault
public final class InstanceIconDialog extends JFXDialogLayout {
    /// The instance being edited.
    private final DshInstance instance;

    /// Called after a successful change.
    private final Runnable onFinish;

    /// The tile marked as the pending choice.
    private RipplerContainer marked;

    /// The pending built-in icon, or `null` when a file is pending.
    private @Nullable DshInstanceIcon pendingIcon;

    /// The pending icon file, or `null` when a built-in icon is pending.
    private @Nullable Path pendingFile;

    /// Creates the dialog.
    ///
    /// @param instance the instance being edited
    /// @param onFinish run after the icon changes
    public InstanceIconDialog(DshInstance instance, Runnable onFinish) {
        this.instance = instance;
        this.onFinish = onFinish;

        setHeading(new Label(i18n("dsh.instance.icon")));

        FlowPane tiles = new FlowPane();
        tiles.setHgap(4);
        tiles.setVgap(4);

        tiles.getChildren().add(buildCustomTile());
        // DEFAULT shares GRASS's artwork, so the chooser starts at GRASS and the
        // duplicate would only be a second tile with the same picture.
        for (DshInstanceIcon icon : DshInstanceIcon.values()) {
            if (icon == DshInstanceIcon.DEFAULT) {
                continue;
            }
            Image image = icon.load();
            if (image != null) {
                tiles.getChildren().add(buildTile(image, icon));
            }
        }
        setBody(tiles);

        JFXButton confirm = new JFXButton(i18n("button.ok"));
        confirm.getStyleClass().add("dialog-accept");
        confirm.setOnAction(event -> apply());

        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.getStyleClass().add("dialog-cancel");
        cancel.setOnAction(event -> fireEvent(new DialogCloseEvent()));
        onEscPressed(this, cancel::fire);

        setActions(confirm, cancel);
    }

    /// Builds the tile that picks an image file.
    ///
    /// @return the tile
    private Node buildCustomTile() {
        Node shape = SVG.ADD_CIRCLE.createIcon(32);
        shape.setMouseTransparent(true);

        RipplerContainer container = new RipplerContainer(shape);
        FXUtils.setLimitWidth(container, 36);
        FXUtils.setLimitHeight(container, 36);
        FXUtils.installFastTooltip(container, i18n("dsh.instance.icon.choose_file"));
        FXUtils.onClicked(container, () -> chooseFile(container));
        return container;
    }

    /// Builds one tile for a built-in icon.
    ///
    /// @param image the image to show
    /// @param icon  the icon the tile represents
    /// @return the tile
    private Node buildTile(Image image, DshInstanceIcon icon) {
        RipplerContainer container = buildTile(image);
        if (instance.iconFileOrDefault() == null && instance.iconOrDefault() == icon) {
            mark(container);
        }
        FXUtils.onClicked(container, () -> {
            mark(container);
            pendingIcon = icon;
            pendingFile = null;
        });
        return container;
    }

    /// Builds a tile frame around an image.
    ///
    /// @param image the image to show
    /// @return the tile
    private RipplerContainer buildTile(Image image) {
        ImageView view = new ImageView(image);
        view.setFitWidth(32);
        view.setFitHeight(32);
        view.setPreserveRatio(true);
        view.setMouseTransparent(true);

        RipplerContainer container = new RipplerContainer(view);
        FXUtils.setLimitWidth(container, 36);
        FXUtils.setLimitHeight(container, 36);
        return container;
    }

    /// Marks a tile as the pending choice, clearing the previous mark.
    ///
    /// @param container the tile to mark
    private void mark(RipplerContainer container) {
        if (marked != null) {
            marked.getStyleClass().remove("icon-tile-selected");
        }
        marked = container;
        if (!container.getStyleClass().contains("icon-tile-selected")) {
            container.getStyleClass().add("icon-tile-selected");
        }
    }

    /// Asks for an image file and marks it as the pending choice.
    ///
    /// @param container the add tile, marked so the choice is visible
    private void chooseFile(RipplerContainer container) {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(FXUtils.getImageExtensionFilter());
        Path selected = Controllers.showOpenDialog(chooser);
        if (selected == null) {
            return;
        }
        mark(container);
        pendingIcon = null;
        pendingFile = selected;
    }

    /// Writes the pending choice back to the instance.
    private void apply() {
        if (pendingIcon == null && pendingFile == null) {
            fireEvent(new DialogCloseEvent());
            return;
        }
        try {
            DshInstance updated = pendingFile != null
                    ? instance.withIconFile(pendingFile)
                    : instance.withIcon(pendingIcon == null ? DshInstanceIcon.DEFAULT : pendingIcon).withNoIconFile();
            DshInstanceManager.update(updated);
            onFinish.run();
            fireEvent(new DialogCloseEvent());
        } catch (DshException e) {
            LOG.warning("Failed to set the instance icon", e);
            Controllers.dialog(e.getMessage(), i18n("message.error"),
                    org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.ERROR);
        }
    }
}
