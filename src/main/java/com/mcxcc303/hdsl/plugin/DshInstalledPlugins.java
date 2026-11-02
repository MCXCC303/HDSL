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
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * The plugins an instance's profile holds, and what is known about each of them.
 * <p>
 * Requirements are checked against the harness packages installed for the instance.
 */
@NotNullByDefault
public final class DshInstalledPlugins {
	private DshInstalledPlugins() {}

	/**
	 * Reads the plugins an instance's profile holds.
	 *
	 * @return the plugins, in the order the profile declares them
	 * @throws DshException when the profile's patch layer cannot be read
	 */
	public static List<Installed> of(DshInstance instance) throws DshException {
		Path home = instance.homeDirectory();
		String profile = instance.profile();
		Path directory = DshPluginPatch.profileDirectory(home, profile);
		Map<String, String> dependencies = DshPluginInstaller.readDependencies(home, profile);
		List<String> bundles = DshPluginInstaller.readBundles(home, profile);
		Set<String> disabled = DshPluginPatch.disabledIds(home, profile);
		Map<String, String> core = DshPluginRequirements.coreVersions(instance);

		List<Installed> plugins = new ArrayList<>(dependencies.size());
		for (Map.Entry<String, String> entry : dependencies.entrySet()) {
			String name = entry.getKey();
			Path packageDirectory = directory.resolve("node_modules").resolve(name);
			JsonObject manifest = DshPluginBundle.readManifest(packageDirectory);
			List<DshPluginPatch.Row> rows = DshPluginPatch.insertedRows(home, profile, name);
			JsonObject required = requiredOf(manifest);
			plugins.add(new Installed(name,
			                          versionOf(manifest),
			                          entry.getValue(),
			                          textOf(required),
			                          fitOf(required, core),
			                          stateOf(packageDirectory, bundles.contains(name), rows, disabled),
			                          pathOf(directory, name, packageDirectory),
			                          rows,
			                          PluginIcons.iconFile(packageDirectory),
			                          PluginTitles.titleOf(packageDirectory)));
		}
		return List.copyOf(plugins);
	}

	private static JsonObject requiredOf(@Nullable JsonObject manifest) {
		if (manifest == null) {
			return new JsonObject();
		}
		JsonElement peers = manifest.get("peerDependencies");
		if (peers == null || !peers.isJsonObject()) {
			return new JsonObject();
		}
		JsonObject core = DshPluginRequirements.coreRequirements(peers.getAsJsonObject());
		JsonObject required = new JsonObject();
		for (Map.Entry<String, JsonElement> entry : core.entrySet()) {
			String range = entry.getValue().getAsString();
			if (DshPluginRequirements.isWildcard(range)) {
				// A wildcard asks for any version, so it is not a requirement.
				continue;
			}
			required.add(entry.getKey(), entry.getValue());
		}
		return required;
	}

	private static String textOf(JsonObject required) {
		StringBuilder text = new StringBuilder();
		for (Map.Entry<String, JsonElement> entry : required.entrySet()) {
			if (!text.isEmpty()) text.append(", ");
			text.append(entry.getKey()).append(' ').append(entry.getValue().getAsString());
		}
		return text.toString();
	}

	private static DshPluginVersions.Fit fitOf(JsonObject required, Map<String, String> core) {
		if (required.isEmpty()) return DshPluginVersions.Fit.UNCLAIMED;
		return DshPluginRequirements.fits(required, core) ? DshPluginVersions.Fit.FITS : DshPluginVersions.Fit.NOT_FITS;
	}

	private static State stateOf(Path packageDirectory,
	                             boolean bundle,
	                             List<DshPluginPatch.Row> rows,
	                             Set<String> disabled) {
		if (!Files.isDirectory(packageDirectory)) return State.MISSING;
		if (!bundle) return State.INACTIVE;

		for (DshPluginPatch.Row row : rows) {
			if (disabled.contains(row.id())) return State.DISABLED;
		}
		return State.ACTIVE;
	}

	private static String pathOf(Path profileDirectory, String name, Path packageDirectory) {
		String held = "node_modules/" + name;
		if (!Files.isDirectory(packageDirectory)) {
			return held;
		}
		try {
			Path real = packageDirectory.toRealPath();
			if (!real.startsWith(profileDirectory.toRealPath())) {
				return real.toString();
			}
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not resolve the path of " + packageDirectory, e);
		}
		return held;
	}

	private static @Nullable String versionOf(@Nullable JsonObject manifest) {
		if (manifest == null) {
			return null;
		}
		JsonElement version = manifest.get("version");
		return version == null || version.isJsonNull() ? null : version.getAsString();
	}

	/**
	 * Whether a plugin is in effect.
	 */
	public enum State {
		/**
		 * A bundle the profile loads, with all its inserted rows on.
		 */
		ACTIVE("active"),

		/**
		 * A bundle the profile loads, with at least one inserted row off.
		 */
		DISABLED("disabled"),

		/**
		 * A dependency the profile does not load as a bundle.
		 */
		INACTIVE("inactive"),

		/**
		 * The profile declares it, but the package is not installed.
		 */
		MISSING("missing");

		private final String text;

		State(String text) {
			this.text = text;
		}

		public String text() {
			return text;
		}
	}

	/**
	 * One plugin a profile holds.
	 *
	 * @param version      the installed version, or `null` when the package is not there
	 * @param declared     what the profile declares for it, such as a version range or a local path
	 * @param requirements the harness peer requirements left after wildcards are dropped, as text
	 * @param path         the path inside the profile, or the directory it links to
	 * @param rows         the loader rows its bundle inserts, with the modules they mount
	 * @param icon         the picture the package declares, or `null` when it declares none that can be read
	 * @param title        the title the package publishes in its locales, or `null` when it publishes none
	 */
	public record Installed(String name, @Nullable String version, String declared, String requirements,
	                        DshPluginVersions.Fit fit, State state, String path, List<DshPluginPatch.Row> rows,
	                        @Nullable Path icon, @Nullable String title) {

		/**
		 * @return the ids, in the order the bundle patch writes them
		 */
		public List<String> rowIds() {
			return rows.stream().map(DshPluginPatch.Row::id).toList();
		}

		/**
		 * The row mounting the package itself first
		 * Then the first row, then the package name.
		 *
		 * @return the display name
		 */
		public String displayName() {
			if (title != null) return title;
			for (DshPluginPatch.Row row : rows) {
				if (name.equals(row.name())) return row.id();
			}
			for (DshPluginPatch.Row row : rows) {
				if (row.name() != null && row.name().startsWith(name + "/")) return row.id();
			}
			return rows.isEmpty() ? name : rows.getFirst().id();
		}
	}
}
