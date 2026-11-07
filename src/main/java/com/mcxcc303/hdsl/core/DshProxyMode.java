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
package com.mcxcc303.hdsl.core;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * How the launcher reaches the network.
 */
@NotNullByDefault
public enum DshProxyMode {
	SYSTEM("system"),
	NONE("none"),
	HTTP("http"),
	SOCKS("socks");

	private final String id;

	DshProxyMode(String id) {
		this.id = id;
	}

	/**
	 * Reads a stored value.
	 *
	 * @param value the stored value, or {@code null}
	 * @return the mode, or {@link #SYSTEM} when invalid or {@code null}
	 */
	public static DshProxyMode of(@Nullable String value) {
		if (value != null) {
			for (DshProxyMode mode : values()) {
				if (mode.id.equalsIgnoreCase(value.trim())) {
					return mode;
				}
			}
		}
		return SYSTEM;
	}

	public String id() {
		return id;
	}

	/**
	 * Returns whether this mode requires a host and port.
	 */
	public boolean usesAddress() {
		return this == HTTP || this == SOCKS;
	}
}
