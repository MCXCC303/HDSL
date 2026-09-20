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

import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

/// Prints a diagnostics report describing everything HMCL-DSH needs to run.
///
/// This is the launcher's `--doctor`: it lets a user (or a bug report) show the
/// detected toolchain, the on-disk layout and registry reachability without
/// starting the JavaFX interface. It is also how the version-management core is
/// exercised outside the UI.
@NotNullByDefault
public final class DshDoctor {
    private DshDoctor() {
    }

    /// Runs the diagnostics and prints them.
    ///
    /// @param out the stream to print to
    /// @return the process exit code, non-zero when a hard requirement is missing
    public static int report(PrintStream out) {
        out.println("HMCL-DSH " + Metadata.VERSION + " diagnostics");
        out.println("Java:      " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        out.println("OS:        " + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        out.println();

        out.println("Directories");
        out.println("  user home: " + Metadata.HMCL_USER_HOME);
        out.println("  versions:  " + DshPaths.VERSIONS);
        out.println("  instances: " + DshPaths.INSTANCES);
        out.println();

        out.println("JavaScript toolchain");
        Optional<DshNodeRuntime> detected = DshNodeRuntime.detect();
        boolean runtimeOk;
        if (detected.isEmpty()) {
            out.println("  node:  NOT FOUND");
            out.println("  required: " + DshNodeRuntime.requirement());
            runtimeOk = false;
        } else {
            DshNodeRuntime runtime = detected.get();
            out.println("  node:  " + runtime.nodeVersion() + "  (" + runtime.node() + ")"
                    + (runtime.isNodeSupported() ? "  [supported]" : "  [UNSUPPORTED]"));
            out.println("  npm:   " + describe(runtime.npm(), runtime.npmVersion()));
            out.println("  pnpm:  " + describe(runtime.pnpm(), runtime.pnpmVersion())
                    + (runtime.canManagePlugins() ? "" : "  [plugin management unavailable]"));
            out.println("  required: " + DshNodeRuntime.requirement());
            runtimeOk = runtime.isNodeSupported() && runtime.canInstall();
        }
        out.println();

        out.println("Installed DSH versions");
        List<DshVersion> installed = DshVersionManager.listInstalled();
        if (installed.isEmpty()) {
            out.println("  (none)");
        } else {
            for (DshVersion version : installed) {
                out.println("  " + version.version() + "  ->  " + version.directory()
                        + (version.isUsable() ? "" : "  [INCOMPLETE]"));
            }
        }
        out.println();

        out.println("npm registry");
        if (!runtimeOk) {
            out.println("  skipped (toolchain incomplete)");
        } else {
            try {
                List<DshRelease> releases = DshVersionManager.fetchReleases();
                out.println("  reachable, " + releases.size() + " published version(s)");
                releases.stream().limit(5).forEach(release ->
                        out.println("    " + release.version()
                                + (release.primaryTag() == null ? "" : "  [" + release.primaryTag() + "]")));
            } catch (DshException e) {
                out.println("  FAILED: " + e.getMessage());
            }
        }

        out.println();
        out.println(runtimeOk ? "Result: ready to install DeepSeek Harness versions."
                : "Result: the JavaScript toolchain is incomplete.");
        return runtimeOk ? 0 : 1;
    }

    /// Renders an optional executable and its version.
    ///
    /// @param path    the executable, or `null`
    /// @param version the reported version, or `null`
    /// @return a display string
    private static String describe(@Nullable java.nio.file.Path path, @Nullable String version) {
        if (path == null) {
            return "NOT FOUND";
        }
        return (version == null ? "unknown" : version) + "  (" + path + ")";
    }
}
