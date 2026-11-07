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
package com.mcxcc303.hdsl.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.plugin.DshPluginSettings;
import com.mcxcc303.hdsl.skill.DshSkillManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Manifest definitions and parsers for the {@code .hdslp} modpack archive format.
 */
@NotNullByDefault
public final class PackManifest {
	public static final String FORMAT = "hdsl-modpack";
	public static final String FILE_EXTENSION = ".hdslp";
	public static final List<String> ACCEPTED_EXTENSIONS = List.of(".hdslp", ".zip");
	public static final int FORMAT_VERSION = 3;
	public static final String MANIFEST = "manifest.json";

	private PackManifest() {
	}

	/**
	 * Reads and parses the modpack manifest from an archive.
	 *
	 * @param pack the archive path
	 * @return parsed manifest
	 * @throws DshException when the archive holds no manifest
	 */
	public static Manifest readManifest(Path pack) throws DshException {
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (MANIFEST.equals(entry.getName())) {
					JsonElement parsed = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
					if (!parsed.isJsonObject()) {
						throw new DshException("The pack's manifest is not an object");
					}
					return manifestOf(parsed.getAsJsonObject());
				}
			}
		} catch (IOException e) {
			throw new DshException("Failed to read " + pack, e);
		}
		throw new DshException("This file holds no modpack manifest");
	}

	/**
	 * Parses a JSON object into a Manifest record.
	 *
	 * @param root the JSON manifest root
	 * @return parsed manifest
	 */
	static Manifest manifestOf(JsonObject root) {
		List<Plugin> plugins = new ArrayList<>();
		JsonElement list = root.get("plugins");
		if (list != null && list.isJsonArray()) {
			for (JsonElement element : list.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject object = element.getAsJsonObject();
				String name = string(object, "name");
				if (name == null) {
					continue;
				}
				plugins.add(new Plugin(name,
				                       string(object, "version"),
				                       bool(object, "active"),
				                       bool(object, "local"),
				                       bool(object, "bundled")));
			}
		}

		List<String> bundles = new ArrayList<>();
		JsonElement active = root.get("bundles");
		if (active != null && active.isJsonArray()) {
			for (JsonElement element : active.getAsJsonArray()) {
				if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
					bundles.add(element.getAsString());
				}
			}
		}

		return new Manifest(string(root, "format") == null ? "" : string(root, "format"),
		                    integer(root, "version"),
		                    string(root, "createdAt") == null ? "" : string(root, "createdAt"),
		                    string(root, "instanceId") == null ? "" : string(root, "instanceId"),
		                    string(root, "dshVersion") == null ? "" : string(root, "dshVersion"),
		                    string(root, "appBoot"),
		                    string(root, "profile") == null ? DshInstance.DEFAULT_PROFILE : string(root, "profile"),
		                    List.copyOf(plugins),
		                    List.copyOf(bundles),
		                    bool(root, "hasPatch"),
		                    string(root, "name") == null ? "" : string(root, "name"),
		                    string(root, "packVersion") == null ? "1.0" : string(root, "packVersion"),
		                    string(root, "author") == null ? "" : string(root, "author"),
		                    string(root, "description") == null ? "" : string(root, "description"),
		                    string(root, "url") == null ? "" : string(root, "url"),
		                    string(root, "referenceUrl") == null ? "" : string(root, "referenceUrl"),
		                    integer(root, "sessionCount"),
		                    strings(root, "settings"),
		                    strings(root, "settingsOmitted"),
		                    strings(root, "skills"));
	}

	/**
	 * Reads an array of strings from a JSON property.
	 *
	 * @param root the JSON object
	 * @param name property name
	 * @return list of strings
	 */
	static List<String> strings(JsonObject root, String name) {
		List<String> values = new ArrayList<>();
		JsonElement element = root.get(name);
		if (element != null && element.isJsonArray()) {
			for (JsonElement item : element.getAsJsonArray()) {
				if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
					values.add(item.getAsString());
				}
			}
		}
		return List.copyOf(values);
	}

	/**
	 * Validates that the manifest format and version are supported.
	 *
	 * @param manifest the pack's manifest
	 * @throws DshException when the format or version is unsupported
	 */
	static void requireFormat(Manifest manifest) throws DshException {
		if (!FORMAT.equals(manifest.format())) {
			throw new DshException("This file is not an instance pack (" + manifest.format() + ")");
		}
		if (manifest.version() > FORMAT_VERSION) {
			throw new DshException("This pack was written by a newer launcher (format " + manifest.version() +
			                       "), which this one cannot read");
		}
		if (manifest.dshVersion().isBlank()) {
			throw new DshException("This pack does not say which DeepSeek Harness version it wants");
		}
	}

	static @Nullable JsonElement member(JsonObject object, String name) {
		JsonElement element = object.get(name);
		return element == null || !element.isJsonPrimitive() ? null : element;
	}

	static @Nullable String string(JsonObject object, String name) {
		JsonElement element = member(object, name);
		return element == null || !((JsonPrimitive) element).isString() ? null : element.getAsString();
	}

	static boolean bool(JsonObject object, String name) {
		JsonElement element = member(object, name);
		if (element == null) {
			return false;
		}
		try {
			return element.getAsBoolean();
		} catch (RuntimeException e) {
			return false;
		}
	}

	static int integer(JsonObject object, String name) {
		JsonElement element = member(object, name);
		if (element == null || !((JsonPrimitive) element).isNumber()) {
			return 0;
		}
		return element.getAsInt();
	}

	/**
	 * Reports whether a declared version is really the path of a local file or directory.
	 *
	 * @param version the declared version string
	 * @return true if the version represents a local specification
	 */
	static boolean isLocalSpec(@Nullable String version) {
		if (version == null || version.isBlank()) {
			return false;
		}
		String value = version.trim().toLowerCase(Locale.ROOT);
		return value.startsWith("file:") || value.startsWith("link:") || value.startsWith("/") ||
		       value.startsWith("./") || value.startsWith("../");
	}

	/**
	 * Plugin entry declared in a pack manifest.
	 *
	 * @param name    the package name
	 * @param version the version or local specification
	 * @param active  whether the pack boots it as a bundle
	 * @param local   whether the instance installed it from a local file
	 * @param bundled whether the pack carries the plugin's own files
	 */
	public record Plugin(String name, String version, boolean active, boolean local, boolean bundled) {
	}

	/**
	 * Modpack manifest describing instance configuration and contents.
	 *
	 * @param format          the format identifier
	 * @param version         the format version
	 * @param createdAt       timestamp when the pack was written
	 * @param instanceId      the instance it came from
	 * @param dshVersion      the DeepSeek Harness version it pins
	 * @param appBoot         boot library pin, or null
	 * @param profile         the profile name
	 * @param plugins         the plugins declared
	 * @param bundles         the active bundle list
	 * @param hasPatch        whether the pack carries a patch layer
	 * @param name            pack name
	 * @param packVersion     pack version
	 * @param author          author name
	 * @param description     pack description
	 * @param url             pack URL
	 * @param referenceUrl    documentation reference URL
	 * @param sessionCount    number of conversations carried
	 * @param settings        included settings.yaml sections
	 * @param settingsOmitted omitted settings keys
	 * @param skills          skill pack names carried
	 */
	public record Manifest(String format, int version, String createdAt, String instanceId, String dshVersion,
	                       @Nullable String appBoot, String profile, List<Plugin> plugins, List<String> bundles,
	                       boolean hasPatch, String name, String packVersion, String author, String description,
	                       String url, String referenceUrl, int sessionCount, List<String> settings,
	                       List<String> settingsOmitted, List<String> skills) {

		/**
		 * Returns the plugin installation specifications in order.
		 *
		 * @return list of dependency specifications
		 */
		public List<String> installSpecs() {
			List<String> specs = new ArrayList<>();
			for (Plugin plugin : plugins) {
				if (plugin.local()) {
					continue;
				}
				specs.add(plugin.version() == null || plugin.version().isBlank()
				          ? plugin.name()
				          : plugin.name() + "@" + plugin.version());
			}
			return specs;
		}

		/**
		 * Returns the plugins installed from a local file.
		 *
		 * @return list of local plugins
		 */
		public List<Plugin> localPlugins() {
			return plugins.stream().filter(Plugin::local).toList();
		}

		/**
		 * Returns the local plugins whose files travel inside the pack.
		 *
		 * @return list of bundled plugins
		 */
		public List<Plugin> bundledPlugins() {
			return plugins.stream().filter(Plugin::bundled).toList();
		}

		/**
		 * Returns the local plugins that cannot be installed from this pack.
		 *
		 * @return list of uninstallable plugins
		 */
		public List<Plugin> uninstallablePlugins() {
			return plugins.stream().filter(plugin -> plugin.local() && !plugin.bundled()).toList();
		}
	}

	/**
	 * Pack export configuration options.
	 *
	 * @param name            pack name
	 * @param version         pack version
	 * @param author          pack author
	 * @param description     pack description
	 * @param url             pack URL
	 * @param referenceUrl    documentation reference URL
	 * @param includeSessions whether to export sessions
	 * @param excludedBundles set of bundle names to exclude
	 * @param settings        set of settings sections to include
	 * @param skills          set of skill names to include
	 */
	public record Options(String name, String version, String author, String description, String url,
	                      String referenceUrl, boolean includeSessions, Set<String> excludedBundles,
	                      Set<String> settings, Set<String> skills) {

		public Options(String name, String version, String author, String description, boolean includeSessions) {
			this(name, version, author, description, "", "", includeSessions, Set.of(), Set.of(), Set.of());
		}

		/**
		 * Creates default options for an instance.
		 *
		 * @param instance the instance
		 * @return default options
		 */
		public static Options of(DshInstance instance) {
			Set<String> settings = Set.of();
			try {
				settings = new LinkedHashSet<>(DshPluginSettings.sectionsOf(instance.homeDirectory()));
			} catch (DshException e) {
				LOG.warning("Failed to read the settings of " + instance.id(), e);
			}
			Set<String> skills = Set.of();
			try {
				skills = new LinkedHashSet<>(DshSkillManager.packableNames(instance.homeDirectory()));
			} catch (DshException e) {
				LOG.warning("Failed to read the skills of " + instance.id(), e);
			}
			return new Options(instance.id(), "1.0", "", "", "", "", false, Set.of(), settings, skills);
		}

		/**
		 * Checks whether a bundle is included in the export.
		 *
		 * @param bundle bundle name
		 * @return true if the bundle is included
		 */
		public boolean includesBundle(String bundle) {
			return !excludedBundles.contains(bundle);
		}
	}
}
