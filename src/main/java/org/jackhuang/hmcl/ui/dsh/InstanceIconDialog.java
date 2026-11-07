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
package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Dialog for selecting or importing an instance icon.
 */
@NotNullByDefault
public final class InstanceIconDialog extends JFXDialogLayout {
	private final DshInstance instance;
	private final Runnable onFinish;

	/**
	 * Creates the instance icon dialog.
	 *
	 * @param instance instance being edited
	 * @param onFinish callback invoked after the icon changes
	 */
	public InstanceIconDialog(DshInstance instance, Runnable onFinish) {
		this.instance = instance;
		this.onFinish = onFinish;

		setHeading(new Label(i18n("settings.icon")));

		FlowPane tiles = new FlowPane();
		tiles.getChildren().add(buildCustomTile());

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
		confirm.setOnAction(event -> fireEvent(new DialogCloseEvent()));

		JFXButton cancel = new JFXButton(i18n("button.cancel"));
		cancel.getStyleClass().add("dialog-cancel");
		cancel.setOnAction(event -> fireEvent(new DialogCloseEvent()));
		onEscPressed(this, cancel::fire);

		setActions(confirm, cancel);
	}

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

	private Node buildTile(Image image, DshInstanceIcon icon) {
		RipplerContainer container = buildTile(image);
		FXUtils.onClicked(container, () -> apply(icon, null));
		return container;
	}

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

	private void chooseFile() {
		FileChooser chooser = new FileChooser();
		chooser.getExtensionFilters().add(FXUtils.getImageExtensionFilter());
		Path selected = Controllers.showOpenDialog(chooser);
		if (selected != null) {
			apply(null, selected);
		}
	}

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
			Controllers.dialog(e.getMessage(), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
		}
	}
}
