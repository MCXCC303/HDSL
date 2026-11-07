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

import com.mcxcc303.hdsl.account.DshProfilePatch;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.AppBootPin;
import com.mcxcc303.hdsl.harness.DshPackageRegistry;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.launch.DshSession;
import com.mcxcc303.hdsl.launch.DshSessions;
import com.mcxcc303.hdsl.plugin.DshPluginBundle;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import com.mcxcc303.hdsl.plugin.DshPluginSettings;
import com.mcxcc303.hdsl.skill.DshSkill;
import com.mcxcc303.hdsl.skill.DshSkillManager;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Exports an instance configuration as a modpack archive.
 */
@NotNullByDefault
public final class PackExporter {
	private static final String PATCH = "cordis.patch.yml";

	private PackExporter() {
	}

	/**
	 * Exports an instance configuration into a pack archive with default options.
	 *
	 * @param instance the instance to describe
	 * @param target   the archive to create
	 * @param onStage  receives progress lines, or null
	 * @return export summary
	 * @throws DshException when the configuration cannot be read or written
	 */
	public static ExportResult export(DshInstance instance, Path target, @Nullable Consumer<String> onStage)
	throws DshException {
		return export(instance, target, PackManifest.Options.of(instance), onStage);
	}

	/**
	 * Writes an instance configuration into a pack archive with custom options.
	 *
	 * @param instance the instance to describe
	 * @param target   the archive to create
	 * @param options  options describing pack contents and metadata
	 * @param onStage  receives progress lines, or null
	 * @return export summary
	 * @throws DshException when the configuration cannot be read or written
	 */
	public static ExportResult export(DshInstance instance,
	                                  Path target,
	                                  PackManifest.Options options,
	                                  @Nullable Consumer<String> onStage) throws DshException {
		Path profileDirectory = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
		Map<String, String> dependencies = DshPluginInstaller.readDependencies(instance.homeDirectory(),
		                                                                       instance.profile());
		List<String> bundles = new ArrayList<>(DshPluginInstaller.readBundles(instance.homeDirectory(),
		                                                                      instance.profile()));
		bundles.removeIf(bundle -> !options.includesBundle(bundle));

		List<PackManifest.Plugin> plugins = new ArrayList<>();
		List<DshPluginBundle.Payload> carried = new ArrayList<>();
		for (Map.Entry<String, String> entry : dependencies.entrySet()) {
			if (!options.includesBundle(entry.getKey())) {
				continue;
			}
			boolean active = bundles.contains(entry.getKey());
			if (!PackManifest.isLocalSpec(entry.getValue())) {
				plugins.add(new PackManifest.Plugin(entry.getKey(), entry.getValue(), active, false, false));
				continue;
			}
			plugins.add(localPlugin(profileDirectory, entry.getKey(), entry.getValue(), active, carried, onStage));
		}

		Path patch = profileDirectory.resolve("cordis.patch.yml");
		boolean hasPatch = Files.isRegularFile(patch);
		String appBoot = AppBootPin.readAppBoot(instance);
		if (appBoot != null && appBoot.equals(instance.version())) {
			appBoot = null;
		}

		List<DshSession> sessions = options.includeSessions() ? DshSessions.list(instance.homeDirectory()) : List.of();

		DshPluginSettings.Carried settings = options.settings().isEmpty()
		                                     ? new DshPluginSettings.Carried("",
		                                                                     List.of(),
		                                                                     List.of())
		                                     : DshPluginSettings.extract(instance.homeDirectory(), options.settings());
		if (!settings.sections().isEmpty()) {
			report(onStage,
			       "Carrying the settings of " + settings.sections().size() + " plugin(s): " +
			       String.join(", ", settings.sections()));
		}
		if (!settings.omitted().isEmpty()) {
			report(onStage,
			       "Left out " + settings.omitted().size() + " value(s) that look like credentials: " +
			       String.join(", ", settings.omitted()));
		}

		List<DshSkill> skills = DshSkillManager.packable(instance.homeDirectory(), options.skills());
		List<String> skillNames = skills.stream().map(DshSkill::fileName).toList();

		PackManifest.Manifest manifest = new PackManifest.Manifest(PackManifest.FORMAT,
		                                                           PackManifest.FORMAT_VERSION,
		                                                           Instant.now().toString(),
		                                                           instance.id(),
		                                                           instance.version(),
		                                                           appBoot,
		                                                           instance.profile(),
		                                                           List.copyOf(plugins),
		                                                           List.copyOf(bundles),
		                                                           hasPatch,
		                                                           options.name(),
		                                                           options.version(),
		                                                           options.author(),
		                                                           options.description(),
		                                                           options.url(),
		                                                           options.referenceUrl(),
		                                                           sessions.size(),
		                                                           settings.sections(),
		                                                           settings.omitted(),
		                                                           skillNames);

		report(onStage, "Recording " + plugins.size() + " plugin(s), " + bundles.size() + " active bundle(s)");
		Path parent = target.toAbsolutePath().getParent();
		try {
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
				if (hasPatch) {
					byte[] body = DshProfilePatch.withoutRedundantInserts(Files.readString(patch,
					                                                                       StandardCharsets.UTF_8),
					                                                      bundles)
					                             .text()
					                             .getBytes(StandardCharsets.UTF_8);
					zip.putNextEntry(new ZipEntry(PATCH));
					zip.write(body);
					zip.closeEntry();
				}
				if (!settings.text().isEmpty()) {
					zip.putNextEntry(new ZipEntry(DshPluginSettings.ENTRY));
					zip.write(settings.text().getBytes(StandardCharsets.UTF_8));
					zip.closeEntry();
				}
				for (DshPluginBundle.Payload payload : carried) {
					DshPluginBundle.writeInto(zip, payload, onStage);
				}
				if (!sessions.isEmpty()) {
					DshSessionPacks.writeInto(zip, instance.homeDirectory(), sessions, onStage);
					DshSessionPacks.writeAttachmentsInto(zip, instance.homeDirectory(), sessions, onStage);
				}

				if (!skills.isEmpty()) {
					DshSkillManager.writeInto(zip, skills, onStage);
				}
				zip.putNextEntry(new ZipEntry(PackManifest.MANIFEST));
				zip.write(JsonUtils.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		} catch (IOException e) {
			throw new DshException("Failed to write " + target, e);
		}

		long size = sizeOf(target);
		report(onStage,
		       "Wrote a pack describing DeepSeek Harness " + instance.version() +
		       (appBoot == null ? "" : " with boot library " + appBoot) + ", " + plugins.size() + " plugin(s)");
		LOG.info("Wrote a modpack for " + instance.id() + " to " + target);
		return new ExportResult(plugins.size(), size);
	}

	/**
	 * Resolves a local plugin dependency for inclusion in a pack.
	 *
	 * @param profileDirectory profile directory path
	 * @param name             dependency name
	 * @param declared         declared version specification
	 * @param active           whether the plugin is in the bundle list
	 * @param carried          list collecting bundled plugin payloads
	 * @param onStage          consumer for progress messages, or null
	 * @return plugin manifest entry
	 */
	static PackManifest.Plugin localPlugin(Path profileDirectory,
	                                       String name,
	                                       String declared,
	                                       boolean active,
	                                       List<DshPluginBundle.Payload> carried,
	                                       @Nullable Consumer<String> onStage) {
		DshPluginBundle.Payload payload = DshPluginBundle.locate(profileDirectory, name, declared);
		if (payload == null) {
			report(onStage,
			       DshPluginBundle.namesADirectory(declared, profileDirectory)
			       ? "Note: " + name + " is a plugin under development, installed from the directory" +
			         " it is worked on in, so the pack does not carry it"
			       : "Note: " + name + " was installed from a file this instance no longer has," +
			         " so the pack cannot carry it");
			return new PackManifest.Plugin(name, declared, active, true, false);
		}

		DshPackageRegistry.Availability availability = DshPackageRegistry.availability(name, payload.version());
		if (availability == DshPackageRegistry.Availability.PUBLISHED) {
			report(onStage,
			       name + " " + payload.version() + " is published, so the pack fetches it" +
			       " from the registry rather than carrying it");
			return new PackManifest.Plugin(name, payload.version(), active, false, false);
		}
		if (availability == DshPackageRegistry.Availability.UNKNOWN) {
			report(onStage,
			       "Could not ask the registry about " + name + " " + payload.version() +
			       "; carrying its files instead");
		} else {
			report(onStage, name + " " + payload.version() + " is not published, so the pack must carry it");
		}
		carried.add(payload);
		return new PackManifest.Plugin(name, payload.version(), active, true, true);
	}

	/**
	 * Lists local file or directory plugins installed in an instance.
	 *
	 * @param instance the instance
	 * @return list of local plugins
	 * @throws DshException if reading dependencies fails
	 */
	public static List<LocalPlugin> localPlugins(DshInstance instance) throws DshException {
		Path profileDirectory = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
		List<LocalPlugin> plugins = new ArrayList<>();
		for (Map.Entry<String, String> entry : DshPluginInstaller.readDependencies(instance.homeDirectory(),
		                                                                           instance.profile()).entrySet()) {
			if (PackManifest.isLocalSpec(entry.getValue())) {
				plugins.add(new LocalPlugin(entry.getKey(),
				                            DshPluginBundle.namesADirectory(entry.getValue(), profileDirectory)));
			}
		}
		return List.copyOf(plugins);
	}

	/**
	 * Reads the settings a pack carries.
	 *
	 * @param pack the archive
	 * @return the text, or an empty string when the pack carries none
	 * @throws DshException when the archive cannot be read
	 */
	static String readSettings(Path pack) throws DshException {
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (DshPluginSettings.ENTRY.equals(entry.getName())) {
					return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
				}
			}
		} catch (IOException e) {
			throw new DshException("Failed to read " + pack, e);
		}
		return "";
	}

	/**
	 * Reads a pack's patch layer into a profile.
	 *
	 * @param pack   the archive
	 * @param target the file to write
	 * @throws DshException when the archive cannot be read or the file written
	 */
	static void writePatch(Path pack, Path target) throws DshException {
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (PATCH.equals(entry.getName())) {
					byte[] body = zip.readAllBytes();
					Files.createDirectories(target.getParent());
					Path staging = target.resolveSibling("cordis.patch.yml.pack");
					Files.write(staging, body);
					Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
					return;
				}
			}
		} catch (IOException e) {
			throw new DshException("Failed to read " + pack, e);
		}
		throw new DshException("The pack says it carries a patch layer, but does not");
	}

	static void report(@Nullable Consumer<String> onStage, String message) {
		if (onStage != null) {
			onStage.accept(message);
		}
	}

	static long sizeOf(Path file) {
		try {
			return Files.size(file);
		} catch (IOException e) {
			return 0;
		}
	}

	/**
	 * Summary of an export result.
	 *
	 * @param plugins how many plugins were recorded
	 * @param bytes   the pack's size in bytes
	 */
	public record ExportResult(int plugins, long bytes) {
	}

	/**
	 * One plugin an instance installed from this machine rather than from a registry.
	 *
	 * @param name        the dependency name
	 * @param development true if installed from a development directory
	 */
	public record LocalPlugin(String name, boolean development) {
	}
}
