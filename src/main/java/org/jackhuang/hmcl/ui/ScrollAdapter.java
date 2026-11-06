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
package org.jackhuang.hmcl.ui;

import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.ScrollEvent;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Bridges control geometry and native skin dispatch to the shared scrolling policy.
@NotNullByDefault
abstract class ScrollAdapter {
    /// Whether native skins need zero-distance input to animate overlay scrollbar visibility.
    static final boolean TOUCH_SUPPORTED = Platform.isSupported(ConditionalFeature.INPUT_TOUCH);

    /// Base wheel pixel multiplier scaled by the startup `hdsl.scroll.speed` preference.
    private static final double WHEEL_SPEED = readWheelSpeed(
            System.getProperty("hdsl.scroll.speed", System.getenv("HDSL_SCROLL_SPEED")));

    /// Resolves a factor or percentage to a wheel pixel multiplier, using the default for invalid values.
    static double readWheelSpeed(@Nullable String value) {
        final double speedBase = 3.0;
        double speedScale = 1.0;
        try {
            if (StringUtils.isNotBlank(value)) {
                value = value.strip();
                speedScale = value.endsWith("%")
                        ? Double.parseDouble(value.substring(0, value.length() - 1)) / 100.0
                        : Double.parseDouble(value);
            }
        } catch (NumberFormatException ignored) {
        }
        return Double.isFinite(speedScale) && speedScale > 0
                ? speedBase * Math.max(speedScale, 0.0001)
                : speedBase;
    }

    /// Coordinates animation and gestures independently of this control's geometry.
    final ScrollController controller;

    /// Installs common lifecycle observation before the adapter's skin hooks.
    ScrollAdapter(Node node) {
        controller = new ScrollController(node, this);
    }

    /// Returns the scrollable pixel distance available in the requested direction.
    abstract double limit(boolean horizontal, double pixels);

    /// Applies a bounded pixel displacement and returns the distance actually moved.
    abstract double scroll(boolean horizontal, double pixels);

    /// Scales platform wheel pixels consistently, preserving system settings and fractional deltas.
    double wheelDelta(ScrollEvent event, boolean horizontal) {
        return -(horizontal ? event.getDeltaX() : event.getDeltaY()) * WHEEL_SPEED;
    }

    /// Maps continuous input to pixels without applying wheel line or page scaling.
    double gestureDelta(ScrollEvent event, boolean horizontal) {
        return horizontal ? -event.getDeltaX() : -event.getDeltaY();
    }

    /// Preserves a skin's scroll lifecycle without applying its native displacement.
    static ScrollEvent stationaryScroll(ScrollEvent event) {
        return new ScrollEvent(event.getSource(), event.getTarget(), event.getEventType(),
                event.getX(), event.getY(), event.getScreenX(), event.getScreenY(),
                event.isShiftDown(), event.isControlDown(), event.isAltDown(), event.isMetaDown(),
                event.isDirect(), event.isInertia(), 0, 0, 0, 0,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, event.getTouchCount(), event.getPickResult());
    }
}
