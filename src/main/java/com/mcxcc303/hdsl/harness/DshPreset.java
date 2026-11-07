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

/**
 * An entry in the quick-install catalogue.
 *
 * @param id           a stable identifier
 * @param name         the display name shown in the wizard
 * @param spec         the package spec handed to {@code dsh plugin add}
 * @param description  a one-line explanation
 * @param recommended  whether the entry is preselected
 * @param requiresPnpm whether installing needs {@code pnpm} on {@code PATH}
 */
@NotNullByDefault
public record DshPreset(String id, String name, String spec, String description, boolean recommended,
                        boolean requiresPnpm) {

	/**
	 * Creates a recommended preset that needs {@code pnpm}.
	 *
	 * @param id          the identifier
	 * @param name        the display name
	 * @param spec        the package spec
	 * @param description the one-line explanation
	 * @return the preset
	 */
	public static DshPreset recommended(String id, String name, String spec, String description) {
		return new DshPreset(id, name, spec, description, true, true);
	}

	/**
	 * Creates an optional preset that needs {@code pnpm}.
	 *
	 * @param id          the identifier
	 * @param name        the display name
	 * @param spec        the package spec
	 * @param description the one-line explanation
	 * @return the preset
	 */
	public static DshPreset optional(String id, String name, String spec, String description) {
		return new DshPreset(id, name, spec, description, false, true);
	}
}
