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
import com.jfoenix.controls.JFXTextField;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.css.PseudoClass;
import javafx.scene.input.MouseEvent;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * A line text field supporting inheritance from global launcher settings.
 */
@NotNullByDefault
public class LineInheritableTextField extends LinePane {

	private static final int INHERIT_ICON_SIZE = 12;
	private static final double INHERIT_FAINT = 0.45;

	private final BooleanProperty overridden = new SimpleBooleanProperty(this, "overridden", false);
	private final JFXTextField field = new JFXTextField();
	private final JFXButton inheritButton;

	/**
	 * Creates an inheritable text field row with the given title.
	 *
	 * @param title row title
	 */
	public LineInheritableTextField(String title) {
		setTitle(title);
		field.setMinWidth(420);
		setRight(field);

		inheritButton = FXUtils.newToggleButton4(SVG.PUBLIC);
		inheritButton.setGraphic(SVG.PUBLIC.createIcon(INHERIT_ICON_SIZE));
		inheritButton.setPrefSize(22, 22);
		inheritButton.setMaxSize(22, 22);
		inheritButton.setMinSize(javafx.scene.layout.Region.USE_PREF_SIZE,
				javafx.scene.layout.Region.USE_PREF_SIZE);
		inheritButton.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
			setOverridden(!isOverridden());
			event.consume();
		});
		setTitleTrailing(inheritButton);

		overridden.addListener((observable, was, value) -> applyState());
		applyState();
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

	/**
	 * Returns the text property.
	 *
	 * @return text property
	 */
	public StringProperty textProperty() {
		return field.textProperty();
	}

	/**
	 * Returns the current text.
	 *
	 * @return text value
	 */
	public String getText() {
		return field.getText();
	}

	/**
	 * Sets the text value.
	 *
	 * @param text new text
	 */
	public void setText(@Nullable String text) {
		field.setText(text == null ? "" : text);
	}

	private void applyState() {
		boolean overridden = isOverridden();
		field.setDisable(!overridden);
		field.setOpacity(overridden ? 1.0 : INHERIT_FAINT);
		inheritButton.setGraphic((overridden ? SVG.EDIT : SVG.PUBLIC).createIcon(INHERIT_ICON_SIZE));
		FXUtils.installFastTooltip(inheritButton,
				i18n(overridden ? "dsh.settings.override.tooltip" : "dsh.settings.inherit.tooltip"));
		pseudoClassStateChanged(PseudoClass.getPseudoClass("inherited"), !overridden);
	}
}
