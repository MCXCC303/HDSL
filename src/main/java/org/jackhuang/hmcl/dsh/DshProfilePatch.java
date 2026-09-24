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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// A profile's own patch layer, edited the one way a pack needs it edited.
///
/// A profile is composed from its bundles' layers and then from this file, and a plugin listed in
/// `dsh.profile.bundles` is applied **by that bundle's layer**. A pack that also inserts the same
/// plugin applies it twice, and a plugin that claims a route on the way up cannot be applied twice:
/// the profile then fails to load at all, with `webserver: duplicate exact route "…"` — which is how
/// one pack in the market came to be unlaunchable, and it is not obvious from the pack's contents,
/// because both halves look reasonable on their own.
///
/// So an insert that a bundle already applies is taken out. **An entry that carries configuration is
/// left alone and reported instead**: dropping it would drop the settings with it, and moving them
/// onto a row that targets the bundle's own id is a rewrite this does not do.
@NotNullByDefault
public final class DshProfilePatch {
    private DshProfilePatch() {
    }

    /// Removes every insert that a bundle already applies.
    ///
    /// @param patch   the profile's patch layer
    /// @param bundles the bundle list the profile boots, in order
    /// @return the entry ids left alone because they carry configuration, never `null`
    /// @throws DshException when the file cannot be read or written
    public static List<String> dropRedundantInserts(Path patch, List<String> bundles)
            throws DshException {
        if (bundles.isEmpty() || !Files.isRegularFile(patch)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = List.of(Files.readString(patch, StandardCharsets.UTF_8).split("\n", -1));
        } catch (IOException e) {
            throw new DshException("Could not read " + patch, e);
        }

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
                // Applied twice is wrong, but so is throwing the settings away: said out loud rather
                // than decided here.
                withConfiguration.add(valueOf(lines, i, end, "id") == null
                        ? unquote(name) : unquote(valueOf(lines, i, end, "id")));
                kept.addAll(lines.subList(i, end));
                i = end - 1;
                continue;
            }
            changed = true;
            i = end - 1;
        }

        if (changed) {
            try {
                Files.writeString(patch, String.join("\n", kept), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new DshException("Could not write " + patch, e);
            }
            LOG.info("Removed inserts from " + patch + " that its own bundles already apply");
        }
        return List.copyOf(withConfiguration);
    }

    /// Reports whether a line opens an item of an `insert` list.
    ///
    /// The shape is the loader's: a patch entry is `- insert:` and the plugins it inserts are the
    /// lines under it that begin with `- `.
    ///
    /// @param line the line
    /// @return whether it opens an inserted plugin
    private static boolean isInsertItem(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("- ")) {
            return false;
        }
        // An item that names a plugin, not the `- insert:` that holds the list: the latter sits at the
        // same indentation as the list's own items would at the file's top level.
        return trimmed.startsWith("- id:") || trimmed.startsWith("- name:");
    }

    /// Returns where the item starting at `start` ends.
    ///
    /// @param lines the file's lines
    /// @param start the item's first line
    /// @return the first line after the item
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

    /// Returns the value of a key inside an item, or `null`.
    ///
    /// @param lines the file's lines
    /// @param start the item's first line
    /// @param end   the line after the item
    /// @param key   the key
    /// @return the raw value, or `null` when the item does not name it
    private static String valueOf(List<String> lines, int start, int end, String key) {
        int at = keyAt(lines, start, end, key);
        return at < 0 ? null : contentOf(lines.get(at)).substring(key.length() + 1).trim();
    }

    /// Reports whether an item names a key.
    ///
    /// @param lines the file's lines
    /// @param start the item's first line
    /// @param end   the line after the item
    /// @param key   the key
    /// @return whether the item has it
    private static boolean hasKey(List<String> lines, int start, int end, String key) {
        return keyAt(lines, start, end, key) >= 0;
    }

    /// Returns the line an item names a key on, or `-1`.
    ///
    /// @param lines the file's lines
    /// @param start the item's first line
    /// @param end   the line after the item
    /// @param key   the key
    /// @return the line's index, or `-1`
    private static int keyAt(List<String> lines, int start, int end, String key) {
        for (int i = start; i < end && i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("- ")) {
                trimmed = trimmed.substring(2);
            }
            if (trimmed.startsWith(key + ":")
                    && (trimmed.length() == key.length() + 1
                            || Character.isWhitespace(trimmed.charAt(key.length() + 1)))) {
                return i;
            }
        }
        return -1;
    }

    /// Returns a line without its indentation or the list marker that opens it.
    ///
    /// An inserted plugin is written as `- id: …`, so the key is behind both.
    ///
    /// @param line the line
    /// @return what is left
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

    /// Returns a YAML scalar without the quotes a writer may have put around it.
    ///
    /// @param value the raw value
    /// @return the value itself
    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && (trimmed.charAt(0) == '\'' || trimmed.charAt(0) == '"')
                && trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0)) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
