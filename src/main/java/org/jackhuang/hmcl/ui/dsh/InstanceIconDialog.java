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
        for (DshInstanceIcon icon : DshInstanceIcon.values()) {
            Image image = icon.load();
            if (image != null) {
                tiles.getChildren().add(buildTile(image, true, () -> apply(icon, null)));
            }
        }
        setBody(tiles);

        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.getStyleClass().add("dialog-cancel");
        cancel.setOnAction(event -> fireEvent(new DialogCloseEvent()));
        onEscPressed(this, cancel::fire);
        setActions(cancel);
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
        FXUtils.onClicked(container, this::chooseFile);
        return container;
    }

    /// Builds one tile.
    ///
    /// @param image   the image to show
    /// @param selected whether this tile is the current choice
    /// @param action  the action to run on click
    /// @return the tile
    private Node buildTile(Image image, boolean selected, Runnable action) {
        ImageView view = new ImageView(image);
        view.setFitWidth(32);
        view.setFitHeight(32);
        view.setPreserveRatio(true);
        view.setMouseTransparent(true);

        RipplerContainer container = new RipplerContainer(view);
        FXUtils.setLimitWidth(container, 36);
        FXUtils.setLimitHeight(container, 36);
        if (selected) {
            container.getStyleClass().add("icon-tile-selected");
        }
        FXUtils.onClicked(container, action);
        return container;
    }

    /// Asks for an image file and uses it as the instance icon.
    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(FXUtils.getImageExtensionFilter());
        Path selected = Controllers.showOpenDialog(chooser);
        if (selected == null) {
            return;
        }
        apply(null, selected);
    }

    /// Writes the choice back to the instance.
    ///
    /// @param icon the chosen built-in icon, or `null` when a file was chosen
    /// @param file the chosen file, or `null` when a built-in icon was chosen
    private void apply(@Nullable DshInstanceIcon icon, @Nullable Path file) {
        try {
            DshInstance updated = file != null
                    ? instance.withIconFile(file)
                    : instance.withIcon(icon == null ? DshInstanceIcon.DEFAULT : icon).withNoIconFile();
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
