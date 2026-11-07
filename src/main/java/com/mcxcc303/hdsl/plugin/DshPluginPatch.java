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

/// Switches an installed plugin on and off in a profile's own patch layer.
///
/// DeepSeek Harness composes a profile from its bundles' layers and then from
/// the profile's `cordis.patch.yml`. A bundle switches **itself** off by a row
/// there —
///
/// ```yaml
/// - id: better-sidebar
///   disabled: true
/// ```
///
/// — which the loader re-applies on every boot. The row id is the loader entry
/// the package inserts, not the package name, so the package's own bundle patch
/// is what says which rows are its; a bundle patch may also carry rows that only
/// **reconfigure** other plugins, and writing `disabled: true` onto those would
/// take a neighbour down with it. That distinction is why [insertedIds] reads
/// only rows nested under an `insert:` block.
///
/// The patch file is the person's, so it is edited by appending and by removing
/// exactly the block this class wrote, never by reparsing and re-emitting it: a
/// hand-written `!!js` expression or comment survives intact.
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

	/// Matches a whole `- id: X` + `disabled: true` block for one id.
	private static Pattern disableBlock(String id) {
		return Pattern.compile("(?m)^- id: ['\"]?" + Pattern.quote(id) + "['\"]?\\r?\\n  disabled: true\\r?\\n");
	}

	public static Path profileDirectory(Path home, String profile) {
		return home.resolve("profiles").resolve(profile);
	}

	public static Path patchFile(Path home, String profile) {
		return profileDirectory(home, profile).resolve(PATCH_NAME);
	}

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

	/// Returns the entry ids the profile's own patch layer disables.
	///
	/// @param home    the `DSH_HOME`
	/// @param profile the profile's name
	/// @return the disabled ids, or an empty set when there is no patch file
	/// @throws DshException when the patch file cannot be read
	public static Set<String> disabledIds(Path home, String profile) throws DshException {
		return disabledIdsIn(read(patchFile(home, profile)));
	}

	/// Switches every row a package inserts on or off.
	///
	/// Disabling appends a `disabled: true` block per row; enabling removes the
	/// block again, so a row the package itself holds down with an expression
	/// (an aggregate guard, for instance) is left exactly as the package wrote
	/// it.
	///
	/// @param home        the `DSH_HOME`
	/// @param profile     the profile's name
	/// @param packageName the package to switch
	/// @param enabled     whether it should run
	/// @throws DshException when the package has no rows, or the patch file cannot be written
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

	/// Returns the rows a package declares through `dsh.bundle.patch`.
	///
	/// @param directory the package directory
	/// @return the declared patch file, or `null` when the manifest names none
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

	/**
	 * Reads a patch file, tolerating absence.
	 *
	 * @return the text, or `null` when it is not a readable regular file
	 */
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

	/// Reads the ids a patch layer disables.
	///
	/// @param text the patch text
	/// @return the ids carrying a `disabled: true` flag
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

	/// Appends a disable block unless the id is disabled already.
	///
	/// @param text the patch text
	/// @param id   the row id
	/// @return the updated text
	/// @throws DshException when the file cannot take another entry
	private static String appendDisable(String text, String id) throws DshException {
		if (disabledIdsIn(text).contains(id)) {
			return text;
		}
		return appendEntry(text, "- id: " + id + "\n  disabled: true\n");
	}

	/// Removes the disable block for an id, restoring the empty-list placeholder.
	///
	/// @param text the patch text
	/// @param id   the row id
	/// @return the updated text
	private static String removeDisable(String text, String id) {
		String without = disableBlock(id).matcher(text).replaceFirst("");
		return without.equals(text) ? text : withPlaceholderRestored(without);
	}

	/// Appends one top-level entry, keeping the file a single YAML array.
	///
	/// The profile template ships an empty `[]` placeholder, and appending after
	/// it would make two top-level elements in one document — the exact YAML
	/// error a hand-edit runs into. The placeholder is commented out instead,
	/// and [withPlaceholderRestored] puts it back when the last entry goes.
	///
	/// Shared with [DshProfilePatch], which appends a row for an entry the file
	/// does not mention yet: the placeholder is the same trap for both.
	///
	/// @param text  the patch text
	/// @param block the entry to append
	/// @return the updated text
	/// @throws DshException when the file is not an entry list
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

	/// Puts the empty-list placeholder back when nothing else is left.
	///
	/// Appending the first entry comments the template's `[]` out, so removing
	/// the last one leaves a file of pure comments — not a top-level array, and
	/// a profile the loader refuses to boot. Switching a plugin off and on again
	/// would otherwise brick the profile.
	///
	/// @param text the patch text
	/// @return the text, with a placeholder when it holds no entries
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

	/// Reads a patch file, treating absence as an empty file.
	///
	/// @param file the file
	/// @return the text, or an empty string
	/// @throws DshException when the file exists but cannot be read
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

	/// Writes a file the harness may be reading, through a staging file.
	///
	/// @param file the file
	/// @param text what to put in it
	/// @throws DshException when it cannot be written
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
