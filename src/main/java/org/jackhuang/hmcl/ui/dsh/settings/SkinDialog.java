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
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXRadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshAccount;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.skin.DefaultSkin;
import org.jackhuang.hmcl.dsh.skin.DshSkin;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.LinePane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.MultiFileItem;
import org.jackhuang.hmcl.ui.dsh.skin.SkinCanvas;
import org.jackhuang.hmcl.ui.dsh.skin.animation.SkinAniRunning;
import org.jackhuang.hmcl.ui.dsh.skin.animation.SkinAniWavingArms;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Chooses the skin.
///
/// The original's arrangement, kept because each part answers a different question: the model turning
/// on the left (what does it look like), the ways of getting a skin listed down the middle (where does
/// it come from), and — for the ways that need something — the field that says which one on the right
/// (which one).
///
/// The list of ways is a `MultiFileItem`, which is the component the original builds its own chooser
/// from: one selectable row per way, and the application's own content beside each. Building it from
/// loose radio buttons instead is what made the rows and their fields drift apart.
///
/// Two of the original's ways are not here, both for one reason: a nickname lookup on LittleSkin and
/// a custom skin-loader API are lookups against services that exist to serve Minecraft accounts. What
/// is left is what can be done without asking anybody — the launcher's own skins, and a picture on
/// this machine.
@NotNullByDefault
public final class SkinDialog extends JFXDialogLayout {
    /// How a skin is being chosen.
    private enum Source {
        /// The body's own default skin.
        DEFAULT,
        /// The wide-armed model.
        STEVE,
        /// The slim-armed model.
        ALEX,
        /// A picture on this machine.
        LOCAL_FILE
    }

    /// The turning model.
    private SkinCanvas canvas;

    /// The ways of choosing a skin.
    private final MultiFileItem<Source> sourceItem = new MultiFileItem<>();

    /// Which of the launcher's own skins, when that is the way in force.

    /// The file's name, when a file is the way in force.
    private final Label fileLabel = new Label();

    /// Whether the skin is drawn on the wide body.
    private final JFXRadioButton wideModel = new JFXRadioButton(i18n("dsh.skin.model.wide"));

    /// Whether the skin is drawn on the slim body.
    private final JFXRadioButton slimModel = new JFXRadioButton(i18n("dsh.skin.model.slim"));

    /// The picture the dialog is currently describing, or `null` until one is read.
    private @Nullable Image preview;

    /// Whether the preview is the slim body.
    private boolean previewSlim;

    /// The file chosen this time round, or `null` when none has been.
    private @Nullable Path chosenFile;

    /// The rows whose visibility follows the way in force.
    private ComponentList fields = new ComponentList();

    /// The account whose skin this dialog changes.
    private final DshAccount account;

    /// Creates the dialog.
    ///
    /// @param account the account whose skin is being changed
    public SkinDialog(DshAccount account) {
        this.account = account;
        setHeading(new Label(i18n("account.skin")));

        BorderPane body = new BorderPane();
        body.setLeft(buildPreview());
        body.setCenter(buildSources());
        body.setRight(buildFields());
        setBody(body);
        setActions(buildActions());

        populate();
    }

    /// Builds the turning model.
    ///
    /// The animations are the original's — a wave and a run — so a skin's arms and legs are seen
    /// moving rather than only standing.
    ///
    /// @return the preview
    private Node buildPreview() {
        canvas = new SkinCanvas(DshSkin.imageOrFallback(account.key()), 260, 260, true);
        canvas.getAnimationPlayer().addSkinAnimation(
                new SkinAniWavingArms(100, 2000, 7.5, canvas),
                new SkinAniRunning(100, 100, 30, canvas));
        canvas.enableRotation(0.5);

        // A skin can be dropped on it, which is the original's own gesture: somebody with the file in
        // front of them should not have to find it in a file chooser.
        canvas.addEventHandler(DragEvent.DRAG_OVER, event -> {
            if (event.getDragboard().hasFiles()
                    && DshSkin.looksLikeAnImage(event.getDragboard().getFiles().get(0).getName())) {
                event.acceptTransferModes(TransferMode.COPY);
            }
        });
        canvas.addEventHandler(DragEvent.DRAG_DROPPED, event -> {
            if (event.isAccepted() && !event.getDragboard().getFiles().isEmpty()) {
                acceptFile(event.getDragboard().getFiles().get(0).toPath());
                sourceItem.setSelectedData(Source.LOCAL_FILE);
            }
        });

        StackPane pane = new StackPane(canvas);
        pane.setMinSize(280, 340);
        pane.setPrefSize(280, 340);
        BorderPane.setMargin(pane, new Insets(0, 16, 0, 0));
        return pane;
    }

    /// Builds the list of ways to choose a skin.
    ///
    /// @return the middle column
    private Node buildSources() {
        sourceItem.loadChildren(List.of(
                new MultiFileItem.Option<>(i18n("message.default"), Source.DEFAULT),
                new MultiFileItem.Option<>(i18n("account.skin.type.steve"), Source.STEVE),
                new MultiFileItem.Option<>(i18n("account.skin.type.alex"), Source.ALEX),
                new MultiFileItem.Option<>(i18n("account.skin.type.local_file"), Source.LOCAL_FILE)));
        sourceItem.setToggleSelectedListener(toggle -> {
            syncFields();
            readPreview();
        });

        VBox column = new VBox(sourceItem);
        column.setMinWidth(240);
        BorderPane.setMargin(column, new Insets(0, 16, 0, 0));
        return column;
    }

    /// Builds the fields for whichever way is in force.
    ///
    /// @return the right column
    private Node buildFields() {
        ToggleGroup models = new ToggleGroup();
        wideModel.setToggleGroup(models);
        slimModel.setToggleGroup(models);
        models.selectedToggleProperty().addListener((observable, was, value) -> readPreview());

        LinePane modelRow = new LinePane();
        modelRow.setTitle(i18n("account.skin.model"));
        modelRow.setRight(new VBox(6, wideModel, slimModel));

        JFXButton browse = new JFXButton();
        browse.setGraphic(SVG.FOLDER_OPEN.createIcon());
        browse.getStyleClass().add("toggle-icon4");
        FXUtils.installFastTooltip(browse, i18n("dsh.skin.import"));
        browse.setOnAction(event -> chooseFile());

        fileLabel.setMinWidth(120);
        fileLabel.setMaxWidth(Double.MAX_VALUE);
        HBox fileRow = new HBox(8, fileLabel, browse);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        LinePane filePane = new LinePane();
        filePane.setTitle(i18n("account.skin"));
        filePane.setRight(fileRow);

        fields.getContent().addAll(modelRow, filePane);

        VBox column = new VBox(fields);
        column.setMinWidth(260);
        return column;
    }

    /// Shows only the rows the current way needs.
    private void syncFields() {
        boolean localFile = sourceItem.getSelectedData() == Source.LOCAL_FILE;

        // The body choice is always there — it applies to all four ways, and the original keeps it
        // visible throughout. The file rows are **only** for the way that uses a file, and they
        // appear when it is chosen: the original's right-hand column is emptied and refilled per
        // selection, which is why its dialog does not show a field for something not in force.
        setVisible(fields.getContent().get(0), true);
        setVisible(fields.getContent().get(1), localFile);
    }

    /// Adds or removes a row without leaving a gap where it was.
    ///
    /// @param node   the row
    /// @param usable whether to show it
    private void setVisible(Node node, boolean usable) {
        node.setVisible(usable);
        node.setManaged(usable);
    }

    /// Builds the buttons.
    ///
    /// @return the actions
    private Node buildActions() {
        JFXButton accept = new JFXButton(i18n("button.ok"));
        accept.getStyleClass().add("dialog-accept");
        accept.setOnAction(event -> confirm());

        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.getStyleClass().add("dialog-cancel");
        cancel.setOnAction(event -> fireEvent(new DialogCloseEvent()));

        HBox actions = new HBox(8, accept, cancel);
        actions.setAlignment(Pos.CENTER_RIGHT);
        return actions;
    }

    /// Puts the dialog into the state the launcher is already in.
    ///
    /// It opens on what is in force rather than on the first entry of the list: the dialog is about the
    /// skin as it is, and the ways of choosing are ways of changing it.
    private void populate() {
        (DshSkin.isSlim(account.key()) ? slimModel : wideModel).setSelected(true);
        if (DshSkin.isSet(account.key())) {
            chosenFile = DshSkin.file(account.key());
            fileLabel.setText(chosenFile.getFileName().toString());
            sourceItem.setSelectedData(Source.LOCAL_FILE);
        } else {
            sourceItem.setSelectedData(Source.DEFAULT);
        }
        syncFields();
        readPreview();
    }

    /// Returns the launcher's own skin the current way names.
    ///
    /// The three ways are the original's own three: the body's default, the wide model and the slim
    /// one. Which picture that is is a lookup, not a question.
    ///
    /// @return the skin
    private @Nullable DefaultSkin bundledSkin() {
        Source source = sourceItem.getSelectedData();
        // Nothing is selected yet while the dialog is being built: the body radio buttons are wired
        // before the list has chosen anything, so their listener fires first and there is no way in
        // force to read. Nothing to draw is the honest answer at that moment.
        if (source == null) {
            return null;
        }
        return switch (source) {
            case STEVE -> DefaultSkin.STEVE;
            case ALEX -> DefaultSkin.ALEX;
            // "Default" is the wide model's own skin, which is what a body with nothing chosen wears.
            case DEFAULT -> DefaultSkin.STEVE;
            case LOCAL_FILE -> null;
        };
    }

    /// Reads the picture the current choices describe and puts it on the model.
    private void readPreview() {
        boolean slim = slimModel.isSelected();
        DefaultSkin bundled = bundledSkin();
        if (bundled != null) {
            // One of the launcher's own three. Each is drawn for its own body, so the body picks
            // which file to read rather than reinterpreting a picture.
            preview = bundled.image(slim);
            previewSlim = slim;
        } else {
            Image fromFile = chosenFile == null ? null : DshSkin.read(chosenFile);
            preview = fromFile != null ? fromFile : DshSkin.imageOrFallback(account.key());
            // An imported picture decides its own body, which is what makes importing a slim skin
            // switch the arms without anybody being asked.
            previewSlim = fromFile != null ? DshSkin.slimOf(fromFile) : slim;
        }
        if (preview != null) {
            canvas.updateSkin(preview, previewSlim, null);
        }
    }

    /// Asks for a picture and shows it.
    private void chooseFile() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("dsh.skin.import"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("dsh.skin.filter"), "*.png"));
        java.io.File chosen = chooser.showOpenDialog(Controllers.getStage());
        if (chosen != null) {
            acceptFile(chosen.toPath());
            sourceItem.setSelectedData(Source.LOCAL_FILE);
        }
    }

    /// Takes a picture, after checking that it is one.
    ///
    /// Checked before it is remembered: a file that is not a skin must not replace the picture of one
    /// that is, or a mistyped path would leave the dialog showing nothing with no way back.
    ///
    /// @param file the file
    private void acceptFile(Path file) {
        try {
            DshSkin.check(file);
        } catch (DshException e) {
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
            return;
        }
        chosenFile = file;
        fileLabel.setText(file.getFileName().toString());
        readPreview();
    }

    /// Saves what the dialog describes.
    private void confirm() {
        try {
            DefaultSkin bundled = bundledSkin();
            if (bundled != null) {
                DshSkin.setFromImage(account.key(), bundled.image(slimModel.isSelected()),
                        slimModel.isSelected());
            } else if (chosenFile != null) {
                DshSkin.setFrom(account.key(), chosenFile);
            }
            fireEvent(new DialogCloseEvent());
        } catch (DshException e) {
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
        }
    }
}
