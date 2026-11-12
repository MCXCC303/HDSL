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
 * A pnpm the launcher can run.
 *
 * <p>A pnpm is a JavaScript program, so it runs on a Node.js. Which one is a separate matter from
 * where the pnpm itself came from, and the two are told apart here: a pnpm the launcher installed
 * is run by naming the Node.js that runs it, and a pnpm that was already on the machine is run by
 * its own shim, which finds the Node.js it was installed with.
 *
 * <p>Running a managed pnpm on a named Node.js rather than through its shim is what makes the
 * version of Node.js that installs a tree the same version that runs it. The shim resolves
 * {@code node} from {@code PATH}, and {@code PATH} belongs to whoever started the launcher.
 *
 * @param command the program to run
 * @param node    the Node.js that runs it, or {@code null} when it runs itself
 * @param version the version it reports, or {@code null} when it is not known
 * @author HDSL contributors
 */
@NotNullByDefault
public record DshPnpm(Path command, @Nullable Path node, @Nullable String version) {

	/**
	 * Builds the command line that runs this pnpm.
	 *
	 * @param arguments the arguments to pass to it
	 * @return the command line
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
