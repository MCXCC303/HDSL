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

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.skin.DefaultSkin;
import org.jackhuang.hmcl.dsh.skin.DshSkin;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LinePane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.dsh.skin.SkinCanvas;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Chooses the skin.
///
/// Four parts, which is the original's arrangement and worth keeping: the model turning on the
/// left, the ways of getting a skin listed down the middle, what the chosen way needs on the right,
/// and the buttons that end it at the foot. The model is the reason the dialog is worth its size —
/// a skin is painted onto a body, and a skin whose face is upside down or whose second layer is
/// transparent in the wrong place looks perfectly fine as a flat rectangle.
///
/// Two of the original's ways are not here. Both — a nickname lookup on LittleSkin and a
/// custom skin-loader API — are lookups against a service, and both services exist to serve
/// Minecraft accounts. What is left is what can be done without asking anybody: the launcher's own
/// skins, and a picture on this machine.
@NotNullByDefault
public final class SkinDialog extends com.jfoenix.controls.JFXDialogLayout {
    /// The turning model.
    private final SkinCanvas canvas;

    /// Which way of choosing a skin is in force.
    private final ToggleGroup methods = new ToggleGroup();

    /// Which body the skin is for.
    private final ToggleGroup models = new ToggleGroup();

    /// The launcher's own skins.
    private final RadioButton defaultMethod = new RadioButton(i18n("dsh.skin.method.default"));

    /// A picture from this machine.
    private final RadioButton fileMethod = new RadioButton(i18n("dsh.skin.method.file"));

    /// Which bundled skin, when that is the way in force.
    private final javafx.scene.control.ComboBox<DefaultSkin> bundled =
            new javafx.scene.control.ComboBox<>();

    /// The file, when that is the way in force.
    private final Label chosenFile = new Label();

    /// Whether the destination is the wide body.
    private final RadioButton wideModel = new RadioButton(i18n("dsh.skin.model.wide"));

    /// Whether the destination is the slim body.
    private final RadioButton slimModel = new RadioButton(i18n("dsh.skin.model.slim"));

    /// The picture currently shown, before it is saved.
    private @org.jetbrains.annotations.Nullable Image preview;

    /// Whether the preview is the slim body.
    private boolean previewSlim;

    /// Whether the picture came from a file, and where it is.
    private @org.jetbrains.annotations.Nullable java.nio.file.Path previewFile;

    /// Creates the dialog.
    public SkinDialog() {
        setHeading(new Label(i18n("dsh.skin.title")));

        canvas = new SkinCanvas(DshSkin.imageOrFallback(), 300, 320, true);
        // Dragging turns it; a model that cannot be turned hides exactly the parts being checked.
        canvas.enableRotation(0.4);
        StackPane previewPane = new StackPane(canvas);
        previewPane.setMinWidth(300);
        previewPane.setPrefWidth(300);

        BorderPane body = new BorderPane();
        body.setLeft(previewPane);
        body.setCenter(buildMethods());
        body.setRight(buildFields());
        setBody(body);
        setActions(buildActions());

        // It opens on the launcher's own skins whatever is in force, because that is the one choice
        // that needs nothing from the person using it: opening on "a file on this machine" would
        // show a way in that they may not have, and would make the dialog's first frame a statement
        // about a file they chose long ago. The model still shows what is in force, so nothing is
        // hidden — and confirming without touching anything keeps it, because the default method is
        // not what writes.
        defaultMethod.setSelected(true);
        bundled.getSelectionModel().selectFirst();
        (DshSkin.isSlim() ? slimModel : wideModel).setSelected(true);
        if (DshSkin.isSet()) {
            chosenFile.setText(i18n("dsh.skin.file.chosen"));
        }
        // The first frame shows what is in force rather than the first bundled skin: the dialog is
        // about the skin as it is, and the choices are ways of changing it.
        preview = DshSkin.isSet() ? DshSkin.previewImage() : bundled.getValue().image(wideModel.isSelected());
        previewChanged();
        syncEnabled();
    }

    /// Builds the list of ways to choose a skin.
    ///
    /// @return the middle column
    private VBox buildMethods() {
        defaultMethod.setToggleGroup(methods);
        fileMethod.setToggleGroup(methods);

        wideModel.setToggleGroup(models);
        slimModel.setToggleGroup(models);

        bundled.getItems().setAll(DefaultSkin.offered());
        bundled.setConverter(FXUtils.stringConverter(skin -> i18n(skin.i18nKey())));
        bundled.getSelectionModel().selectFirst();
        bundled.setMaxWidth(Double.MAX_VALUE);
        bundled.valueProperty().addListener((observable, was, value) -> {
            if (defaultMethod.isSelected()) {
                pickFromStore();
            }
        });

        methods.selectedToggleProperty().addListener((observable, was, value) -> {
            syncEnabled();
            pickFromStore();
        });
        models.selectedToggleProperty().addListener((observable, was, value) -> previewChanged());

        VBox box = new VBox(12, defaultMethod, bundled, fileMethod, chosenFile);
        box.setPadding(new Insets(0, 16, 0, 16));
        return box;
    }

    /// Builds the right column: what the chosen way needs.
    ///
    /// @return the right column
    private GridPane buildFields() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(10);

        JFXButton browse = new JFXButton();
        browse.setGraphic(SVG.FOLDER_OPEN.createIcon());
        browse.getStyleClass().add("toggle-icon4");
        FXUtils.installFastTooltip(browse, i18n("dsh.skin.import"));
        browse.setOnAction(event -> chooseFile());

        grid.add(new Label(i18n("dsh.skin.model")), 0, 0);
        grid.add(new VBox(6, wideModel, slimModel), 1, 0, 2, 1);
        grid.add(new Label(i18n("dsh.skin.file")), 0, 1);
        grid.add(chosenFile, 1, 1);
        grid.add(browse, 2, 1);
        GridPane.setHgrow(chosenFile, Priority.ALWAYS);
        chosenFile.setMaxWidth(Double.MAX_VALUE);
        return grid;
    }

    /// Builds the buttons that end the dialog.
    ///
    /// @return the foot
    private javafx.scene.Node buildActions() {
        JFXButton confirm = new JFXButton(i18n("button.ok"));
        confirm.getStyleClass().add("dialog-accept");
        confirm.setOnAction(event -> confirm());

        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.getStyleClass().add("dialog-cancel");
        cancel.setOnAction(event -> fireEvent(new org.jackhuang.hmcl.ui.construct.DialogCloseEvent()));

        HBox actions = new HBox(8, confirm, cancel);
        actions.setAlignment(Pos.CENTER_RIGHT);
        return actions;
    }

    /// Greys out what the chosen way does not use.
    private void syncEnabled() {
        boolean bundledChosen = defaultMethod.isSelected();
        bundled.setDisable(!bundledChosen);
        chosenFile.setDisable(bundledChosen);
    }

    /// Puts the picture the dialog is currently describing on the model.
    private void previewChanged() {
        boolean slim = slimModel.isSelected();
        if (preview == null || slim != previewSlim) {
            previewSlim = slim;
            preview = currentPicture(slim);
        }
        canvas.updateSkin(preview, slim, null);
    }

    /// Returns the picture the current choices describe.
    ///
    /// @param slim whether the slim body is wanted, which decides which of a bundled pair to read
    /// @return the picture, or `null` when nothing matches
    private @org.jetbrains.annotations.Nullable Image currentPicture(boolean slim) {
        if (defaultMethod.isSelected()) {
            DefaultSkin skin = bundled.getValue();
            return skin == null ? null : skin.image(slim);
        }
        if (previewFile != null) {
            return DshSkin.read(previewFile);
        }
        // A skin already chosen, shown on whichever body is selected.
        return DshSkin.previewImage() != null ? DshSkin.previewImage() : DshSkin.imageOrFallback();
    }

    /// Re-reads the picture because something other than the body type changed.
    private void pickFromStore() {
        preview = null;
        previewChanged();
    }

    /// Asks for a picture and remembers it for confirming.
    private void chooseFile() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("dsh.skin.import"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("dsh.skin.filter"), "*.png"));
        java.io.File chosen = chooser.showOpenDialog(Controllers.getStage());
        if (chosen == null) {
            return;
        }
        // Checked before it is remembered: a file that is not a skin must not replace the preview of
        // one that is, or a mistyped path would leave the dialog showing nothing with no way back.
        try {
            DshSkin.check(chosen.toPath());
        } catch (DshException e) {
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
            return;
        }
        previewFile = chosen.toPath();
        chosenFile.setText(previewFile.getFileName().toString());
        fileMethod.setSelected(true);
        syncEnabled();
        pickFromStore();
    }

    /// Saves what the dialog describes.
    private void confirm() {
        try {
            if (defaultMethod.isSelected()) {
                DefaultSkin skin = bundled.getValue();
                if (skin == null) {
                    return;
                }
                // A bundled skin is written out like any other, so that what is in force afterwards
                // is one thing read one way, rather than a special case the rest of the code has to
                // remember. Its body type is the one the dialog is showing, not what the picture
                // happens to contain: the bundled pairs are drawn for their own body.
                DshSkin.setFromImage(skin.image(slimModel.isSelected()), slimModel.isSelected());
            } else if (previewFile != null) {
                // The picture decides the body here: that is what makes importing a slim skin
                // switch the arms without anybody being asked.
                DshSkin.setFrom(previewFile);
            } else if (!DshSkin.isSet()) {
                return;
            } else {
                // Keeping the skin that is already there, on the body now selected.
                DshSkin.setFromImage(DshSkin.previewImage(), slimModel.isSelected());
            }
            fireEvent(new org.jackhuang.hmcl.ui.construct.DialogCloseEvent());
        } catch (DshException e) {
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
        }
    }
}
