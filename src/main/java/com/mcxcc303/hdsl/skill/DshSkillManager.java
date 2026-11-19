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

import com.mcxcc303.hdsl.core.DshDiskUsage;
import com.mcxcc303.hdsl.core.DshException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/*
 * Reads and edits the skill packs in a DSH home.
 */
@NotNullByDefault
public final class DshSkillManager {
	private static final String DIRECTORY = "skills";
	public static final String PREFIX = DIRECTORY + "/";
	static final String INSTRUCTION = "SKILL.md";
	private static final String DISABLE = "disable-model-invocation";
	private static final String SYSTEM = ".system";

	private DshSkillManager() {
	}

	public static Path directory(Path home) {
		return home.resolve(DIRECTORY);
	}

	public static @Unmodifiable List<DshSkill> packable(Path home, Set<String> wanted) throws DshException {
		if (wanted.isEmpty()) return List.of();

		List<DshSkill> chosen = new ArrayList<>();
		for (DshSkill skill : list(home)) {
			if (SYSTEM.equals(skill.fileName())) continue;
			if (wanted.contains(skill.name()) || wanted.contains(skill.fileName())) chosen.add(skill);
		}
		return List.copyOf(chosen);
	}

	public static @Unmodifiable List<String> packableNames(Path home) throws DshException {
		List<String> names = new ArrayList<>();
		for (DshSkill skill : list(home)) {
			if (!SYSTEM.equals(skill.fileName())) names.add(skill.fileName());
		}
		return List.copyOf(names);
	}

	public static int writeInto(ZipOutputStream zip, List<DshSkill> skills, @Nullable Consumer<String> onStage)
	throws DshException, IOException {
		int files = 0;
		for (DshSkill skill : skills) {
			if (!skill.bundle()) {
				copyInto(zip, skill.instructions(), PREFIX + skill.fileName());
				files++;
				continue;
			}

			List<Path> tree;
			try (Stream<Path> walk = Files.walk(skill.entry())) {
				tree = walk.filter(Files::isRegularFile).toList();
			} catch (IOException e) {
				throw new DshException("Failed to read " + skill.entry(), e);
			}

			for (Path file : tree) {
				String relative = skill.entry().relativize(file).toString().replace(File.separatorChar, '/');
				copyInto(zip, file, PREFIX + skill.fileName() + "/" + relative);
				files++;
			}
		}
		if (!skills.isEmpty()) {
			report(onStage, "Wrote " + skills.size() + " skill pack(s), " + files + " file(s)");
		}
		return files;
	}

	public static int restoreInto(Path archive, Path home, @Nullable Consumer<String> onStage) throws DshException {
		Path directory = directory(home);
		int files = 0;
		Set<String> packs = new TreeSet<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.isDirectory()) continue;

				String name = entry.getName().replace('\\', '/');
				if (!name.startsWith(PREFIX)) continue;

				String relative = name.substring(PREFIX.length());
				if (relative.isEmpty() || relative.equals(SYSTEM) || relative.startsWith(SYSTEM + "/")) continue;

				if (relative.startsWith("/") || relative.contains("..")) continue;

				Path target = directory.resolve(relative);
				Files.createDirectories(target.getParent());
				Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
				packs.add(relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : relative);
				files++;
			}
		} catch (IOException e) {
			throw new DshException("Failed to read " + archive, e);
		}

		if (files > 0) {
			report(onStage, "Restored " + packs.size() + " skill pack(s), " + files + " file(s)");
		}
		return files;
	}

	private static void copyInto(ZipOutputStream zip, Path file, String name) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		Files.copy(file, zip);
		zip.closeEntry();
	}

	private static void report(@Nullable Consumer<String> onStage, String line) {
		if (onStage != null) onStage.accept(line);
	}

	public static @Unmodifiable List<DshSkill> list(Path home) throws DshException {
		Path directory = directory(home);
		if (!Files.isDirectory(directory)) return List.of();

		List<DshSkill> skills = new ArrayList<>();
		try (Stream<Path> entries = Files.list(directory)) {
			for (Path entry : entries.toList()) {
				DshSkill skill = read(entry);
				if (skill != null) skills.add(skill);
			}
		} catch (IOException e) {
			throw new DshException("Failed to read " + directory, e);
		}

		skills.sort(Comparator.comparing(DshSkill::name, String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(skills);
	}

	private static @Nullable DshSkill read(Path entry) throws DshException {
		String fileName = entry.getFileName().toString();
		if (fileName.startsWith(".")) return null;

		boolean bundle = Files.isDirectory(entry);
		Path instructions;
		if (bundle) {
			instructions = entry.resolve(INSTRUCTION);
			if (!Files.isRegularFile(instructions)) return null;
		} else if (Files.isRegularFile(entry) && fileName.endsWith(".md")) {
			instructions = entry;
		} else return null;

		try {
			Map<String, String> frontmatter = DshSkillFrontmatter.parse(DshSkillFiles.readLines(instructions));

			String name = frontmatter.get("name");
			if (name == null || name.isBlank()) {
				name = bundle ? fileName : fileName.substring(0, fileName.length() - ".md".length());
			}
			String description = DshSkillFrontmatter.collapse(frontmatter.getOrDefault("description", ""));
			boolean disabled = "true".equalsIgnoreCase(frontmatter.getOrDefault(DISABLE, "").trim());

			long size = bundle ? DshDiskUsage.sizeBytes(entry) : Files.size(instructions);
			long modified = Files.getLastModifiedTime(instructions).toMillis();
			return new DshSkill(entry, instructions, name, description, bundle, !disabled, size, modified);
		} catch (IOException e) {
			throw new DshException("Failed to read " + entry, e);
		}
	}

	public static void setEnabled(DshSkill skill, boolean enabled) throws DshException {
		Path file = skill.instructions();
		try {
			List<String> lines = DshSkillFiles.readLines(file);
			if (DshSkillFrontmatter.rangeOf(lines) == null) {
				throw new DshException(file + " has no frontmatter, so " + skill.name() + " cannot be switched");
			}

			DshSkillFiles.writeLines(file,
			                         enabled
			                      ? DshSkillFrontmatter.without(lines, DISABLE)
			                      : DshSkillFrontmatter.with(lines, DISABLE, "true"));
		} catch (IOException e) {
			throw new DshException("Failed to write " + file, e);
		}
	}

	public static void remove(DshSkill skill) throws DshException {
		try {
			if (skill.bundle()) {
				DshSkillFiles.deleteTree(skill.entry());
			} else {
				Files.deleteIfExists(skill.entry());
			}
		} catch (IOException e) {
			throw new DshException("Failed to remove " + skill.entry(), e);
		}
	}

	public static DshSkill install(Path home, Path source) throws DshException {
		if (Files.isRegularFile(source) && source.getFileName().toString().endsWith(".zip")) {
			return installArchive(home, source);
		}

		Path fileName = source.getFileName();
		boolean bundle = Files.isDirectory(source);
		Path instructions;
		if (bundle) {
			instructions = source.resolve(INSTRUCTION);
			if (!Files.isRegularFile(instructions)) {
				throw new DshException(source + " is not a skill pack: it holds no " + INSTRUCTION);
			}
		} else if (Files.isRegularFile(source) && fileName.toString().endsWith(".md")) {
			instructions = source;
		} else {
			throw new DshException(source + " is not a skill pack: pick a " + INSTRUCTION + " folder or a .md file");
		}

		Map<String, String> frontmatter;
		try {
			frontmatter = DshSkillFrontmatter.parse(DshSkillFiles.readLines(instructions));
		} catch (IOException e) {
			throw new DshException("Failed to read " + instructions, e);
		}

		String name = frontmatter.get("name");
		if (name == null || name.isBlank()) {
			throw new DshException(instructions + " names no skill: its frontmatter has no name");
		}

		String description = frontmatter.get("description");
		if (description == null || description.isBlank()) {
			throw new DshException(instructions + " describes no skill: it has no description");
		}

		Path directory = directory(home);
		Path target = directory.resolve(fileName.toString());
		if (Files.exists(target)) {
			throw new DshException("A skill pack called " + fileName + " is already installed");
		}

		try {
			Files.createDirectories(directory);
			if (bundle) {
				DshSkillFiles.copyTree(source, target);
			} else {
				Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
			}
		} catch (IOException e) {
			throw new DshException("Failed to install " + source, e);
		}

		DshSkill installed = read(target);
		if (installed == null) {
			throw new DshException("The skill pack " + target + " was copied but is not readable");
		}
		return installed;
	}

	private static DshSkill installArchive(Path home, Path source) throws DshException {
		Path staging;
		try {
			staging = Files.createTempDirectory("hdsl-skill-zip-");
		} catch (IOException e) {
			throw new DshException("Failed to make somewhere to unpack " + source, e);
		}

		try {
			DshSkillFiles.unzip(source, staging);
			Path pack = DshSkillFiles.shallowest(staging, INSTRUCTION);
			if (pack == null) {
				throw new DshException(source + " holds no skill pack: no " + INSTRUCTION + " in it");
			}
			return install(home, pack);
		} catch (IOException e) {
			throw new DshException("Failed to unpack " + source, e);
		} finally {
			try {
				DshSkillFiles.deleteTree(staging);
			} catch (IOException ignored) {
			}
		}
	}
}
