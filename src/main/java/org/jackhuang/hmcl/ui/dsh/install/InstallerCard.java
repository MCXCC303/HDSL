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
package org.jackhuang.hmcl.ui.dsh.install;

import com.jfoenix.controls.JFXButton;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.css.PseudoClass;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// One card of the install grid.
///
/// The node structure and style classes are the original's, because its
/// stylesheet is the one this project ships: the background, the radius, the
/// width, the drop shadow and the centring all live in rules that select
/// `.installer-item-wrapper:card` and `.installer-item-wrapper .installer-item:card`.
/// Building the card as one node wearing both names matches none of them, which
/// is why a hand-built card looks nearly right and never quite is.
///
/// The arrow is the original's too: a button carrying `SVG.ARROW_FORWARD`, not a
/// character. A glyph depends on the interface font having the character and on
/// its metrics, so it drifts from the icon set the rest of the interface uses.
@NotNullByDefault
public final class InstallerCard extends StackPane {
    /// The pseudo-class marking a card, as the stylesheet expects.
    private static final PseudoClass CARD = PseudoClass.getPseudoClass("card");

    /// The state of the choice, shown beneath the name.
    private final StringProperty status = new SimpleStringProperty();

    /// Creates a card.
    ///
    /// @param icon   the component's icon, or `null` for a card without one
    /// @param name   the component's name
    /// @param status the state of the choice
    /// @param onOpen what opening the card does, or `null` for a card that
    ///               states something rather than offering a choice
    public InstallerCard(@Nullable SVG icon, String name, String status, @Nullable Runnable onOpen) {
        VBox pane = new VBox();
        pane.getStyleClass().add("installer-item");
        pane.pseudoClassStateChanged(CARD, true);
        pane.setAlignment(Pos.CENTER);

        if (icon != null) {
            SVGContainer image = new SVGContainer(icon, 32);
            image.setMouseTransparent(true);
            image.getStyleClass().add("installer-item-image");
            // The original's spacing: nothing between the children, and the icon
            // held off the top and bottom edges instead.
            VBox.setMargin(image, new Insets(8, 0, 8, 0));
            pane.getChildren().add(image);
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

        JFXButton arrow = new JFXButton();
        arrow.setGraphic(SVG.ARROW_FORWARD.createIcon());
        arrow.getStyleClass().add("toggle-icon4");
        if (onOpen == null) {
            // The original's cards all carry the arrow, including the one that
            // states the version rather than offering it; they line up because
            // they hold the same children. A card that opens nothing simply does
            // nothing, rather than losing the row and sitting a line higher.
            arrow.setDisable(true);
        } else {
            arrow.setOnAction(event -> onOpen.run());
        }
        pane.getChildren().add(arrow);

        StackPane wrapper = new StackPane();
        wrapper.getStyleClass().add("installer-item-wrapper");
        wrapper.pseudoClassStateChanged(CARD, true);
        // One rippler, held so the card's own surface can be given the action
        // beside the arrow's. Building a second one around the same pane would
        // move the pane into it and leave the wrapper empty.
        RipplerContainer rippler = new RipplerContainer(pane);
        wrapper.getChildren().add(rippler);

        if (onOpen != null) {
            // The original wires the arrow and the card's whole surface to the
            // same action, and gives the card the hand cursor. A card that only
            // responds on its arrow is a card most of whose area does nothing,
            // and the pointer does not say so until it is over the arrow.
            rippler.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
                    onOpen.run();
                    event.consume();
                }
            });
            pane.setCursor(Cursor.HAND);
        }

        getChildren().setAll(wrapper);

        // The original ties a card's height to its width, so cards in a row stay
        // the same shape whether their text wraps or not.
        FXUtils.onWeakChangeAndOperate(widthProperty(), width ->
                FXUtils.setLimitHeight(pane, width.doubleValue() * 0.7));
    }

    /// Returns the state shown beneath the name.
    ///
    /// A property rather than a setter alone, because a card is often built
    /// before the value it reports on is known.
    ///
    /// @return the property
    public StringProperty statusProperty() {
        return status;
    }

    /// Sets the state shown beneath the name.
    ///
    /// @param value the state
    public void setStatus(String value) {
        status.set(value);
    }
}
