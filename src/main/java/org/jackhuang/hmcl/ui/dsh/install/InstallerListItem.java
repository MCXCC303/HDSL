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
package org.jackhuang.hmcl.ui.dsh.install;

import com.jfoenix.controls.JFXButton;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * A row component representing an installer item in the auto-install list.
 */
@NotNullByDefault
public final class InstallerListItem extends StackPane {
	private static final PseudoClass LIST_ITEM = PseudoClass.getPseudoClass("list-item");
	private static final String WIDE_NAME = "wide";

	private final Label nameLabel;
	private final StringProperty status = new SimpleStringProperty();
	private final JFXButton change = FXUtils.newToggleButton4(SVG.UPDATE);
	private final JFXButton remove = FXUtils.newToggleButton4(SVG.CLOSE);
	private final @Nullable RipplerContainer rippler;
	private @Nullable Runnable activate;
	private @Nullable Runnable changeAction;
	private @Nullable Runnable removeAction;

	/**
	 * Creates a list item with an image icon.
	 *
	 * @param icon the image, or {@code null}
	 * @param name the component name
	 */
	public InstallerListItem(@Nullable Image icon, String name) {
		this(icon == null ? null : new ImageView(icon), name);
	}

	/**
	 * Creates a list item with an arbitrary icon node.
	 *
	 * @param icon the icon node, or {@code null}
	 * @param name the component name
	 */
	public InstallerListItem(@Nullable Node icon, String name) {
		HBox pane = new HBox();
		pane.getStyleClass().add("installer-item");
		pane.pseudoClassStateChanged(LIST_ITEM, true);

		if (icon != null) {
			icon.setMouseTransparent(true);
			icon.getStyleClass().add("installer-item-image");
			pane.getChildren().add(icon);
		}

		nameLabel = new Label(name);
		nameLabel.getStyleClass().add("installer-item-name");
		nameLabel.setMouseTransparent(true);
		HBox.setMargin(nameLabel, new Insets(0, 4, 0, 4));
		pane.getChildren().add(nameLabel);

		Label statusLabel = new Label();
		statusLabel.getStyleClass().add("installer-item-status");
		statusLabel.setMouseTransparent(true);
		statusLabel.textProperty().bind(status);
		HBox.setHgrow(statusLabel, Priority.ALWAYS);
		pane.getChildren().add(statusLabel);

		HBox actions = new HBox(8);
		actions.setAlignment(Pos.CENTER);
		actions.setPickOnBounds(false);
		change.setVisible(false);
		change.setManaged(false);
		remove.setVisible(false);
		remove.setManaged(false);
		change.setOnAction(event -> run(changeAction));
		remove.setOnAction(event -> run(removeAction));
		actions.getChildren().setAll(change, remove);
		pane.getChildren().add(actions);

		StackPane wrapper = new StackPane();
		wrapper.getStyleClass().add("installer-item-wrapper");
		this.rippler = new RipplerContainer(pane);
		wrapper.getChildren().setAll(rippler);
		getChildren().setAll(wrapper);
	}

	private static void run(@Nullable Runnable action) {
		if (action != null) {
			action.run();
		}
	}

	/**
	 * Sets whether to apply the wider name column style.
	 *
	 * @param wide whether the name column should be widened
	 */
	public void setWideName(boolean wide) {
		if (wide && !nameLabel.getStyleClass().contains(WIDE_NAME)) {
			nameLabel.getStyleClass().add(WIDE_NAME);
		} else if (!wide) {
			nameLabel.getStyleClass().remove(WIDE_NAME);
		}
	}

	/**
	 * Returns the status text property shown between the name and actions.
	 *
	 * @return the status property
	 */
	public StringProperty statusProperty() {
		return status;
	}

	/**
	 * Sets the action executed when the row is clicked.
	 *
	 * @param action the action, or {@code null} for no-op
	 */
	public void setOnActivate(@Nullable Runnable action) {
		activate = action;
		if (rippler != null) {
			FXUtils.onClicked(rippler, () -> {
				if (activate != null) {
					activate.run();
				}
			});
		}
	}

	/**
	 * Sets the action executed by the change button and shows or hides it.
	 *
	 * @param action  the action, or {@code null} to hide the button
	 * @param tooltip tooltip description for the change button
	 */
	public void setOnChange(@Nullable Runnable action, String tooltip) {
		this.changeAction = action;
		change.setVisible(action != null);
		change.setManaged(action != null);
		if (action != null) {
			FXUtils.installFastTooltip(change, tooltip);
		}
	}

	/**
	 * Sets the action executed by the remove button and shows or hides it.
	 *
	 * @param action  the action, or {@code null} to hide the button
	 * @param tooltip tooltip description for the remove button
	 */
	public void setOnRemove(@Nullable Runnable action, String tooltip) {
		this.removeAction = action;
		remove.setVisible(action != null);
		remove.setManaged(action != null);
		if (action != null) {
			FXUtils.installFastTooltip(remove, tooltip);
		}
	}
}
