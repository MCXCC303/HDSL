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
 * Requirements are judged against the harness packages the instance's installation holds.
 */
@NotNullByDefault
public final class DshInstalledPlugins {

	/**
	 * Whether a plugin is in effect.
	 */
	public enum State {
		/**
		 * A bundle the profile loads, with none of the loader rows it inserts switched off.
		 */
		ACTIVE("active"),

		/**
		 * A bundle the profile loads, with at least one of the loader rows it inserts switched off.
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

		/**
		 * Returns the word this state is written as.
		 */
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
	 * @param rows         the loader row ids its bundle inserts
	 */
	public record Installed(String name,
	                        @Nullable String version,
	                        String declared,
	                        String requirements,
	                        DshPluginVersions.Fit fit,
	                        State state,
	                        String path,
	                        List<String> rows) {
	}

	private DshInstalledPlugins() {
	}

	/**
	 * Reads the plugins an instance's profile holds.
	 *
	 * @param instance the instance
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
			List<String> rows = DshPluginPatch.insertedIds(home, profile, name);
			JsonObject required = requiredOf(manifest);
			plugins.add(new Installed(name,
			                          versionOf(manifest),
			                          entry.getValue(),
			                          textOf(required),
			                          fitOf(required, core),
			                          stateOf(packageDirectory, bundles.contains(name), rows, disabled),
			                          pathOf(directory, name, packageDirectory),
			                          rows));
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
			if (text.length() > 0) {
				text.append(", ");
			}
			text.append(entry.getKey()).append(' ').append(entry.getValue().getAsString());
		}
		return text.toString();
	}

	private static DshPluginVersions.Fit fitOf(JsonObject required, Map<String, String> core) {
		if (required.size() == 0) {
			return DshPluginVersions.Fit.UNCLAIMED;
		}
		return DshPluginRequirements.fits(required, core)
		       ? DshPluginVersions.Fit.FITS
		       : DshPluginVersions.Fit.NOT_FITS;
	}

	private static State stateOf(Path packageDirectory, boolean bundle, List<String> rows, Set<String> disabled) {
		if (!Files.isDirectory(packageDirectory)) {
			return State.MISSING;
		}
		if (!bundle) {
			return State.INACTIVE;
		}
		for (String row : rows) {
			if (disabled.contains(row)) {
				return State.DISABLED;
			}
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
}
