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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

@NotNullByDefault
public enum DshNpmChannel {
	STABLE, RC, BETA, ALPHA, OTHER;

	public static DshNpmChannel of(String version, @Nullable JsonObject tags) {
		int dash = version.indexOf('-');
		if (dash >= 0) {
			String pre = version.substring(dash + 1).toLowerCase(Locale.ROOT);
			if (pre.startsWith("rc")) return RC;
			if (pre.startsWith("beta")) return BETA;
			if (pre.startsWith("alpha")) return ALPHA;
			return OTHER;
		}
		if (tags != null) {
			for (Map.Entry<String, JsonElement> tag : tags.entrySet()) {
				if (!tag.getValue().isJsonPrimitive() || !version.equals(tag.getValue().getAsString())) {
					continue;
				}
				switch (tag.getKey().toLowerCase(Locale.ROOT)) {
					case "rc" -> {
						return RC;
					}
					case "beta" -> {
						return BETA;
					}
					case "alpha" -> {
						return ALPHA;
					}
					case "dev", "next", "canary", "snapshot" -> {
						return OTHER;
					}
					default -> {
					}
				}
			}
		}
		return STABLE;
	}
}
