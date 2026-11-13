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

/**
 * A Node.js runtime installed under {@link DshPaths#RUNTIMES}.
 *
 * @param version   the version without a leading {@code v}
 * @param directory the directory holding the unpacked distribution
 */
@NotNullByDefault
public record NodeRuntime(String version, Path directory) {
	/**
	 * Returns the {@code node} executable in a distribution directory.
	 *
	 * @param directory the unpacked distribution
	 * @return {@code bin/node}, or {@code node.exe} in the distribution root on Windows
	 */
	public static Path nodeIn(Path directory) {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		       ? directory.resolve("node.exe")
		       : directory.resolve("bin").resolve("node");
	}

	/**
	 * Returns the {@code npm} entry point in a distribution directory.
	 *
	 * @param directory the unpacked distribution
	 * @return {@code bin/npm}, or {@code npm.cmd} in the distribution root on Windows
	 */
	public static Path npmIn(Path directory) {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		       ? directory.resolve("npm.cmd")
		       : directory.resolve("bin").resolve("npm");
	}

	/**
	 * Returns the {@code npx} entry point in a distribution directory.
	 *
	 * @param directory the unpacked distribution
	 * @return {@code bin/npx}, or {@code npx.cmd} in the distribution root on Windows
	 */
	public static Path npxIn(Path directory) {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		       ? directory.resolve("npx.cmd")
		       : directory.resolve("bin").resolve("npx");
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

	public boolean isUsable() {
		Path node = node();
		return Files.isRegularFile(node) || Files.isSymbolicLink(node);
	}
}