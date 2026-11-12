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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A resolved pnpm runtime for the launcher.
 *
 * @param command       the pnpm entry point to run
 * @param node          the Node.js interpreter that runs {@code command}; {@code null} if
 *                      {@code command} is directly executable
 * @param version       the pnpm version, or {@code null} if unknown
 * @param shimDirectory the directory containing the {@code pnpm} shim
 */
@NotNullByDefault
public record DshPnpm(Path command, @Nullable Path node, @Nullable String version, Path shimDirectory) {

	/**
	 * Builds the pnpm command line.
	 */
	public List<String> commandFor(List<String> arguments) {
		List<String> command = new ArrayList<>();
		if (node != null) {
			command.add(node.toString());
		}
		command.add(this.command.toString());
		command.addAll(arguments);
		return List.copyOf(command);
	}
}
