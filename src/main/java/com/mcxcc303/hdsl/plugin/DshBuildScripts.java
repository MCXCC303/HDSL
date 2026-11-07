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

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Inspects and configures build script approvals in {@code pnpm-workspace.yaml}.
 */
@NotNullByDefault
public final class DshBuildScripts {
	private static final String PLACEHOLDER = "set this to true or false";

	private DshBuildScripts() {
	}

	/**
	 * Reads the pending build script entries from the instance's profile.
	 *
	 * @param instance the instance
	 * @return the entries in order
	 * @throws DshException when the profile settings cannot be read
	 */
	public static List<Pending> pending(DshInstance instance) throws DshException {
		Path workspace = workspaceFile(instance);
		if (!Files.isRegularFile(workspace)) {
			return List.of();
		}

		List<Pending> entries = new ArrayList<>();
		boolean inSection = false;
		try {
			for (String line : Files.readAllLines(workspace, StandardCharsets.UTF_8)) {
				String trimmed = line.trim();
				if (trimmed.startsWith("allowBuilds:")) {
					inSection = true;
					continue;
				}
				if (inSection) {
					if (!line.startsWith(" ") && !line.startsWith("\t") && !trimmed.isEmpty()) {
						// Another top-level key ends the section.
						break;
					}
					Entry entry = entryOf(line);
					if (entry == null) {
						continue;
					}
					entries.add(new Pending(entry.name(), answerOf(entry.value())));
				}
			}
		} catch (IOException e) {
			throw new DshException("Failed to read " + workspace, e);
		}
		return List.copyOf(entries);
	}

	/**
	 * Updates the build script permission for the given packages.
	 *
	 * @param instance the instance
	 * @param packages the packages to answer about
	 * @param allowed  whether their install scripts may run
	 * @return how many entries were updated
	 * @throws DshException when the profile settings cannot be read or written
	 */
	public static int answer(DshInstance instance, List<String> packages, boolean allowed) throws DshException {
		Path workspace = workspaceFile(instance);
		if (!Files.isRegularFile(workspace)) {
			throw new DshException("The instance has no profile settings at " + workspace);
		}

		Set<String> wanted = new LinkedHashSet<>(packages);
		List<String> lines;
		try {
			lines = new ArrayList<>(Files.readAllLines(workspace, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new DshException("Failed to read " + workspace, e);
		}

		int written = 0;
		boolean inSection = false;
		boolean sectionSeen = false;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			String trimmed = line.trim();

			if (trimmed.startsWith("allowBuilds:")) {
				inSection = true;
				sectionSeen = true;
				continue;
			}
			if (!inSection) {
				continue;
			}
			if (!line.startsWith(" ") && !line.startsWith("\t") && !trimmed.isEmpty()) {
				inSection = false;
				continue;
			}

			Entry entry = entryOf(line);
			if (entry == null || !wanted.contains(entry.name())) {
				continue;
			}
			lines.set(i, line.substring(0, line.indexOf(trimmed)) + entry.name() + ": " + allowed);
			written++;
		}

		if (written == 0 && !sectionSeen && !wanted.isEmpty()) {
			return 0;
		}

		writeAtomically(workspace, String.join("\n", lines) + "\n");
		LOG.info("Answered about " + written + " build script(s) in " + workspace + ": " + allowed);
		return written;
	}

	/**
	 * Returns the names of packages still waiting for an answer.
	 *
	 * @param instance the instance
	 * @return the package names
	 * @throws DshException when the profile cannot be read
	 */
	public static List<String> unanswered(DshInstance instance) throws DshException {
		return pending(instance).stream().filter(entry -> entry.allowed() == null).map(Pending::name).toList();
	}

	/**
	 * Returns the names of packages whose install scripts are disallowed.
	 *
	 * @param instance the instance
	 * @return the package names
	 * @throws DshException when the profile cannot be read
	 */
	public static List<String> refused(DshInstance instance) throws DshException {
		return pending(instance).stream()
		                        .filter(entry -> Boolean.FALSE.equals(entry.allowed()))
		                        .map(Pending::name)
		                        .toList();
	}

	/**
	 * Proposes pending approval entries for packages needing build scripts.
	 *
	 * @param instance the instance
	 * @param keys     the entry keys to propose
	 * @return how many entries were added
	 * @throws DshException when the profile settings cannot be read or written
	 */
	public static int propose(DshInstance instance, List<String> keys) throws DshException {
		Path workspace = workspaceFile(instance);
		if (keys.isEmpty() || !Files.isRegularFile(workspace)) {
			return 0;
		}

		Set<String> known = new LinkedHashSet<>();
		for (Pending entry : pending(instance)) {
			known.add(entry.name());
		}
		List<String> added = new ArrayList<>();
		for (String key : keys) {
			if (known.add(key)) {
				added.add(key);
			}
		}
		if (added.isEmpty()) {
			return 0;
		}

		List<String> lines;
		try {
			lines = new ArrayList<>(Files.readAllLines(workspace, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new DshException("Failed to read " + workspace, e);
		}

		// Insert after the allowBuilds section or append at end of file
		int insertAt = -1;
		boolean inSection = false;
		for (int i = 0; i < lines.size(); i++) {
			String trimmed = lines.get(i).trim();
			if (!inSection) {
				if (trimmed.startsWith("allowBuilds:")) {
					inSection = true;
					insertAt = i + 1;
				}
				continue;
			}
			boolean indented = lines.get(i).startsWith(" ") || lines.get(i).startsWith("\t");
			if (!indented && !trimmed.isEmpty()) {
				break;
			}
			insertAt = i + 1;
		}

		List<String> written = new ArrayList<>();
		for (String key : added) {
			written.add("  " + key + ": " + PLACEHOLDER);
		}
		if (insertAt >= 0) {
			lines.addAll(insertAt, written);
		} else {
			if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
				lines.add("");
			}
			lines.add("allowBuilds:");
			lines.addAll(written);
		}

		writeAtomically(workspace, String.join("\n", lines) + "\n");
		LOG.info("Recorded " + added.size() + " build script question(s) in " + workspace);
		return added.size();
	}

	private static @Nullable Entry entryOf(String line) {
		String trimmed = line.trim();
		int colon = trimmed.lastIndexOf(": ");
		if (colon <= 0) {
			return null;
		}
		String name = trimmed.substring(0, colon).trim();
		return name.isEmpty() ? null : new Entry(name, trimmed.substring(colon + 2).trim());
	}

	private static Path workspaceFile(DshInstance instance) throws DshException {
		return instance.homeDirectory().resolve("profiles").resolve(instance.profile()).resolve("pnpm-workspace.yaml");
	}

	private static @Nullable Boolean answerOf(String value) {
		String clean = value.replace("\"", "").replace("'", "").trim();
		if (clean.equalsIgnoreCase("true")) {
			return Boolean.TRUE;
		}
		if (clean.equalsIgnoreCase("false")) {
			return Boolean.FALSE;
		}
		if (clean.contains(PLACEHOLDER)) {
			return null;
		}
		return null;
	}

	private static void writeAtomically(Path file, String body) throws DshException {
		Path staging = file.resolveSibling(file.getFileName() + ".hdsl");
		try {
			Files.writeString(staging, body, StandardCharsets.UTF_8);
			Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new DshException("Failed to write " + file, e);
		}
	}

	/**
	 * A package waiting to be answered about.
	 *
	 * @param name    the package name or key
	 * @param allowed whether it is approved, refused, or {@code null} if pending
	 */
	public record Pending(String name, @Nullable Boolean allowed) {
	}

	private record Entry(String name, String value) {
	}
}
