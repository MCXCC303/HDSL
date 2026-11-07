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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Manages extraction and merging of plugin configuration in {@code settings.yaml} for instance packs.
 */
@NotNullByDefault
public final class DshPluginSettings {
	/** The name the file travels under inside a pack. */
	public static final String ENTRY = "settings.yaml";
	private static final String FILE = "settings.yaml";
	private static final Set<String> RESERVED = Set.of("agent-default-model", "llm-pi-ai", "ui-onboarding");

	private static final Pattern SECRET_KEY = Pattern.compile(
			"(?i).*(token|secret|password|passwd|api[-_]?key|apikey|credential|private[-_]?key|" +
			"access[-_]?key|client[-_]?secret|auth[-_]?key|bearer).*");

	private static final Pattern REFERENCE_KEY = Pattern.compile(
			"(?i).*(ref|refs|name|env|id|path|file|store|provider)$");

	private DshPluginSettings() {
	}

	/**
	 * Returns the sections of a home's settings that a pack can carry.
	 *
	 * @param home the {@code DSH_HOME}
	 * @return the section names, in the file's order
	 * @throws DshException when the file cannot be read
	 */
	public static List<String> sectionsOf(Path home) throws DshException {
		List<String> names = new ArrayList<>();
		for (Section section : split(read(home)).sections()) {
			if (!RESERVED.contains(section.name())) {
				names.add(section.name());
			}
		}
		return List.copyOf(names);
	}

	/**
	 * Builds the settings a pack carries.
	 *
	 * @param home   the home to read
	 * @param wanted the sections to carry; a name that is not there is skipped
	 * @return what travels, with the credential-looking values left out
	 * @throws DshException when the file cannot be read
	 */
	public static Carried extract(Path home, Set<String> wanted) throws DshException {
		Parsed parsed = split(read(home));
		StringBuilder text = new StringBuilder();
		List<String> sections = new ArrayList<>();
		List<String> omitted = new ArrayList<>();
		for (Section section : parsed.sections()) {
			if (!wanted.contains(section.name()) || RESERVED.contains(section.name())) {
				continue;
			}
			sections.add(section.name());
			text.append("# ").append(section.name()).append('\n');
			text.append(parsed.lines().get(section.header())).append('\n');
			// Scan section keys at shallowest indentation
			int bodyIndent = -1;
			boolean scalar = false;
			for (String line : parsed.lines().subList(section.header() + 1, section.end())) {
				if (!line.isBlank()) {
					int indent = indentOf(line);
					if (scalar && indent <= bodyIndent) {
						scalar = false;
					} else if (scalar) {
						text.append(line).append('\n');
						continue;
					}
					if (bodyIndent < 0) {
						bodyIndent = indent;
					}
					if (indent == bodyIndent && opensBlockScalar(line)) {
						scalar = true;
						text.append(line).append('\n');
						continue;
					}
					if (indent == bodyIndent && isCredential(line)) {
						omitted.add(section.name() + "." + keyOf(line));
						continue;
					}
				}
				text.append(line).append('\n');
			}
		}
		return new Carried(text.toString(), List.copyOf(sections), List.copyOf(omitted));
	}

	/**
	 * Merges a pack's settings into a home's own file.
	 *
	 * @param home    the {@code DSH_HOME}
	 * @param carried what the pack holds
	 * @return the section names that were merged
	 * @throws DshException when the file cannot be read or written
	 */
	public static List<String> merge(Path home, String carried) throws DshException {
		Parsed incoming = split(carried);
		if (incoming.sections().isEmpty()) {
			return List.of();
		}

		String existing = read(home);
		Parsed current = split(existing);
		List<String> lines = new ArrayList<>(current.lines());
		List<Section> replaced = new ArrayList<>();
		List<Section> appended = new ArrayList<>();
		List<String> merged = new ArrayList<>();

		for (Section section : incoming.sections()) {
			if (RESERVED.contains(section.name())) {
				LOG.warning("A pack carries the settings section " + section.name() +
				            ", which this launcher does not carry");
				continue;
			}
			merged.add(section.name());
			int index = current.indexOf(section.name());
			if (index < 0) {
				appended.add(section);
			} else {
				replaced.add(current.sections().get(index));
			}
		}

		// Replace from back to front to preserve line indices
		List<Section> ordered = new ArrayList<>(replaced);
		ordered.sort((left, right) -> Integer.compare(right.start(), left.start()));
		for (Section mine : ordered) {
			Section section = incoming.section(mine.name());
			if (section == null) {
				continue;
			}
			List<String> block = new ArrayList<>(mine.preamble(current.lines()));
			block.add(current.lines().get(mine.header()));
			block.addAll(mergedBody(mine.body(current.lines()), section.body(incoming.lines())));
			lines.subList(mine.start(), mine.end()).clear();
			lines.addAll(mine.start(), block);
		}

		for (Section section : appended) {
			if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
				lines.add("");
			}
			lines.addAll(section.lines(incoming.lines()));
		}

		while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
			lines.remove(lines.size() - 1);
		}
		write(home.resolve(FILE), lines.isEmpty() ? "" : String.join("\n", lines) + "\n");
		return List.copyOf(merged);
	}

	/**
	 * Returns the sections a carried text holds.
	 *
	 * @param carried the carried text
	 * @return the section names
	 */
	public static List<String> sectionsIn(String carried) {
		Set<String> names = new LinkedHashSet<>();
		for (Section section : split(carried).sections()) {
			names.add(section.name());
		}
		return List.copyOf(names);
	}

	private static List<String> mergedBody(List<String> home, List<String> packed) {
		int indent = bodyIndentOf(packed) < 0 ? bodyIndentOf(home) : bodyIndentOf(packed);
		if (indent < 0) {
			return List.copyOf(packed);
		}
		Map<String, List<String>> mine = entries(home, indent);
		Map<String, List<String>> theirs = entries(packed, indent);

		List<String> merged = new ArrayList<>(headOf(home, indent));
		for (Map.Entry<String, List<String>> entry : mine.entrySet()) {
			merged.addAll(theirs.getOrDefault(entry.getKey(), entry.getValue()));
		}
		for (Map.Entry<String, List<String>> entry : theirs.entrySet()) {
			if (!mine.containsKey(entry.getKey())) {
				merged.addAll(entry.getValue());
			}
		}
		return merged;
	}

	private static Map<String, List<String>> entries(List<String> body, int indent) {
		Map<String, List<String>> entries = new LinkedHashMap<>();
		String key = null;
		List<String> block = new ArrayList<>();
		for (String line : body) {
			String declared = !line.isBlank() && indentOf(line) == indent && keyOf(line.trim()) != null
			                  ? keyOf(line.trim())
			                  : null;
			if (declared != null) {
				if (key != null) {
					entries.put(key, List.copyOf(block));
				}
				key = declared;
				block = new ArrayList<>();
			}
			if (key != null) {
				block.add(line);
			}
		}
		if (key != null) {
			entries.put(key, List.copyOf(block));
		}
		return entries;
	}

	private static List<String> headOf(List<String> body, int indent) {
		List<String> head = new ArrayList<>();
		for (String line : body) {
			if (!line.isBlank() && indentOf(line) == indent && keyOf(line.trim()) != null) {
				break;
			}
			head.add(line);
		}
		return head;
	}

	private static int bodyIndentOf(List<String> body) {
		for (String line : body) {
			if (!line.isBlank() && keyOf(line.trim()) != null && (indentOf(line) > 0 || !line.startsWith("#"))) {
				return indentOf(line);
			}
		}
		return -1;
	}

	private static String read(Path home) throws DshException {
		Path file = home.resolve(FILE);
		if (!Files.isRegularFile(file)) {
			return "";
		}
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new DshException("Failed to read " + file, e);
		}
	}

	private static void write(Path file, String text) throws DshException {
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path staging = file.resolveSibling(FILE + ".pack");
			Files.writeString(staging, text, StandardCharsets.UTF_8);
			Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new DshException("Failed to write " + file, e);
		}
	}

	private static boolean isCredential(String line) {
		String key = keyOf(line.trim());
		if (key == null || !SECRET_KEY.matcher(key).matches()) {
			return false;
		}
		// Skip references to credentials
		if (REFERENCE_KEY.matcher(key).matches()) {
			return false;
		}
		String value = valueOf(line);
		if (value.isEmpty()) {
			return false;
		}
		// Skip environment variables and interpolations
		return !value.startsWith("$") && !value.startsWith("env:");
	}

	/**
	 * Extracts the key from a YAML {@code key: value} line.
	 *
	 * @param line the line text
	 * @return the key, or {@code null} if not a key-value line
	 */
	static @Nullable String keyOf(String line) {
		int colon = line.indexOf(':');
		if (colon <= 0) {
			return null;
		}
		String key = line.substring(0, colon).trim();
		return key.isEmpty() || key.indexOf(' ') >= 0 || key.indexOf('\t') >= 0 ? null : key;
	}

	private static String valueOf(String line) {
		String trimmed = line.trim();
		int colon = trimmed.indexOf(':');
		return colon < 0 ? "" : trimmed.substring(colon + 1).trim();
	}

	private static int indentOf(String line) {
		int i = 0;
		while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
			i++;
		}
		return i;
	}

	private static boolean opensBlockScalar(String line) {
		String value = valueOf(line);
		return value.equals("|") || value.equals("|-") || value.equals("|+") || value.equals(">") ||
		       value.equals(">-") || value.equals(">+");
	}

	private static Parsed split(String text) {
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}

		List<Integer> headers = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			String key = declares(lines.get(i)) ? keyOf(lines.get(i)) : null;
			if (key != null) {
				headers.add(i);
				names.add(key);
			}
		}

		List<Section> sections = new ArrayList<>();
		int previousEnd = 0;
		for (int k = 0; k < headers.size(); k++) {
			int header = headers.get(k);
			int next = k + 1 < headers.size() ? headers.get(k + 1) : lines.size();
			int end = header + 1;
			for (int i = next - 1; i > header; i--) {
				if (!introduced(lines.get(i))) {
					end = i + 1;
					break;
				}
			}
			int start = header;
			while (start > previousEnd && introduced(lines.get(start - 1))) {
				start--;
			}
			sections.add(new Section(names.get(k), start, header, end));
			previousEnd = end;
		}
		return new Parsed(List.copyOf(sections), List.copyOf(lines));
	}

	private static boolean declares(String line) {
		return !line.isBlank() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#") &&
		       keyOf(line) != null;
	}

	private static boolean introduced(String line) {
		return line.isBlank() || line.startsWith("#");
	}

	/**
	 * Extracted plugin settings payload for inclusion in a pack.
	 *
	 * @param text     the settings YAML content
	 * @param sections the section names included
	 * @param omitted  the keys omitted as sensitive credentials
	 */
	public record Carried(String text, List<String> sections, List<String> omitted) {
	}

	private record Parsed(List<Section> sections, List<String> lines) {

		int indexOf(String name) {
			for (int i = 0; i < sections.size(); i++) {
				if (sections.get(i).name().equals(name)) {
					return i;
				}
			}
			return -1;
		}

		@Nullable Section section(String name) {
			int index = indexOf(name);
			return index < 0 ? null : sections.get(index);
		}
	}

	private record Section(String name, int start, int header, int end) {

		List<String> preamble(List<String> file) {
			return List.copyOf(file.subList(start, header));
		}

		List<String> lines(List<String> file) {
			return List.copyOf(file.subList(header, end));
		}

		List<String> body(List<String> file) {
			return List.copyOf(file.subList(header + 1, end));
		}
	}
}
