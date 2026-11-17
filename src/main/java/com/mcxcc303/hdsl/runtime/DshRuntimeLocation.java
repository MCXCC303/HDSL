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

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshPaths;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@NotNullByDefault
public final class DshRuntimeLocation {
	public static final String SYSTEM = "system";

	private DshRuntimeLocation() {}

	public static boolean isSystem(@Nullable String selection) {
		return selection == null || selection.isBlank() || SYSTEM.equalsIgnoreCase(selection.trim());
	}

	public static @Nullable Path pathOf(@Nullable String selection) throws DshException {
		return pathOf(selection, DshPaths.RUNTIMES);
	}

	public static @Nullable Path pnpmPathOf(@Nullable String selection) throws DshException {
		return pathOf(selection, DshPaths.PNPM);
	}

	private static @Nullable Path pathOf(@Nullable String selection, Path legacyRoot) throws DshException {
		String value = selection == null ? "" : selection.trim();
		if (value.isEmpty() || SYSTEM.equalsIgnoreCase(value)) {
			return null;
		}

		try {
			Path path = Path.of(value);
			if (path.isAbsolute()) {
				return path.normalize();
			}
		} catch (InvalidPathException e) {
			throw new DshException("Invalid runtime selection: " + value, e);
		}
		return legacyRoot.resolve(DshPaths.segment(value, "runtime version"));
	}

	public static String selectionOf(Path directory) {
		return directory.toAbsolutePath().normalize().toString();
	}
}
