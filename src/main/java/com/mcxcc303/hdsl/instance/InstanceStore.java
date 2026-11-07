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
package com.mcxcc303.hdsl.instance;

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshFiles;
import com.mcxcc303.hdsl.core.DshPaths;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Reads and writes instance manifests.
 */
@NotNullByDefault
public final class InstanceStore {
	/**
	 * The manifest file inside an instance directory.
	 */
	public static final String MANIFEST_NAME = "instance.json";

	private InstanceStore() {
	}

	private static @Nullable DshInstance read(Path directory) {
		Path manifest = directory.resolve(MANIFEST_NAME);
		if (!Files.isRegularFile(manifest)) {
			return null;
		}
		try {
			DshInstance instance = JsonUtils.fromJsonFile(manifest, DshInstance.class);
			if (instance == null || instance.id() == null || instance.version() == null) {
				return null;
			}
			return instance.withDirectory(directory);
		} catch (Exception e) {
			LOG.warning("Failed to read instance manifest " + manifest, e);
			return null;
		}
	}

	/**
	 * Writes an instance manifest.
	 *
	 * @param instance the instance to persist
	 * @throws DshException when the manifest cannot be written
	 */
	static void write(DshInstance instance) throws DshException {
		try {
			Path directory = instance.instanceDirectory();
			Files.createDirectories(directory);
			JsonUtils.writeToJsonFile(directory.resolve(MANIFEST_NAME), instance);
		} catch (IOException e) {
			throw new DshException("Failed to write the instance manifest for " + instance.id(), e);
		}
	}

	/**
	 * Lists every readable instance in the given root directory, sorted newest first.
	 *
	 * @param root the directory containing instances
	 * @return the known instances
	 */
	public static List<DshInstance> listIn(Path root) {
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		List<DshInstance> instances = new ArrayList<>();
		try (var children = Files.list(root)) {
			for (Path child : children.filter(Files::isDirectory).toList()) {
				DshInstance instance = read(child);
				if (instance != null) {
					instances.add(instance);
				}
			}
		} catch (IOException e) {
			LOG.warning("Failed to read instances in " + root, e);
			return List.of();
		}
		instances.sort(Comparator.comparing(DshInstance::createdAt).reversed());
		return List.copyOf(instances);
	}

	/**
	 * Finds an instance by ID across all configured instance folders.
	 *
	 * @param id the instance ID
	 * @return the instance, or {@code null} if not found or unreadable
	 */
	public static @Nullable DshInstance find(String id) {
		try {
			String segment = DshPaths.segment(id, "instance id");
			DshInstance found = null;
			Path foundIn = null;
			for (Path root : InstanceFolders.folders()) {
				DshInstance instance = read(root.resolve(segment));
				if (instance == null) {
					continue;
				}
				if (found == null) {
					found = instance;
					foundIn = instance.instanceDirectory();
				} else {
					// Continue checking other folders to warn about duplicate instance names.
					InstanceListing.warnAboutASharedName(id, foundIn, instance.instanceDirectory());
				}
			}
			return found;
		} catch (DshException e) {
			return null;
		}
	}

	/**
	 * Deletes a path, suppressing any I/O errors.
	 *
	 * @param path the path to delete
	 */
	static void deleteQuietly(Path path) {
		if (path != null) {
			DshFiles.deleteTreeQuietly(path);
		}
	}
}
