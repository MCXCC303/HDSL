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
package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * A line selection button supporting inheritance from global launcher settings.
 *
 * @param <T> the type of value chosen by this row
 */
@NotNullByDefault
public class LineInheritableSelectButton<T extends @org.jetbrains.annotations.UnknownNullability Object>
		extends LineSelectButton<T> {

	private static final int INHERIT_ICON_SIZE = 12;
	private static final SVG MANUAL_ICON = SVG.EDIT;
	private static final double INHERIT_FAINT = 0.45;

	private final BooleanProperty overridden = new SimpleBooleanProperty(this, "overridden", false);

	/**
	 * Creates an inheritable select button row.
	 */
	public LineInheritableSelectButton() {
		getStyleClass().add("line-inheritable-value");

		JFXButton inheritButton = FXUtils.newToggleButton4(SVG.PUBLIC);
		inheritButton.setGraphic(SVG.PUBLIC.createIcon(INHERIT_ICON_SIZE));
		inheritButton.setPrefSize(22, 22);
		inheritButton.setMaxSize(22, 22);
		inheritButton.setMinSize(javafx.scene.layout.Region.USE_PREF_SIZE,
				javafx.scene.layout.Region.USE_PREF_SIZE);
		FXUtils.installFastTooltip(inheritButton, i18n("dsh.settings.inherit"));
		inheritButton.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
			setOverridden(!isOverridden());
			event.consume();
		});
		setTitleTrailing(inheritButton);
		makeClickable(inheritButton);

		Runnable showState = () -> {
			boolean overridden = isOverridden();
			inheritButton.setGraphic((overridden ? SVG.TUNE : SVG.PUBLIC).createIcon(INHERIT_ICON_SIZE));
			inheritButton.pseudoClassStateChanged(
					javafx.css.PseudoClass.getPseudoClass("overridden"), overridden);
			FXUtils.installFastTooltip(inheritButton,
					i18n(overridden ? "dsh.settings.override.tooltip" : "dsh.settings.inherit.tooltip"));
		};
		overridden.addListener((observable, was, value) -> {
			showState.run();
			applyEditable();
		});
		showState.run();
		applyEditable();
	}

	private void makeClickable(javafx.scene.Node node) {
		javafx.scene.Node current = node;
		while (current != null && current != this) {
			current.setMouseTransparent(false);
			current = current.getParent();
		}
	}

	@Override
	public void fire() {
		setOverridden(true);
		super.fire();
	}

	/**
	 * Returns the overridden property.
	 *
	 * @return the overridden property
	 */
	public BooleanProperty overriddenProperty() {
		return overridden;
	}

	/**
	 * Returns whether this row overrides the inherited setting.
	 *
	 * @return {@code true} if overridden
	 */
	public boolean isOverridden() {
		return overridden.get();
	}

	/**
	 * Sets whether this row overrides the inherited setting.
	 *
	 * @param value {@code true} to override
	 */
	public void setOverridden(boolean value) {
		overridden.set(value);
	}

	private void applyEditable() {
		javafx.application.Platform.runLater(() -> {
			javafx.scene.Node value = lookup(".trailing-label");
			if (value != null) {
				value.setOpacity(isOverridden() ? 1.0 : 0.55);
			}
		});
		pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("inherited"), !isOverridden());
	}
}
