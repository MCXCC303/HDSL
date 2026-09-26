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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Carries a plugin an instance installed from a file.
///
/// A profile records a local installation as the path it was given, and a path on
/// somebody's disk is not something another machine can install from: a pack that
/// named it would describe an installation nobody can make, and an import would
/// stop there. So a pack carries the files themselves — the tarball the instance
/// keeps, or the directory the profile installed from — and hands them back to the
/// next instance under the directory [DshLocalPlugins] owns, where they are a local
/// installation like any other.
///
/// A plugin that the registry publishes at the version the instance has is *not*
/// carried: naming `name@version` installs the same thing from the registry, which
/// is smaller and is what a pack should do when it can. Deciding that is the
/// exporter's job — see [DshPackageRegistry#availability] — and what is left for
/// this class is the other case, where the files exist nowhere but here.
@NotNullByDefault
public final class DshPluginBundle {
    /// The directory a pack holds carried plugins under.
    public static final String ENTRY_PREFIX = "plugins/";

    private DshPluginBundle() {
    }

    /// The files of one local plugin, as they are found on the exporting side.
    ///
    /// @param name      the dependency name, which is the key the profile manifest uses
    /// @param version   the package's own version
    /// @param archive   the packed plugin the instance keeps, or `null` for a directory
    /// @param directory the directory the profile installed from, or `null` for an archive
    public record Payload(String name, String version, @Nullable Path archive, @Nullable Path directory) {

        /// Returns the name this payload is written under.
        ///
        /// @return the entry name, ending in `/` for a directory
        public String entryName() {
            String safe = DshLocalPlugins.safeName(name) + "-" + DshLocalPlugins.safeName(version);
            return archive != null ? ENTRY_PREFIX + safe + ".tgz" : ENTRY_PREFIX + safe + "/";
        }

        /// Reports how big the payload is.
        ///
        /// @return the size in bytes, or zero when it cannot be measured
        public long size() {
            try {
                if (archive != null) {
                    return Files.size(archive);
                }
                try (var walk = Files.walk(directory)) {
                    return walk.filter(Files::isRegularFile).mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).sum();
                }
            } catch (IOException e) {
                return 0L;
            }
        }
    }

    /// Finds the files an instance installed a local plugin from.
    ///
    /// The profile records the specification, so the specification is what is read first: a packed
    /// plugin the launcher copied into the instance is still there, and it is exactly what the other
    /// machine needs. A specification that names no readable file — `link:`, a directory, a file since
    /// deleted — falls back to what the profile *did* install, which is a directory inside its own
    /// `node_modules`.
    ///
    /// @param profileDirectory the profile the plugin belongs to
    /// @param name             the dependency name
    /// @param declared         what the profile declares for it
    /// @return the payload, or `null` when no files can be found
    public static @Nullable Payload locate(Path profileDirectory, String name, String declared) {
        Path file = fileOf(declared, profileDirectory);
        if (file != null && Files.isRegularFile(file)) {
            try {
                DshLocalPlugins.Package pkg = DshLocalPlugins.inspect(file);
                return new Payload(name, pkg.version(), file, null);
            } catch (DshException e) {
                LOG.warning("Could not read the packed plugin " + file, e);
            }
        }

        Path directory = profileDirectory.resolve("node_modules").resolve(name);
        if (Files.isDirectory(directory)) {
            String version = versionOf(directory);
            if (version != null) {
                return new Payload(name, version, null, directory);
            }
        }
        return null;
    }

    /// Reads the file a local specification names.
    ///
    /// @param declared         the specification
    /// @param profileDirectory the profile, for a specification that is relative
    /// @return the file, or `null` when the specification does not name one
    private static @Nullable Path fileOf(String declared, Path profileDirectory) {
        String raw = declared.trim();
        if (raw.startsWith("link:")) {
            // A link is a promise about a directory, not a file, and the directory is not part of
            // the instance: what the profile installed is read from its own node_modules instead.
            return null;
        }
        if (raw.startsWith("file:")) {
            raw = raw.substring("file:".length());
            // `file:///x` and `file:/x` are both spellings of an absolute path.
            if (raw.startsWith("//")) {
                raw = raw.substring(2);
            }
        }
        if (raw.isBlank() || raw.startsWith("http:") || raw.startsWith("https:")) {
            return null;
        }
        Path path = null;
        try {
            path = Path.of(raw);
        } catch (RuntimeException e) {
            // A specification this is not a path at all; it was handled as a non-local one elsewhere.
            return null;
        }
        if (!path.isAbsolute()) {
            path = profileDirectory.resolve(path);
        }
        if (!Files.isRegularFile(path) && raw.contains("%")) {
            // npm records a path as a URI when it contains characters that need escaping.
            try {
                Path decoded = Path.of(java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8));
                if (Files.isRegularFile(decoded)) {
                    return decoded;
                }
            } catch (RuntimeException e) {
                return path;
            }
        }
        return path;
    }

    /// Reads a package's version from its directory.
    ///
    /// @param directory the package directory
    /// @return the version, or `null` when the directory holds no readable manifest
    private static @Nullable String versionOf(Path directory) {
        Path manifest = directory.resolve("package.json");
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(manifest));
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            JsonElement version = object.get("version");
            return version == null || version.isJsonNull() ? null : version.getAsString();
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not read the version of " + directory, e);
            return null;
        }
    }

    /// Returns the names of the plugins a pack carries.
    ///
    /// @param pack the archive
    /// @return the entry names, in the order the archive holds them
    /// @throws DshException when the archive cannot be read
    public static List<String> carried(Path pack) throws DshException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().startsWith(ENTRY_PREFIX)) {
                    names.add(entry.getName());
                }
            }
        } catch (IOException e) {
            throw new DshException("Failed to read " + pack, e);
        }
        return names;
    }

    /// Writes a plugin's files into a pack.
    ///
    /// @param zip     the archive being written
    /// @param payload the files
    /// @param onStage receives progress lines, or `null`
    /// @throws IOException when the archive cannot be written
    public static void writeInto(ZipOutputStream zip, Payload payload,
                                 @Nullable Consumer<String> onStage) throws IOException {
        if (payload.archive() != null) {
            ZipEntry entry = new ZipEntry(payload.entryName());
            entry.setTime(0L);
            zip.putNextEntry(entry);
            Files.copy(payload.archive(), zip);
            zip.closeEntry();
            report(onStage, "Carrying " + payload.name() + " " + payload.version()
                    + " as the plugin file this instance keeps");
            return;
        }

        Path directory = payload.directory();
        if (directory == null) {
            return;
        }
        String prefix = payload.entryName();
        int files = 0;
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String relative = directory.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith("node_modules/") || relative.contains("/node_modules/")
                        || relative.startsWith(".git/") || relative.contains("/.git/")) {
                    continue;
                }
                // The manifest goes in last: it is what makes a directory a package, so an archive
                // that was cut short holds files no package manager will pick up rather than half a
                // plugin.
                if (relative.equals("package.json")) {
                    continue;
                }
                ZipEntry entry = new ZipEntry(prefix + relative);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                Files.copy(path, zip);
                zip.closeEntry();
                files++;
            }
        }
        // The manifest goes in last: it is what makes the directory a package, so an archive that
        // was cut short holds files that no package manager will pick up rather than half a plugin.
        Path manifest = directory.resolve("package.json");
        if (Files.isRegularFile(manifest)) {
            ZipEntry entry = new ZipEntry(prefix + "package.json");
            entry.setTime(0L);
            zip.putNextEntry(entry);
            Files.copy(manifest, zip);
            zip.closeEntry();
            files++;
        }
        report(onStage, "Carrying " + payload.name() + " " + payload.version() + " from the directory it was"
                + " installed from, " + files + " file(s)");
    }

    /// Puts a pack's copy of a plugin back into an instance.
    ///
    /// The files are written under the directory [DshLocalPlugins] keeps local plugin files in, so
    /// the instance that receives them has a local installation exactly like the one the pack was
    /// made from — including a file the interface can later remove or replace.
    ///
    /// @param pack             the archive
    /// @param pluginsDirectory the instance's plugin directory
    /// @param name             the dependency name
    /// @param version          the version the pack recorded
    /// @return where the files were written, or `null` when the pack carries none
    /// @throws DshException when the archive cannot be read or written out
    public static @Nullable Path release(Path pack, Path pluginsDirectory, String name, String version)
            throws DshException {
        String safe = DshLocalPlugins.safeName(name);
        String wanted = ENTRY_PREFIX + safe + "-" + DshLocalPlugins.safeName(version) + ".tgz";
        Path released = null;
        try {
            Files.createDirectories(pluginsDirectory);
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String entryName = entry.getName();
                    if (entry.isDirectory() || !entryName.startsWith(ENTRY_PREFIX)) {
                        continue;
                    }
                    // What the pack called the payload is what the instance calls it too, so a pack
                    // whose recorded version differs from the name it was written under still comes
                    // back out whole.
                    String rest = entryName.substring(ENTRY_PREFIX.length());
                    int slash = rest.indexOf('/');
                    String carried = slash < 0 ? rest : rest.substring(0, slash);
                    if (!isOurs(carried, safe)) {
                        continue;
                    }
                    if (slash < 0) {
                        if (!entryName.endsWith(".tgz")) {
                            continue;
                        }
                        Path target = pluginsDirectory.resolve(carried);
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                        return target.toAbsolutePath().normalize();
                    }
                    Path target = pluginsDirectory.resolve(carried)
                            .resolve(rest.substring(slash + 1).replace('/', java.io.File.separatorChar));
                    Files.createDirectories(target.getParent());
                    // Read straight out of the archive: closing anything here would close the
                    // archive, and the entries after this one would be gone.
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    released = pluginsDirectory.resolve(carried).toAbsolutePath().normalize();
                }
            }
        } catch (IOException e) {
            throw new DshException("Failed to put the pack's copy of " + name + " into " + pluginsDirectory, e);
        }
        return released;
    }

    /// Reports whether a carried payload belongs to a package.
    ///
    /// The name a payload is written under is the package's name and version, so it starts with the
    /// package's name and a separator. A package whose name is a prefix of another's — `dsh-a` beside
    /// `dsh-ab` — must not take the other's files, which is what the separator is for.
    ///
    /// @param carried the name the payload is carried under
    /// @param safe    the package's safe name
    /// @return whether the payload is this package's
    private static boolean isOurs(String carried, String safe) {
        if (carried.equals(safe) || carried.equals(safe + ".tgz")) {
            return true;
        }
        return carried.startsWith(safe + "-") || carried.startsWith(safe + ".");
    }

    /// Reports a line of progress.
    ///
    /// @param onStage receives the line, or `null`
    /// @param line    the line
    private static void report(@Nullable Consumer<String> onStage, String line) {
        if (onStage != null) {
            onStage.accept(line);
        }
    }
}
