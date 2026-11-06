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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

@NotNullByDefault
public enum NodeSource {
	OFFICIAL("official", "https://nodejs.org/dist/"),
	MIRROR("mirror", "https://npmmirror.com/mirrors/node/");

	private final String id;
	private final String base;

	NodeSource(String id, String base) {
		this.id = id;
		this.base = base;
	}

	/**
	 * Resolves a source from its stored name.
	 *
	 * @param value the stored name, possibly `null` or of the wrong case
	 * @return the matching source, or [NodeSource#OFFICIAL] when unrecognized
	 */
	public static NodeSource of(@Nullable String value) {
		if (value != null) {
			for (NodeSource source : values()) {
				if (source.id.equals(value.trim().toLowerCase(Locale.ROOT))) {
					return source;
				}
			}
		}
		return OFFICIAL;
	}

	public String id() {
		return id;
	}

	public String host() {
		String withoutScheme = base.substring(base.indexOf("//") + 2);
		int slash = withoutScheme.indexOf('/');
		return slash < 0 ? withoutScheme : withoutScheme.substring(0, slash);
	}

	public String indexUrl() {
		return base + "index.json";
	}

	/**
	 * Returns the URL of one release's archive.
	 *
	 * @param version  the version without its leading `v`
	 * @param fileName the archive's file name
	 * @return the URL
	 */
	public String archiveUrl(String version, String fileName) {
		return base + "v" + version + "/" + fileName;
	}
}
