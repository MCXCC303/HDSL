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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * The title a plugin publishes for itself in its own locale files.
 * <p>
 * Reads {@code locale/<language>.json} straight from disk. <br>
 * Fall back to the package name when none is published
 */
@NotNullByDefault
public final class PluginTitles {
	/**
	 * Language used when the requested one has no title.
	 */
	private static final String FALLBACK = "en";

	private PluginTitles() {}

	/**
	 * @return the title in the launcher's language, or {@code null} when none is readable.
	 */
	public static @Nullable String titleOf(Path packageDirectory) {
		return titleOf(packageDirectory, Locale.getDefault());
	}

	/**
	 * Returns the title in the given language.
	 *
	 * @param locale the language to read first
	 * @return the title, or {@code null} when none is readable
	 */
	static @Nullable String titleOf(Path packageDirectory, Locale locale) {
		Path localeDirectory = packageDirectory.resolve("locale");
		String language = locale.getLanguage().toLowerCase(Locale.ROOT);
		String title = titleIn(localeDirectory.resolve(language + ".json"), language);
		if (title != null || language.equals(FALLBACK)) return title;

		return titleIn(localeDirectory.resolve(FALLBACK + ".json"), language);
	}

	/**
	 * Reads one locale file's title.
	 *
	 * @param language the language to look up in the title's translations
	 * @return the title, or {@code null} when none is readable
	 */
	private static @Nullable String titleIn(Path file, String language) {
		if (!Files.isRegularFile(file)) return null;

		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) return null;

			JsonElement meta = parsed.getAsJsonObject().get("meta");
			if (meta == null || !meta.isJsonObject()) return null;

			return textOf(meta.getAsJsonObject().get("title"), language);
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not read the title of " + file, e);
			return null;
		}
	}

	/**
	 * Reads a title declared as a plain string or as a per-language map.
	 *
	 * @param value    the declared title, may be null
	 * @param language the language to read first
	 * @return the title, or {@code null} when nothing usable is declared
	 */
	private static @Nullable String textOf(@Nullable JsonElement value, String language) {
		if (value == null || value.isJsonNull()) return null;
		if (value.isJsonObject()) {
			JsonObject translations = value.getAsJsonObject();
			JsonElement localized = translations.get(language);
			return textOf(localized == null ? translations.get(FALLBACK) : localized, language);
		}
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;

		String text = value.getAsString().trim();
		return text.isEmpty() ? null : text;
	}
}
