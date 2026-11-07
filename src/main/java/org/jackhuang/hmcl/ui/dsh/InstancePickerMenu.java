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

import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXPopup;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcons;
import com.mcxcc303.hdsl.launch.DshProcess;
import com.mcxcc303.hdsl.launch.DshProcessManager;
import com.mcxcc303.hdsl.launch.DshProcessManager.LaunchState;
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

/**
 * Dropdown list allowing quick selection of an instance from the main launch pane.
 */
@NotNullByDefault
public final class InstancePickerMenu extends JFXListView<DshInstance> {
	private static final double MAX_HEIGHT = 365;
	private static final double CELL_HEIGHT = 50;
	private static final double WIDTH = 300;

	private final Consumer<DshInstance> onSelect;

	/**
	 * Creates an instance picker menu.
	 *
	 * @param instances instances to display
	 * @param onSelect  callback when an instance is selected
	 */
	public InstancePickerMenu(List<DshInstance> instances, Consumer<DshInstance> onSelect) {
		this.onSelect = onSelect;

		setMaxHeight(MAX_HEIGHT);
		setFixedCellSize(CELL_HEIGHT);
		setPrefWidth(WIDTH);
		setCellFactory(Cell::new);
		getStyleClass().add("popup-menu-content");
		getItems().setAll(instances);
	}

	/**
	 * Displays the picker menu positioned relative to the owner node.
	 *
	 * @param owner     anchor node
	 * @param instances instances to offer
	 * @param onSelect  selection callback
	 * @return the popup instance
	 */
	public static JFXPopup show(Node owner, List<DshInstance> instances, Consumer<DshInstance> onSelect) {
		InstancePickerMenu menu = new InstancePickerMenu(instances, onSelect);
		JFXPopup popup = new JFXPopup(menu);
		popup.show(owner,
		           JFXPopup.PopupVPosition.BOTTOM,
		           JFXPopup.PopupHPosition.RIGHT,
		           0,
		           -owner.getBoundsInLocal().getHeight());
		return popup;
	}

	private final class Cell extends ListCell<DshInstance> {
		private final ImageContainer icon = new ImageContainer(32);
		private final TwoLineListItem content = new TwoLineListItem();
		private final StringProperty tag = new SimpleStringProperty();
		private final Region graphic;

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

			LaunchState state = DshLaunchService.state(instance.id());
			DshProcess running = DshProcessManager.find(instance.id()).orElse(null);
			content.setTitle(instance.id());
			content.setSubtitle(state == LaunchState.RUNNING && running != null
			                    ? i18n("dsh.instance.running.since",
			                           running.uptime().toSeconds())
			                    : i18n("dsh.instance.summary",
			                           instance.version(),
			                           instance.profile(),
			                           i18n("dsh.instance.home." +
			                                instance.homeMode().name().toLowerCase(Locale.ROOT))));
			tag.set(DshLaunchService.stateTag(state));
		}
	}
}
