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

import org.jackhuang.hmcl.dsh.DshCli;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Arrays;
import java.util.List;

/// The process entry point.
///
/// This class must not extend [javafx.application.Application]: the JavaFX
/// launcher refuses to start when the main class is an `Application` subclass
/// but the JavaFX modules are only on the classpath. Delegating from a plain
/// class avoids that check and keeps the classpath-based build working.
///
/// Command-line arguments are handled here so that version management never
/// needs a display: see [DshCli] for the supported commands. Anything the CLI
/// does not recognise starts the interface.
@NotNullByDefault
public final class Main {
    private Main() {
    }

    /// Starts HMCL-DSH.
    ///
    /// @param args command-line arguments
    public static void main(String[] args) {
        List<String> arguments = Arrays.asList(args);

        // The CLI is consulted first so that subcommand options never collide
        // with the launcher's own flags.
        // Before anything reads an instance: an instance that follows the
        // launcher needs the launcher's defaults to resolve, and the command
        // line reads them exactly as the interface does.
        org.jackhuang.hmcl.setting.EnvironmentDefaults.install();

        DshCli.Invocation invocation = DshCli.parse(arguments);
        if (invocation != null) {
            System.exit(DshCli.run(invocation, System.out, System.err));
        }

        if (arguments.contains("--version")) {
            System.out.println(Metadata.FULL_TITLE);
            return;
        }

        Launcher.main(args);
    }
}
