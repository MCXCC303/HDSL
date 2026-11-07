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
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.DshPackageRegistry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Bundles and extracts local plugin files for instance export and import packages.
 */
@NotNullByDefault
public final class DshPluginBundle {
	/** Directory prefix inside pack archives for plugin payloads. */
	public static final String ENTRY_PREFIX = "plugins/";

	private DshPluginBundle() {
	}

	/**
	 * Finds the files an instance installed a local plugin from.
	 *
	 * @param profileDirectory the profile the plugin belongs to
	 * @param name             the dependency name
	 * @param declared         what the profile declares for it
	 * @return the payload, or {@code null} when no files can be found
	 */
	public static @Nullable Payload locate(Path profileDirectory, String name, String declared) {
		Path file = fileOf(declared, profileDirectory);
		if (file != null && Files.isRegularFile(file)) {
			try {
				DshLocalPlugins.Package pkg = DshLocalPlugins.inspect(file);
				return new Payload(name, pkg.version(), file, null);
			} catch (DshException e) {
				LOG.warning("Could not read the packed plugin " + file, e);
			}
		}

		if (namesADirectory(declared, profileDirectory) || isLinked(profileDirectory, name)) {
			return null;
		}
		Path directory = profileDirectory.resolve("node_modules").resolve(name);
		if (Files.isDirectory(directory)) {
			String version = versionOf(directory);
			if (version != null) {
				return new Payload(name, version, null, readable(directory));
			}
		}
		return null;
	}

	/**
	 * Reports whether a local specification names a directory rather than a packed plugin.
	 *
	 * @param declared         the specification
	 * @param profileDirectory the profile, for a specification that is relative
	 * @return whether it names a directory
	 */
	public static boolean namesADirectory(String declared, Path profileDirectory) {
		String raw = declared == null ? "" : declared.trim();
		if (raw.startsWith("link:")) {
			return true;
		}
		Path path = fileOf(raw, profileDirectory);
		if (path == null) {
			return false;
		}
		if (Files.isDirectory(path)) {
			return true;
		}
		return !DshPluginSources.ARCHIVE.matcher(path.getFileName().toString()).matches();
	}

	private static Path readable(Path directory) {
		try {
			Path parent = directory.getParent().toRealPath();
			Path real = directory.toRealPath();
			return real.equals(parent.resolve(directory.getFileName())) ? directory : real;
		} catch (IOException | RuntimeException e) {
			return directory;
		}
	}

	/**
	 * Reports whether the profile's copy of a package is a link out of the profile.
	 *
	 * @param profileDirectory the profile the plugin belongs to
	 * @param name             the dependency name
	 * @return whether the entry names a directory outside the profile
	 */
	public static boolean isLinked(Path profileDirectory, String name) {
		Path directory = profileDirectory.resolve("node_modules").resolve(name);
		if (!Files.isDirectory(directory)) {
			return false;
		}
		try {
			return !directory.toRealPath().startsWith(profileDirectory.toRealPath());
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * Reads the file a local specification names.
	 *
	 * @param declared         the specification
	 * @param profileDirectory the profile, for a specification that is relative
	 * @return the file, or {@code null} when the specification does not name one
	 */
	static @Nullable Path fileOf(String declared, Path profileDirectory) {
		String raw = declared.trim();
		if (raw.startsWith("link:")) {
			return null;
		}
		if (raw.startsWith("file:")) {
			raw = raw.substring("file:".length());
			if (raw.startsWith("//")) {
				raw = raw.substring(2);
			}
		}
		if (raw.isBlank() || raw.startsWith("http:") || raw.startsWith("https:")) {
			return null;
		}
		Path path = null;
		try {
			path = Path.of(raw);
		} catch (RuntimeException e) {
			return null;
		}
		if (!path.isAbsolute()) {
			path = profileDirectory.resolve(path);
		}
		if (!Files.isRegularFile(path) && raw.contains("%")) {
			try {
				Path decoded = Path.of(URLDecoder.decode(raw, StandardCharsets.UTF_8));
				if (Files.isRegularFile(decoded)) {
					return decoded;
				}
			} catch (RuntimeException e) {
				return path;
			}
		}
		return path;
	}

	/**
	 * Reads a package's manifest.
	 *
	 * @param directory the package directory
	 * @return the manifest, or {@code null} when the directory holds no readable one
	 */
	public static @Nullable JsonObject readManifest(Path directory) {
		Path manifest = directory.resolve("package.json");
		if (!Files.isRegularFile(manifest)) {
			return null;
		}
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(manifest));
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not read the manifest of " + directory, e);
			return null;
		}
	}

	private static @Nullable String versionOf(Path directory) {
		JsonObject manifest = readManifest(directory);
		if (manifest == null) {
			return null;
		}
		JsonElement version = manifest.get("version");
		return version == null || version.isJsonNull() ? null : version.getAsString();
	}

	/**
	 * Returns the names of the plugins a pack carries.
	 *
	 * @param pack the archive
	 * @return the entry names, in the order the archive holds them
	 * @throws DshException when the archive cannot be read
	 */
	public static List<String> carried(Path pack) throws DshException {
		List<String> names = new ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().startsWith(ENTRY_PREFIX)) {
					names.add(entry.getName());
				}
			}
		} catch (IOException e) {
			throw new DshException("Failed to read " + pack, e);
		}
		return names;
	}

	/**
	 * Writes a plugin's files into a pack.
	 *
	 * @param zip     the archive being written
	 * @param payload the files
	 * @param onStage receives progress lines, or {@code null}
	 * @throws IOException when the archive cannot be written
	 */
	public static void writeInto(ZipOutputStream zip, Payload payload, @Nullable Consumer<String> onStage)
	throws IOException {
		if (payload.archive() != null) {
			ZipEntry entry = new ZipEntry(payload.entryName());
			entry.setTime(0L);
			zip.putNextEntry(entry);
			Files.copy(payload.archive(), zip);
			zip.closeEntry();
			report(onStage,
			       "Carrying " + payload.name() + " " + payload.version() + " as the plugin file this instance keeps");
			return;
		}

		Path directory = payload.directory();
		if (directory == null) {
			return;
		}
		String prefix = payload.entryName();
		int files = 0;
		try (var walk = Files.walk(directory)) {
			for (Path path : walk.filter(Files::isRegularFile).toList()) {
				String relative = directory.relativize(path).toString().replace('\\', '/');
				if (relative.startsWith("node_modules/") || relative.contains("/node_modules/") ||
				    relative.startsWith(".git/") || relative.contains("/.git/")) {
					continue;
				}
				// Write package.json last
				if (relative.equals("package.json")) {
					continue;
				}
				ZipEntry entry = new ZipEntry(prefix + relative);
				entry.setTime(0L);
				zip.putNextEntry(entry);
				Files.copy(path, zip);
				zip.closeEntry();
				files++;
			}
		}
		// Write package.json last
		Path manifest = directory.resolve("package.json");
		if (Files.isRegularFile(manifest)) {
			ZipEntry entry = new ZipEntry(prefix + "package.json");
			entry.setTime(0L);
			zip.putNextEntry(entry);
			Files.copy(manifest, zip);
			zip.closeEntry();
			files++;
		}
		report(onStage,
		       "Carrying " + payload.name() + " " + payload.version() + " from the directory it was" +
		       " installed from, " + files + " file(s)");
	}

	/**
	 * Puts a pack's copy of a plugin back into an instance.
	 *
	 * @param pack             the archive
	 * @param pluginsDirectory the instance's plugin directory
	 * @param name             the dependency name
	 * @param version          the version the pack recorded
	 * @return where the files were written, or {@code null} when the pack carries none
	 * @throws DshException when the archive cannot be read or written out
	 */
	public static @Nullable Path release(Path pack, Path pluginsDirectory, String name, String version)
	throws DshException {
		String safe = DshLocalPlugins.safeName(name);
		String wanted = ENTRY_PREFIX + safe + "-" + DshLocalPlugins.safeName(version) + ".tgz";
		Path released = null;
		try {
			Files.createDirectories(pluginsDirectory);
			try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
				ZipEntry entry;
				while ((entry = zip.getNextEntry()) != null) {
					String entryName = entry.getName();
					if (entry.isDirectory() || !entryName.startsWith(ENTRY_PREFIX)) {
						continue;
					}
					String rest = entryName.substring(ENTRY_PREFIX.length());
					int slash = rest.indexOf('/');
					String carried = slash < 0 ? rest : rest.substring(0, slash);
					if (!isOurs(carried, safe)) {
						continue;
					}
					if (slash < 0) {
						if (!entryName.endsWith(".tgz")) {
							continue;
						}
						Path target = pluginsDirectory.resolve(carried);
						Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
						return target.toAbsolutePath().normalize();
					}
					Path base = pluginsDirectory.toAbsolutePath().normalize();
					Path target = base.resolve(carried)
					                  .resolve(rest.substring(slash + 1).replace('/', File.separatorChar))
					                  .normalize();
					// Verify target path stays inside base directory to prevent zip slip
					if (!target.startsWith(base)) {
						LOG.warning("Not unpacking " + entryName + " out of " + pack.getFileName() +
						            ": it would be written outside " + base);
						throw new IOException(
								"That pack tries to write outside the plugin directory (" + entryName + ")");
					}
					Files.createDirectories(target.getParent());
					// Read straight out of the archive
					Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
					released = pluginsDirectory.resolve(carried).toAbsolutePath().normalize();
				}
			}
		} catch (IOException e) {
			throw new DshException("Failed to put the pack's copy of " + name + " into " + pluginsDirectory, e);
		}
		return released;
	}

	private static boolean isOurs(String carried, String safe) {
		if (carried.equals(safe) || carried.equals(safe + ".tgz")) {
			return true;
		}
		return carried.startsWith(safe + "-") || carried.startsWith(safe + ".");
	}

	private static void report(@Nullable Consumer<String> onStage, String line) {
		if (onStage != null) {
			onStage.accept(line);
		}
	}

	/**
	 * The files of one local plugin, as they are found on the exporting side.
	 *
	 * @param name      the dependency name, which is the key the profile manifest uses
	 * @param version   the package's own version
	 * @param archive   the packed plugin the instance keeps, or {@code null} for a directory
	 * @param directory the profile's own copy of the plugin's files, or {@code null} for an archive
	 */
	public record Payload(String name, String version, @Nullable Path archive, @Nullable Path directory) {

		/**
		 * Returns the name this payload is written under.
		 *
		 * @return the entry name, ending in {@code /} for a directory
		 */
		public String entryName() {
			String safe = DshLocalPlugins.safeName(name) + "-" + DshLocalPlugins.safeName(version);
			return archive != null ? ENTRY_PREFIX + safe + ".tgz" : ENTRY_PREFIX + safe + "/";
		}

		/**
		 * Reports how big the payload is.
		 *
		 * @return the size in bytes, or zero when it cannot be measured
		 */
		public long size() {
			try {
				if (archive != null) {
					return Files.size(archive);
				}
				try (var walk = Files.walk(directory)) {
					return walk.filter(Files::isRegularFile).mapToLong(path -> {
						try {
							return Files.size(path);
						} catch (IOException e) {
							return 0L;
						}
					}).sum();
				}
			} catch (IOException e) {
				return 0L;
			}
		}
	}
}
