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

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.nio.file.Path;

/// A Node.js runtime installed under [DshPaths#RUNTIMES].
///
/// Unlike [DshNodeRuntime], which describes whatever the system happens to have
/// on `PATH`, this is a runtime the launcher owns: it is versioned, isolated and
/// safe to pin an instance to.
///
/// The layout of a Node distribution is not the same on every platform, so the
/// paths are answered per platform rather than fixed:
///
/// - Linux unpacks `bin/node` and links `bin/npm` and `bin/npx` into
///   `lib/node_modules`;
/// - Windows unpacks `node.exe` into the distribution root and provides `npm`
///   and `npx` as `npm.cmd` and `npx.cmd` beside it. A `pnpm` installed into it
///   by npm lands in that same root as `pnpm.cmd`.
///
/// @param version   the version without a leading `v`
/// @param directory the directory holding the unpacked distribution
@NotNullByDefault
public record NodeRuntime(String version, Path directory) {
    /// Returns the `node` executable of a distribution directory.
    ///
    /// @param directory the unpacked distribution
    /// @return `bin/node`, or `node.exe` in the root on Windows
    public static Path nodeIn(Path directory) {
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                ? directory.resolve("node.exe")
                : directory.resolve("bin").resolve("node");
    }

    /// Returns the `npm` entry point of a distribution directory.
    ///
    /// The Linux distribution ships `bin/npm` as a symlink into
    /// `lib/node_modules`; the Windows one ships `npm.cmd` beside `node.exe`.
    ///
    /// @param directory the unpacked distribution
    /// @return the npm entry point
    public static Path npmIn(Path directory) {
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                ? directory.resolve("npm.cmd")
                : directory.resolve("bin").resolve("npm");
    }

    /// Returns the `npx` entry point of a distribution directory.
    ///
    /// @param directory the unpacked distribution
    /// @return the npx entry point
    public static Path npxIn(Path directory) {
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                ? directory.resolve("npx.cmd")
                : directory.resolve("bin").resolve("npx");
    }

    /// Returns the `pnpm` entry point of a distribution directory, if it has one.
    ///
    /// pnpm is never part of a Node distribution: it is installed into one by
    /// npm, which writes a `pnpm` shim on Linux and a `pnpm.cmd` on Windows.
    /// Every spelling that can be there is accepted, and the first one that
    /// exists is the answer.
    ///
    /// @param directory the unpacked distribution
    /// @return the pnpm entry point, or `null` when the distribution has none
    public static Path pnpmIn(Path directory) {
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            // npm writes `pnpm.cmd`; corepack and a standalone installer have
            // also been seen to leave `pnpm.exe`. Both run the same package.
            for (String name : new String[]{"pnpm.cmd", "pnpm.exe", "pnpm"}) {
                Path candidate = directory.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
        Path pnpm = directory.resolve("bin").resolve("pnpm");
        return Files.isRegularFile(pnpm) || Files.isSymbolicLink(pnpm) ? pnpm : null;
    }

    /// Returns the `node` executable.
    ///
    /// @return the path of the distribution's node executable
    public Path node() {
        return nodeIn(directory);
    }

    /// Returns the `npm` entry point.
    ///
    /// The distribution ships `bin/npm` as a symlink into `lib/node_modules` on
    /// Linux and `npm.cmd` beside `node.exe` on Windows; both forms are answered
    /// by the platform's own.
    ///
    /// @return the path of the distribution's npm entry point
    public Path npm() {
        return npmIn(directory);
    }

    /// Returns the `npx` entry point.
    ///
    /// @return the path of the distribution's npx entry point
    public Path npx() {
        return npxIn(directory);
    }

    /// Returns the `pnpm` entry point, if this runtime has one.
    ///
    /// @return the path of the pnpm entry point, or `null` when pnpm was never
    ///         installed into this runtime
    public Path pnpm() {
        return pnpmIn(directory);
    }

    /// Reports whether the archive was unpacked completely.
    ///
    /// @return whether the `node` executable exists
    public boolean isUsable() {
        Path node = node();
        return Files.isRegularFile(node) || Files.isSymbolicLink(node);
    }
}
