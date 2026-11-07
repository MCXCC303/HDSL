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
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A Node.js runtime installed under {@link DshPaths#RUNTIMES}.
 *
 * @param version   the version without a leading v
 * @param directory the directory holding the unpacked distribution
 */
@NotNullByDefault
public record NodeRuntime(String version, Path directory) {

	/**
	 * Returns the node executable of a distribution directory.
	 *
	 * @param directory the unpacked distribution
	 * @return bin/node, or node.exe in the root on Windows
	 */
	public static Path nodeIn(Path directory) {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		       ? directory.resolve("node.exe")
		       : directory.resolve("bin").resolve("node");
	}

	/**
	 * Returns the npm entry point of a distribution directory.
	 *
	 * @param directory the unpacked distribution
	 * @return the npm entry point
	 */
	public static Path npmIn(Path directory) {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		       ? directory.resolve("npm.cmd")
		       : directory.resolve("bin").resolve("npm");
	}

	/**
	 * Returns the npx entry point of a distribution directory.
	 *
	 * @param directory the unpacked distribution
	 * @return the npx entry point
	 */
	public static Path npxIn(Path directory) {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		       ? directory.resolve("npx.cmd")
		       : directory.resolve("bin").resolve("npx");
	}

	/**
	 * Returns the pnpm entry point of a distribution directory, if present.
	 *
	 * @param directory the unpacked distribution
	 * @return the pnpm entry point, or {@code null} when absent
	 */
	public static @Nullable Path pnpmIn(Path directory) {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
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

	public Path node() {
		return nodeIn(directory);
	}

	public Path npm() {
		return npmIn(directory);
	}

	public Path npx() {
		return npxIn(directory);
	}

	public @Nullable Path pnpm() {
		return pnpmIn(directory);
	}

	/**
	 * Reports whether the runtime archive was unpacked completely.
	 */
	public boolean isUsable() {
		Path node = node();
		return Files.isRegularFile(node) || Files.isSymbolicLink(node);
	}
}
