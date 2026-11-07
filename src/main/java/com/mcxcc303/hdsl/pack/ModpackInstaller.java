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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.AppBootPin;
import com.mcxcc303.hdsl.harness.HarnessInstaller;
import com.mcxcc303.hdsl.instance.*;
import com.mcxcc303.hdsl.plugin.DshLocalPlugins;
import com.mcxcc303.hdsl.plugin.DshPluginBundle;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import com.mcxcc303.hdsl.plugin.DshPluginSettings;
import com.mcxcc303.hdsl.skill.DshSkillManager;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Installs and restores instances from modpack archives.
 */
@NotNullByDefault
public final class ModpackInstaller {
	private ModpackInstaller() {
	}

	/**
	 * Builds an instance from a pack.
	 *
	 * @param pack      the archive
	 * @param id        the id to give the instance
	 * @param workspace the working directory the instance starts sessions in
	 * @param onStage   receives progress lines, or null
	 * @return what was built
	 * @throws DshException when the pack cannot be read, the id is taken, or installation fails
	 */
	public static InstallResult install(Path pack, String id, Path workspace, @Nullable Consumer<String> onStage)
	throws DshException {
		return install(pack, id, workspace, null, onStage);
	}

	/**
	 * Builds an instance from a pack, in a folder of the caller's choosing.
	 *
	 * @param pack      the archive
	 * @param id        the id to give the instance
	 * @param workspace the working directory the instance starts sessions in
	 * @param folder    the folder to make the instance in, or null for the selected one
	 * @param onStage   receives progress lines, or null
	 * @return what was built
	 * @throws DshException when the pack cannot be read, the id is taken, or installation fails
	 */
	public static InstallResult install(Path pack,
	                                    String id,
	                                    Path workspace,
	                                    @Nullable Path folder,
	                                    @Nullable Consumer<String> onStage) throws DshException {
		PackManifest.Manifest manifest = PackManifest.readManifest(pack);
		PackManifest.requireFormat(manifest);

		DshInstance existing = InstanceStore.find(id);
		if (existing == null) {
			PackExporter.report(onStage, "Creating instance " + id);
			existing = DshInstanceManager.create(id,
			                                     manifest.dshVersion(),
			                                     manifest.profile(),
			                                     workspace,
			                                     null,
			                                     DshHomeMode.ISOLATED,
			                                     null,
			                                     List.of(),
			                                     Map.of(),
			                                     folder != null ? folder : InstanceFolders.currentFolder());
		}

		boolean installed = HarnessInstaller.isInstalled(existing);
		if (!installed) {
			PackExporter.report(onStage, "Installing DeepSeek Harness " + manifest.dshVersion());
			HarnessInstaller.install(existing, manifest.dshVersion(), onStage);
		} else {
			PackExporter.report(onStage, "Instance " + id + " already has its own DeepSeek Harness");
		}

		if (manifest.appBoot() != null && !manifest.appBoot().equals(existing.version())) {
			PackExporter.report(onStage, "Holding it to boot library " + manifest.appBoot());
			AppBootPin.overrideAppBoot(existing, manifest.appBoot(), onStage);
		}

		int plugins = restoreProfile(existing, manifest, pack, onStage);
		if (manifest.sessionCount() > 0) {
			DshSessionPacks.restoreInto(pack, existing.homeDirectory(), onStage);
		}
		if (!manifest.skills().isEmpty()) {
			DshSkillManager.restoreInto(pack, existing.homeDirectory(), onStage);
		}
		return new InstallResult(existing, !installed, plugins, manifest.bundles().size());
	}

	/**
	 * Puts a pack's profile into an instance that already exists.
	 *
	 * @param instance the instance to fill
	 * @param manifest the pack's manifest
	 * @param pack     the archive, for its patch layer
	 * @param onStage  receives progress lines, or null
	 * @return how many plugins were resolved
	 * @throws DshException when the profile cannot be written or the resolve fails
	 */
	public static int restoreProfile(DshInstance instance,
	                                 PackManifest.Manifest manifest,
	                                 Path pack,
	                                 @Nullable Consumer<String> onStage) throws DshException {
		Path profileDirectory = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
		Path manifestFile = profileDirectory.resolve("package.json");

		if (!Files.isRegularFile(manifestFile)) {
			PackExporter.report(onStage, "Initializing profile " + instance.profile());
			DshPluginInstaller.resolve(instance, onStage);
		}

		if (manifest.hasPatch()) {
			Path patch = profileDirectory.resolve("cordis.patch.yml");
			PackExporter.report(onStage, "Restoring the profile's patch layer");
			PackExporter.writePatch(pack, patch);
		}

		String settings = PackExporter.readSettings(pack);
		if (!settings.isBlank()) {
			List<String> merged = DshPluginSettings.merge(instance.homeDirectory(), settings);
			PackExporter.report(onStage,
			                    "Put the pack's settings for " + merged.size() + " plugin(s) into" +
			                    " settings.yaml: " + String.join(", ", merged));
		}

		if (!manifest.plugins().isEmpty() || !manifest.bundles().isEmpty()) {
			PackExporter.report(onStage, "Writing the pack's plugin list");
			Map<String, Path> released = releaseCarried(instance, manifest, pack, onStage);
			writeProfileManifest(manifestFile, manifest, released);
			if (!manifest.uninstallablePlugins().isEmpty()) {
				PackExporter.report(onStage,
				                    "Note: " + manifest.uninstallablePlugins().size() +
				                    " plugin(s) were installed from a file the pack does not carry, so they are left" +
				                    " out rather than pointed at a path from another machine: " + String.join(", ",
				                                                                                              manifest.uninstallablePlugins()
				                                                                                                      .stream()
				                                                                                                      .map(PackManifest.Plugin::name)
				                                                                                                      .toList()));
			}
			PackExporter.report(onStage, "Resolving " + manifest.installSpecs().size() + " plugin(s)");
			DshPluginInstaller.resolve(instance, onStage);
		}

		return manifest.plugins().size();
	}

	/**
	 * Puts the plugins a pack carries back into the instance.
	 *
	 * @param instance the instance being filled
	 * @param manifest the pack's manifest
	 * @param pack     the archive
	 * @param onStage  receives progress lines, or null
	 * @return map of plugin names to extracted file paths
	 * @throws DshException when the files cannot be written
	 */
	static Map<String, Path> releaseCarried(DshInstance instance,
	                                        PackManifest.Manifest manifest,
	                                        Path pack,
	                                        @Nullable Consumer<String> onStage) throws DshException {
		List<PackManifest.Plugin> bundled = manifest.bundledPlugins();
		if (bundled.isEmpty()) {
			return Map.of();
		}
		Path pluginsDirectory = instance.instanceDirectory().resolve(DshLocalPlugins.DIRECTORY);
		Map<String, Path> released = new LinkedHashMap<>();
		for (PackManifest.Plugin plugin : bundled) {
			Path path = DshPluginBundle.release(pack, pluginsDirectory, plugin.name(), plugin.version());
			if (path == null) {
				PackExporter.report(onStage,
				                    "Note: the pack says it carries " + plugin.name() +
				                    ", but it holds no files for it");
				continue;
			}
			PackExporter.report(onStage, "Put " + plugin.name() + " " + plugin.version() + " into the instance");
			released.put(plugin.name(), path);
		}
		return released;
	}

	/**
	 * Writes a pack's dependencies and bundle list into a profile manifest.
	 *
	 * @param manifestFile the profile's package.json
	 * @param pack         the pack's manifest
	 * @param released     where the pack's carried plugins were put, by package name
	 * @throws DshException when the file cannot be read or written
	 */
	static void writeProfileManifest(Path manifestFile, PackManifest.Manifest pack, Map<String, Path> released)
	throws DshException {
		JsonObject manifest;
		try {
			manifest = JsonUtils.fromJsonFile(manifestFile, JsonObject.class);
			if (manifest == null) {
				manifest = new JsonObject();
			}
		} catch (Exception e) {
			throw new DshException("Failed to read " + manifestFile, e);
		}

		JsonObject dependencies = new JsonObject();
		for (Map.Entry<String, String> entry : dependenciesOf(pack, released).entrySet()) {
			dependencies.addProperty(entry.getKey(), entry.getValue());
		}
		manifest.add("dependencies", dependencies);

		JsonObject dsh = manifest.has("dsh") && manifest.get("dsh").isJsonObject()
		                 ? manifest.getAsJsonObject("dsh")
		                 : new JsonObject();
		JsonObject profile = dsh.has("profile") && dsh.get("profile").isJsonObject()
		                     ? dsh.getAsJsonObject("profile")
		                     : new JsonObject();
		JsonArray bundles = new JsonArray();
		listsBootable(pack, dependencies).forEach(bundles::add);
		profile.add("bundles", bundles);
		dsh.add("profile", profile);
		manifest.add("dsh", dsh);

		Path staging = manifestFile.resolveSibling("package.json.pack");
		try {
			Files.writeString(staging, JsonUtils.GSON.toJson(manifest));
			Files.move(staging, manifestFile, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new DshException("Failed to write " + manifestFile, e);
		}
		LOG.info(
				"Restored the plugin list of " + manifestFile.getParent().getFileName() + ": " + pack.plugins().size() +
				" plugin(s), " + pack.bundles().size() + " bundle(s)");
	}

	/**
	 * Returns the dependencies a pack's profile should declare.
	 *
	 * @param pack     the pack's manifest
	 * @param released where the pack's carried plugins were put, by package name
	 * @return dependency declarations mapped by package name
	 */
	public static Map<String, String> dependenciesOf(PackManifest.Manifest pack, Map<String, Path> released) {
		Map<String, String> ordered = new LinkedHashMap<>();
		for (PackManifest.Plugin plugin : pack.plugins()) {
			if (plugin.bundled()) {
				Path path = released.get(plugin.name());
				if (path != null) {
					ordered.put(plugin.name(), path.toString());
				}
				continue;
			}
			if (plugin.local()) {
				continue;
			}
			ordered.put(plugin.name(), plugin.version() == null ? "" : plugin.version());
		}
		return ordered;
	}

	/**
	 * Returns the bundles a profile with these dependencies can boot.
	 *
	 * @param pack         the pack's manifest
	 * @param dependencies what the profile will declare
	 * @return the bundle list, in the pack's order
	 */
	public static List<String> listsBootable(PackManifest.Manifest pack, JsonObject dependencies) {
		List<String> bootable = new ArrayList<>();
		for (String bundle : pack.bundles()) {
			boolean named = pack.plugins().stream().anyMatch(plugin -> plugin.name().equals(bundle));
			if (!named || dependencies.has(bundle)) {
				bootable.add(bundle);
			}
		}
		return bootable;
	}

	/**
	 * Result summary of a modpack installation.
	 *
	 * @param instance  the instance that was built
	 * @param installed whether the harness itself had to be installed
	 * @param plugins   how many plugins were resolved
	 * @param bundles   how many bundles are active
	 */
	public record InstallResult(DshInstance instance, boolean installed, int plugins, int bundles) {
	}
}
