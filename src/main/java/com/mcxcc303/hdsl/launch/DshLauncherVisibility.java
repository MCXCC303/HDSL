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
package com.mcxcc303.hdsl.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Launcher window visibility behavior while an instance is running.
 */
@NotNullByDefault
public enum DshLauncherVisibility {
	KEEP("keep"),
	HIDE("hide"),
	MINIMIZE("minimize");

	private final String id;

	DshLauncherVisibility(String id) {
		this.id = id;
	}

	/**
	 * Resolves visibility mode from its stored identifier string.
	 *
	 * @param value the stored identifier, or null
	 * @return the resolved visibility mode, defaulting to {@link #KEEP}
	 */
	public static DshLauncherVisibility of(@Nullable String value) {
		if (value != null) {
			for (DshLauncherVisibility choice : values()) {
				if (choice.id.equalsIgnoreCase(value.trim())) {
					return choice;
				}
			}
		}
		return KEEP;
	}

	/**
	 * Returns the identifier stored for this setting.
	 *
	 * @return the setting identifier
	 */
	public String id() {
		return id;
	}
}
