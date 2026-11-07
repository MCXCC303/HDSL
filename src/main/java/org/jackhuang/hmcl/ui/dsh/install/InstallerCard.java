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
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * A card component representing an installable option in the install wizard grid.
 */
@NotNullByDefault
public final class InstallerCard extends StackPane {
	private static final PseudoClass CARD = PseudoClass.getPseudoClass("card");

	private final StringProperty status = new SimpleStringProperty();

	/**
	 * Creates a card with an image icon.
	 *
	 * @param icon   the component icon image, or {@code null}
	 * @param name   the component name
	 * @param status the status text displayed below the name
	 * @param onOpen action executed when the card is activated, or {@code null}
	 */
	public InstallerCard(@Nullable Image icon, String name, String status, @Nullable Runnable onOpen) {
		this(icon == null ? null : new ImageView(icon), name, status, onOpen);
	}

	/**
	 * Creates a card with an SVG icon.
	 *
	 * @param icon   the SVG icon, or {@code null}
	 * @param name   the component name
	 * @param status the status text displayed below the name
	 * @param onOpen action executed when the card is activated, or {@code null}
	 */
	public InstallerCard(@Nullable SVG icon, String name, String status, @Nullable Runnable onOpen) {
		this(icon == null ? null : new SVGContainer(icon, 32), name, status, onOpen);
	}

	/**
	 * Creates a card with an arbitrary icon node.
	 *
	 * @param icon   the icon node, or {@code null}
	 * @param name   the component name
	 * @param status the status text displayed below the name
	 * @param onOpen action executed when the card is activated, or {@code null}
	 */
	public InstallerCard(@Nullable Node icon, String name, String status, @Nullable Runnable onOpen) {
		VBox pane = new VBox();
		pane.getStyleClass().add("installer-item");
		pane.pseudoClassStateChanged(CARD, true);
		pane.setAlignment(Pos.CENTER);

		if (icon != null) {
			icon.setMouseTransparent(true);
			icon.getStyleClass().add("installer-item-image");
			VBox.setMargin(icon, new Insets(8, 0, 8, 0));
			pane.getChildren().add(icon);
		}

		Label nameLabel = new Label(name);
		nameLabel.getStyleClass().add("installer-item-name");
		nameLabel.setMouseTransparent(true);
		pane.getChildren().add(nameLabel);

		Label statusLabel = new Label();
		statusLabel.getStyleClass().add("installer-item-status");
		statusLabel.setMouseTransparent(true);
		statusLabel.textProperty().bind(this.status);
		pane.getChildren().add(statusLabel);
		setStatus(status);

		if (onOpen != null) {
			JFXButton arrow = new JFXButton();
			arrow.setGraphic(SVG.ARROW_FORWARD.createIcon());
			arrow.getStyleClass().add("toggle-icon4");
			arrow.setOnAction(event -> onOpen.run());
			pane.getChildren().add(arrow);
		}

		StackPane wrapper = new StackPane();
		wrapper.getStyleClass().add("installer-item-wrapper");
		wrapper.pseudoClassStateChanged(CARD, true);
		RipplerContainer rippler = new RipplerContainer(pane);
		wrapper.getChildren().add(rippler);

		if (onOpen != null) {
			rippler.setOnMouseClicked(event -> {
				if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
					onOpen.run();
					event.consume();
				}
			});
			pane.setCursor(Cursor.HAND);
		}

		getChildren().setAll(wrapper);

		FXUtils.onWeakChangeAndOperate(widthProperty(),
		                               width -> FXUtils.setLimitHeight(pane, width.doubleValue() * 0.7));
	}

	/**
	 * Returns the status property shown beneath the name.
	 *
	 * @return the status property
	 */
	public StringProperty statusProperty() {
		return status;
	}

	/**
	 * Sets the status text shown beneath the name.
	 *
	 * @param value the status text
	 */
	public void setStatus(String value) {
		status.set(value);
	}
}
