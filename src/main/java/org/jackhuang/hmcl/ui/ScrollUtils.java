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

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.skin.VirtualFlow;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Installs smooth wheel scrolling while preserving native gesture and skin behavior.
@NotNullByDefault
final class ScrollUtils {
    /// Identifies the controller installed on a node, making installation idempotent.
    private static final Object CONTROLLER_KEY = new Object();

    /// Prevents construction of this utility class.
    private ScrollUtils() {
    }

    /// Installs smooth wheel scrolling and direct pixel-based gestures on the FX thread.
    public static void addSmoothScrolling(ScrollPane scrollPane) {
        if (controller(scrollPane) == null)
            scrollPane.getProperties().put(CONTROLLER_KEY, new ScrollPaneAdapter(scrollPane).controller);
    }

    /// Installs scrolling on either flow orientation, preserving native cross-axis scrolling.
    public static void addSmoothScrolling(VirtualFlow<?> virtualFlow) {
        if (controller(virtualFlow) == null)
            virtualFlow.getProperties().put(CONTROLLER_KEY, new VirtualFlowAdapter(virtualFlow).controller);
    }

    /// Returns an installed controller without allocating a property map on ordinary nodes.
    static @Nullable ScrollController controller(Node node) {
        return node.hasProperties() && node.getProperties().get(CONTROLLER_KEY) instanceof ScrollController controller
                ? controller : null;
    }
}
