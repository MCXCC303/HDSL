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

import java.io.PrintStream;
import java.util.List;

/// A small headless command surface for version management.
///
/// This exists so that the launcher's core can be driven, scripted and verified
/// without a display. The GUI is a second consumer of exactly the same
/// [DshVersionManager] calls.
@NotNullByDefault
public final class DshCli {
    private DshCli() {
    }

    /// The commands understood by the launcher.
    public enum Command {
        /// Prints a full diagnostics report.
        DOCTOR,
        /// Prints the installed versions.
        LIST_INSTALLED,
        /// Prints the versions published to the npm registry.
        LIST_REMOTE,
        /// Installs one version.
        INSTALL,
        /// Removes one version.
        UNINSTALL
    }

    /// A parsed command line.
    ///
    /// @param command the requested command
    /// @param operand the version argument, or `null` when the command takes none
    public record Invocation(Command command, String operand) {
    }

    /// Parses launcher arguments into an invocation.
    ///
    /// @param args the raw command-line arguments
    /// @return the parsed invocation, or `null` when the arguments request the GUI or are unknown
    public static Invocation parse(List<String> args) {
        if (args.contains("--doctor")) {
            return new Invocation(Command.DOCTOR, null);
        }
        if (args.contains("--list-installed")) {
            return new Invocation(Command.LIST_INSTALLED, null);
        }
        if (args.contains("--list-versions")) {
            return new Invocation(Command.LIST_REMOTE, null);
        }
        int installIndex = args.indexOf("--install");
        if (installIndex >= 0 && installIndex + 1 < args.size()) {
            return new Invocation(Command.INSTALL, args.get(installIndex + 1));
        }
        int uninstallIndex = args.indexOf("--uninstall");
        if (uninstallIndex >= 0 && uninstallIndex + 1 < args.size()) {
            return new Invocation(Command.UNINSTALL, args.get(uninstallIndex + 1));
        }
        return null;
    }

    /// Runs a parsed command.
    ///
    /// @param invocation the command to run
    /// @param out        the stream for normal output
    /// @param err        the stream for error output
    /// @return the process exit code
    public static int run(Invocation invocation, PrintStream out, PrintStream err) {
        try {
            switch (invocation.command()) {
                case DOCTOR -> {
                    return DshDoctor.report(out);
                }
                case LIST_INSTALLED -> {
                    List<DshVersion> installed = DshVersionManager.listInstalled();
                    if (installed.isEmpty()) {
                        out.println("(no installed versions)");
                    }
                    installed.forEach(version -> out.println(version.version() + "\t" + version.directory()));
                    return 0;
                }
                case LIST_REMOTE -> {
                    for (DshRelease release : DshVersionManager.fetchReleases()) {
                        String tag = release.primaryTag();
                        out.println(release.version() + (tag == null ? "" : "\t" + tag));
                    }
                    return 0;
                }
                case INSTALL -> {
                    out.println("Installing DeepSeek Harness " + invocation.operand() + " ...");
                    DshVersion version = DshVersionManager.install(invocation.operand(), out::println);
                    out.println("Installed " + version.version() + " into " + version.directory());
                    return 0;
                }
                case UNINSTALL -> {
                    DshVersionManager.uninstall(invocation.operand());
                    out.println("Removed " + invocation.operand());
                    return 0;
                }
                default -> {
                    err.println("Unhandled command: " + invocation.command());
                    return 2;
                }
            }
        } catch (DshException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }
}
