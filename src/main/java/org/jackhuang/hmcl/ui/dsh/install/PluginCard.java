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
import com.mcxcc303.hdsl.harness.DshPreset;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Skin;
import javafx.scene.control.SkinBase;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * A selectable plugin card in the quick-install grid.
 */
@NotNullByDefault
public final class PluginCard extends Control {
	private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

	private final DshPreset preset;
	private final BooleanProperty selected = new SimpleBooleanProperty(this, "selected", false);
	private final ObjectProperty<@Nullable Runnable> onConfigure = new SimpleObjectProperty<>(this, "onConfigure");
	private final ObjectProperty<@Nullable String> chosenVersion = new SimpleObjectProperty<>(this, "chosenVersion");
	private Runnable onClear = () -> {};

	/**
	 * Creates a card for a catalog preset.
	 *
	 * @param preset   the preset entry
	 * @param selected whether the card starts selected
	 */
	public PluginCard(DshPreset preset, boolean selected) {
		this.preset = preset;

		getStyleClass().add("installer-item-card");
		this.selected.set(selected);
		this.selected.addListener((observable, oldValue, newValue) -> pseudoClassStateChanged(SELECTED, newValue));
		pseudoClassStateChanged(SELECTED, selected);

		setOnMouseClicked(event -> {
			Runnable action = onConfigure.get();
			if (action != null) {
				action.run();
			}
		});
	}

	/**
	 * Sets the action executed when the card is activated.
	 *
	 * @param action the action
	 */
	public void setOnConfigure(Runnable action) {
		onConfigure.set(action);
	}

	/**
	 * Returns the version currently displayed.
	 *
	 * @return the version, an empty string for latest, or {@code null} if not installing
	 */
	public @Nullable String chosenVersion() {
		return chosenVersion.get();
	}

	/**
	 * Sets the version to display.
	 *
	 * @param version the version, or {@code null} for not installing
	 */
	public void setChosenVersion(@Nullable String version) {
		chosenVersion.set(version);
		selected.set(version != null);
		pseudoClassStateChanged(SELECTED, version != null);
	}

	/**
	 * Returns the catalog preset.
	 *
	 * @return the preset
	 */
	public DshPreset preset() {
		return preset;
	}

	/**
	 * Returns whether the plugin is selected for installation.
	 *
	 * @return whether selected
	 */
	public boolean isSelected() {
		return selected.get();
	}

	/**
	 * Returns the selection property.
	 *
	 * @return the selection property
	 */
	public BooleanProperty selectedProperty() {
		return selected;
	}

	private void clearChosenVersion() {
		setChosenVersion(null);
		onClear.run();
	}

	private void configure() {
		@Nullable Runnable action = onConfigure.get();
		if (action != null) {
			action.run();
		}
	}

	/**
	 * Sets the callback invoked when the version choice is cleared.
	 *
	 * @param action the clear action
	 */
	public void setOnClear(Runnable action) {
		onClear = action;
	}

	/**
	 * Returns the chosen version property.
	 *
	 * @return the chosen version property
	 */
	public ObjectProperty<@Nullable String> chosenVersionProperty() {
		return chosenVersion;
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new PluginCardSkin(this);
	}

	/**
	 * Renders the icon, name and status of a plugin card.
	 */
	private static final class PluginCardSkin extends SkinBase<PluginCard> {
		private PluginCardSkin(PluginCard control) {
			super(control);

			VBox pane = new VBox();
			pane.getStyleClass().add("installer-item");
			pane.pseudoClassStateChanged(PseudoClass.getPseudoClass("card"), true);

			Node icon;
			if (control.preset().id().equals("dshmarket")) {
				ImageContainer picture = new ImageContainer(32);
				picture.setImage(DshInstanceIcon.FABRIC.load());
				icon = picture;
			} else {
				icon = new SVGContainer(iconFor(control.preset()), 32);
			}
			icon.setMouseTransparent(true);
			icon.getStyleClass().add("installer-item-image");
			VBox.setMargin(icon, new Insets(8, 0, 8, 0));
			pane.getChildren().add(icon);

			Label name = new Label(control.preset().name());
			name.getStyleClass().add("installer-item-name");
			name.setMouseTransparent(true);
			pane.getChildren().add(name);

			Label status = new Label();
			status.getStyleClass().add("installer-item-status");
			status.setMouseTransparent(true);
			status.textProperty().bind(Bindings.createStringBinding(() -> {
				String version = control.chosenVersion();
				if (version == null) {
					return i18n("dsh.install.plugin.not_installing");
				}
				return version.isEmpty() ? i18n("dsh.install.plugin.latest") : version;
			}, control.chosenVersionProperty()));
			pane.getChildren().add(status);

			HBox buttons = new HBox(8);
			buttons.setAlignment(Pos.CENTER);

			JFXButton remove = FXUtils.newToggleButton4(SVG.CLOSE);
			FXUtils.installFastTooltip(remove, i18n("dsh.install.plugin.not_installing"));
			remove.visibleProperty().bind(control.chosenVersionProperty().isNotNull());
			remove.managedProperty().bind(remove.visibleProperty());
			remove.setOnAction(event -> control.clearChosenVersion());

			JFXButton choose = new JFXButton();
			choose.graphicProperty()
			      .bind(Bindings.createObjectBinding(() -> control.chosenVersion() == null
			                                               ? SVG.ARROW_FORWARD.createIcon()
			                                               : SVG.UPDATE.createIcon(), control.chosenVersionProperty()));
			choose.getStyleClass().add("toggle-icon4");
			FXUtils.installFastTooltip(choose, i18n("dsh.install.plugin.choose"));
			choose.setOnAction(event -> control.configure());

			buttons.getChildren().setAll(remove, choose);
			pane.getChildren().add(buttons);

			StackPane wrapper = new StackPane();
			wrapper.getStyleClass().add("installer-item-wrapper");
			wrapper.pseudoClassStateChanged(PseudoClass.getPseudoClass("card"), true);
			wrapper.getChildren().add(new RipplerContainer(pane));

			getChildren().setAll(wrapper);

			FXUtils.onWeakChangeAndOperate(control.widthProperty(),
			                               width -> FXUtils.setLimitHeight(pane, width.doubleValue() * 0.7));
		}

		private static SVG iconFor(DshPreset preset) {
			return switch (preset.id()) {
				case "dshmarket" -> SVG.EXTENSION;
				case "better-sidebar", "skin-center" -> SVG.STYLE;
				case "context", "cost-meter" -> SVG.TUNE;
				case "git-graph" -> SVG.FOLDER_COPY;
				case "subagent-codex", "subagent-claude-code", "agent-team" -> SVG.ROCKET_LAUNCH;
				default -> SVG.EXTENSION_FILL;
			};
		}
	}
}
