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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Sets the model the harness starts with, by editing two keys of its settings file.
///
/// An account given to an instance through `--patch` adds a route; it does not make the harness
/// *use* it. That is recorded in one place, the `agent-default-model` section, and an overlay
/// cannot put it there: layers merge with the user's settings on top, so a section supplied by
/// `--patch` is the one that loses. The key has to be set where the user's own answer is.
///
/// **Only a line is ever rewritten.** The harness reads this file, and while it runs it also writes
/// it — under a cross-process lock, with a leaf-level diff that keeps comments and formatting. So
/// the one thing this must never do is take a copy of the file and put it back later: a restore
/// would silently discard every change the person made through the interface during the run, and
/// could race the harness's own atomic write. What is done here is the smallest edit that has an
/// effect — the two scalar values under the section's own indentation — leaving every other byte,
/// including comments about them, exactly where it was.
///
/// Nothing else is touched, and the edit is skipped whenever it would have to guess. A route with
/// no model named is left alone: the harness refuses to start on a default it cannot resolve, so an
/// invented model name would turn a helpful default into a launch that fails.
@NotNullByDefault
public final class DshDefaultModel {
    /// The file, inside a `DSH_HOME`.
    private static final String FILE = "settings.yaml";

    /// The section that records what the harness starts with.
    private static final String SECTION = "agent-default-model";

    /// A line feed, named rather than written.
    ///
    /// `"\n"` in Java source is one character, but a newline typed *into* the literal is a real
    /// newline and makes the string two characters long — a mistake that does not fail to compile
    /// and does not throw, it just makes every comparison quietly false.
    private static final String NEWLINE = "\n";

    private DshDefaultModel() {
    }

    /// Points a home's default model at a route, unless it already says so.
    ///
    /// @param home     the `DSH_HOME`
    /// @param provider the route name
    /// @param model    the model id, or `null`/blank to leave the file alone
    /// @return whether the file now names this provider and model
    /// @throws DshException when the file cannot be read or written
    public static boolean apply(Path home, String provider, @Nullable String model) throws DshException {
        if (model == null || model.isBlank()) {
            // Which model a route should default to is the vendor's answer or the person's, not a
            // guess: the harness refuses to start on a default it cannot resolve.
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
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            throw new DshException("Failed to set the default model in " + file, e);
        }
    }

    /// Returns the provider a home's settings name, or `null`.
    ///
    /// @param home the `DSH_HOME`
    /// @return the provider name
    /// @throws DshException when the file cannot be read
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
            // `\\s*$` rather than `\\s*$` after a literal newline: an entry on the file's last line has
            // no line ending to match, and requiring one would fail to read back a value the writer
            // had just written.
            Matcher matcher = Pattern.compile("^[ \\t]*provider:[ \\t]*(\\S+)[ \\t]*$", Pattern.MULTILINE)
                    .matcher(section);
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException e) {
            throw new DshException("Failed to read " + file, e);
        }
    }

    /// Rewrites the two scalar keys of the section, leaving every other line alone.
    ///
    /// @param text     the file's contents
    /// @param provider the route name
    /// @param model    the model id
    /// @return the contents it should have
    static String setSection(String text, String provider, String model) {
        // Work on line indices rather than on a rebuilt document: the point of this edit is that
        // every byte it was not asked about stays where it was, and the surest way to guarantee
        // that is to change at most two lines and never re-serialise the rest.
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));

        // `split(..., -1)` leaves one element per line plus a final empty one when the text ends in
        // a newline. Dropping that element makes `lines` a one-to-one list of the file's lines —
        // including a file of a single line, which is why the test is on the element being empty
        // rather than on the text's last character.
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
        for (int i = 0; i < lines.size(); i++) {
            if (isSectionHeader(lines.get(i))) {
                header = i;
                break;
            }
        }

        if (header < 0) {
            // No section: append one. A file whose last line has no newline gets one first, so the
            // header is not run into it.
            if (!lines.isEmpty() && !endedWithNewline) {
                lines.add("");
            }
            lines.add(SECTION + ":");
            lines.add("  provider: " + provider);
            lines.add("  model: " + model);
            return String.join(newline, lines) + newline;
        }

        // The section's children: every following line that is indented. A blank line inside them is
        // kept as a child — it is not a line at column zero, so it does not end the section.
        int first = header + 1;
        int last = header;          // the index of the last child, or the header when it has none
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
            if (pad == null) {
                pad = line.substring(0, line.length() - line.stripLeading().length());
            }
            String body = line.strip();
            if (body.startsWith("provider:")) {
                atProvider = i;
            } else if (body.startsWith("model:")) {
                atModel = i;
            }
            last = i;
        }
        String indent = pad == null ? "  " : pad;

        if (atProvider >= 0) {
            lines.set(atProvider, indent + "provider: " + provider);
        }
        if (atModel >= 0) {
            lines.set(atModel, indent + "model: " + model);
        }
        // A key the section does not have goes directly after the last child it does have, so it
        // ends up inside the section rather than after a blank line or after the next section.
        int insert = last + 1;
        if (atProvider < 0) {
            lines.add(insert, indent + "provider: " + provider);
            insert++;
        }
        if (atModel < 0) {
            lines.add(insert, indent + "model: " + model);
        }

        // The file gets a final newline either way: one line ending per line, and no line left open.
        return String.join(newline, lines) + newline;
    }

    /// Reports whether a line is the section's header.
    ///
    /// The one test used everywhere the header is looked for, so the writer and the reader cannot
    /// disagree about whether a section is there. A header is the name, a colon, and either nothing
    /// or a space — `agent-default-modelish:` is a different key.
    ///
    /// @param line the line
    /// @return whether it opens the section
    private static boolean isSectionHeader(String line) {
        if (!line.startsWith(SECTION + ":") || line.length() == SECTION.length() + 1) {
            return line.equals(SECTION + ":");
        }
        return Character.isWhitespace(line.charAt(SECTION.length() + 1));
    }

    /// Returns the section's own lines, or `null` when the file has no such section.
    ///
    /// @param text the file's contents
    /// @return the lines belonging to the section
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
