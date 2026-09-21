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

import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXPopup;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceIcons;
import org.jackhuang.hmcl.dsh.DshProcess;
import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.jackhuang.hmcl.dsh.DshProcessManager.LaunchState;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The list of instances shown by the launch pane's arrow.
///
/// HMCL's menu is a bounded, scrolling list rather than a menu that grows with
/// its contents: it caps its height and scrolls, so a machine with thirty
/// instances still gets a menu that fits on screen. Its rows also carry the
/// instance's icon, which is what makes the list scannable — the name alone
/// leaves the reader matching text.
@NotNullByDefault
public final class InstancePickerMenu extends JFXListView<DshInstance> {
    /// The greatest height the menu will take before scrolling.
    ///
    /// HMCL's value; it is what keeps the menu shorter than the window behind it.
    private static final double MAX_HEIGHT = 365;

    /// The height of one row.
    private static final double CELL_HEIGHT = 50;

    /// The width of the menu.
    private static final double WIDTH = 300;

    /// Called when an instance is chosen.
    private final Consumer<DshInstance> onSelect;

    /// Creates the menu.
    ///
    /// @param instances the instances to offer
    /// @param onSelect  run with the chosen instance
    public InstancePickerMenu(List<DshInstance> instances, Consumer<DshInstance> onSelect) {
        this.onSelect = onSelect;

        setMaxHeight(MAX_HEIGHT);
        setFixedCellSize(CELL_HEIGHT);
        setPrefWidth(WIDTH);
        setCellFactory(Cell::new);
        getStyleClass().add("popup-menu-content");
        getItems().setAll(instances);
    }

    /// Shows the menu above a node.
    ///
    /// @param owner the node the menu is anchored to
    /// @param instances the instances to offer
    /// @param onSelect run with the chosen instance
    /// @return the popup, so the caller can close it
    public static JFXPopup show(Node owner, List<DshInstance> instances, Consumer<DshInstance> onSelect) {
        InstancePickerMenu menu = new InstancePickerMenu(instances, onSelect);
        JFXPopup popup = new JFXPopup(menu);
        popup.show(owner, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.RIGHT,
                0, -owner.getBoundsInLocal().getHeight());
        return popup;
    }

    /// One row of the picker.
    ///
    /// The shape is HMCL's: the instance's icon, its name and a summary line,
    /// inside a rippling row that reports the selection.
    private final class Cell extends ListCell<DshInstance> {
        /// The instance icon.
        private final ImageContainer icon = new ImageContainer(32);

        /// The name and summary.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The tag shown beside the name, used here for the running state.
        private final StringProperty tag = new SimpleStringProperty();

        /// The row's graphic, re-installed for every item.
        private final Region graphic;

        /// Creates the cell.
        ///
        /// @param listView the owning list
        Cell(ListView<DshInstance> listView) {
            setPadding(Insets.EMPTY);

            icon.setMouseTransparent(true);
            BorderPane.setAlignment(icon, Pos.CENTER);

            content.setMouseTransparent(true);
            FXUtils.onChangeAndOperate(tag, value -> {
                content.getTags().clear();
                if (value != null && !value.isBlank()) {
                    content.addTag(value);
                }
            });

            BorderPane container = new BorderPane();
            container.getStyleClass().add("container");
            container.setPickOnBounds(false);
            container.setLeft(icon);
            container.setCenter(content);

            RipplerContainer rippler = new RipplerContainer(container);

            StackPane rootPane = new StackPane();
            rootPane.getStyleClass().add("advanced-list-item");
            rootPane.getChildren().setAll(rippler);
            rootPane.maxWidthProperty().bind(listView.widthProperty().subtract(5));

            FXUtils.onClicked(rootPane, () -> {
                DshInstance instance = getItem();
                if (instance != null) {
                    onSelect.accept(instance);
                    if (getScene() != null && getScene().getWindow() instanceof JFXPopup popup) {
                        popup.hide();
                    }
                }
            });

            this.graphic = rootPane;
        }

        @Override
        protected void updateItem(@Nullable DshInstance instance, boolean empty) {
            DshInstance old = getItem();
            boolean wasEmpty = isEmpty();

            super.updateItem(instance, empty);

            if (old == instance && wasEmpty == empty) {
                return;
            }

            if (empty || instance == null) {
                setGraphic(null);
                return;
            }

            setGraphic(graphic);
            icon.setImage(DshInstanceIcons.load(instance));

            // What the row says is the instance's state, taken from the one place
            // that decides it, so a picker row and a list row cannot disagree.
            LaunchState state = DshLaunchService.state(instance.id());
            DshProcess running = DshProcessManager.find(instance.id()).orElse(null);
            content.setTitle(instance.id());
            content.setSubtitle(state == LaunchState.RUNNING && running != null
                    ? i18n("dsh.instance.running.since", running.uptime().toSeconds())
                    : i18n("dsh.instance.summary", instance.version(), instance.profile(),
                            i18n("dsh.instance.home." + instance.homeMode().name().toLowerCase(Locale.ROOT))));
            tag.set(DshLaunchService.stateTag(state));
        }
    }
}
