/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.skin.DefaultSkin;
import com.mcxcc303.hdsl.skin.DshSkin;
import com.mcxcc303.hdsl.skin.DshSkinChoice;
import javafx.beans.InvalidationListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
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

/**
 * Dialog for selecting and customizing an account's skin and cape.
 * <p>
 * Layout architecture:
 * <ul>
 *   <li>The animated 3D model canvas sits in the center (260x260 {@link StackPane}),
 *       while skin selection options are placed on the right.</li>
 *   <li>The custom file options column ({@code right}) is dynamically added to or removed
 *       from {@code body} rather than toggled via visibility, because a hidden node still occupies
 *       its 230px width and 20px gap.</li>
 *   <li>Three attributes are customizable: body model (wide/slim), skin texture, and cape texture.</li>
 *   <li>Operates purely offline on local files and bundled skins (Steve/Alex), omitting third-party
 *       network skin-lookup services.</li>
 * </ul>
 */
@NotNullByDefault
public final class SkinDialog extends JFXDialogLayout {

	private final DshAccount account;
	private final @Nullable Runnable onSaved;
	private final SkinCanvas canvas;
	private final MultiFileItem<Source> skinItem = new MultiFileItem<>();
	private final JFXComboBox<DshSkinChoice.TextureModel> modelBox = new JFXComboBox<>();
	private final FileSelector skinSelector = new FileSelector();
	private final FileSelector capeSelector = new FileSelector();
	private final VBox right = new VBox();
	private final HBox body = new HBox(20);

	/**
	 * Keeps the preview's listener alive.
	 * <p>
	 * {@link FXUtils#observeWeak} registers a weak listener that would be garbage collected
	 * without a strong reference held on this instance.
	 */
	@SuppressWarnings("unused")
	private final InvalidationListener previewBinding;

	/**
	 * Creates the dialog.
	 *
	 * @param account the account whose skin is being changed
	 */
	public SkinDialog(DshAccount account) {
		this(account, null);
	}

	/**
	 * Creates the dialog with a callback upon saving.
	 *
	 * @param account the account whose skin is being changed
	 * @param onSaved callback invoked once saved, or {@code null}
	 */
	public SkinDialog(DshAccount account, @Nullable Runnable onSaved) {
		this.account = account;
		this.onSaved = onSaved;

		getStyleClass().add("skin-pane");
		setHeading(new Label(i18n("account.skin")));

		BorderPane pane = new BorderPane();
		canvas = new SkinCanvas(DshSkin.imageOrFallback(account.key()), 260, 260, true);
		pane.setCenter(buildPreview());

		StackPane skinOptionPane = new StackPane();
		pane.setRight(skinOptionPane);
		setBody(pane);

		skinSelector.setMaxWidth(Double.MAX_VALUE);
		capeSelector.setMaxWidth(Double.MAX_VALUE);
		modelBox.setMaxWidth(Double.MAX_VALUE);

		skinItem.loadChildren(List.of(new MultiFileItem.Option<>(i18n("message.default"), Source.DEFAULT),
		                              new MultiFileItem.Option<>(i18n("account.skin.type.steve"), Source.STEVE),
		                              new MultiFileItem.Option<>(i18n("account.skin.type.alex"), Source.ALEX),
		                              new MultiFileItem.Option<>(i18n("account.skin.type.local_file"),
		                                                         Source.LOCAL_FILE)));

		modelBox.setConverter(FXUtils.stringConverter(model -> i18n("account.skin.model." + model.modelName)));
		modelBox.getItems().setAll(DshSkinChoice.TextureModel.WIDE, DshSkinChoice.TextureModel.SLIM);

		openOnStoredChoice();

		right.setPadding(new Insets(0, 0, 0, 10));
		right.setSpacing(12);
		right.setPrefWidth(230);
		HBox.setHgrow(right, Priority.ALWAYS);

		skinItem.setPrefWidth(170);
		skinItem.setMinWidth(170);
		body.getChildren().add(skinItem);
		skinOptionPane.getChildren().setAll(body);

		FXUtils.onChangeAndOperate(skinItem.selectedDataProperty(), selected -> {
			right.getChildren().clear();
			if (selected == Source.LOCAL_FILE) {
				right.getChildren()
				     .addAll(new Label(i18n("account.skin.model")),
				             modelBox,
				             new Label(i18n("account.skin")),
				             skinSelector,
				             new Label(i18n("account.cape")),
				             capeSelector);
			}
			if (right.getChildren().isEmpty()) {
				body.getChildren().remove(right);
			} else if (!body.getChildren().contains(right)) {
				body.getChildren().add(right);
			}
		});

		// Retain strong reference to prevent garbage collection of the weak listener
		previewBinding = FXUtils.observeWeak(this::loadPreview,
		                                     skinItem.selectedDataProperty(),
		                                     modelBox.valueProperty(),
		                                     skinSelector.valueProperty(),
		                                     capeSelector.valueProperty());

		setActions(buildActions());
	}

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

	private Node buildPreview() {
		canvas.getAnimationPlayer()
		      .addSkinAnimation(new SkinAniWavingArms(100, 2000, 7.5, canvas),
		                        new SkinAniRunning(100, 100, 30, canvas));
		canvas.enableRotation(0.5);

		canvas.addEventHandler(DragEvent.DRAG_OVER, event -> {
			if (event.getDragboard().hasFiles() &&
			    DshSkin.looksLikeAnImage(event.getDragboard().getFiles().get(0).getName())) {
				event.acceptTransferModes(TransferMode.COPY);
			}
		});
		canvas.addEventHandler(DragEvent.DRAG_DROPPED, event -> {
			if (event.isAccepted() && !event.getDragboard().getFiles().isEmpty()) {
				skinSelector.setValue(event.getDragboard().getFiles().get(0).getAbsolutePath());
				skinItem.setSelectedData(Source.LOCAL_FILE);
			}
		});

		StackPane canvasPane = new StackPane(canvas);
		canvasPane.setPrefWidth(260);
		canvasPane.setPrefHeight(260);
		return canvasPane;
	}

	private void openOnStoredChoice() {
		DshSkinChoice stored = account.skinOrDefault();
		if (stored.type() == DshSkinChoice.Type.DEFAULT && DshSkin.isSet(account.key())) {
			skinSelector.setValue(DshSkin.file(account.key()).toString());
			if (DshSkin.cape(account.key()) != null) {
				capeSelector.setValue(DshSkin.capeFile(account.key()).toString());
			}
			modelBox.setValue(DshSkin.isSlim(account.key())
			                  ? DshSkinChoice.TextureModel.SLIM
			                  : DshSkinChoice.TextureModel.WIDE);
			skinItem.setSelectedData(Source.LOCAL_FILE);
			return;
		}
		modelBox.setValue(stored.model());
		skinSelector.setValue(stored.localSkinPath());
		capeSelector.setValue(stored.localCapePath());
		skinItem.setSelectedData(Source.of(stored.type()));
	}

	private Node buildActions() {
		JFXButton accept = new JFXButton(i18n("button.ok"));
		accept.getStyleClass().add("dialog-accept");
		accept.setOnAction(event -> confirm());

		JFXButton cancel = new JFXButton(i18n("button.cancel"));
		cancel.getStyleClass().add("dialog-cancel");
		cancel.setOnAction(event -> fireEvent(new DialogCloseEvent()));
		FXUtils.onEscPressed(this, cancel::fire);

		HBox actions = new HBox(8, accept, cancel);
		actions.setAlignment(Pos.CENTER_RIGHT);
		return actions;
	}

	private void loadPreview() {
		Source source = skinItem.getSelectedData();
		if (source == null) {
			return;
		}

		switch (source) {
			case DEFAULT -> showDefault();
			case STEVE -> showBundled(DefaultSkin.STEVE, false);
			case ALEX -> showBundled(DefaultSkin.ALEX, true);
			case LOCAL_FILE -> showFile();
		}
	}

	private void showDefault() {
		canvas.updateSkin(DshSkin.defaultImage(account.key()), DshSkin.defaultSlim(account.key()), null);
	}

	private void showBundled(DefaultSkin skin, boolean slim) {
		Image picture = skin.image(slim);
		if (picture == null) {
			showDefault();
			return;
		}
		canvas.updateSkin(picture, slim, null);
	}

	private void showFile() {
		Path file = pathOf(skinSelector.getValue());
		Image picture = file == null ? null : DshSkin.read(file);
		Path capeFile = pathOf(capeSelector.getValue());
		Image cape = capeFile == null ? null : DshSkin.read(capeFile);

		if (picture == null) {
			canvas.updateSkin(DshSkin.defaultImage(account.key()), DshSkin.defaultSlim(account.key()), cape);
			return;
		}
		canvas.updateSkin(picture, modelBox.getValue() == DshSkinChoice.TextureModel.SLIM, cape);
	}

	/**
	 * Persists the user's choice: clearing customizations on {@code DEFAULT},
	 * copying bundled textures, or copying external local skin/cape files into storage.
	 */
	private void confirm() {
		Source source = skinItem.getSelectedData();
		if (source == null) {
			return;
		}
		try {
			DshSkinChoice choice = switch (source) {
				case DEFAULT -> {
					DshSkin.clear(account.key());
					DshSkin.clearCape(account.key());
					yield new DshSkinChoice(DshSkinChoice.Type.DEFAULT, DshSkinChoice.TextureModel.WIDE, null, null);
				}
				case STEVE -> {
					keepBundled(DefaultSkin.STEVE, false);
					yield new DshSkinChoice(DshSkinChoice.Type.STEVE, DshSkinChoice.TextureModel.WIDE, null, null);
				}
				case ALEX -> {
					keepBundled(DefaultSkin.ALEX, true);
					yield new DshSkinChoice(DshSkinChoice.Type.ALEX, DshSkinChoice.TextureModel.SLIM, null, null);
				}
				case LOCAL_FILE -> keepFile();
			};
			remember(choice);
			fireEvent(new DialogCloseEvent());
		} catch (DshException e) {
			Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
		}
	}

	private void keepBundled(DefaultSkin skin, boolean slim) throws DshException {
		Image picture = skin.image(slim);
		if (picture == null) {
			throw new DshException(i18n("dsh.skin.bundled.missing", skin.displayName()), null);
		}
		DshSkin.setFromImage(account.key(), picture, slim);
		DshSkin.clearCape(account.key());
	}

	/**
	 * Copies chosen skin and cape images into launcher storage.
	 * Both files are duplicated rather than stored by path, ensuring custom skins
	 * survive moving, renaming, or deleting the original external files.
	 *
	 * @return the skin choice to store
	 * @throws DshException when a file is missing or invalid
	 */
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
		                         skinValue,
		                         capeValue);
	}

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

	private enum Source {
		DEFAULT,
		STEVE,
		ALEX,
		LOCAL_FILE;

		static Source of(DshSkinChoice.Type stored) {
			return switch (stored) {
				case DEFAULT -> DEFAULT;
				case STEVE -> STEVE;
				case ALEX -> ALEX;
				case LOCAL_FILE -> LOCAL_FILE;
			};
		}

		DshSkinChoice.Type stored() {
			return switch (this) {
				case DEFAULT -> DshSkinChoice.Type.DEFAULT;
				case STEVE -> DshSkinChoice.Type.STEVE;
				case ALEX -> DshSkinChoice.Type.ALEX;
				case LOCAL_FILE -> DshSkinChoice.Type.LOCAL_FILE;
			};
		}
	}
}
