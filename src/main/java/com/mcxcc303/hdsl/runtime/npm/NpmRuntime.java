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
package com.mcxcc303.hdsl.runtime.npm;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The npm bundled in a Node.js distribution.
 */
@NotNullByDefault
public record NpmRuntime(Path directory) {

	public static Path packageRootIn(Path directory) {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		       ? directory.resolve("node_modules").resolve("npm")
		       : directory.resolve("lib").resolve("node_modules").resolve("npm");
	}

	public static Path cliIn(Path directory) {
		return packageRootIn(directory).resolve("bin").resolve("npm-cli.js");
	}

	public static @Nullable NpmRuntime of(Path node) {
		Path parent = node.getParent();
		Path directory = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		                ? parent
		                : parent == null ? null : parent.getParent();
		return directory == null ? null : new NpmRuntime(directory);
	}

	public Path cli() {
		return cliIn(directory);
	}

	public boolean isUsable() {
		return Files.isRegularFile(cli());
	}
}
