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
package com.mcxcc303.hdsl.plugin;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Known marketplace sources for discovering and loading plugin catalogues.
 */
@NotNullByDefault
public enum MarketSource {
	/** The community plugin catalogue market source. */
	AWESOME_DSH_PLUGIN("awesome-dsh-plugin",
	                   "Awesome DSH Plugin",
	                   "https://awesome-dsh-plugin.com/plugins.json",
	                   "dsh-plugin-catalog");

	private final String id;
	private final String label;
	private final List<String> addresses;
	private final @Nullable String fallback;

	MarketSource(String id, String label, String... addresses) {
		this.id = id;
		this.label = label;
		this.addresses = List.of(addresses);
		this.fallback = null;
	}

	MarketSource(String id, String label, String address, String fallback) {
		this.id = id;
		this.label = label;
		this.addresses = List.of(address);
		this.fallback = fallback;
	}

	/**
	 * Looks up a market source by its identifier, falling back to the default.
	 *
	 * @param id the stored identifier, or {@code null}
	 * @return the matching market source
	 */
	public static MarketSource of(@Nullable String id) {
		if (id != null) {
			for (MarketSource source : values()) {
				if (source.id.equals(id.trim())) {
					return source;
				}
			}
		}
		return AWESOME_DSH_PLUGIN;
	}

	/**
	 * Returns the stored identifier of this market source.
	 *
	 * @return the id
	 */
	public String id() {
		return id;
	}

	/**
	 * Returns the display name of this market source.
	 *
	 * @return the label
	 */
	public String label() {
		return label;
	}

	/**
	 * Returns the source URLs for this market in order of precedence.
	 *
	 * @return the address list
	 */
	public List<String> addresses() {
		return addresses;
	}

	/**
	 * Returns the fallback package name used when all addresses fail.
	 *
	 * @return the fallback package name, or {@code null}
	 */
	public @Nullable String fallback() {
		return fallback;
	}
}