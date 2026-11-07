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

/**
 * Policy determining whether newly created instances use an isolated home directory.
 */
@NotNullByDefault
public enum DshIsolationPolicy {
	ALWAYS("always"),
	WITH_PLUGINS("modded"),
	NEVER("never");

	private final String id;

	DshIsolationPolicy(String id) {
		this.id = id;
	}

	/**
	 * Reads a stored value.
	 *
	 * @param value the stored value, or {@code null}
	 * @return the policy, or {@link #ALWAYS} when it is unrecognized
	 */
	public static DshIsolationPolicy of(@Nullable String value) {
		if (value != null) {
			for (DshIsolationPolicy policy : values()) {
				if (policy.id.equalsIgnoreCase(value.trim())) {
					return policy;
				}
			}
		}
		return ALWAYS;
	}

	/**
	 * Returns the value the setting is stored as.
	 *
	 * @return the id
	 */
	public String id() {
		return id;
	}

	/**
	 * Decides how a new instance keeps its state.
	 *
	 * @param carriesItsOwn whether the installation brings plugins or presets of its own
	 * @param shared        the mode used when the instance does not keep a home of its own
	 * @return the mode to create the instance with
	 */
	public DshHomeMode homeMode(boolean carriesItsOwn, DshHomeMode shared) {
		return switch (this) {
			case ALWAYS -> DshHomeMode.ISOLATED;
			case NEVER -> shared;
			case WITH_PLUGINS -> carriesItsOwn ? DshHomeMode.ISOLATED : shared;
		};
	}
}
