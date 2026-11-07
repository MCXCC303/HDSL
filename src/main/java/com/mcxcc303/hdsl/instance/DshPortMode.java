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
package com.mcxcc303.hdsl.instance;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Port selection strategy for the browser surface of an instance.
 */
@NotNullByDefault
public enum DshPortMode {
	AUTO,
	FIXED,
	GLOBAL;

	/**
	 * Resolves a mode from its stored name.
	 *
	 * @param value the stored name, possibly {@code null}
	 * @return the matching mode, or {@link #AUTO} when unrecognised
	 */
	public static DshPortMode of(@Nullable String value) {
		if (value != null) {
			for (DshPortMode mode : values()) {
				if (mode.name().equalsIgnoreCase(value.trim())) {
					return mode;
				}
			}
		}
		return AUTO;
	}

	/**
	 * Returns the name used in settings files and on the command line.
	 *
	 * @return the lower-case name
	 */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}
}
