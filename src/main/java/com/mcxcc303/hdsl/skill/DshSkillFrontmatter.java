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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * The frontmatter block of a SKILL.md, read and written as lines
 *
 * Only the subset the harness itself reads is understood: scalar keys, and the folded and literal block scalars a long description is written as
 */
@NotNullByDefault
final class DshSkillFrontmatter {
	private static final String DELIMITER = "---";

	private DshSkillFrontmatter() {
	}

	/**
	 * Reads the keys and values of a block, empty when the file has none.
	 */
	static Map<String, String> parse(List<String> lines) {
		int[] block = rangeOf(lines);
		if (block == null) return Map.of();

		Map<String, String> values = new LinkedHashMap<>();
		for (int i = block[0] + 1; i < block[1]; i++) {
			String line = lines.get(i);
			if (line.isBlank() || Character.isWhitespace(line.charAt(0)) || line.stripLeading().startsWith("#")) {
				continue;
			}

			int colon = line.indexOf(':');
			if (colon < 0) continue;

			String key = line.substring(0, colon).trim();
			String value = line.substring(colon + 1).trim();
			if (value.isEmpty() || value.startsWith(">") || value.startsWith("|")) {
				String separator = value.startsWith("|") ? "\n" : " ";
				StringBuilder folded = new StringBuilder();

				while (i + 1 < block[1] && !lines.get(i + 1).isBlank() &&
				       Character.isWhitespace(lines.get(i + 1).charAt(0))) {
					if (!folded.isEmpty()) folded.append(separator);
					folded.append(lines.get(++i).trim());
				}
				values.put(key, folded.toString());
			} else values.put(key, unquote(value));
		}
		return Map.copyOf(values);
	}

	/**
	 * Returns the opening and closing line of the block, or {@code null} when there is none.
	 */
	static int @Nullable [] rangeOf(List<String> lines) {
		int open = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (!lines.get(i).isBlank()) {
				open = i;
				break;
			}
		}
		if (open < 0 || !DELIMITER.equals(lines.get(open).trim())) return null;

		for (int i = open + 1; i < lines.size(); i++) {
			if (DELIMITER.equals(lines.get(i).trim())) return new int[]{open, i};
		}
		return null;
	}

	/**
	 * Returns the key a line declares, or an empty string.
	 */
	static String keyOf(String line) {
		int colon = line.indexOf(':');
		return colon < 0 ? "" : line.substring(0, colon).trim();
	}

	/**
	 * Sets one key, adding it to the block when it is not there yet, and returns the lines to write.
	 * <p>
	 * The lines come back unchanged when there is no block to write into.
	 */
	static List<String> with(List<String> lines, String key, String value) {
		int[] block = rangeOf(lines);
		if (block == null) return lines;

		for (int i = block[0] + 1; i < block[1]; i++) {
			if (key.equals(keyOf(lines.get(i)))) {
				lines.set(i, key + ": " + value);
				return lines;
			}
		}
		lines.add(block[1], key + ": " + value);
		return lines;
	}

	/**
	 * Removes one key, when it is there, and returns the lines to write.
	 * <p>
	 * The lines come back unchanged when the key was not there.
	 */
	static List<String> without(List<String> lines, String key) {
		for (int i = 0; i < lines.size(); i++) {
			if (key.equals(keyOf(lines.get(i)))) {
				lines.remove(i);
				return lines;
			}
		}
		return lines;
	}

	private static String unquote(String value) {
		char quote = value.isEmpty() ? '\0' : value.charAt(0);
		if (value.length() >= 2 && (quote == '"' || quote == '\'') && value.charAt(value.length() - 1) == quote) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	/**
	 * Collapses every run of whitespace into one space.
	 */
	static String collapse(String text) {
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
}
