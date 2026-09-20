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

import java.nio.file.Files;
import java.nio.file.Path;

/// A Node.js runtime installed under [DshPaths#RUNTIMES].
///
/// Unlike [DshNodeRuntime], which describes whatever the system happens to have
/// on `PATH`, this is a runtime the launcher owns: it is versioned, isolated and
/// safe to pin an instance to.
///
/// @param version   the version without the leading `v`
/// @param directory the directory holding the unpacked distribution
@NotNullByDefault
public record NodeRuntime(String version, Path directory) {
    /// Returns the `node` executable.
    ///
    /// @return the path of `bin/node`
    public Path node() {
        return directory.resolve("bin/node");
    }

    /// Returns the `npm` entry point.
    ///
    /// The distribution ships `bin/npm` as a symlink into `lib/node_modules`;
    /// both forms are accepted.
    ///
    /// @return the path of `bin/npm`
    public Path npm() {
        return directory.resolve("bin/npm");
    }

    /// Returns the `npx` entry point.
    ///
    /// @return the path of `bin/npx`
    public Path npx() {
        return directory.resolve("bin/npx");
    }

    /// Reports whether the archive was unpacked completely.
    ///
    /// @return whether the `node` executable exists
    public boolean isUsable() {
        return Files.isRegularFile(node()) || Files.isSymbolicLink(node());
    }
}
