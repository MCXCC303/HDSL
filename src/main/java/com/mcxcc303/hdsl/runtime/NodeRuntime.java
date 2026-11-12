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
package com.mcxcc303.hdsl.runtime;

import com.mcxcc303.hdsl.core.DshPaths;

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

	/// Reports whether the archive was unpacked completely.
	///
	/// @return whether the `node` executable exists
	public boolean isUsable() {
		Path node = node();
		return Files.isRegularFile(node) || Files.isSymbolicLink(node);
	}
}
