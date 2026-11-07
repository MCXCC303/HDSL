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
package com.mcxcc303.hdsl.harness;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A DeepSeek Harness version installed under {@link #directory}.
 *
 * @param version   the version string, for example {@code 0.1.6-alpha.2}
 * @param directory the npm prefix holding this version
 */
@NotNullByDefault
public record DshVersion(String version, Path directory) {
	/**
	 * The installed {@code @deepseek-ai/dsh} package directory.
	 */
	public static final String PACKAGE_PATH = "node_modules/@deepseek-ai/dsh";

	private static final String BIN_SCRIPT = "lib/bin.js";

	/**
	 * Returns the installed package directory.
	 *
	 * @return the path of {@code node_modules/@deepseek-ai/dsh}
	 */
	public Path packageDirectory() {
		return directory.resolve(PACKAGE_PATH);
	}

	/**
	 * Returns the CLI entry script that must be run with {@code node}.
	 *
	 * @return the path of the {@code dsh} entry script
	 */
	public Path binScript() {
		return packageDirectory().resolve(BIN_SCRIPT);
	}

	/**
	 * Returns the generated {@code dsh} shim in the prefix's {@code .bin} directory.
	 *
	 * @return the path of the {@code dsh} shim
	 */
	public Path shim() {
		return directory.resolve("node_modules/.bin/dsh");
	}

	/**
	 * Reports whether this version is complete enough to boot.
	 *
	 * @return whether the CLI entry script exists
	 */
	public boolean isUsable() {
		return Files.isRegularFile(binScript());
	}

	/**
	 * Reports whether this version's install left a {@code package.json}.
	 *
	 * @return whether the prefix looks like an npm install
	 */
	public boolean hasManifest() {
		return Files.isRegularFile(directory.resolve("package.json"));
	}
}
