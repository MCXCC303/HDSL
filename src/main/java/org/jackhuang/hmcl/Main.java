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
package org.jackhuang.hmcl;

import com.mcxcc303.hdsl.cli.Cli;
import org.jackhuang.hmcl.setting.LauncherLock;
import org.jackhuang.hmcl.setting.SettingsManager;
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
/// needs a display: see [Cli] for the supported commands. Anything the CLI
/// does not recognise starts the interface.
@NotNullByDefault
public final class Main {
    private Main() {
    }

    /// Starts HDSL.
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

        Cli.Invocation invocation = Cli.parse(arguments);
        if (invocation != null) {
            System.exit(runCommandLine(invocation));
        }

        if (arguments.contains("--version")) {
            System.out.println(Metadata.FULL_TITLE);
            return;
        }

        // One launcher to a home: one settings file, one instance store, one set of running
        // harnesses. A second window would write the settings out of its own copy of them — read
        // when it started, stale within minutes — and would keep its own idea of what is running,
        // so it does not open. It leaves quietly: to whoever started it, a window already being
        // open is the whole of what they asked for.
        if (!LauncherLock.shared().acquire()) {
            return;
        }

        Launcher.main(args);
    }

    /// Loads the launcher settings before a CLI command runs.
    ///
    /// Loading the settings class also registers the network environment supplier, so a command that
    /// starts npm or another child process sees the configured proxy and download concurrency.
    static void initializeCommandLine() {
        SettingsManager.settings();
    }

    /// Runs one parsed command, after the settings a command may need are in place.
    ///
    /// @param invocation the command and its arguments
    /// @return the process exit code
    static int runCommandLine(Cli.Invocation invocation) {
        initializeCommandLine();
        return Cli.run(invocation, System.out, System.err);
    }
}
