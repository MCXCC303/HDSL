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
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * Curated catalogue of installable presets for instances.
 */
@NotNullByDefault
public final class DshPresetCatalog {
	private static final @Unmodifiable List<DshPreset> BUILTIN = List.of(DshPreset.recommended("dshmarket",
	                                                                                           "dsh-market",
	                                                                                           "dshmarket",
	                                                                                           "Plugin marketplace: browse, install and share DeepSeek Harness plugins."));

	private DshPresetCatalog() {
	}

	/**
	 * Returns the built-in presets.
	 *
	 * @return the catalogue, in display order
	 */
	public static @Unmodifiable List<DshPreset> builtin() {
		return BUILTIN;
	}

	/**
	 * Returns the presets that should be selected by default.
	 *
	 * @return the recommended presets
	 */
	public static @Unmodifiable List<DshPreset> recommended() {
		return BUILTIN.stream().filter(DshPreset::recommended).toList();
	}
}
