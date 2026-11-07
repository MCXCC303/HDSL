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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcxcc303.hdsl.core.DshException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Switches an installed plugin on and off in a profile's own patch layer ({@code cordis.patch.yml}).
 *
 * <p>DeepSeek Harness composes a profile from its bundles' layers and then from the profile's
 * {@code cordis.patch.yml}. A plugin is disabled by appending a top-level entry:
 * <pre>{@code
 * - id: better-sidebar
 *   disabled: true
 * }</pre>
 * which the loader applies on boot. The row id corresponds to the loader entry inserted by the package,
 * not necessarily the package name itself.
 *
 * <p>The patch file is preserved across edits by surgically inserting or removing specific lines
 * rather than round-tripping through a YAML parser, ensuring comments and {@code !!js} expressions
 * remain intact.
 *
 * <p><strong>Empty list placeholder handling:</strong> Profile templates ship with an empty list
 * ({@code []}) placeholder. Appending an entry comments out the placeholder ({@code # []}) so the document
 * remains a valid single top-level YAML array. When the last entry is removed, the placeholder is restored
 * to prevent leaving an invalid empty/comment-only document that fails to boot.
 */
@NotNullByDefault
public final class DshPluginPatch {
	public static final String PATCH_NAME = "cordis.patch.yml";

	private static final String CONVENTIONAL_PATCH = "cordis.patch.yml";
	private static final Pattern ROW_ID = Pattern.compile("^[A-Za-z0-9_.-]+$");
	private static final Pattern INSERT_LINE = Pattern.compile("^\\s*-?\\s*insert:\\s*$");
	private static final Pattern ROW_KEY = Pattern.compile("^\\s*-?\\s*(?:id|name|config):");
	private static final Pattern ID_LINE = Pattern.compile("^\\s*-?\\s*id:\\s*['\"]?([^'\"\\s]+)");
	private static final Pattern NAME_LINE = Pattern.compile("^\\s*-?\\s*name:\\s*['\"]?([^'\"\\s]+)");
	private static final Pattern DISABLE_ROW = Pattern.compile("^- id: ([A-Za-z0-9_.-]+)\\s*$");
	private static final Pattern DISABLED_NEXT = Pattern.compile("^ {2}disabled: true\\s*$");

	private DshPluginPatch() {}

	private static Pattern disableBlock(String id) {
		return Pattern.compile("(?m)^- id: ['\"]?" + Pattern.quote(id) + "['\"]?\\r?\\n  disabled: true\\r?\\n");
	}

	/**
	 * Returns the directory for a profile.
	 *
	 * @param home    the {@code DSH_HOME}
	 * @param profile the profile name
	 * @return the profile directory
	 */
	public static Path profileDirectory(Path home, String profile) {
		return home.resolve("profiles").resolve(profile);
	}

	/**
	 * Returns the patch file path for a profile.
	 *
	 * @param home    the {@code DSH_HOME}
	 * @param profile the profile name
	 * @return the patch file path
	 */
	public static Path patchFile(Path home, String profile) {
		return profileDirectory(home, profile).resolve(PATCH_NAME);
	}

	/**
	 * Returns the loader row IDs inserted by a package.
	 *
	 * @param home        the {@code DSH_HOME}
	 * @param profile     the profile name
	 * @param packageName the package name
	 * @return the inserted row IDs
	 */
	public static List<String> insertedIds(Path home, String profile, String packageName) {
		return insertedRows(home, profile, packageName).stream().map(Row::id).toList();
	}

	/**
	 * Returns the loader rows a package adds to the tree, along with their mounted modules.
	 * <p>
	 * Reads both the patch declared by {@code dsh.bundle.patch} and the root-level
	 * {@code cordis.patch.yml}, since the loader checks both. <br>
	 * Rows that only reconfigure the package are omitted.
	 *
	 * @param home        the {@code DSH_HOME}
	 * @param profile     the profile name
	 * @param packageName the package name as it appears in the profile manifest
	 * @return the rows, or an empty list if the package adds none
	 */
	public static List<Row> insertedRows(Path home, String profile, String packageName) {
		Path directory = profileDirectory(home, profile).resolve("node_modules").resolve(packageName);
		List<Row> rows = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();

		Path declared = declaredPatch(directory);
		if (declared != null) addInsertedRows(rows, seen, readIfFile(declared));

		Path conventional = directory.resolve(CONVENTIONAL_PATCH);
		if (declared == null || !declared.equals(conventional)) {
			addInsertedRows(rows, seen, readIfFile(conventional));
		}
		return List.copyOf(rows);
	}

	/**
	 * Returns the entry IDs disabled in the profile's patch layer.
	 *
	 * @param home    the {@code DSH_HOME}
	 * @param profile the profile's name
	 * @return the disabled IDs, or an empty set when there is no patch file
	 * @throws DshException when the patch file cannot be read
	 */
	public static Set<String> disabledIds(Path home, String profile) throws DshException {
		return disabledIdsIn(read(patchFile(home, profile)));
	}

	/**
	 * Switches all rows inserted by a package on or off.
	 *
	 * @param home        the {@code DSH_HOME}
	 * @param profile     the profile's name
	 * @param packageName the package to switch
	 * @param enabled     whether the plugin should be enabled
	 * @throws DshException when the package has no rows or the patch cannot be written
	 */
	public static void setEnabled(Path home, String profile, String packageName, boolean enabled) throws DshException {
		List<String> ids = insertedIds(home, profile, packageName);
		if (ids.isEmpty()) {
			throw new DshException("This plugin declares no loader rows that can be switched");
		}

		Path file = patchFile(home, profile);
		String text = read(file);
		for (String id : ids) {
			if (!ROW_ID.matcher(id).matches()) {
				throw new DshException("Loader row " + id + " cannot be written to the patch layer");
			}
			text = enabled ? removeDisable(text, id) : appendDisable(text, id);
		}
		write(file, text);
	}

	private static @Nullable Path declaredPatch(Path directory) {
		try {
			JsonObject manifest = JsonParser.parseString(Files.readString(directory.resolve("package.json"),
			                                                              StandardCharsets.UTF_8)).getAsJsonObject();
			if (!manifest.has("dsh") || !manifest.get("dsh").isJsonObject()) {
				return null;
			}
			JsonObject bundle = manifest.getAsJsonObject("dsh").getAsJsonObject("bundle");
			if (bundle == null || !bundle.has("patch") || !bundle.get("patch").isJsonPrimitive()) {
				return null;
			}
			String declared = bundle.get("patch").getAsString();
			return declared == null || declared.isBlank() ? null : directory.resolve(declared).normalize();
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Adds the rows one patch file inserts, keeping the first mention of each id.
	 *
	 * @param rows the rows read so far
	 * @param seen the ids already read, from this file or the other one
	 * @param text the patch text, or {@code null} when there is no file
	 */
	private static void addInsertedRows(List<Row> rows, Set<String> seen, @Nullable String text) {
		if (text == null) return;

		for (Row row : insertedRowsIn(text)) {
			if (seen.add(row.id())) rows.add(row);
		}
	}

	private static @Nullable String readIfFile(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.warning("Could not read " + file, e);
			return null;
		}
	}

	/**
	 * Reads the ids nested under {@code insert:} blocks.
	 *
	 * @param text the patch text
	 * @return the inserted ids, in order and without duplicates
	 */
	static List<String> insertedIdsIn(String text) {
		return insertedRowsIn(text).stream().map(Row::id).toList();
	}

	/**
	 * Reads the rows nested under {@code insert:} blocks, with their mounted modules.
	 * <p>
	 * Parses line by line, not as YAML. <br>
	 * loader-accepted expressions and structures are not rejected.
	 *
	 * @param text the patch text
	 * @return the rows, in order and without duplicates
	 */
	static List<Row> insertedRowsIn(String text) {
		List<Row> rows = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		@Nullable Integer insertIndent = null;
		@Nullable String id = null;
		@Nullable String name = null;
		int keyIndent = -1;

		for (String raw : text.split("\\r?\\n", -1)) {
			String line = raw.replaceFirst("#.*$", "");
			if (line.trim().isEmpty()) continue;

			int indent = line.length() - line.stripLeading().length();
			if (insertIndent != null && indent <= insertIndent && !ROW_KEY.matcher(line).find()) {
				insertIndent = null;
			}
			if (INSERT_LINE.matcher(line).find()) {
				addRow(rows, seen, id, name);
				id = null;
				name = null;
				insertIndent = indent;
				continue;
			}

			Matcher rowId = ID_LINE.matcher(line);
			if (!rowId.find()) {
				if (insertIndent != null && id != null && name == null && indent == keyIndent) {
					Matcher module = NAME_LINE.matcher(line);
					if (module.find()) name = module.group(1);
				}
				continue;
			}
			addRow(rows, seen, id, name);

			id = null;
			name = null;
			if (insertIndent != null && indent > insertIndent) {
				id = rowId.group(1);
				keyIndent = line.indexOf("id:");
			} else {
				insertIndent = null;
			}
		}
		addRow(rows, seen, id, name);
		return List.copyOf(rows);
	}

	/**
	 * Adds one row, keeping the first mention of its id.
	 */
	private static void addRow(List<Row> rows, Set<String> seen, @Nullable String id, @Nullable String name) {
		if (id != null && seen.add(id)) rows.add(new Row(id, name));
	}

	/**
	 * Reads the IDs that a patch layer disables.
	 *
	 * @param text the patch text
	 * @return the IDs carrying a {@code disabled: true} flag
	 */
	static Set<String> disabledIdsIn(String text) {
		Set<String> disabled = new LinkedHashSet<>();
		String[] lines = text.split("\\r?\\n", -1);
		boolean inInsert = false;
		for (int index = 0; index < lines.length; index++) {
			String line = lines[index];
			if (line.matches("^- insert:\\s*$")) {
				inInsert = true;
				continue;
			}
			if (line.startsWith("- ")) {
				inInsert = false;
			}
			if (inInsert) {
				continue;
			}
			Matcher row = DISABLE_ROW.matcher(line);
			if (!row.find()) {
				continue;
			}
			String next = index + 1 < lines.length ? lines[index + 1] : "";
			if (DISABLED_NEXT.matcher(next).matches()) {
				disabled.add(row.group(1));
			}
		}
		return disabled;
	}

	private static String appendDisable(String text, String id) throws DshException {
		if (disabledIdsIn(text).contains(id)) {
			return text;
		}
		return appendEntry(text, "- id: " + id + "\n  disabled: true\n");
	}

	private static String removeDisable(String text, String id) {
		String without = disableBlock(id).matcher(text).replaceFirst("");
		return without.equals(text) ? text : withPlaceholderRestored(without);
	}

	/**
	 * Appends a top-level YAML entry while preserving the array structure.
	 *
	 * @param text  the patch text
	 * @param block the entry to append
	 * @return the updated patch text
	 * @throws DshException when the file structure prevents appending
	 */
	public static String appendEntry(String text, String block) throws DshException {
		if (text.trim().isEmpty()) {
			return block;
		}
		String withoutComments = text.replaceAll("(?m)^[ \\t]*#.*$", "").trim();
		if (withoutComments.isEmpty()) {
			return endWithNewline(text) + block;
		}
		if (withoutComments.equals("[]") || withoutComments.equals("[ ]")) {
			String commented = text.replaceFirst("(?m)^[ \\t]*\\[[ \\t]*\\][ \\t]*(?:#.*)?(?:\\r?\\n|$)", "# []\n");
			return endWithNewline(commented) + block;
		}
		String lastContent = "";
		for (String line : text.split("\\r?\\n", -1)) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
				lastContent = trimmed;
			}
		}
		if (lastContent.startsWith("[") || lastContent.startsWith("{")) {
			throw new DshException("The patch layer ends in a top-level flow structure; refusing to append");
		}
		return endWithNewline(text) + block;
	}

	/**
	 * Restores the empty list placeholder when no entries remain in the patch text.
	 *
	 * @param text the patch text
	 * @return the text with placeholder restored if empty
	 */
	static String withPlaceholderRestored(String text) {
		if (!text.replaceAll("(?m)^[ \\t]*#.*$", "").trim().isEmpty()) {
			return text;
		}
		String uncommented = text.replaceFirst("(?m)^[ \\t]*#[ \\t]*\\[[ \\t]*\\][ \\t]*(?:\\r?\\n|$)", "[]\n");
		if (!uncommented.equals(text)) {
			return uncommented;
		}
		return endWithNewline(text) + "[]\n";
	}

	private static String endWithNewline(String text) {
		return text.endsWith("\n") ? text : text + "\n";
	}

	private static String read(Path file) throws DshException {
		if (!Files.isRegularFile(file)) {
			return "";
		}
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new DshException("Could not read " + file, e);
		}
	}

	private static void write(Path file, String text) throws DshException {
		try {
			Files.createDirectories(file.getParent());
			Path staging = file.resolveSibling(file.getFileName() + ".hdsl-toggling");
			Files.writeString(staging, text, StandardCharsets.UTF_8);
			try {
				Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new DshException("Could not write " + file, e);
		}
	}

	/**
	 * One row a bundle patch inserts.
	 *
	 * @param id   the loader entry id, which is the plugin's identity in the harness
	 * @param name the module the row mounts, or {@code null} when the row names none
	 */
	public record Row(String id, @Nullable String name) {}
}
