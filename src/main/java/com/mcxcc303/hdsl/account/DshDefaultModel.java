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
package com.mcxcc303.hdsl.account;

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.YamlScalar;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates the agent default model and provider configuration in an instance's {@code settings.yaml}.
 *
 * <p>A route in the profile's patch layer says what the harness is offered; it does not make the
 * harness <em>use</em> it. Which supplier and model to start on is recorded in the
 * {@code agent-default-model} section in {@code settings.yaml}.
 *
 * <p><strong>Only targeted lines are rewritten.</strong> While the harness runs, it also writes this file
 * under a cross-process lock with a leaf-level diff preserving formatting and comments. Restoring a copy
 * of the file would silently discard user changes made via the interface during the run and could race
 * the harness's atomic writes. This class performs minimal line-level edits to the two scalar values
 * under the section's indentation, preserving trailing comments and formatting.
 */
@NotNullByDefault
public final class DshDefaultModel {
	private static final String FILE = "settings.yaml";
	private static final String SECTION = "agent-default-model";
	private static final String NEWLINE = "\n";

	private DshDefaultModel() {
	}

	/**
	 * Configures the default provider and model in an instance's {@code settings.yaml}.
	 *
	 * @param home     the {@code DSH_HOME} path
	 * @param provider the provider route name
	 * @param model    the model identifier, or {@code null}/blank to leave untouched
	 * @return true if the settings were updated or already matched
	 * @throws DshException if reading or writing fails
	 */
	public static boolean apply(Path home, String provider, @Nullable String model) throws DshException {
		if (model == null || model.isBlank()) {
			return false;
		}
		Path file = home.resolve(FILE);
		String text;
		try {
			text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
			String updated = setSection(text, provider, model.trim());
			if (updated.equals(text)) {
				return true;
			}
			Files.createDirectories(home);
			// Write via staging file and replace atomically.
			Path staging = file.resolveSibling(file.getFileName() + ".hdsl-writing");
			Files.writeString(staging, updated, StandardCharsets.UTF_8);
			try {
				Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				try {
					Files.deleteIfExists(staging);
				} catch (IOException ignored) {
				}
				throw e;
			}
			return true;
		} catch (IOException e) {
			throw new DshException("Failed to set the default model in " + file, e);
		}
	}

	/**
	 * Reads the configured default provider from an instance's {@code settings.yaml}.
	 *
	 * @param home the {@code DSH_HOME} path
	 * @return the provider name, or {@code null} if unconfigured
	 * @throws DshException if reading fails
	 */
	public static @Nullable String providerOf(Path home) throws DshException {
		Path file = home.resolve(FILE);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			String section = sectionOf(Files.readString(file, StandardCharsets.UTF_8));
			if (section == null) {
				return null;
			}
			// Match provider line with optional indentation and trailing whitespace.
			Matcher matcher = Pattern.compile("^[ \\t]*provider:[ \\t]*(\\S+)[ \\t]*$", Pattern.MULTILINE)
			                         .matcher(section);
			return matcher.find() ? matcher.group(1) : null;
		} catch (IOException e) {
			throw new DshException("Failed to read " + file, e);
		}
	}

	/**
	 * Updates the {@code agent-default-model} section in YAML text, preserving other lines and comments.
	 *
	 * @param text     existing YAML content
	 * @param provider the provider route name
	 * @param model    the model identifier
	 * @return updated YAML content
	 * @throws DshException if the section appears multiple times
	 */
	static String setSection(String text, String provider, String model) throws DshException {
		String newline = text.contains("\r\n") ? "\r\n" : "\n";
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));

		boolean endedWithNewline = text.endsWith("\n");
		if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).endsWith("\r")) {
				lines.set(i, lines.get(i).substring(0, lines.get(i).length() - 1));
			}
		}

		int header = -1;
		int headers = 0;
		for (int i = 0; i < lines.size(); i++) {
			if (isSectionHeader(lines.get(i))) {
				headers++;
				if (header < 0) {
					header = i;
				}
			}
		}
		if (headers > 1) {
			throw new DshException("The settings file has \"" + SECTION + "\" more than once, " +
			                       "so the default model was left alone");
		}

		if (header < 0) {
			if (!lines.isEmpty() && !endedWithNewline) {
				lines.add("");
			}
			lines.add(SECTION + ":");
			lines.add("  provider: " + YamlScalar.of(provider));
			lines.add("  model: " + YamlScalar.of(model));
			return String.join(newline, lines) + newline;
		}

		int first = header + 1;
		int last = header;
		String pad = null;
		int atProvider = -1;
		int atModel = -1;
		for (int i = first; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.isBlank()) {
				continue;
			}
			if (!Character.isWhitespace(line.charAt(0))) {
				break;
			}
			String linePad = line.substring(0, line.length() - line.stripLeading().length());
			if (pad == null) {
				pad = linePad;
			}
			// Match only direct children of the section.
			if (linePad.equals(pad)) {
				String body = line.strip();
				if (body.startsWith("provider:")) {
					atProvider = i;
				} else if (body.startsWith("model:")) {
					atModel = i;
				}
			}
			last = i;
		}
		String indent = pad == null ? "  " : pad;

		// Preserve inline comments when updating values.
		if (atProvider >= 0) {
			lines.set(atProvider, indent + "provider: " + YamlScalar.of(provider) + commentOf(lines.get(atProvider)));
		}
		if (atModel >= 0) {
			lines.set(atModel, indent + "model: " + YamlScalar.of(model) + commentOf(lines.get(atModel)));
		}
		int insert = last + 1;
		if (atProvider < 0) {
			lines.add(insert, indent + "provider: " + YamlScalar.of(provider));
			insert++;
		}
		if (atModel < 0) {
			lines.add(insert, indent + "model: " + YamlScalar.of(model));
		}

		return String.join(newline, lines) + newline;
	}

	/**
	 * Returns the trailing comment of a line, with the space before it, or the empty string.
	 *
	 * <p>A {@code #} only starts a comment when it is at the start of the line or after whitespace, and it
	 * is not inside quotes.
	 *
	 * @param line the line
	 * @return the comment, including the whitespace that separates it
	 */
	private static String commentOf(String line) {
		char quote = 0;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quote != 0) {
				if (c == quote) {
					quote = 0;
				}
				continue;
			}
			if (c == '"' || c == '\'') {
				quote = c;
				continue;
			}
			if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
				int from = i;
				while (from > 0 && Character.isWhitespace(line.charAt(from - 1))) {
					from--;
				}
				return line.substring(from);
			}
		}
		return "";
	}

	private static boolean isSectionHeader(String line) {
		if (!line.startsWith(SECTION + ":") || line.length() == SECTION.length() + 1) {
			return line.equals(SECTION + ":");
		}
		return Character.isWhitespace(line.charAt(SECTION.length() + 1));
	}

	private static @Nullable String sectionOf(String text) {
		StringBuilder section = new StringBuilder();
		boolean inside = false;
		for (String line : text.split("\n", -1)) {
			if (!inside) {
				if (isSectionHeader(line)) {
					inside = true;
					section.append(line).append('\n');
				}
				continue;
			}
			if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
				break;
			}
			section.append(line).append('\n');
		}
		return inside ? section.toString() : null;
	}
}
