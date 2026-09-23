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
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshAccount;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.skin.DefaultSkin;
import org.jackhuang.hmcl.dsh.skin.DshSkin;
import org.jackhuang.hmcl.dsh.skin.DshSkinChoice;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.FileSelector;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.MultiFileItem;
import org.jackhuang.hmcl.ui.dsh.skin.SkinCanvas;
import org.jackhuang.hmcl.ui.dsh.skin.animation.SkinAniRunning;
import org.jackhuang.hmcl.ui.dsh.skin.animation.SkinAniWavingArms;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Chooses the skin.
///
/// The original's arrangement, kept node for node, because every part of it is load-bearing:
///
/// ```java
/// BorderPane pane = new BorderPane();
/// StackPane canvasPane = new StackPane(canvas);   // 260x260
/// pane.setCenter(canvasPane);                     // the turning model, in the middle
/// pane.setRight(skinOptionPane);                  // the ways of choosing, on the right
/// HBox body = new HBox(20);                       // 20 apart
/// skinItem.setPrefWidth(170); skinItem.setMinWidth(170);
/// VBox right = new VBox();                        // 230 wide, 12 apart, padded 10 on the left
/// ```
///
/// Two details of that are easy to get wrong and both show. The ways of choosing sit at the
/// **right of the body with the model at the centre** — putting the model on the left instead moves
/// everything and leaves the model's column the wrong size. And the field column is **added to and
/// removed from the box** as the choice changes, rather than hidden: a hidden column still takes its
/// 230 pixels and the 20-pixel gap before it, which is what left a gap in the middle of the dialog
/// with nothing in it.
///
/// The fields themselves are the original's too, and there are three of them rather than two: which
/// body, which picture, and which cape. The cape is why the model's cloak can be seen at all — the
/// renderer has always drawn one, and nothing here ever offered it.
///
/// The ways of choosing are a `MultiFileItem`, which is the component the original builds its own
/// chooser from: one selectable row per way, and the application's own content beside each. Building
/// it from loose radio buttons instead is what made the rows and their fields drift apart.
///
/// Two of the original's ways are not here, both for one reason: a nickname lookup on LittleSkin and
/// a custom skin-loader API are lookups against services that exist to serve Minecraft accounts. What
/// is left is what can be done without asking anybody — the launcher's own skins, and a picture on
/// this machine.
@NotNullByDefault
public final class SkinDialog extends JFXDialogLayout {
    /// How a skin is being chosen.
    ///
    /// The original's `Skin.Type`, less the three that need a service. The names are the same and so
    /// are the stored spellings, so a choice written by either program means the same thing.
    private enum Source {
        /// The body's own default skin.
        DEFAULT,
        /// The wide-armed model.
        STEVE,
        /// The slim-armed model.
        ALEX,
        /// A picture on this machine.
        LOCAL_FILE;

        /// Returns the way a stored choice names.
        ///
        /// @param stored the stored choice
        /// @return the way
        static Source of(DshSkinChoice.Type stored) {
            return switch (stored) {
                case DEFAULT -> DEFAULT;
                case STEVE -> STEVE;
                case ALEX -> ALEX;
                case LOCAL_FILE -> LOCAL_FILE;
            };
        }

        /// Returns the choice this way stores.
        ///
        /// @return the choice
        DshSkinChoice.Type stored() {
            return switch (this) {
                case DEFAULT -> DshSkinChoice.Type.DEFAULT;
                case STEVE -> DshSkinChoice.Type.STEVE;
                case ALEX -> DshSkinChoice.Type.ALEX;
                case LOCAL_FILE -> DshSkinChoice.Type.LOCAL_FILE;
            };
        }
    }

    /// The account whose skin this dialog changes.
    private final DshAccount account;

    /// What to tell once the choice has been saved, or `null`.
    private final @Nullable Runnable onSaved;

    /// The turning model.
    private final SkinCanvas canvas;

    /// The ways of choosing a skin.
    private final MultiFileItem<Source> skinItem = new MultiFileItem<>();

    /// The body, when a file is the way in force.
    private final JFXComboBox<DshSkinChoice.TextureModel> modelBox = new JFXComboBox<>();

    /// The file, when a file is the way in force.
    private final FileSelector skinSelector = new FileSelector();

    /// The cape's file, when a file is the way in force.
    private final FileSelector capeSelector = new FileSelector();

    /// The fields, in a column that is added to the body only while there are any.
    private final VBox right = new VBox();

    /// The ways of choosing and the fields, 20 apart.
    private final HBox body = new HBox(20);

    /// Keeps the preview's listener alive, which a weak listener cannot do for itself.
    ///
    /// Assigned and never read: what matters is that something points at it. See the note where it
    /// is set.
    @SuppressWarnings("unused")
    private final javafx.beans.InvalidationListener previewBinding;

    /// Creates the dialog.
    ///
    /// @param account the account whose skin is being changed
    public SkinDialog(DshAccount account) {
        this(account, null);
    }

    /// Creates the dialog, telling somebody once the choice has been saved.
    ///
    /// @param account the account whose skin is being changed
    /// @param onSaved what to tell, or `null`
    public SkinDialog(DshAccount account, @Nullable Runnable onSaved) {
        this.account = account;
        this.onSaved = onSaved;

        // The original puts this on the pane that holds the dialog; here the dialog is the pane, so
        // it goes here. It is what gives the path boxes their 200-pixel width — without it they are
        // sized by their text and the column does not line up.
        getStyleClass().add("skin-pane");
        setHeading(new Label(i18n("account.skin")));

        BorderPane pane = new BorderPane();
        canvas = new SkinCanvas(DshSkin.imageOrFallback(account.key()), 260, 260, true);
        pane.setCenter(buildPreview());

        StackPane skinOptionPane = new StackPane();
        pane.setRight(skinOptionPane);
        setBody(pane);

        // A field fills the column it is in; without this each is as wide as its own text and the
        // three of them end at three different places.
        skinSelector.setMaxWidth(Double.MAX_VALUE);
        capeSelector.setMaxWidth(Double.MAX_VALUE);
        modelBox.setMaxWidth(Double.MAX_VALUE);

        skinItem.loadChildren(List.of(
                new MultiFileItem.Option<>(i18n("message.default"), Source.DEFAULT),
                new MultiFileItem.Option<>(i18n("account.skin.type.steve"), Source.STEVE),
                new MultiFileItem.Option<>(i18n("account.skin.type.alex"), Source.ALEX),
                new MultiFileItem.Option<>(i18n("account.skin.type.local_file"), Source.LOCAL_FILE)));

        // The labels come from the original's own keys — `account.skin.model.default` reads
        // "Classic" and `account.skin.model.slim` reads "Slim" — rather than from keys invented
        // here, so the two programs say the same thing about the same two bodies.
        modelBox.setConverter(FXUtils.stringConverter(
                model -> i18n("account.skin.model." + model.modelName)));
        modelBox.getItems().setAll(DshSkinChoice.TextureModel.WIDE, DshSkinChoice.TextureModel.SLIM);

        // Opened on what is in force, which is the choice the account holds rather than anything
        // read off the disk: "Steve" and "a file that happens to be Steve's picture" are different
        // answers, and only the stored choice can tell them apart.
        openOnStoredChoice();

        right.setPadding(new Insets(0, 0, 0, 10));
        right.setSpacing(12);
        right.setPrefWidth(230);
        HBox.setHgrow(right, Priority.ALWAYS);

        skinItem.setPrefWidth(170);
        skinItem.setMinWidth(170);
        body.getChildren().add(skinItem);
        skinOptionPane.getChildren().setAll(body);

        // The original's own switch, and its own emptiness rule: the three named skins need no
        // field at all, so for them the column is taken out of the box rather than left standing
        // empty. Keeping the model selector for them — which is what this did — asks a question the
        // chosen skin has already answered.
        FXUtils.onChangeAndOperate(skinItem.selectedDataProperty(), selected -> {
            right.getChildren().clear();
            if (selected == Source.LOCAL_FILE) {
                right.getChildren().addAll(
                        new Label(i18n("account.skin.model")), modelBox,
                        new Label(i18n("account.skin")), skinSelector,
                        new Label(i18n("account.cape")), capeSelector);
            }
            if (right.getChildren().isEmpty()) {
                body.getChildren().remove(right);
            } else if (!body.getChildren().contains(right)) {
                body.getChildren().add(right);
            }
        });

        // The original watches all four and redraws on any of them, which is what makes typing a
        // path show the picture without a button to press.
        //
        // The returned listener is **kept**, and that is not tidiness. `observeWeak` registers a
        // listener that holds its real listener weakly, so that a page's own listener does not keep
        // the page alive — and it hands the strong one back for exactly this reason. Dropping the
        // return value leaves nothing pointing at it, the collector takes it, and from the next
        // collection on the preview silently stops following the fields. It still draws once,
        // because `observeWeak` runs the body before it returns, which is what made this look like
        // "the preview works" while choosing a file did nothing.
        previewBinding = FXUtils.observeWeak(this::loadPreview,
                skinItem.selectedDataProperty(), modelBox.valueProperty(),
                skinSelector.valueProperty(), capeSelector.valueProperty());

        setActions(buildActions());
    }

    /// Builds the turning model.
    ///
    /// The animations are the original's — a wave and a run — so a skin's arms and legs are seen
    /// moving rather than only standing. The canvas itself is made by the constructor, because a
    /// field of this class cannot be given its value from in here.
    ///
    /// @return the preview
    private Node buildPreview() {
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
                // Set on the selector rather than kept aside, so the path the person dropped is the
                // path they can see, edit, and confirm — exactly as if they had browsed to it.
                skinSelector.setValue(event.getDragboard().getFiles().get(0).getAbsolutePath());
                skinItem.setSelectedData(Source.LOCAL_FILE);
            }
        });

        StackPane canvasPane = new StackPane(canvas);
        canvasPane.setPrefWidth(260);
        canvasPane.setPrefHeight(260);
        return canvasPane;
    }

    /// Puts the dialog into the state the account's stored choice describes.
    ///
    /// A skin chosen before the choice itself was written down is accounted for too: the picture is
    /// on the disk and nothing records how it got there, and opening on "nothing chosen" would then
    /// draw one face while the account wears another. The file is taken to be the choice it plainly
    /// was, which is also what makes the dialog agree with the avatar beside it.
    private void openOnStoredChoice() {
        DshSkinChoice stored = account.skinOrDefault();
        if (stored.type() == DshSkinChoice.Type.DEFAULT && DshSkin.isSet(account.key())) {
            skinSelector.setValue(DshSkin.file(account.key()).toString());
            if (DshSkin.cape(account.key()) != null) {
                capeSelector.setValue(DshSkin.capeFile(account.key()).toString());
            }
            modelBox.setValue(DshSkin.isSlim(account.key())
                    ? DshSkinChoice.TextureModel.SLIM : DshSkinChoice.TextureModel.WIDE);
            skinItem.setSelectedData(Source.LOCAL_FILE);
            return;
        }
        modelBox.setValue(stored.model());
        skinSelector.setValue(stored.localSkinPath());
        capeSelector.setValue(stored.localCapePath());
        skinItem.setSelectedData(Source.of(stored.type()));
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
        FXUtils.onEscPressed(this, cancel::fire);

        HBox actions = new HBox(8, accept, cancel);
        actions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        return actions;
    }

    /// Draws whatever the current choices describe.
    ///
    /// Nothing is written here: a picture is shown before it is agreed to, so nothing the person did
    /// not confirm can replace what they had.
    private void loadPreview() {
        Source source = skinItem.getSelectedData();
        if (source == null) {
            // Nothing is selected yet while the dialog is being built: the four properties are
            // watched before the stored choice has been set on them, so this runs once with no way
            // in force to read. Nothing to draw is the honest answer at that moment.
            return;
        }

        switch (source) {
            case DEFAULT -> showDefault();
            case STEVE -> showBundled(DefaultSkin.STEVE, false);
            case ALEX -> showBundled(DefaultSkin.ALEX, true);
            case LOCAL_FILE -> showFile();
        }
    }

    /// Draws the skin an account wears when it has chosen none.
    ///
    /// Not one fixed picture: the original picks one of nine from the account's identity, so this
    /// account's own default is what is drawn — and it is drawn on the body that choice comes with,
    /// which is why the body is not read from the field here.
    private void showDefault() {
        canvas.updateSkin(DshSkin.defaultImage(account.key()),
                DshSkin.defaultSlim(account.key()), null);
    }

    /// Draws one of the launcher's own skins.
    ///
    /// Each is drawn for its own body rather than for whatever the field says, because for the two
    /// named skins the body is part of what was chosen — `ALEX` is slim, `STEVE` is not.
    ///
    /// @param skin the skin
    /// @param slim which of its two pictures to draw
    private void showBundled(DefaultSkin skin, boolean slim) {
        Image picture = skin.image(slim);
        if (picture == null) {
            showDefault();
            return;
        }
        canvas.updateSkin(picture, slim, null);
    }

    /// Draws the picture and cape the fields name.
    private void showFile() {
        Path file = pathOf(skinSelector.getValue());
        Image picture = file == null ? null : DshSkin.read(file);
        Path capeFile = pathOf(capeSelector.getValue());
        Image cape = capeFile == null ? null : DshSkin.read(capeFile);

        if (picture == null) {
            canvas.updateSkin(DshSkin.defaultImage(account.key()),
                    DshSkin.defaultSlim(account.key()), cape);
            return;
        }
        // The body is the field's answer here, which is the one place it is a question: a file
        // carries no record of which body it was drawn for, unlike the two bundled skins.
        canvas.updateSkin(picture,
                modelBox.getValue() == DshSkinChoice.TextureModel.SLIM, cape);
    }

    /// Saves what the dialog describes.
    private void confirm() {
        Source source = skinItem.getSelectedData();
        if (source == null) {
            return;
        }
        try {
            DshSkinChoice choice = switch (source) {
                case DEFAULT -> {
                    // "Nothing chosen" is stored as such — the account's skin is cleared rather than
                    // set to one of the bundled pictures, so that the choice and the picture that
                    // stands in for it stay different answers.
                    DshSkin.clear(account.key());
                    DshSkin.clearCape(account.key());
                    yield new DshSkinChoice(DshSkinChoice.Type.DEFAULT,
                            DshSkinChoice.TextureModel.WIDE, null, null);
                }
                case STEVE -> {
                    keepBundled(DefaultSkin.STEVE, false);
                    yield new DshSkinChoice(DshSkinChoice.Type.STEVE,
                            DshSkinChoice.TextureModel.WIDE, null, null);
                }
                case ALEX -> {
                    keepBundled(DefaultSkin.ALEX, true);
                    yield new DshSkinChoice(DshSkinChoice.Type.ALEX,
                            DshSkinChoice.TextureModel.SLIM, null, null);
                }
                case LOCAL_FILE -> keepFile();
            };
            remember(choice);
            fireEvent(new DialogCloseEvent());
        } catch (DshException e) {
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
        }
    }

    /// Keeps one of the launcher's own skins as this account's.
    ///
    /// The choice was made when the row was picked, so the body is the type's own — there is no
    /// question left for the field to answer, which is why it is not on screen for these.
    ///
    /// @param skin the skin
    /// @param slim which of its two pictures
    /// @throws DshException when the picture cannot be read or written
    private void keepBundled(DefaultSkin skin, boolean slim) throws DshException {
        Image picture = skin.image(slim);
        if (picture == null) {
            throw new DshException(i18n("dsh.skin.bundled.missing", skin.displayName()), null);
        }
        DshSkin.setFromImage(account.key(), picture, slim);
        DshSkin.clearCape(account.key());
    }

    /// Keeps the picture and cape the fields name.
    ///
    /// Both are copied in rather than remembered as paths: a path is where a file *was*, and the
    /// skin has to survive the person moving, renaming, or deleting it.
    ///
    /// @return the choice to store
    /// @throws DshException when a file is missing, is not a picture, or cannot be written
    private DshSkinChoice keepFile() throws DshException {
        String skinValue = skinSelector.getValue();
        Path file = pathOf(skinValue);
        if (file == null) {
            throw new DshException(i18n("dsh.skin.file.missing"), null);
        }
        DshSkin.setFrom(account.key(), file);

        String capeValue = capeSelector.getValue();
        Path capeFile = pathOf(capeValue);
        if (capeFile == null) {
            DshSkin.clearCape(account.key());
        } else {
            DshSkin.setCape(account.key(), capeFile);
        }

        return new DshSkinChoice(DshSkinChoice.Type.LOCAL_FILE,
                modelBox.getValue() == null ? DshSkinChoice.TextureModel.WIDE : modelBox.getValue(),
                skinValue, capeValue);
    }

    /// Stores the choice on the account and writes the settings out.
    ///
    /// @param choice the choice
    private void remember(DshSkinChoice choice) {
        List<DshAccount> accounts = SettingsManager.settings().getAccounts();
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).matchesKey(account.key())) {
                accounts.set(i, account.withSkin(choice));
                break;
            }
        }
        SettingsManager.save();
        if (onSaved != null) {
            onSaved.run();
        }
    }

    /// Reads a path out of a field.
    ///
    /// A field is a text box as well as a button, so what arrives may be blank, or may be something
    /// no path can be made of. Both mean the same thing here: nothing has been named.
    ///
    /// @param value the field's value, or `null`
    /// @return the path, or `null`
    private static @Nullable Path pathOf(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value.trim());
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
