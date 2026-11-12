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

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A pnpm installed under an npm prefix.
 *
 * @param version   the pnpm version, without a leading {@code v}
 * @param directory the npm prefix holding the pnpm
 */
@NotNullByDefault
public record PnpmRuntime(String version, Path directory) {

	/**
	 * Returns the pnpm package directory inside an npm prefix.
	 */
	public static Path packageRootIn(Path directory) {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		       ? directory.resolve("node_modules").resolve("pnpm")
		       : directory.resolve("lib").resolve("node_modules").resolve("pnpm");
	}

	public static Path cliIn(Path directory) {
		Path bin = packageRootIn(directory).resolve("bin");
		for (String name : new String[]{"pnpm.cjs", "pnpm.mjs"}) {
			Path candidate = bin.resolve(name);
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		return bin.resolve("pnpm.cjs");
	}

	public static Path shimDirectoryIn(Path directory) {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? directory : directory.resolve("bin");
	}

	/**
	 * Returns the pnpm script that Node.js runs.
	 */
	public Path cli() {
		return cliIn(directory);
	}

	public Path shimDirectory() {
		return shimDirectoryIn(directory);
	}

	/**
	 * Reports whether the entry script exists.
	 */
	public boolean isUsable() {
		return Files.isRegularFile(cli());
	}
}
