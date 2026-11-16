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

import com.mcxcc303.hdsl.core.DshCommand;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * The npm of a Node.js runtime.
 */
@NotNullByDefault
public final class NpmRuntimeManager {
	private NpmRuntimeManager() {
	}

	/**
	 * Resolves the npm bundled with a Node.js distribution.
	 * <p>
	 * The npm of a distribution is a script, and the Node.js of that distribution runs it, so
	 * that the npm works on a machine that has no Node.js on {@code PATH}.
	 *
	 * @param node the node executable of the distribution
	 * @return the npm, or {@code null} when the distribution carries none
	 */
	public static @Nullable DshNpm bundled(Path node) {
		NpmRuntime runtime = NpmRuntime.of(node);
		if (runtime == null || !runtime.isUsable()) {
			return null;
		}
		Path cli = runtime.cli();
		Path parent = node.getParent();
		return new DshNpm(cli,
		                  node,
		                  DshCommand.versionOf(List.of(node.toString(), cli.toString(), "--version")),
		                  parent == null ? Path.of(".") : parent);
	}

	/**
	 * Resolves the npm on {@code PATH}.
	 *
	 * @param shim the npm executable
	 * @return the npm
	 */
	public static DshNpm onPath(Path shim) {
		Path parent = shim.getParent();
		return new DshNpm(shim,
		                  null,
		                  DshCommand.versionOf(List.of(shim.toString(), "--version")),
		                  parent == null ? Path.of(".") : parent);
	}
}
