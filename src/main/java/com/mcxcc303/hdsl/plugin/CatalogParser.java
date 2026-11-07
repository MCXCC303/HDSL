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
import com.mcxcc303.hdsl.plugin.Catalog.Plugin;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Parses catalog JSON documents and merges multiple sources.
 */
@NotNullByDefault
public final class CatalogParser {
	private CatalogParser() {
	}

	/**
	 * Combines what the sources answered into one catalogue.
	 *
	 * @param parts      the catalogues that answered, in source order
	 * @param unreadable the sources that did not
	 * @return the merged catalogue
	 */
	static Catalog merge(List<Catalog> parts, List<String> unreadable) {
		List<Plugin> plugins = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		Map<String, String> categories = new TreeMap<>();
		String updated = null;

		for (Catalog part : parts) {
			if (updated == null) {
				updated = part.updated();
			}
			for (Plugin plugin : part.plugins()) {
				if (seen.add(identityOf(plugin))) {
					plugins.add(plugin);
				}
			}
			for (String category : part.categories()) {
				categories.putIfAbsent(category.toLowerCase(Locale.ROOT), category);
			}
		}

		return new Catalog(updated, List.copyOf(plugins), List.copyOf(categories.values()), List.copyOf(unreadable));
	}

	private static String identityOf(Plugin plugin) {
		String npm = plugin.npm();
		if (npm != null && !npm.isBlank()) {
			return "npm:" + npm.trim().toLowerCase(Locale.ROOT);
		}
		return "repo:" + DshPluginSources.repoOf(plugin.url()).toLowerCase(Locale.ROOT);
	}

	/**
	 * Parses a catalogue JSON document.
	 *
	 * @param body the JSON document
	 * @return the catalogue
	 */
	public static Catalog parse(String body) {
		JsonElement parsed = JsonParser.parseString(body);
		if (!parsed.isJsonObject()) {
			throw new IllegalArgumentException("the catalogue is not an object");
		}
		JsonObject root = parsed.getAsJsonObject();

		List<Plugin> plugins = new ArrayList<>();
		JsonElement entries = root.get("plugins");
		if (entries != null && entries.isJsonArray()) {
			for (JsonElement element : entries.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				Plugin plugin = readPlugin(element.getAsJsonObject());
				if (plugin != null) {
					plugins.add(plugin);
				}
			}
		}

		// Categories come from the entries rather than from the document's own list
		Map<String, String> byKey = new TreeMap<>();
		for (Plugin plugin : plugins) {
			byKey.putIfAbsent(plugin.category().toLowerCase(Locale.ROOT), plugin.category());
		}

		return new Catalog(asString(root, "updated"),
		                   List.copyOf(plugins),
		                   List.copyOf(new LinkedHashSet<>(byKey.values())));
	}

	/**
	 * Sorts plugins by the given criterion.
	 *
	 * @param plugins the plugins
	 * @param sort    the sort key: {@code "downloads"}, {@code "stars"}, or {@code "added"}
	 * @return the sorted list
	 */
	public static List<Plugin> sorted(List<Plugin> plugins, String sort) {
		Comparator<Plugin> comparator = switch (sort == null ? "" : sort) {
			case "stars" -> Comparator.comparingInt(Plugin::stars).reversed();
			case "added" -> Comparator.comparing(Plugin::added, Comparator.nullsLast(Comparator.reverseOrder()));
			default -> Comparator.comparingInt(Plugin::downloads).reversed();
		};
		return plugins.stream().sorted(comparator.thenComparing(Plugin::name)).toList();
	}

	private static @Nullable Plugin readPlugin(JsonObject object) {
		String name = asString(object, "name");
		String url = asString(object, "url");
		if (name == null || name.isBlank() || url == null || url.isBlank()) {
			return null;
		}

		String description = null;
		String descriptionZh = null;
		JsonElement descriptions = object.get("description");
		if (descriptions != null && descriptions.isJsonObject()) {
			JsonObject localized = descriptions.getAsJsonObject();
			description = asString(localized, "en");
			descriptionZh = asString(localized, "zh");
		}

		return new Plugin(name,
		                  orEmpty(asString(object, "owner")),
		                  url,
		                  orEmpty(asString(object, "category")),
		                  description,
		                  descriptionZh,
		                  asString(object, "npm"),
		                  asString(object, "version"),
		                  asInt(object, "stars"),
		                  asInt(object, "downloads"),
		                  asString(object, "tarball"),
		                  asString(object, "added"));
	}

	private static @Nullable String asString(JsonObject object, String name) {
		JsonElement element = object.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return null;
		}
		String value = element.getAsString();
		return value.isBlank() ? null : value;
	}

	private static int asInt(JsonObject object, String name) {
		JsonElement element = object.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return 0;
		}
		try {
			return element.getAsInt();
		} catch (RuntimeException e) {
			LOG.warning("Ignoring a " + name + " that is not an integer", e);
			return 0;
		}
	}

	private static String orEmpty(@Nullable String value) {
		return value == null ? "" : value;
	}
}
