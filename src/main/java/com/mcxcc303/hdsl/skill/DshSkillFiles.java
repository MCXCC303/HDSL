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
package com.mcxcc303.hdsl.skill;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/*
 * The filesystem work the skill code does: reading a file as lines, replacing one without a reader seeing half of it, and the shapes a pack travels in -- a directory tree and a zip
 */
@NotNullByDefault
final class DshSkillFiles {
	// The suffix a rewrite is staged under before it replaces the file
	private static final String STAGING = ".hdsl-skills";

	private DshSkillFiles() {}

	static List<String> readLines(Path file) throws IOException {
		return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
	}

	/**
	 * Replaces a file with the given lines, staged beside it and moved atomically when supported.
	 */
	static void writeLines(Path file, List<String> lines) throws IOException {
		Path staging = file.resolveSibling(file.getFileName() + STAGING);
		Files.writeString(staging, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		try {
			Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static long sizeOf(Path directory) throws IOException {
		long total = 0L;
		try (Stream<Path> walk = Files.walk(directory)) {
			for (Path path : walk.toList()) {
				if (Files.isRegularFile(path)) total += Files.size(path);
			}
		}
		return total;
	}

	static void copyTree(Path source, Path target) throws IOException {
		try (Stream<Path> walk = Files.walk(source)) {
			for (Path path : walk.toList()) {
				Path destination = target.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
	}

	static void deleteTree(Path directory) throws IOException {
		try (Stream<Path> walk = Files.walk(directory)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	static void unzip(Path archive, Path target) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				if (entry.isDirectory()) continue;

				String name = entry.getName().replace('\\', '/');
				if (name.isEmpty() || name.startsWith("/") || name.contains("..")) continue;

				Path file = target.resolve(name);
				Files.createDirectories(file.getParent());
				Files.copy(zip, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	/**
	 * Returns the shallowest directory holding a file of this name.
	 *
	 * @param directory the unpacked tree
	 * @param name      the file to look for
	 * @return the directory holding it, or {@code null} when there is none
	 * @throws IOException when the tree cannot be walked
	 */
	static @Nullable Path shallowest(Path directory, String name) throws IOException {
		Path found = null;
		int depth = Integer.MAX_VALUE;
		try (Stream<Path> walk = Files.walk(directory)) {
			for (Path path : walk.toList()) {
				if (!Files.isRegularFile(path) || !name.equals(path.getFileName().toString())) continue;

				int at = directory.relativize(path).getNameCount();
				if (at < depth) {
					depth = at;
					found = path.getParent();
				}
			}
		}
		return found;
	}
}
