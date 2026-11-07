/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.construct;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXSwitch;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.css.PseudoClass;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * A line component editing an inheritable boolean setting.
 */
@NotNullByDefault
public final class LineInheritableSwitch extends LineButtonBase {

	private static final String DEFAULT_STYLE_CLASS = "line-inheritable-switch";
	private static final PseudoClass PSEUDO_OVERRIDDEN = PseudoClass.getPseudoClass("overridden");
	private static final String INHERIT_BUTTON_STYLE_CLASS = "toggle-icon-tiny";
	private static final int INHERIT_BUTTON_ICON_SIZE = 12;
	private static final double INHERIT_FAINT = 0.45;

	private final JFXButton inheritButton;
	private final Tooltip inheritTooltip;
	private final JFXSwitch switchControl;
	private final BooleanProperty rawValue = new SimpleBooleanProperty(this, "rawValue");
	private final BooleanProperty overridden = new SimpleBooleanProperty(this, "overridden");
	private final BooleanProperty inheritAvailable = new SimpleBooleanProperty(this, "inheritAvailable", true);
	private final StringProperty inheritedTooltip = new SimpleStringProperty(this, "inheritedTooltip", "");
	private final StringProperty overriddenTooltip = new SimpleStringProperty(this, "overriddenTooltip", "");

	/**
	 * Creates an inheritable boolean toggle row.
	 */
	public LineInheritableSwitch() {
		this.getStyleClass().addAll(DEFAULT_STYLE_CLASS, "line-switch");
		this.getStyleClass().add("line-inheritable-value");

		this.inheritButton = FXUtils.newToggleButton4(SVG.PUBLIC);
		this.inheritTooltip = new Tooltip();
		setInheritedTooltip(i18n("dsh.settings.inherit.tooltip"));
		setOverriddenTooltip(i18n("dsh.settings.override.tooltip"));
		FXUtils.installFastTooltip(inheritButton, inheritTooltip);
		inheritButton.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
			if (!isInheritAvailable()) {
				return;
			}

			if (!isOverridden()) {
				setOverridden(true);
			} else {
				setOverridden(false);
			}
			super.fire();
			event.consume();
		});

		this.switchControl = new JFXSwitch();
		switchControl.setMouseTransparent(true);

		setTitleTrailing(inheritButton);
		setRowTrailing(switchControl);

		rawValue.addListener(observable -> refresh());
		overridden.addListener(observable -> refresh());
		inheritAvailable.addListener(observable -> refresh());
		inheritedTooltip.addListener(observable -> refresh());
		overriddenTooltip.addListener(observable -> refresh());
		refresh();
	}

	@Override
	public void fire() {
		setOverridden(true);
		setRawValue(!getRawValue());
		super.fire();
	}

	private void refresh() {
		boolean inheritAvailable = isInheritAvailable();
		boolean inherited = inheritAvailable && !isOverridden();
		boolean overridden = inheritAvailable && isOverridden();

		inheritButton.setGraphic((inherited ? SVG.PUBLIC : SVG.TUNE).createIcon(INHERIT_BUTTON_ICON_SIZE));
		inheritButton.pseudoClassStateChanged(PSEUDO_OVERRIDDEN, overridden);
		inheritButton.setVisible(inheritAvailable);
		inheritButton.setManaged(inheritAvailable);
		inheritTooltip.setText(inherited ? getInheritedTooltip() : getOverriddenTooltip());

		switchControl.setSelected(getRawValue());
		switchControl.setOpacity(inherited ? INHERIT_FAINT : 1.0);
	}

	/**
	 * Returns the raw setting value property.
	 *
	 * @return the raw value property
	 */
	public BooleanProperty rawValueProperty() {
		return rawValue;
	}

	/**
	 * Returns the raw setting value.
	 *
	 * @return raw boolean value
	 */
	public boolean getRawValue() {
		return rawValueProperty().get();
	}

	/**
	 * Sets the raw setting value.
	 *
	 * @param rawValue raw boolean value
	 */
	public void setRawValue(boolean rawValue) {
		rawValueProperty().set(rawValue);
	}

	/**
	 * Returns the overridden property.
	 *
	 * @return overridden property
	 */
	public BooleanProperty overriddenProperty() {
		return overridden;
	}

	/**
	 * Returns whether the value overrides the inherited value.
	 *
	 * @return {@code true} if overridden
	 */
	public boolean isOverridden() {
		return overriddenProperty().get();
	}

	/**
	 * Sets whether the value overrides the inherited value.
	 *
	 * @param overridden {@code true} to override
	 */
	public void setOverridden(boolean overridden) {
		overriddenProperty().set(overridden);
	}

	/**
	 * Returns the inherit-available property.
	 *
	 * @return inherit-available property
	 */
	public BooleanProperty inheritAvailableProperty() {
		return inheritAvailable;
	}

	/**
	 * Returns whether inheritance can be selected.
	 *
	 * @return {@code true} if inheritance is available
	 */
	public boolean isInheritAvailable() {
		return inheritAvailableProperty().get();
	}

	/**
	 * Sets whether inheritance can be selected.
	 *
	 * @param inheritAvailable {@code true} if inheritance is available
	 */
	public void setInheritAvailable(boolean inheritAvailable) {
		inheritAvailableProperty().set(inheritAvailable);
	}

	/**
	 * Returns the tooltip displayed in inherited mode.
	 *
	 * @return inherited tooltip property
	 */
	public StringProperty inheritedTooltipProperty() {
		return inheritedTooltip;
	}

	/**
	 * Returns the tooltip displayed in inherited mode.
	 *
	 * @return tooltip text
	 */
	public String getInheritedTooltip() {
		return inheritedTooltipProperty().get();
	}

	/**
	 * Sets the tooltip displayed in inherited mode.
	 *
	 * @param inheritedTooltip tooltip text
	 */
	public void setInheritedTooltip(String inheritedTooltip) {
		inheritedTooltipProperty().set(inheritedTooltip);
	}

	/**
	 * Returns the tooltip displayed in overridden mode.
	 *
	 * @return overridden tooltip property
	 */
	public StringProperty overriddenTooltipProperty() {
		return overriddenTooltip;
	}

	/**
	 * Returns the tooltip displayed in overridden mode.
	 *
	 * @return tooltip text
	 */
	public String getOverriddenTooltip() {
		return overriddenTooltipProperty().get();
	}

	/**
	 * Sets the tooltip displayed in overridden mode.
	 *
	 * @param overriddenTooltip tooltip text
	 */
	public void setOverriddenTooltip(String overriddenTooltip) {
		overriddenTooltipProperty().set(overriddenTooltip);
	}

	/**
	 * Sets the tooltip displayed on the inherit button in inherited mode.
	 *
	 * @param tooltip tooltip text
	 */
	public void setInheritTooltip(String tooltip) {
		setInheritedTooltip(tooltip);
	}
}
