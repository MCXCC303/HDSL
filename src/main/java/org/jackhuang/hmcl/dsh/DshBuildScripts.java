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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Decides whether a plugin's install scripts may run.
///
/// A package can carry a script that runs when it is installed, and a package manager
/// will not run one unless it is told to: that is the whole point of the setting, since
/// the script runs with the user's own rights and nobody has read it. When pnpm meets a
/// package it will not build, it writes the package into the profile's
/// `pnpm-workspace.yaml` with a placeholder instead of an answer:
///
/// ```yaml
/// allowBuilds:
///   node-pty: set this to true or false
/// ```
///
/// and refuses the installation until somebody answers. Answering is a decision about
/// running code, so this launcher does not answer on its own: it reads what is waiting
/// so the interface can say so, and writes an answer when a person gives one. That is
/// what makes it a setting rather than a behaviour — one that can be on for an instance
/// whose plugins are known and off for one whose plugins are not.
@NotNullByDefault
public final class DshBuildScripts {
    /// What pnpm writes where an answer belongs.
    private static final String PLACEHOLDER = "set this to true or false";

    private DshBuildScripts() {
    }

    /// One package waiting to be answered about.
    ///
    /// @param name    the package
    /// @param allowed whether it is already answered, and how
    public record Pending(String name, @Nullable Boolean allowed) {
    }

    /// Reads what the profile's package manager is waiting to be told.
    ///
    /// @param instance the instance
    /// @return the entries, in the order the file lists them
    /// @throws DshException when the profile cannot be read
    public static List<Pending> pending(DshInstance instance) throws DshException {
        Path workspace = workspaceFile(instance);
        if (!Files.isRegularFile(workspace)) {
            return List.of();
        }

        List<Pending> entries = new ArrayList<>();
        boolean inSection = false;
        try {
            for (String line : Files.readAllLines(workspace, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("allowBuilds:")) {
                    inSection = true;
                    continue;
                }
                if (inSection) {
                    if (!line.startsWith(" ") && !line.startsWith("\t") && !trimmed.isEmpty()) {
                        // Another top-level key ends the section.
                        break;
                    }
                    int colon = trimmed.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String name = trimmed.substring(0, colon).trim();
                    String value = trimmed.substring(colon + 1).trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    entries.add(new Pending(name, answerOf(value)));
                }
            }
        } catch (IOException e) {
            throw new DshException("Failed to read " + workspace, e);
        }
        return List.copyOf(entries);
    }

    /// Answers about the packages named.
    ///
    /// Only the entries named are touched: an answer somebody already gave, and any
    /// other part of the file, is left exactly as it was.
    ///
    /// @param instance the instance
    /// @param packages the packages to answer about
    /// @param allowed  whether their install scripts may run
    /// @return how many entries were written
    /// @throws DshException when the profile cannot be read or written
    public static int answer(DshInstance instance, List<String> packages, boolean allowed) throws DshException {
        Path workspace = workspaceFile(instance);
        if (!Files.isRegularFile(workspace)) {
            throw new DshException("The instance has no profile settings at " + workspace);
        }

        Set<String> wanted = new LinkedHashSet<>(packages);
        List<String> lines;
        try {
            lines = new ArrayList<>(Files.readAllLines(workspace, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DshException("Failed to read " + workspace, e);
        }

        int written = 0;
        boolean inSection = false;
        boolean sectionSeen = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            if (trimmed.startsWith("allowBuilds:")) {
                inSection = true;
                sectionSeen = true;
                continue;
            }
            if (!inSection) {
                continue;
            }
            if (!line.startsWith(" ") && !line.startsWith("\t") && !trimmed.isEmpty()) {
                inSection = false;
                continue;
            }

            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = trimmed.substring(0, colon).trim();
            if (!wanted.contains(name)) {
                continue;
            }
            lines.set(i, line.substring(0, line.indexOf(trimmed)) + name + ": " + allowed);
            written++;
        }

        if (written == 0 && !sectionSeen && !wanted.isEmpty()) {
            // Nothing to answer yet: the section is written when the package manager
            // first refuses a build, and creating it here would be approving scripts
            // for packages that never needed it.
            return 0;
        }

        writeAtomically(workspace, String.join("\n", lines) + "\n");
        LOG.info("Answered about " + written + " build script(s) in " + workspace + ": " + allowed);
        return written;
    }

    /// Returns the names of the packages still waiting for an answer.
    ///
    /// @param instance the instance
    /// @return the names
    /// @throws DshException when the profile cannot be read
    public static List<String> unanswered(DshInstance instance) throws DshException {
        return pending(instance).stream().filter(entry -> entry.allowed() == null)
                .map(Pending::name).toList();
    }

    /// Returns the profile's package-manager settings.
    ///
    /// @param instance the instance
    /// @return the file
    /// @throws DshException when the home cannot be resolved
    private static Path workspaceFile(DshInstance instance) throws DshException {
        return instance.homeDirectory().resolve("profiles").resolve(instance.profile())
                .resolve("pnpm-workspace.yaml");
    }

    /// Reads an answer, or `null` when the entry holds a placeholder.
    ///
    /// @param value the text after the colon
    /// @return the answer, or `null`
    private static @Nullable Boolean answerOf(String value) {
        String clean = value.replace("\"", "").replace("'", "").trim();
        if (clean.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (clean.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        if (clean.contains(PLACEHOLDER)) {
            return null;
        }
        return null;
    }

    /// Writes a file by replacing it, so a half-written file is never read.
    ///
    /// @param file the file
    /// @param body what to write
    /// @throws DshException when it cannot be written
    private static void writeAtomically(Path file, String body) throws DshException {
        Path staging = file.resolveSibling(file.getFileName() + ".hdsl");
        try {
            Files.writeString(staging, body, StandardCharsets.UTF_8);
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DshException("Failed to write " + file, e);
        }
    }
}
