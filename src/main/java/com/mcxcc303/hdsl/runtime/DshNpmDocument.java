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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

@NotNullByDefault
public final class DshNpmDocument {
	private DshNpmDocument() {}

	/**
	 * Reads the release channel a version belongs to.
	 *
	 * @param version the version
	 * @param tags    the document's dist-tags, or {@code null} when it has none
	 * @return the channel
	 */
	public static DshReleaseChannel channelOf(String version, @Nullable JsonObject tags) {
		int dash = version.indexOf('-');
		if (dash >= 0) {
			String pre = version.substring(dash + 1).toLowerCase(Locale.ROOT);
			if (pre.startsWith("rc")) return DshReleaseChannel.RC;
			if (pre.startsWith("beta")) return DshReleaseChannel.BETA;
			if (pre.startsWith("alpha")) return DshReleaseChannel.ALPHA;
			return DshReleaseChannel.OTHER;
		}
		if (tags != null) {
			for (Map.Entry<String, JsonElement> tag : tags.entrySet()) {
				if (!tag.getValue().isJsonPrimitive() || !version.equals(tag.getValue().getAsString())) {
					continue;
				}
				switch (tag.getKey().toLowerCase(Locale.ROOT)) {
					case "rc" -> {
						return DshReleaseChannel.RC;
					}
					case "beta" -> {
						return DshReleaseChannel.BETA;
					}
					case "alpha" -> {
						return DshReleaseChannel.ALPHA;
					}
					case "dev", "next", "canary", "snapshot" -> {
						return DshReleaseChannel.OTHER;
					}
					default -> {
					}
				}
			}
		}
		return DshReleaseChannel.STABLE;
	}

	/**
	 * Reads an object member.
	 *
	 * @param object the object
	 * @param name   the member
	 * @return the object, or {@code null} when it is absent or is not an object
	 */
	public static @Nullable JsonObject object(@Nullable JsonObject object, String name) {
		if (object == null || !object.has(name) || !object.get(name).isJsonObject()) {
			return null;
		}
		return object.getAsJsonObject(name);
	}

	/**
	 * Reads a text member.
	 *
	 * @param object the object
	 * @param name   the member
	 * @return the text, or {@code null} when it is absent, is not text, or is blank
	 */
	public static @Nullable String text(@Nullable JsonObject object, String name) {
		if (object == null) return null;

		JsonElement element = object.get(name);
		if (element == null || !element.isJsonPrimitive()) return null;

		String value = element.getAsString();
		return value.isBlank() ? null : value;
	}

	/**
	 * Reads the moment one version was published.
	 *
	 * @param times   the document's `time` member, or {@code null}
	 * @param version the version
	 * @return the moment, or {@code null} when the document did not report
	 */
	public static @Nullable Instant moment(@Nullable JsonObject times, String version) {
		String value = text(times, version);
		if (value == null) return null;

		try {
			return Instant.parse(value);
		} catch (DateTimeParseException e) {
			return null;
		}
	}
}
