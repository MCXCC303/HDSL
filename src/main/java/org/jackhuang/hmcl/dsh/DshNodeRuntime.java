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
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// Describes the JavaScript toolchain the launcher found on this machine.
///
/// DeepSeek Harness needs Node `^22.19.0 || >=24.0.0`. `pnpm` is only needed
/// for `dsh plugin`, so its absence degrades plugin management rather than
/// preventing the launcher from working.
///
/// @param node         the resolved `node` executable
/// @param nodeVersion  the node version string, without a leading `v`
/// @param npm          the resolved `npm` executable, or `null` when absent
/// @param npmVersion   the npm version string, or `null` when absent
/// @param pnpm         the resolved `pnpm` executable, or `null` when absent
/// @param pnpmVersion  the pnpm version string, or `null` when absent
@NotNullByDefault
public record DshNodeRuntime(
        Path node,
        String nodeVersion,
        @Nullable Path npm,
        @Nullable String npmVersion,
        @Nullable Path pnpm,
        @Nullable String pnpmVersion) {

    /// The lowest supported Node 22 release.
    private static final Version MINIMUM_NODE_22 = new Version(22, 19, 0);

    /// The next major after Node 22; the `^22.19.0` range stops before it.
    private static final int NODE_22_CEILING = 23;

    /// The lowest supported Node 24-or-newer release.
    private static final int MINIMUM_NODE_MODERN_MAJOR = 24;

    /// A parsed `major.minor.patch` version.
    ///
    /// @param major the major component
    /// @param minor the minor component
    /// @param patch the patch component
    private record Version(int major, int minor, int patch) implements Comparable<Version> {
        @Override
        public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(minor, other.minor);
            return result != 0 ? result : Integer.compare(patch, other.patch);
        }
    }

    /// The selection value meaning "use whatever is on `PATH`".
    public static final String SYSTEM = "system";

    /// The selection meaning "whatever the launcher is set to".
    public static final String GLOBAL = "global";

    /// Describes a launcher-managed runtime in the same shape as a probed one.
    ///
    /// `pnpm` is deliberately left unset: a managed runtime has no `pnpm` of its
    /// own, so plugin management falls back to whatever the system provides.
    ///
    /// @param runtime the installed runtime
    /// @return the descriptor
    public static DshNodeRuntime fromManaged(NodeRuntime runtime) {
        // pnpm lives beside node, not in the distribution metadata, so it is
        // probed directly. Leaving it unresolved made every managed runtime look
        // incapable of plugin management even after pnpm was installed into it.
        Path bin = runtime.node().getParent();
        Path pnpm = bin == null ? null : bin.resolve("pnpm");
        if (pnpm != null && !Files.isExecutable(pnpm)) {
            pnpm = null;
        }

        return new DshNodeRuntime(
                runtime.node(),
                runtime.version(),
                runtime.npm(),
                runtime.npm() == null ? null : versionOf(runtime.npm()),
                pnpm,
                pnpm == null ? null : versionOf(pnpm));
    }

    /// Probes the toolchain on the current `PATH`.
    ///
    /// @return the detected runtime, or empty when no usable `node` was found
    public static Optional<DshNodeRuntime> detect() {
        Path node = which("node").orElse(null);
        if (node == null) {
            return Optional.empty();
        }

        String nodeVersion = versionOf(node);
        if (nodeVersion == null) {
            return Optional.empty();
        }

        Path npm = which("npm").orElse(null);
        Path pnpm = which("pnpm").orElse(null);

        return Optional.of(new DshNodeRuntime(
                node,
                nodeVersion,
                npm,
                npm == null ? null : versionOf(npm),
                pnpm,
                pnpm == null ? null : versionOf(pnpm)));
    }

    /// Reports whether this Node release satisfies DeepSeek Harness's `engines` range.
    ///
    /// @return whether the version is `^22.19.0` or `>=24.0.0`
    public boolean isNodeSupported() {
        return isSupportedNodeVersion(nodeVersion);
    }

    /// Reports whether this runtime can install DeepSeek Harness versions.
    ///
    /// @return whether `npm` is available
    public boolean canInstall() {
        return npm != null && npmVersion != null;
    }

    /// Reports whether plugin management through `dsh plugin` is possible.
    ///
    /// @return whether `pnpm` is available
    public boolean canManagePlugins() {
        return pnpm != null;
    }

    /// Returns the environment entries that put this runtime's tools first.
    ///
    /// DeepSeek Harness resolves `pnpm` by name through PATH, so any child the
    /// launcher starts for an instance must be given the instance's runtime
    /// ahead of the system's. Otherwise a managed runtime silently borrows the
    /// system tooling, or finds none.
    ///
    /// @return the environment additions
    public java.util.Map<String, String> pathEnvironment() {
        String existing = System.getenv("PATH");
        String bin = binDirectory().toString();
        return java.util.Map.of("PATH", existing == null || existing.isBlank()
                ? bin
                : bin + java.io.File.pathSeparator + existing);
    }

    /// Returns the directory holding this runtime's executables.
    ///
    /// Used to put the runtime ahead of the system's tooling on the child's
    /// PATH, which is what makes a managed runtime self-contained.
    ///
    /// @return the `bin` directory
    public Path binDirectory() {
        Path parent = node.getParent();
        return parent == null ? node : parent;
    }

    /// Tests a version string against DeepSeek Harness's Node range.
    ///
    /// @param version the version string, optionally prefixed with `v`
    /// @return whether the version is supported
    public static boolean isSupportedNodeVersion(String version) {
        Version parsed = parse(version);
        if (parsed == null) {
            return false;
        }
        if (parsed.major() >= MINIMUM_NODE_MODERN_MAJOR) {
            return true;
        }
        return parsed.major() == MINIMUM_NODE_22.major()
                && parsed.major() < NODE_22_CEILING
                && parsed.compareTo(MINIMUM_NODE_22) >= 0;
    }

    /// Returns the toolchain requirement shown to the user.
    ///
    /// @return a human-readable requirement string
    public static String requirement() {
        return "Node.js ^22.19.0 || >=24.0.0, plus pnpm for plugin management";
    }

    /// Parses a leading `major.minor.patch` from a version string.
    ///
    /// @param version the raw version, optionally prefixed with `v`
    /// @return the parsed version, or `null` when it cannot be parsed
    private static @Nullable Version parse(String version) {
        String cleaned = version.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }
        String[] parts = cleaned.split("[.\\-+]", 3);
        if (parts.length == 0) {
            return null;
        }
        try {
            int major = Integer.parseInt(parts[0].trim());
            int minor = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2].split("[.\\-+]", 2)[0].trim()) : 0;
            return new Version(major, minor, patch);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /// Asks an executable for its version.
    ///
    /// @param executable the program to run with `--version`
    /// @return the trimmed first output line, or `null` when the probe failed
    private static @Nullable String versionOf(Path executable) {
        try {
            DshCommand.Result result = DshCommand.run(List.of(executable.toString(), "--version"));
            if (!result.isSuccess() || result.output().isEmpty()) {
                return null;
            }
            String first = result.output().get(0).trim();
            return first.startsWith("v") || first.startsWith("V") ? first.substring(1) : first;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /// Resolves an executable on `PATH`.
    ///
    /// @param name the executable name
    /// @return the resolved path, or empty when it is not on `PATH`
    public static Optional<Path> which(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
