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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A resolved npm runtime for the launcher.
 */
@NotNullByDefault
public record DshNpm(Path command, @Nullable Path node, @Nullable String version, Path shimDirectory) {
	public List<String> commandFor(List<String> arguments) {
		List<String> command = new ArrayList<>();
		if (node != null) {
			command.add(node.toString());
		}
		command.add(this.command.toString());
		command.addAll(arguments);
		return List.copyOf(command);
	}

	public Map<String, String> pathEnvironment() {
		String existing = System.getenv("PATH");
		return Map.of("PATH", existing == null || existing.isBlank()
				? shimDirectory.toString()
				: shimDirectory + File.pathSeparator + existing);
	}
}
