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
package org.jackhuang.hmcl.setting;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import org.jetbrains.annotations.NotNullByDefault;

/// Holds the launcher window geometry that survives a restart.
///
/// The position is stored in screen-relative units so that a window restored on
/// a differently sized display still lands inside the visible area.
@NotNullByDefault
public final class LauncherState {
    /// The horizontal window position as a fraction of the primary screen width.
    private final DoubleProperty x = new SimpleDoubleProperty();

    /// The vertical window position as a fraction of the primary screen height.
    private final DoubleProperty y = new SimpleDoubleProperty();

    /// The window content width in pixels.
    private final DoubleProperty width = new SimpleDoubleProperty();

    /// The window content height in pixels.
    private final DoubleProperty height = new SimpleDoubleProperty();

    /// Returns the horizontal window position fraction.
    ///
    /// @return the horizontal position
    public double getX() {
        return x.get();
    }

    /// Sets the horizontal window position fraction.
    ///
    /// @param value the horizontal position
    public void setX(double value) {
        x.set(value);
    }

    /// Returns the vertical window position fraction.
    ///
    /// @return the vertical position
    public double getY() {
        return y.get();
    }

    /// Sets the vertical window position fraction.
    ///
    /// @param value the vertical position
    public void setY(double value) {
        y.set(value);
    }

    /// Returns the window content width in pixels.
    ///
    /// @return the content width
    public double getWidth() {
        return width.get();
    }

    /// Sets the window content width in pixels.
    ///
    /// @param value the content width
    public void setWidth(double value) {
        width.set(value);
    }

    /// Returns the window content height in pixels.
    ///
    /// @return the content height
    public double getHeight() {
        return height.get();
    }

    /// Sets the window content height in pixels.
    ///
    /// @param value the content height
    public void setHeight(double value) {
        height.set(value);
    }
}
