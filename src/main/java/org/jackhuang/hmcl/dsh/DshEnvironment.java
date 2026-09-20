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
package org.jackhuang.hmcl.dsh;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.Supplier;

/// The launcher-wide environment an instance may inherit from.
///
/// An instance can pin its own Node runtime and its own `DSH_HOME` policy, or
/// follow whatever the launcher is set to. The defaults live in the launcher's
/// settings, which are a layer above this package — so rather than reaching up
/// and creating a cycle, the values are pushed down here once at start-up.
///
/// The suppliers are read on every lookup rather than captured, so changing the
/// global setting takes effect without a restart.
@NotNullByDefault
public final class DshEnvironment {
    private DshEnvironment() {
    }

    /// Supplies the Node runtime an instance follows.
    private static volatile Supplier<String> nodeDefault = () -> DshNodeRuntime.SYSTEM;

    /// Supplies the DSH_HOME policy an instance follows.
    private static volatile Supplier<DshHomeMode> homeDefault = () -> DshHomeMode.ISOLATED;

    /// Installs the suppliers the launcher reads its defaults from.
    ///
    /// @param node the default Node runtime
    /// @param home the default DSH_HOME policy
    public static void install(Supplier<String> node, Supplier<DshHomeMode> home) {
        nodeDefault = node;
        homeDefault = home;
    }

    /// Returns the Node runtime an instance follows the launcher to.
    ///
    /// @return the default runtime selection
    public static String nodeRuntimeDefault() {
        String value = nodeDefault.get();
        return value == null || value.isBlank() ? DshNodeRuntime.SYSTEM : value;
    }

    /// Returns the DSH_HOME policy an instance follows the launcher to.
    ///
    /// @return the default policy, never [DshHomeMode#GLOBAL]
    public static DshHomeMode homeModeDefault() {
        DshHomeMode value = homeDefault.get();
        return value == null || value == DshHomeMode.GLOBAL ? DshHomeMode.ISOLATED : value;
    }
}
