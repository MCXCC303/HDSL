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
package org.jackhuang.hmcl;

import org.jetbrains.annotations.NotNullByDefault;

/// The process entry point.
///
/// This class must not extend [javafx.application.Application]: the JavaFX
/// launcher refuses to start when the main class is an `Application` subclass
/// but the JavaFX modules are only on the classpath. Delegating from a plain
/// class avoids that check and keeps the classpath-based build working.
@NotNullByDefault
public final class Main {
    private Main() {
    }

    /// Starts HMCL-DSH.
    ///
    /// @param args command-line arguments, forwarded to the JavaFX application
    public static void main(String[] args) {
        Launcher.main(args);
    }
}
