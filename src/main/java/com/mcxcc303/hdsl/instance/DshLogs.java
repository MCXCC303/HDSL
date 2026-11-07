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

import com.mcxcc303.hdsl.core.DshPaths;
import com.mcxcc303.hdsl.core.DshPermissions;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Exports instance execution logs to stamped files in the launcher log directory.
 */
@NotNullByDefault
public final class DshLogs {
	private static final String PREFIX = "hdsl";
	private static final String SUFFIX = ".log";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");
	private static final int NAME_LIMIT = 64;
	private static final String FORBIDDEN = "/\\:*?\"<>|";

	private DshLogs() {
	}

	/**
	 * Writes log lines into the launcher's log directory stamped with the current time.
	 *
	 * @param instanceId the instance the lines came from, or {@code null}
	 * @param lines      the log lines to write
	 * @return the written file path
	 * @throws IOException when the directory or file cannot be written
	 */
	public static Path write(@Nullable String instanceId, List<String> lines) throws IOException {
		return write(DshPaths.LOGS, instanceId, lines, LocalDateTime.now());
	}

	/**
	 * Writes lines into a directory, stamped with the given timestamp.
	 *
	 * @param directory  target directory
	 * @param instanceId the instance the lines came from, or {@code null}
	 * @param lines      the log lines to write
	 * @param at         the timestamp to record in the file name
	 * @return the written file path
	 * @throws IOException when the directory or file cannot be written
	 */
	static Path write(Path directory, @Nullable String instanceId, List<String> lines, LocalDateTime at)
	throws IOException {
		DshPermissions.createRestrictedDirectory(directory, DshPermissions.SECRET_DIRECTORY);
		Path file = unused(directory.resolve(fileName(instanceId, at)));
		DshPermissions.createRestricted(file, DshPermissions.SECRET_FILE);
		Files.write(file, lines);
		return file;
	}

	/**
	 * Formats the log export file name.
	 *
	 * @param instanceId the instance id, or {@code null}
	 * @param at         the timestamp
	 * @return the generated file name
	 */
	static String fileName(@Nullable String instanceId, LocalDateTime at) {
		String instance = usableName(instanceId);
		String stamp = at.format(STAMP);
		return instance.isEmpty() ? PREFIX + "-" + stamp + SUFFIX : PREFIX + "-" + instance + "-" + stamp + SUFFIX;
	}

	private static Path unused(Path wanted) {
		if (!Files.exists(wanted)) {
			return wanted;
		}
		String name = wanted.getFileName().toString();
		String stem = name.substring(0, name.length() - SUFFIX.length());
		for (int i = 2; ; i++) {
			Path candidate = wanted.resolveSibling(stem + "-" + i + SUFFIX);
			if (!Files.exists(candidate)) {
				return candidate;
			}
		}
	}

	private static String usableName(@Nullable String instanceId) {
		String trimmed = instanceId == null ? "" : instanceId.trim();
		if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..")) {
			return "";
		}
		if (trimmed.length() > NAME_LIMIT) {
			trimmed = trimmed.substring(0, NAME_LIMIT);
		}

		StringBuilder safe = new StringBuilder(trimmed.length());
		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			safe.append(Character.isISOControl(c) || FORBIDDEN.indexOf(c) >= 0 ? '-' : c);
		}
		// A name that ends in a dot or a space is a name Windows quietly rewrites.
		while (safe.length() > 0 && (safe.charAt(safe.length() - 1) == '.' || safe.charAt(safe.length() - 1) == ' ')) {
			safe.setLength(safe.length() - 1);
		}
		return safe.toString().trim();
	}
}
