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
import com.jfoenix.controls.JFXRadioButton;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcons;
import com.mcxcc303.hdsl.launch.DshProcessManager.LaunchState;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.ListCell;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
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
import java.util.function.Function;
import java.util.function.Supplier;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * List cell rendering an instance row with radio selector, icon, details, and action buttons.
 */
@NotNullByDefault
public final class InstanceListCell extends ListCell<DshInstance> {
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

	private final ImageContainer icon = new ImageContainer(32);
	private final TwoLineListItem content = new TwoLineListItem();
	private final StringProperty stateTag = new SimpleStringProperty();
	private final JFXButton launch = FXUtils.newToggleButton4(SVG.ROCKET_LAUNCH);
	private final JFXButton menu = FXUtils.newToggleButton4(SVG.MORE_VERT);
	private final RipplerContainer graphic;

	private Consumer<DshInstance> onSelect = instance -> {
	};
	private Consumer<DshInstance> onOpen = instance -> {
	};
	private Consumer<DshInstance> onLaunch = instance -> {
	};
	private BiConsumer<DshInstance, JFXButton> onMenu = (instance, anchor) -> {
	};

	private Supplier<@Nullable DshInstance> selectedInstance = () -> null;
	private Function<DshInstance, LaunchState> stateCheck = instance -> LaunchState.STOPPED;

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
		center.setPrefWidth(Region.USE_PREF_SIZE);
		BorderPane.setMargin(center, new Insets(0, 0, 0, 8));
		BorderPane.setAlignment(content, Pos.CENTER);
		center.getChildren().setAll(icon, content);
		root.setCenter(center);

		FXUtils.onChangeAndOperate(stateTag, tag -> {
			content.getTags().clear();
			if (tag != null && !tag.isBlank()) {
				content.addTag(tag);
			}
		});

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

		root.setCursor(Cursor.HAND);
		graphic.setOnMouseClicked(event -> {
			DshInstance instance = getItem();
			if (instance != null && event.getButton() == MouseButton.PRIMARY) {
				onOpen.accept(instance);
			}
		});
	}

	/**
	 * Sets callbacks for cell actions.
	 */
	public void setHandlers(Consumer<DshInstance> onSelect,
	                        Consumer<DshInstance> onOpen,
	                        Consumer<DshInstance> onLaunch,
	                        BiConsumer<DshInstance, JFXButton> onMenu) {
		this.onSelect = onSelect;
		this.onOpen = onOpen;
		this.onLaunch = onLaunch;
		this.onMenu = onMenu;
	}

	public void setSelectedInstanceSupplier(Supplier<@Nullable DshInstance> supplier) {
		this.selectedInstance = supplier;
	}

	public void setStateCheck(Function<DshInstance, LaunchState> check) {
		this.stateCheck = check;
	}

	@Override
	protected void updateItem(@Nullable DshInstance instance, boolean empty) {
		super.updateItem(instance, empty);

		if (empty || instance == null) {
			setGraphic(null);
			return;
		}

		setGraphic(graphic);

		DshInstance selected = selectedInstance.get();
		selector.setSelected(selected != null && instance.id().equals(selected.id()));
		icon.setImage(DshInstanceIcons.load(instance));
		content.setTitle(instance.id());
		content.setSubtitle(i18n("dsh.instance.summary",
		                         instance.version(),
		                         instance.profile(),
		                         i18n("dsh.instance.home." + instance.homeMode().name().toLowerCase(Locale.ROOT))));

		LaunchState state = stateCheck.apply(instance);
		SVG action = state == LaunchState.STOPPED ? SVG.ROCKET_LAUNCH : SVG.CANCEL;
		launch.setGraphic(action.createIcon());
		FXUtils.installFastTooltip(launch, DshLaunchService.actionHint(state));
		FXUtils.installFastTooltip(menu, i18n("dsh.instance.menu"));
		stateTag.set(DshLaunchService.stateTag(state));
	}
}
