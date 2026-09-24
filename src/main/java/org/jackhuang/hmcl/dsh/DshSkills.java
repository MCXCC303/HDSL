/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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
package org.jackhuang.hmcl.dsh;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// Reads and edits the skill packs in a DSH home.
///
/// The harness's own provider scans {@code <dshHome>/skills} one level deep:
/// {@code <name>/SKILL.md} is a directory bundle and {@code <name>.md} is a flat skill,
/// while a nested {@code SKILL.md} and every other kind of entry are not skills at all.
/// Everything here works on that same one level, so the list the launcher shows is the
/// list the harness will offer.
///
/// A skill is switched off for the agent with the harness's own frontmatter key,
/// {@code disable-model-invocation: true}. Switching off writes that one line into the
/// frontmatter and switching on takes it away again; every other line of the file the
/// author wrote comes back out exactly as it went in.
@NotNullByDefault
public final class DshSkills {
    /// The directory under a DSH home the harness reads skills from.
    private static final String DIRECTORY = "skills";

    /// The file a directory bundle describes itself in.
    private static final String INSTRUCTION = "SKILL.md";

    /// The frontmatter key that hides a skill from the agent.
    private static final String DISABLE = "disable-model-invocation";

    /// The line that opens and closes a frontmatter block.
    private static final String DELIMITER = "---";

    /// The suffix a rewrite is staged under before it replaces the file.
    private static final String STAGING = ".hdsl-skills";

    private DshSkills() {
    }

    /// Returns the directory an instance's skills live in.
    ///
    /// @param home the instance's DSH_HOME
    /// @return the directory, which may not exist
    public static Path directory(Path home) {
        return home.resolve(DIRECTORY);
    }

    /// Lists the skill packs in a home, ordered by name.
    ///
    /// A missing directory is an empty list rather than an error: an instance that has
    /// never had a skill installed is the ordinary state, not a failure.
    ///
    /// @param home the instance's DSH_HOME
    /// @return the packs, each carrying its own frontmatter
    /// @throws DshException when the directory or one of the packs cannot be read
    public static @Unmodifiable List<DshSkill> list(Path home) throws DshException {
        Path directory = directory(home);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<DshSkill> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                DshSkill skill = read(entry);
                if (skill != null) {
                    skills.add(skill);
                }
            }
        } catch (IOException e) {
            throw new DshException("Failed to read " + directory, e);
        }
        skills.sort(Comparator.comparing(DshSkill::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(skills);
    }

    /// Reads one entry as a skill, or reports that it is not one.
    ///
    /// @param entry the entry directly under the skills directory
    /// @return the skill, or {@code null} when the entry is not a skill pack
    /// @throws DshException when the entry is a pack but cannot be read
    private static @Nullable DshSkill read(Path entry) throws DshException {
        String fileName = entry.getFileName().toString();
        // The harness skips the user root's {@code .system} child; anything else hidden is
        // not a skill either, and offering it would offer a row the harness ignores.
        if (fileName.startsWith(".")) {
            return null;
        }

        boolean bundle = Files.isDirectory(entry);
        Path instructions;
        if (bundle) {
            instructions = entry.resolve(INSTRUCTION);
            if (!Files.isRegularFile(instructions)) {
                return null;
            }
        } else if (Files.isRegularFile(entry) && fileName.endsWith(".md")) {
            instructions = entry;
        } else {
            return null;
        }

        Map<String, String> frontmatter = frontmatter(readLines(instructions));
        String name = frontmatter.get("name");
        if (name == null || name.isBlank()) {
            name = bundle ? fileName : fileName.substring(0, fileName.length() - ".md".length());
        }
        String description = collapse(frontmatter.getOrDefault("description", ""));
        boolean disabled = "true".equalsIgnoreCase(frontmatter.getOrDefault(DISABLE, "").trim());

        long size;
        long modified;
        try {
            size = bundle ? sizeOf(entry) : Files.size(instructions);
            modified = Files.getLastModifiedTime(instructions).toMillis();
        } catch (IOException e) {
            throw new DshException("Failed to measure " + entry, e);
        }

        return new DshSkill(entry, instructions, name, description, bundle, !disabled, size, modified);
    }

    /// Switches a skill off for the agent, or back on.
    ///
    /// Switching off writes {@code disable-model-invocation: true} into the frontmatter,
    /// adding the line when the author did not write one. Switching on takes the line
    /// away, which is the absence that means enabled. Nothing else in the file is touched.
    ///
    /// @param skill   the skill to switch
    /// @param enabled whether the agent should be able to invoke it
    /// @throws DshException when the file cannot be read or written, or has no frontmatter
    public static void setEnabled(DshSkill skill, boolean enabled) throws DshException {
        Path file = skill.instructions();
        List<String> lines = readLines(file);

        int[] block = frontmatterRange(lines);
        if (block == null) {
            throw new DshException(file + " has no frontmatter, so " + skill.name()
                    + " cannot be switched");
        }

        int existing = -1;
        for (int i = block[0] + 1; i < block[1]; i++) {
            if (DISABLE.equals(keyOf(lines.get(i)))) {
                existing = i;
                break;
            }
        }

        if (enabled) {
            if (existing < 0) {
                return;
            }
            lines.remove(existing);
        } else {
            if (existing >= 0) {
                lines.set(existing, DISABLE + ": true");
            } else {
                lines.add(block[1], DISABLE + ": true");
            }
        }
        write(file, lines);
    }

    /// Removes a skill pack from the home.
    ///
    /// @param skill the skill to remove
    /// @throws DshException when the entry cannot be deleted
    public static void remove(DshSkill skill) throws DshException {
        try {
            if (skill.bundle()) {
                deleteTree(skill.entry());
            } else {
                Files.deleteIfExists(skill.entry());
            }
        } catch (IOException e) {
            throw new DshException("Failed to remove " + skill.entry(), e);
        }
    }

    /// Copies a skill pack the user picked into the home.
    ///
    /// The pack is validated before it is copied: the harness requires a {@code name} and a
    /// {@code description} in the frontmatter and silently ignores a pack without them, so
    /// installing one would look like nothing happened. A name already taken is refused
    /// rather than overwritten — the pack that is there is the user's.
    ///
    /// @param home   the instance's DSH_HOME
    /// @param source the directory bundle or {@code .md} file to copy
    /// @return the installed skill
    /// @throws DshException when the source is not a pack, has no name or description, or
    ///                       cannot be copied
    public static DshSkill install(Path home, Path source) throws DshException {
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
            throw new DshException(source + " is not a skill pack: pick a " + INSTRUCTION
                    + " folder or a .md file");
        }

        Map<String, String> frontmatter = frontmatter(readLines(instructions));
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
                copyTree(source, target);
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

    // ---- frontmatter ---------------------------------------------------------------------------

    /// Reads the frontmatter of an instruction file.
    ///
    /// Only the subset the harness itself reads is understood: scalar keys, and the folded
    /// and literal block scalars a long description is written as.
    ///
    /// @param lines the file's lines
    /// @return the keys and their values
    private static Map<String, String> frontmatter(List<String> lines) {
        int[] block = frontmatterRange(lines);
        if (block == null) {
            return Map.of();
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (int i = block[0] + 1; i < block[1]; i++) {
            String line = lines.get(i);
            if (line.isBlank() || Character.isWhitespace(line.charAt(0))
                    || line.stripLeading().startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty() || value.startsWith(">") || value.startsWith("|")) {
                String separator = value.startsWith("|") ? "\n" : " ";
                StringBuilder folded = new StringBuilder();
                while (i + 1 < block[1] && !lines.get(i + 1).isBlank()
                        && Character.isWhitespace(lines.get(i + 1).charAt(0))) {
                    if (folded.length() > 0) {
                        folded.append(separator);
                    }
                    folded.append(lines.get(++i).trim());
                }
                values.put(key, folded.toString());
            } else {
                values.put(key, unquote(value));
            }
        }
        return Map.copyOf(values);
    }

    /// Returns where the frontmatter block is, when there is one.
    ///
    /// @param lines the file's lines
    /// @return the opening line's index and the closing line's, or {@code null}
    private static int @Nullable [] frontmatterRange(List<String> lines) {
        int open = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                open = i;
                break;
            }
        }
        if (open < 0 || !DELIMITER.equals(lines.get(open).trim())) {
            return null;
        }
        for (int i = open + 1; i < lines.size(); i++) {
            if (DELIMITER.equals(lines.get(i).trim())) {
                return new int[]{open, i};
            }
        }
        return null;
    }

    /// Returns the key a frontmatter line declares, or an empty string.
    ///
    /// @param line one frontmatter line
    /// @return the text before the colon
    private static String keyOf(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(0, colon).trim();
    }

    /// Strips the quotes a YAML string may be wrapped in.
    ///
    /// @param value the raw scalar
    /// @return the scalar without its wrapping quotes
    private static String unquote(String value) {
        char quote = value.isEmpty() ? '\0' : value.charAt(0);
        if (value.length() >= 2 && (quote == '"' || quote == '\'')
                && value.charAt(value.length() - 1) == quote) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /// Collapses every run of whitespace into one space.
    ///
    /// A description is written across lines as often as not, and the row that shows it
    /// has one line to show it in.
    ///
    /// @param text the raw text
    /// @return the text on one line
    private static String collapse(String text) {
        StringBuilder collapsed = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isWhitespace(character)) {
                pendingSpace = collapsed.length() > 0;
                continue;
            }
            if (pendingSpace) {
                collapsed.append(' ');
                pendingSpace = false;
            }
            collapsed.append(character);
        }
        return collapsed.toString();
    }

    // ---- files ---------------------------------------------------------------------------------

    /// Reads a file as lines.
    ///
    /// @param file the file
    /// @return its lines, without terminators
    /// @throws DshException when it cannot be read
    private static List<String> readLines(Path file) throws DshException {
        try {
            return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DshException("Failed to read " + file, e);
        }
    }

    /// Replaces a file with the given lines.
    ///
    /// Written beside the file and moved onto it, so a reader never sees a half-written
    /// frontmatter: the harness watches these directories and reads them the moment they
    /// change.
    ///
    /// @param file  the file
    /// @param lines what it should say
    /// @throws DshException when it cannot be written
    private static void write(Path file, List<String> lines) throws DshException {
        Path staging = file.resolveSibling(file.getFileName() + STAGING);
        try {
            Files.writeString(staging, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new DshException("Failed to write " + file, e);
        }
    }

    /// Sums the bytes of a bundle's files.
    ///
    /// @param directory the bundle directory
    /// @return its size in bytes
    /// @throws IOException when it cannot be walked
    private static long sizeOf(Path directory) throws IOException {
        long total = 0L;
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.toList()) {
                if (Files.isRegularFile(path)) {
                    total += Files.size(path);
                }
            }
        }
        return total;
    }

    /// Copies a directory tree.
    ///
    /// @param source the directory to copy
    /// @param target where it should land
    /// @throws IOException when it cannot be copied
    private static void copyTree(Path source, Path target) throws IOException {
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

    /// Deletes a directory and everything under it.
    ///
    /// @param directory the directory
    /// @throws IOException when something cannot be deleted
    private static void deleteTree(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
