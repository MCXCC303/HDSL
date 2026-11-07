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
import com.mcxcc303.hdsl.plugin.DshPluginPatch;
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

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Line-level edits to a profile's own patch layer.
 *
 * <p>Comments, {@code !!js} expressions, indentation and key order survive an edit.
 */
@NotNullByDefault
public final class DshProfilePatch {
	private DshProfilePatch() {
	}

	/**
	 * Removes bundle inserts from a profile's patch layer if the profile boots those bundles.
	 *
	 * @param patch   the profile's patch layer path
	 * @param bundles the bundle list booted by the profile, in order
	 * @return entry IDs preserved because they contain configuration
	 * @throws DshException if reading or writing fails
	 */
	public static List<String> dropRedundantInserts(Path patch, List<String> bundles) throws DshException {
		if (bundles.isEmpty() || !Files.isRegularFile(patch)) return List.of();

		String text;
		try {
			text = Files.readString(patch, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new DshException("Could not read " + patch, e);
		}
		Edit edit = withoutRedundantInserts(text, bundles);
		if (edit.changed()) {
			try {
				Files.writeString(patch, edit.text(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new DshException("Could not write " + patch, e);
			}
			LOG.info("Removed inserts from " + patch + " that its own bundles already apply");
		}
		return edit.withConfiguration();
	}

	/**
	 * Removes bundle inserts from patch text if the profile boots those bundles.
	 *
	 * @param text    the patch text
	 * @param bundles the bundle list booted by the profile, in order
	 * @return the edit result
	 */
	public static Edit withoutRedundantInserts(String text, List<String> bundles) {
		if (bundles.isEmpty()) {
			return new Edit(text, false, List.of());
		}
		List<String> lines = List.of(text.split("\n", -1));

		List<String> kept = new ArrayList<>();
		List<String> withConfiguration = new ArrayList<>();
		boolean changed = false;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (!isInsertItem(line)) {
				kept.add(line);
				continue;
			}
			int end = itemEnd(lines, i);
			String name = valueOf(lines, i, end, "name");
			if (name == null || !bundles.contains(unquote(name))) {
				kept.addAll(lines.subList(i, end));
				i = end - 1;
				continue;
			}
			if (hasKey(lines, i, end, "config")) {
				// Preserve inserts that carry custom configuration.
				withConfiguration.add(
						valueOf(lines, i, end, "id") == null ? unquote(name) : unquote(valueOf(lines, i, end, "id")));
				kept.addAll(lines.subList(i, end));
				i = end - 1;
				continue;
			}
			changed = true;
			i = end - 1;
		}

		return new Edit(String.join("\n", kept), changed, List.copyOf(withConfiguration));
	}

	/**
	 * Applies the result of {@link #putProvider(String, String, String, String)} to the profile's patch layer.<br>
	 * Creates the file if the profile doesn't have one, and the file is untouched if the text is unchanged
	 *
	 * @param patch the profile's patch layer
	 * @param entry the loader entry that holds the supplier routes
	 * @param route the route's name
	 * @param block the route's settings, one level in from the route's own key
	 * @return the edit that was applied
	 * @throws DshException the file cannot be read or written, or the edit is refused
	 *                      (see {@link #putProvider(String, String, String, String)})
	 */
	public static Edit putProvider(Path patch, String entry, String route, String block) throws DshException {
		String text = read(patch);
		Edit edit = putProvider(text, entry, route, block);
		if (edit.changed()) {
			write(patch, edit.text());
			LOG.info("Put the account route " + route + " into " + patch);
		}
		return edit;
	}

	/**
	 * Puts the given provider route into the last entry with the given id, under {@code config.providers}.
	 *
	 * @param text  the patch
	 * @param entry the id of the entry to write into
	 * @param route the route's name
	 * @param block the route's settings as YAML (2 spaces' tab)
	 * @return the patched text and whether it changed
	 * @throws DshException when the entry's {@code config} or {@code providers} is written inline with settings
	 */
	public static Edit putProvider(String text, String entry, String route, String block) throws DshException {
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		int item = lastEntryItem(lines, entry);
		if (item < 0) {
			return new Edit(DshPluginPatch.appendEntry(text, newEntry(entry, route, block)), true, List.of());
		}
		int end = itemEnd(lines, item);
		int body = childIndent(lines, item, end, indentOf(lines.get(item)));
		int config = keyAtIndent(lines, item, end, "config", body);
		if (config < 0) {
			int at = beforeBlanks(lines, end);
			List<String> addition = new ArrayList<>();
			addition.add(pad(body) + "config:");
			addition.add(pad(body + 2) + "providers:");
			addition.addAll(routeLines(route, block, body + 4, 2));
			lines.addAll(at, addition);
			return changed(lines, text);
		}

		if (isNullOrEmptyMapping(contentOf(lines.get(config)), "config")) {
			lines.set(config, pad(indentOf(lines.get(config))) + "config:");
		} else if (lines.get(config).trim().contains("{")) {
			throw new DshException("The profile's patch layer already holds `" + entry + "` with an inline or empty `config`; " +
			                       "format it with newlines to configure an account");
		}

		int configIndent = indentOf(lines.get(config));
		int configEnd = blockEnd(lines, config + 1, end, configIndent);
		int providers = keyDeeperThan(lines, config + 1, configEnd, "providers", configIndent);
		if (providers < 0) {
			int at = beforeBlanks(lines, configEnd);
			int providersIndent = childIndent(lines, config + 1, configEnd, configIndent);
			int step = providersIndent - configIndent;
			List<String> addition = new ArrayList<>();
			addition.add(pad(providersIndent) + "providers:");
			addition.addAll(routeLines(route, block, providersIndent + step, step));
			lines.addAll(at, addition);
			return changed(lines, text);
		}

		if (isNullOrEmptyMapping(contentOf(lines.get(providers)), "providers")) {
			lines.set(providers, pad(indentOf(lines.get(providers))) + "providers:");
		} else if (lines.get(providers).trim().contains("{")) {
			throw new DshException("The profile's patch layer already holds `" + entry + "` with an inline or empty `providers`; " +
			                       "format it with newlines to configure an account");
		}

		int providersIndent = indentOf(lines.get(providers));
		int providersEnd = blockEnd(lines, providers + 1, end, providersIndent);
		int existing = routeKey(lines, providers + 1, providersEnd, route, providersIndent);
		int routeIndent = childIndent(lines, providers + 1, providersEnd, providersIndent);
		if (existing < 0) {
			int at = beforeBlanks(lines, providersEnd);
			lines.addAll(at, routeLines(route, block, routeIndent, step(lines, item, body)));
			return changed(lines, text);
		}

		int routeEnd = beforeBlanks(lines, blockEnd(lines, existing + 1, providersEnd, indentOf(lines.get(existing))));
		lines.subList(existing, routeEnd).clear();
		lines.addAll(existing, routeLines(route, block, routeIndent, step(lines, item, body)));
		return changed(lines, text);
	}

	/**
	 * Removes a provider route from an entry in the profile's patch layer.
	 *
	 * @param patch the profile's patch layer path
	 * @param entry the loader entry holding supplier routes
	 * @param route the route name
	 * @return the edit result
	 * @throws DshException if reading or writing fails
	 */
	public static Edit removeProvider(Path patch, String entry, String route) throws DshException {
		String text = read(patch);
		Edit edit = removeProvider(text, entry, route);
		if (edit.changed()) {
			write(patch, edit.text());
			LOG.info("Took the account route " + route + " back out of " + patch);
		}
		return edit;
	}

	/**
	 * Removes a provider route from an entry in patch text.
	 *
	 * @param text  the patch text
	 * @param entry the loader entry holding supplier routes
	 * @param route the route name
	 * @return the edit result
	 */
	public static Edit removeProvider(String text, String entry, String route) {
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		int[] block = routeBlock(lines, entry, route);
		if (block == null) {
			return new Edit(text, false, List.of());
		}
		lines.subList(block[0], block[1]).clear();
		return changed(lines, text);
	}

	private static int @Nullable [] routeBlock(List<String> lines, String entry, String route) {
		int item = lastEntryItem(lines, entry);
		if (item < 0) {
			return null;
		}
		int end = itemEnd(lines, item);
		int body = childIndent(lines, item, end, indentOf(lines.get(item)));
		int config = keyAtIndent(lines, item, end, "config", body);
		if (config < 0) {
			return null;
		}
		int configIndent = indentOf(lines.get(config));
		int configEnd = blockEnd(lines, config + 1, end, configIndent);
		int providers = keyDeeperThan(lines, config + 1, configEnd, "providers", configIndent);
		if (providers < 0) {
			return null;
		}
		int providersIndent = indentOf(lines.get(providers));
		int providersEnd = blockEnd(lines, providers + 1, end, providersIndent);
		int at = routeKey(lines, providers + 1, providersEnd, route, providersIndent);
		if (at < 0) {
			return null;
		}
		return new int[]{at, beforeBlanks(lines, blockEnd(lines, at + 1, providersEnd, indentOf(lines.get(at))))};
	}

	private static Edit changed(List<String> lines, String text) {
		String updated = String.join("\n", lines);
		return new Edit(updated, !updated.equals(text), List.of());
	}

	private static boolean isNullOrEmptyMapping(String content, String key) {
		String value = content.substring(key.length() + 1).trim();
		return value.equals("{}") || value.equals("{ }") || value.equals("null") || value.equals("~");
	}

	private static int lastEntryItem(List<String> lines, String entry) {
		int found = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (indentOf(lines.get(i)) != 0) {
				continue;
			}
			String content = contentOf(lines.get(i));
			if (content.startsWith("id:") && unquote(content.substring(3)).equals(entry)) {
				found = i;
			}
		}
		return found;
	}

	private static String newEntry(String entry, String route, String block) {
		return "- id: " + YamlScalar.of(entry) + "\n  config:\n    providers:\n" +
		       String.join("\n", routeLines(route, block, 6, 2)) + "\n";
	}

	private static List<String> routeLines(String route, String block, int indent, int step) {
		List<String> lines = new ArrayList<>();
		lines.add(pad(indent) + YamlScalar.of(route) + ":");
		for (String line : block.split("\n", -1)) {
			if (line.isEmpty()) {
				continue;
			}
			int level = indentOf(line) / 2 + 1;
			lines.add(pad(indent + step * level) + line.trim());
		}
		return lines;
	}

	private static int step(List<String> lines, int item, int body) {
		int own = indentOf(lines.get(item));
		return body > own ? body - own : 2;
	}

	private static int childIndent(List<String> lines, int start, int end, int parentIndent) {
		for (int i = start; i < end && i < lines.size(); i++) {
			String line = lines.get(i);
			if (!line.isBlank() && indentOf(line) > parentIndent) {
				return indentOf(line);
			}
		}
		return parentIndent + 2;
	}

	private static int beforeBlanks(List<String> lines, int index) {
		int at = Math.min(index, lines.size());
		while (at > 0 && lines.get(at - 1).isBlank()) {
			at--;
		}
		return at;
	}

	private static int blockEnd(List<String> lines, int start, int end, int indent) {
		for (int i = start; i < end && i < lines.size(); i++) {
			String line = lines.get(i);
			if (!line.isBlank() && indentOf(line) <= indent) {
				return i;
			}
		}
		return Math.min(end, lines.size());
	}

	private static int keyAtIndent(List<String> lines, int start, int end, String key, int indent) {
		for (int i = start; i < end && i < lines.size(); i++) {
			if (indentOf(lines.get(i)) == indent && startsWithKey(lines.get(i), key)) {
				return i;
			}
		}
		return -1;
	}

	private static int keyDeeperThan(List<String> lines, int start, int end, String key, int parentIndent) {
		for (int i = start; i < end && i < lines.size(); i++) {
			if (indentOf(lines.get(i)) > parentIndent && startsWithKey(lines.get(i), key)) {
				return i;
			}
		}
		return -1;
	}

	private static int routeKey(List<String> lines, int start, int end, String route, int parentIndent) {
		for (int i = start; i < end && i < lines.size(); i++) {
			if (indentOf(lines.get(i)) <= parentIndent) {
				continue;
			}
			String content = contentOf(lines.get(i));
			if (content.endsWith(":") && unquote(content.substring(0, content.length() - 1)).equals(route)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean startsWithKey(String line, String key) {
		String content = contentOf(line);
		return content.startsWith(key + ":") &&
		       (content.length() == key.length() + 1 || Character.isWhitespace(content.charAt(key.length() + 1)));
	}

	private static String pad(int count) {
		return " ".repeat(Math.max(0, count));
	}

	private static String read(Path patch) throws DshException {
		if (!Files.isRegularFile(patch)) {
			return "";
		}
		try {
			return Files.readString(patch, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new DshException("Could not read " + patch, e);
		}
	}

	private static void write(Path patch, String text) throws DshException {
		try {
			Files.createDirectories(patch.getParent());
			Path staging = patch.resolveSibling(patch.getFileName() + ".hdsl-route");
			Files.writeString(staging, text, StandardCharsets.UTF_8);
			try {
				Files.move(staging, patch, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(staging, patch, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new DshException("Could not write " + patch, e);
		}
	}

	private static boolean isInsertItem(String line) {
		String trimmed = line.trim();
		if (!trimmed.startsWith("- ")) {
			return false;
		}
		return trimmed.startsWith("- id:") || trimmed.startsWith("- name:");
	}

	private static int itemEnd(List<String> lines, int start) {
		int indent = indentOf(lines.get(start));
		int i = start + 1;
		for (; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.isBlank()) {
				continue;
			}
			if (indentOf(line) <= indent) {
				break;
			}
		}
		return i;
	}

	private static String valueOf(List<String> lines, int start, int end, String key) {
		int at = keyAt(lines, start, end, key);
		return at < 0 ? null : contentOf(lines.get(at)).substring(key.length() + 1).trim();
	}

	private static boolean hasKey(List<String> lines, int start, int end, String key) {
		return keyAt(lines, start, end, key) >= 0;
	}

	private static int keyAt(List<String> lines, int start, int end, String key) {
		for (int i = start; i < end && i < lines.size(); i++) {
			String trimmed = lines.get(i).trim();
			if (trimmed.startsWith("- ")) {
				trimmed = trimmed.substring(2);
			}
			if (trimmed.startsWith(key + ":") &&
			    (trimmed.length() == key.length() + 1 || Character.isWhitespace(trimmed.charAt(key.length() + 1)))) {
				return i;
			}
		}
		return -1;
	}

	private static String contentOf(String line) {
		String trimmed = line.trim();
		return trimmed.startsWith("- ") ? trimmed.substring(2).trim() : trimmed;
	}

	private static int indentOf(String line) {
		int i = 0;
		while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
			i++;
		}
		return i;
	}

	private static String unquote(String value) {
		String trimmed = value.trim();
		if (trimmed.length() >= 2 && (trimmed.charAt(0) == '\'' || trimmed.charAt(0) == '"') &&
		    trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0)) {
			return trimmed.substring(1, trimmed.length() - 1);
		}
		return trimmed;
	}

	/**
	 * Result of a patch modification.
	 *
	 * @param text              patch content after editing
	 * @param changed           whether the content differs from original
	 * @param withConfiguration entry IDs preserved due to existing configuration
	 */
	public record Edit(String text, boolean changed, List<String> withConfiguration) {}
}
