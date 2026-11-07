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
import com.mcxcc303.hdsl.instance.DshInstance;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Updates recorded local plugin paths across instance profiles when an instance directory is renamed.
 */
@NotNullByDefault
public final class DshLocalPluginPaths {
	private static final String MANIFEST = "package.json";
	private static final List<String> RECORDS = List.of(MANIFEST, "pnpm-lock.yaml", "node_modules/.pnpm/lock.yaml");

	private DshLocalPluginPaths() {
	}

	/**
	 * Rewrites local plugin paths recorded in profile manifests and lockfiles to reflect an instance move.
	 *
	 * @param instance the instance under its new identity
	 * @param from     the old instance directory
	 * @param to       the new instance directory
	 * @return the names of the packages whose recorded paths moved
	 * @throws DshException when a record cannot be read or written
	 */
	public static List<String> relocate(DshInstance instance, Path from, Path to) throws DshException {
		Path oldDirectory = from.toAbsolutePath().normalize();
		Path newDirectory = to.toAbsolutePath().normalize();
		if (oldDirectory.equals(newDirectory)) {
			return List.of();
		}

		Path home;
		try {
			home = instance.homeDirectory();
		} catch (DshException e) {
			LOG.warning("Could not locate the home of " + instance.id() +
			            " to move the paths it recorded for local plugins", e);
			return List.of();
		}

		List<Record> written = new ArrayList<>();
		Set<String> moved = new LinkedHashSet<>();
		try {
			for (Path profile : profilesIn(home)) {
				for (String name : RECORDS) {
					Path file = profile.resolve(name);
					if (!Files.isRegularFile(file)) {
						continue;
					}
					String text = Files.readString(file, StandardCharsets.UTF_8);
					String rewritten = rewritePaths(text, oldDirectory, newDirectory);
					if (rewritten.equals(text)) {
						continue;
					}
					if (name.equals(MANIFEST)) {
						moved.addAll(changedDependencies(text, rewritten));
					}
					Set<PosixFilePermission> permissions = permissionsOf(file);
					written.add(new Record(file, text, permissions));
					write(file, rewritten, permissions);
				}
			}
		} catch (IOException | RuntimeException e) {
			restore(written);
			throw new DshException("Failed to move the local plugin paths recorded in " + home + " to " + newDirectory,
			                       e);
		}

		if (!moved.isEmpty()) {
			LOG.info("Moved the recorded paths of " + String.join(", ", moved) + " into " + newDirectory);
		}
		return List.copyOf(moved);
	}

	private static List<Path> profilesIn(Path home) throws IOException {
		Path profiles = home.resolve("profiles");
		if (!Files.isDirectory(profiles)) {
			return List.of();
		}
		try (Stream<Path> entries = Files.list(profiles)) {
			return entries.filter(Files::isDirectory).sorted().toList();
		}
	}

	private static String rewritePaths(String text, Path from, Path to) {
		String moved = rewrite(text, from.toString(), to.toString());
		String escapedFrom = jsonEscaped(from.toString());
		if (!escapedFrom.equals(from.toString())) {
			moved = rewrite(moved, escapedFrom, jsonEscaped(to.toString()));
		}
		String slashedFrom = from.toString().replace('\\', '/');
		if (!slashedFrom.equals(from.toString())) {
			moved = rewrite(moved, slashedFrom, to.toString().replace('\\', '/'));
		}
		return moved;
	}

	private static String jsonEscaped(String path) {
		return path.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/**
	 * Replaces occurrences of a directory path in the given text when bounded by segment separators.
	 *
	 * @param text the text to rewrite
	 * @param from the old directory path
	 * @param to   the new directory path
	 * @return the rewritten text
	 */
	static String rewrite(String text, String from, String to) {
		if (from.isEmpty() || text.isEmpty()) {
			return text;
		}
		StringBuilder rewritten = new StringBuilder(text.length());
		int index = 0;
		while (true) {
			int at = text.indexOf(from, index);
			if (at < 0) {
				return rewritten.append(text, index, text.length()).toString();
			}
			int end = at + from.length();
			if (end == text.length() || isSegmentEnd(text.charAt(end))) {
				rewritten.append(text, index, at).append(to);
			} else {
				rewritten.append(text, index, end);
			}
			index = end;
		}
	}

	private static boolean isSegmentEnd(char character) {
		return character == '/' || character == '\\' || character == '"' || character == '\'' ||
		       Character.isWhitespace(character);
	}

	private static List<String> changedDependencies(String before, String after) {
		JsonObject was = dependenciesOf(before);
		JsonObject is = dependenciesOf(after);
		if (was == null || is == null) {
			return List.of();
		}
		List<String> changed = new ArrayList<>();
		for (var entry : was.entrySet()) {
			JsonElement now = is.get(entry.getKey());
			if (now == null || !now.equals(entry.getValue())) {
				changed.add(entry.getKey());
			}
		}
		return changed;
	}

	private static @Nullable JsonObject dependenciesOf(String manifest) {
		try {
			JsonElement parsed = JsonParser.parseString(manifest);
			if (!parsed.isJsonObject()) {
				return null;
			}
			JsonObject root = parsed.getAsJsonObject();
			JsonObject all = new JsonObject();
			for (String field : List.of("dependencies", "devDependencies", "optionalDependencies")) {
				JsonElement element = root.get(field);
				if (element != null && element.isJsonObject()) {
					for (var entry : element.getAsJsonObject().entrySet()) {
						all.add(entry.getKey(), entry.getValue());
					}
				}
			}
			return all;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void write(Path file, String text, @Nullable Set<PosixFilePermission> permissions)
	throws IOException {
		Path staging = file.resolveSibling(file.getFileName() + ".local-paths");
		try {
			Files.writeString(staging, text, StandardCharsets.UTF_8);
			if (permissions != null) {
				Files.setPosixFilePermissions(staging, permissions);
			}
			Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			deleteQuietly(staging);
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			LOG.warning("Failed to remove " + path, e);
		}
	}

	private static @Nullable Set<PosixFilePermission> permissionsOf(Path file) {
		try {
			return Files.getPosixFilePermissions(file);
		} catch (IOException | UnsupportedOperationException e) {
			return null;
		}
	}

	private static void restore(List<Record> written) {
		for (Record record : written) {
			try {
				write(record.file(), record.text(), record.permissions());
			} catch (IOException e) {
				LOG.warning("Failed to put " + record.file() + " back", e);
			}
		}
	}

	private record Record(Path file, String text, @Nullable Set<PosixFilePermission> permissions) {
	}
}
