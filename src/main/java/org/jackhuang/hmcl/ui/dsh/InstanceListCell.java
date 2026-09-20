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
import com.jfoenix.controls.JFXRadioButton;
import javafx.event.ActionEvent;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ListCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceIcons;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// One row of the instance list.
///
/// The layout follows HMCL's `GameListCell`: a radio button that chooses the
/// instance, the instance icon and its two-line label, and the per-row actions
/// pinned to the right. Reaching the original typography depends on using
/// [TwoLineListItem] rather than a generic line row, because the title and
/// subtitle sizes live in the `two-line-list-item` CSS rules.
///
/// The graphic is built once and reused, as a `ListCell` is recycled by the
/// virtual flow; only its contents change between items.
@NotNullByDefault
public final class InstanceListCell extends ListCell<DshInstance> {
    /// The radio button that chooses the instance.
    private final JFXRadioButton selector = new JFXRadioButton() {
        @Override
        public void fire() {
            DshInstance instance = getItem();
            if (!isDisable() && !isSelected() && instance != null) {
                fireEvent(new ActionEvent());
                onSelect.accept(instance);
            }
        }
    };

    /// The instance icon.
    private final ImageContainer icon = new ImageContainer(32);

    /// The instance name, summary and tag.
    private final TwoLineListItem content = new TwoLineListItem();

    /// The launch or stop button.
    private final JFXButton launch = FXUtils.newToggleButton4(SVG.ROCKET_LAUNCH);

    /// The menu button.
    private final JFXButton menu = FXUtils.newToggleButton4(SVG.MORE_VERT);

    /// The row's graphic, built once.
    private final RipplerContainer graphic;

    /// Called when the radio button chooses the instance.
    private Consumer<DshInstance> onSelect = instance -> {
    };

    /// Called when the launch button is pressed.
    private Consumer<DshInstance> onLaunch = instance -> {
    };

    /// Called when the menu button is pressed; receives the row and its anchor.
    private BiConsumer<DshInstance, JFXButton> onMenu = (instance, anchor) -> {
    };

    /// Supplies the id the radio buttons treat as selected.
    private Supplier<@Nullable String> selectedId = () -> null;

    /// Reports whether an instance is running, which swaps the launch icon.
    private Predicate<DshInstance> runningCheck = instance -> false;

    /// Creates the cell.
    public InstanceListCell() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("md-list-cell");
        root.setPadding(new Insets(8, 8, 8, 0));

        selector.setMouseTransparent(false);
        root.setLeft(selector);
        BorderPane.setAlignment(selector, Pos.CENTER);

        HBox center = new HBox(8);
        center.setAlignment(Pos.CENTER_LEFT);
        center.setMouseTransparent(true);
        // The original holds the middle at its preferred width and centres the
        // text within the row rather than stretching it.
        center.setPrefWidth(Region.USE_PREF_SIZE);
        BorderPane.setMargin(center, new Insets(0, 0, 0, 8));
        BorderPane.setAlignment(content, Pos.CENTER);
        center.getChildren().setAll(icon, content);
        root.setCenter(center);

        HBox right = new HBox();
        right.setAlignment(Pos.CENTER_RIGHT);
        launch.setOnAction(event -> {
            DshInstance instance = getItem();
            if (instance != null) {
                onLaunch.accept(instance);
            }
        });
        menu.setOnAction(event -> {
            DshInstance instance = getItem();
            if (instance != null) {
                onMenu.accept(instance, menu);
            }
        });
        right.getChildren().addAll(launch, menu);
        root.setRight(right);

        this.graphic = new RipplerContainer(root);
        setGraphic(graphic);

        // The whole row chooses the instance, which is what the original does.
        // A row that only responds on its radio button is a row most of whose
        // surface does nothing, and the pointer says so before it is clicked.
        root.setCursor(Cursor.HAND);
        graphic.setOnMouseClicked(event -> {
            DshInstance instance = getItem();
            if (instance != null && event.getButton() == MouseButton.PRIMARY) {
                onSelect.accept(instance);
            }
        });
    }

    /// Sets the handlers the row reports to.
    ///
    /// @param onSelect called when the radio button chooses the instance
    /// @param onLaunch called when the launch button is pressed
    /// @param onMenu   called when the menu button is pressed
    public void setHandlers(Consumer<DshInstance> onSelect,
                            Consumer<DshInstance> onLaunch,
                            BiConsumer<DshInstance, JFXButton> onMenu) {
        this.onSelect = onSelect;
        this.onLaunch = onLaunch;
        this.onMenu = onMenu;
    }

    /// Sets how the cell learns which instance is selected.
    ///
    /// A supplier is used rather than a value because the virtual flow recycles
    /// cells: the selected instance can change while a cell is in place.
    ///
    /// @param supplier supplies the selected instance's id, or `null`
    public void setSelectedIdSupplier(Supplier<@Nullable String> supplier) {
        this.selectedId = supplier;
    }

    /// Sets how the cell learns whether an instance is running.
    ///
    /// @param check reports whether the given instance is running
    public void setRunningCheck(Predicate<DshInstance> check) {
        this.runningCheck = check;
    }

    @Override
    protected void updateItem(@Nullable DshInstance instance, boolean empty) {
        super.updateItem(instance, empty);

        if (empty || instance == null) {
            setGraphic(null);
            return;
        }

        setGraphic(graphic);

        selector.setSelected(instance.id().equals(selectedId.get()));
        icon.setImage(DshInstanceIcons.load(instance));
        content.setTitle(instance.id());
        content.setSubtitle(i18n("dsh.instance.summary",
                instance.version(),
                instance.profile(),
                i18n("dsh.instance.home." + instance.homeMode().name().toLowerCase(Locale.ROOT))));

        boolean running = runningCheck.test(instance);
        SVG action = running ? SVG.CANCEL : SVG.ROCKET_LAUNCH;
        launch.setGraphic(action.createIcon(20));
        FXUtils.installFastTooltip(launch, running ? i18n("dsh.stop") : i18n("dsh.launch"));
        FXUtils.installFastTooltip(menu, i18n("dsh.instance.menu"));
    }
}
