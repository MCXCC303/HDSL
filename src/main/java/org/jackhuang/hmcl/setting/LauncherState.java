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
package org.jackhuang.hmcl.setting;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Persisted window geometry and layout coordinates across restarts.
 */
@NotNullByDefault
public final class LauncherState {
	private final DoubleProperty x = new SimpleDoubleProperty();
	private final DoubleProperty y = new SimpleDoubleProperty();
	private final DoubleProperty width = new SimpleDoubleProperty();
	private final DoubleProperty height = new SimpleDoubleProperty();

	public double getX() {
		return x.get();
	}

	public void setX(double value) {
		x.set(value);
	}

	public double getY() {
		return y.get();
	}

	public void setY(double value) {
		y.set(value);
	}

	public double getWidth() {
		return width.get();
	}

	public void setWidth(double value) {
		width.set(value);
	}

	public double getHeight() {
		return height.get();
	}

	public void setHeight(double value) {
		height.set(value);
	}
}
