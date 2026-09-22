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
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// One row of the auto-install list.
///
/// The list half of the original's installer item, and the same `.installer-item`
/// family as [InstallerCard]: that is where the icon size, the two label styles
/// and the row's separator come from, and the original's stylesheet selects the
/// list shape with the `:list-item` pseudo-class rather than with a class of its
/// own. The original keeps both shapes in one control; this keeps the card as it
/// was and puts the row beside it, because the two are built differently — one is
/// a column sized from its width, the other a row that grows a status line and
/// carries the actions on its trailing edge.
@NotNullByDefault
public final class InstallerListItem extends StackPane {
    /// The original's pseudo-class for the list shape of an installer item.
    private static final PseudoClass LIST_ITEM = PseudoClass.getPseudoClass("list-item");

    /// The stylesheet's name for the wider name column.
    private static final String WIDE_NAME = "wide";

    /// The component's name, kept so its column can be sized.
    private final Label nameLabel;

    /// What state the component is in, shown between the name and the actions.
    private final StringProperty status = new SimpleStringProperty();

    /// The button that installs or changes the component.
    private final JFXButton change = FXUtils.newToggleButton4(SVG.UPDATE);

    /// The ripple surface the row is pressed on.
    private @Nullable RipplerContainer rippler;

    /// What pressing the row does.
    private @Nullable Runnable activate;

    /// The button that removes the component.
    private final JFXButton remove = FXUtils.newToggleButton4(SVG.CLOSE);

    /// Creates a row whose icon is a picture.
    ///
    /// @param icon the image, or `null` for none
    /// @param name the component's name
    public InstallerListItem(@Nullable Image icon, String name) {
        this(icon == null ? null : new ImageView(icon), name);
    }

    /// Creates a row whose icon is one of the launcher's marks.
    ///
    /// @param icon the mark, or `null` for none
    /// @param name the component's name
    public InstallerListItem(@Nullable SVG icon, String name) {
        this(icon == null ? null : new SVGContainer(icon, 32), name);
    }

    /// Creates a row.
    ///
    /// @param icon the icon node, already sized, or `null` for none
    /// @param name the component's name
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
        // One rippler, as the card uses: the row's surface is what ripples, and
        // building a second one around the same pane would move the pane into it.
        this.rippler = new RipplerContainer(pane);
        wrapper.getChildren().setAll(rippler);
        getChildren().setAll(wrapper);
    }

    /// The action the change button runs, or `null` when there is none.
    private @Nullable Runnable changeAction;

    /// The action the remove button runs, or `null` when there is none.
    private @Nullable Runnable removeAction;

    /// Gives the row's name column the wider width the stylesheet defines.
    ///
    /// The original sizes that column for its own component names, and the runtime
    /// this launcher lists does not fit. A page that lists several components asks
    /// for the wider column on all of them, so the state each row reports still
    /// starts in the same place.
    ///
    /// @param wide whether the name needs the wider column
    public void setWideName(boolean wide) {
        if (wide && !nameLabel.getStyleClass().contains(WIDE_NAME)) {
            nameLabel.getStyleClass().add(WIDE_NAME);
        } else if (!wide) {
            nameLabel.getStyleClass().remove(WIDE_NAME);
        }
    }

    /// Returns the state shown between the name and the actions.
    ///
    /// A property rather than a setter alone, because a row is often built before
    /// the value it reports on has been read.
    ///
    /// @return the property
    public StringProperty statusProperty() {
        return status;
    }

    /// Sets what the change button does, and shows it.
    ///
    /// @param action the action, or `null` to hide the button
    /// @param tooltip what the button does, for the pointer
    /// Makes the row itself do what its version button does.
    ///
    /// A row that looks pressable and is not is worse than one that does not look
    /// pressable: the original's lists act on the row, and the button at its end is
    /// the same action with a tooltip.
    ///
    /// @param action the action, or `null` for a row that does nothing
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

    public void setOnChange(@Nullable Runnable action, String tooltip) {
        this.changeAction = action;
        change.setVisible(action != null);
        change.setManaged(action != null);
        if (action != null) {
            FXUtils.installFastTooltip(change, tooltip);
        }
    }

    /// Sets what the remove button does, and shows it.
    ///
    /// @param action the action, or `null` to hide the button
    /// @param tooltip what the button does, for the pointer
    public void setOnRemove(@Nullable Runnable action, String tooltip) {
        this.removeAction = action;
        remove.setVisible(action != null);
        remove.setManaged(action != null);
        if (action != null) {
            FXUtils.installFastTooltip(remove, tooltip);
        }
    }

    /// Runs an action, ignoring a button that is not offering one.
    ///
    /// @param action the action, or `null`
    private static void run(@Nullable Runnable action) {
        if (action != null) {
            action.run();
        }
    }
}
