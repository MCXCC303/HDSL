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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Writes an instance as a pack the community's tooling reads.
///
/// The `DSH-PackForge` specification is not this launcher's own, and it describes a different
/// thing from the pack this launcher writes for itself: that one is a recipe — a version, an
/// ordered plugin list, a patch layer — while this one is a **snapshot**: the profile's files,
/// scanned, filtered and carried inside a `.dspack` container with a manifest that says what
/// they are. Both are useful and neither replaces the other, which is why the export wizard asks
/// which one to write.
///
/// The container is a plain ZIP with `dspack.json` at its root — the marker that says this is a
/// `.dspack` and which version of the container it is — and the profile's files live under
/// `overrides/`, which is what an importer copies into the profile it is rebuilding. The manifest
/// is v5 with `type: "profile"`.
///
/// The specification's filter is the part worth taking literally: an exported pack is something
/// people publish, so installed trees, credentials, keys, private keys, tokens and nested archives
/// never travel, whatever the exporter would otherwise copy.
@NotNullByDefault
public final class DshPackForge {
    /// The format's marker file, at the container's root.
    public static final String MARKER = "dspack.json";

    /// The container version this writes.
    public static final int CONTAINER_VERSION = 3;

    /// The manifest's name inside the container.
    public static final String MANIFEST = "manifest.json";

    /// Where a profile's own files go, which is where an importer copies them from.
    public static final String OVERRIDES = "overrides/";

    /// The manifest version this writes.
    public static final int MANIFEST_VERSION = 5;

    /// Names excluded anywhere in a path.
    private static final List<String> DENY_NAMES = List.of(
            "node_modules", "dist", "build", "coverage", ".cache", "cordis.yml", "manifest.json",
            "package-lock.json", "yarn.lock", ".env", ".npmrc", ".credentials.yaml",
            ".anonymous-user-id", "settings.yaml", ".dshpkcfg");

    /// Extensions that are credentials rather than content.
    private static final List<String> DENY_EXTENSIONS = List.of(
            ".key", ".pem", ".p12", ".pfx", ".crt", ".der", ".asc");

    /// Names that are credentials whatever they are called.
    private static final List<Pattern> DENY_FILE_NAMES = List.of(
            Pattern.compile("^\\.env.*"),
            Pattern.compile("^credentials.*\\.ya?ml$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*\\.credentials$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^id_rsa.*"),
            Pattern.compile("^secrets.*\\.(json|ya?ml)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*token.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*api[_-]?key.*", Pattern.CASE_INSENSITIVE));

    /// Archives, which are never nested inside a pack.
    private static final Pattern DENY_ARCHIVE = Pattern.compile(
            ".*\\.(tgz|tar\\.gz|zip|dspack)$", Pattern.CASE_INSENSITIVE);

    /// Runtime and baseline directories, which belong to the home rather than to a profile.
    private static final List<String> DENY_PREFIXES = List.of(
            "attachments/", "profiles/web/", "profiles/headless/", "skills/.system/");

    private DshPackForge() {
    }

    /// What a pack says about itself.
    ///
    /// @param name        the pack's identifier, which has to be kebab-case
    /// @param version     the pack's own version, which has to be a version
    /// @param displayName what people see, or an empty string for the name
    /// @param description what it is for, or an empty string
    /// @param author      who made it, or an empty string
    public record Options(String name, String version, String displayName, String description,
                          String author) {

        /// Returns options that describe an instance without being told anything.
        ///
        /// @param instance the instance
        /// @return the options
        public static Options of(DshInstance instance) {
            String name = kebab(instance.id());
            return new Options(name, "1.0.0", instance.id(), "", "");
        }

        /// Turns anything into a kebab-case identifier.
        ///
        /// The specification requires one, and an instance's name is whatever somebody typed.
        ///
        /// @param text the text
        /// @return the identifier
        public static String kebab(String text) {
            String cleaned = text == null ? "" : text.trim().toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("(^-+)|(-+$)", "");
            return cleaned.isEmpty() ? "pack" : cleaned;
        }
    }

    /// One file the scan found, or refused.
    ///
    /// @param relative the path inside the profile, always with `/` separators
    /// @param size     its size in bytes
    public record Entry(String relative, long size) {
    }

    /// What a scan found.
    ///
    /// @param files    the files that travel, sorted by path
    /// @param excluded what was left out and why
    public record Scan(List<Entry> files, Map<String, String> excluded) {
    }

    /// What an export wrote.
    ///
    /// @param files    how many files travelled
    /// @param excluded how many were left out
    /// @param bytes    the container's size
    /// @param sha256   the container's digest, which is what a publisher writes beside it
    public record Result(int files, int excluded, long bytes, String sha256) {
    }

    /// Scans a profile for what a pack carries.
    ///
    /// @param profile the profile directory
    /// @return what travels and what does not
    /// @throws DshException when the directory cannot be read
    public static Scan scan(Path profile) throws DshException {
        List<Entry> files = new ArrayList<>();
        Map<String, String> excluded = new LinkedHashMap<>();

        try {
            Files.walkFileTree(profile, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (directory.equals(profile)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (Files.isSymbolicLink(directory)) {
                        // A link is never followed: it can leave the profile, and it can loop.
                        excluded.put(relative(profile, directory), "symlink");
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String rel = relative(profile, directory) + "/";
                    String reason = exclusionOf(rel);
                    if (reason != null) {
                        excluded.put(rel, reason);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    String rel = relative(profile, file);
                    if (Files.isSymbolicLink(file)) {
                        excluded.put(rel, "symlink");
                        return FileVisitResult.CONTINUE;
                    }
                    String reason = exclusionOf(rel);
                    if (reason != null) {
                        excluded.put(rel, reason);
                        return FileVisitResult.CONTINUE;
                    }
                    files.add(new Entry(rel, attributes.size()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // Unreadable is the same as absent for a pack's purposes.
                    excluded.put(relative(profile, file), "unreadable");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new DshException("Failed to scan " + profile, e);
        }

        files.sort((left, right) -> left.relative().compareTo(right.relative()));
        return new Scan(List.copyOf(files), Map.copyOf(excluded));
    }

    /// Reports why a path does not travel, or `null` when it does.
    ///
    /// @param relative the path inside the profile
    /// @return the reason, or `null`
    static @Nullable String exclusionOf(String relative) {
        String path = relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (DENY_NAMES.contains(segment)) {
                return "name";
            }
        }

        String name = segments.length == 0 ? path : segments[segments.length - 1];
        for (String extension : DENY_EXTENSIONS) {
            if (name.toLowerCase(Locale.ROOT).endsWith(extension)) {
                return "extension";
            }
        }
        for (Pattern pattern : DENY_FILE_NAMES) {
            if (pattern.matcher(name).matches()) {
                return "credentials";
            }
        }
        if (DENY_ARCHIVE.matcher(path).matches()) {
            return "archive";
        }
        for (String prefix : DENY_PREFIXES) {
            if ((path + "/").startsWith(prefix)) {
                return "runtime";
            }
        }
        return null;
    }

    /// Writes an instance as a `.dspack`.
    ///
    /// @param instance the instance to write
    /// @param target   the container to create
    /// @param options  what it should say about itself
    /// @param onStage  receives progress lines, or `null`
    /// @return what was written
    /// @throws DshException when the profile cannot be read, a dependency cannot be pinned, or the
    /// Only `overrides/` is written. The container also defines `home/`, which the installer
    /// lands at the DSH_HOME root, and that is where anything outside the profile belongs — the
    /// skills under `skills/` among them. This writer does not produce it yet: a pack of this
    /// launcher is made of the profile, and home-level content is a question for the format
    /// repository rather than one to answer here alone. It is also why [DENY_PREFIXES], whose
    /// entries are home-relative paths, matches nothing under the profile it scans today.
    ///
    /// @param instance the instance
    /// @param target   the archive to create
    /// @param options  what it should say about itself
    /// @param onStage  receives progress lines, or `null`
    /// @return what was written
    /// @throws DshException when the profile cannot be read or written
    ///                      container cannot be written
    public static Result export(DshInstance instance, Path target, Options options,
                               @Nullable Consumer<String> onStage) throws DshException {
        Path profile = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
        if (!Files.isDirectory(profile)) {
            throw new DshException("The instance has no profile at " + profile);
        }

        report(onStage, "Scanning " + profile.getFileName());
        Scan scan = scan(profile);
        report(onStage, scan.files().size() + " file(s) travel, " + scan.excluded().size() + " left out");

        JsonObject manifest = buildManifest(instance, options, onStage);

        Path parent = target.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
                // The marker comes first: a loader reads it before anything else.
                put(zip, MARKER, "{\"format\":\"dspack\",\"version\":" + CONTAINER_VERSION + "}");
                put(zip, MANIFEST, JsonUtils.GSON.toJson(manifest));
                for (Entry entry : scan.files()) {
                    copy(zip, profile.resolve(entry.relative()), OVERRIDES + entry.relative());
                }
            }
        } catch (IOException e) {
            throw new DshException("Failed to write " + target, e);
        }

        long size = sizeOf(target);
        String digest = sha256(target);
        report(onStage, "Wrote " + target.getFileName() + " (" + (size / 1024) + " KiB, sha256 "
                + digest.substring(0, 12) + "…)");
        LOG.info("Wrote a DSH-PackForge pack of " + instance.id() + " to " + target);
        return new Result(scan.files().size(), scan.excluded().size(), size, digest);
    }

    /// Builds the v5 manifest for an instance.
    ///
    /// @param instance the instance
    /// @param options  what it should say about itself
    /// @param onStage  receives progress lines, or `null`
    /// @return the manifest
    /// @throws DshException when a dependency cannot be pinned to an exact version
    static JsonObject buildManifest(DshInstance instance, Options options,
                                    @Nullable Consumer<String> onStage) throws DshException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("manifestVersion", MANIFEST_VERSION);
        manifest.addProperty("type", "profile");
        manifest.addProperty("name", Options.kebab(options.name()));
        manifest.addProperty("version", options.version() == null || options.version().isBlank()
                ? "1.0.0" : options.version().trim());
        if (options.displayName() != null && !options.displayName().isBlank()) {
            manifest.addProperty("displayName", options.displayName().trim());
        }
        if (options.description() != null && !options.description().isBlank()) {
            manifest.addProperty("description", options.description().trim());
        }
        if (options.author() != null && !options.author().isBlank()) {
            manifest.addProperty("author", options.author().trim());
        }
        // Exact, because the specification requires it: a pack is a reproducible snapshot.
        manifest.addProperty("dshVersion", instance.version());
        manifest.addProperty("profileName", instance.profile());

        JsonArray bundles = new JsonArray();
        DshPluginInstaller.readBundles(instance.homeDirectory(), instance.profile())
                .forEach(bundles::add);
        manifest.add("bundles", bundles);

        JsonObject dependencies = new JsonObject();
        for (Map.Entry<String, String> entry
                : DshPluginInstaller.readDependencies(instance.homeDirectory(), instance.profile())
                        .entrySet()) {
            if (DshModpacks.isLocalSpec(entry.getValue())) {
                // A specification this instance installed from a file. This format names sources, and
                // a file on this machine is not one — unless the registry publishes the same version,
                // in which case the source it names is the one the file was built from and the pack
                // stays a pack of published sources.
                dependencies.addProperty(entry.getKey(),
                        publishedVersionOf(instance, entry.getKey(), entry.getValue()));
                report(onStage, entry.getKey() + " was installed from a file and is published, so the"
                        + " pack names its published version");
                continue;
            }
            dependencies.addProperty(entry.getKey(), pin(entry.getKey(), entry.getValue(), onStage));
        }
        manifest.add("dependencies", dependencies);
        return manifest;
    }

    /// Returns the published version of a plugin an instance installed from a file.
    ///
    /// @param instance the instance the plugin belongs to
    /// @param name     the dependency name
    /// @param declared what the profile declares for it
    /// @return the published version
    /// @throws DshException when the registry does not publish it, which this format cannot express
    private static String publishedVersionOf(DshInstance instance, String name, String declared)
            throws DshException {
        Path profileDirectory = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
        DshPluginBundle.Payload payload = DshPluginBundle.locate(profileDirectory, name, declared);
        if (payload != null
                && DshPackageRegistry.availability(name, payload.version())
                        == DshPackageRegistry.Availability.PUBLISHED) {
            return payload.version();
        }
        throw new DshException("The plugin " + name + " was installed from a file this instance keeps ("
                + declared + "), and the registry does not publish that version, so a pack of published"
                + " sources cannot name it. Export a pack this launcher reads (.hdslp) to carry the files"
                + " themselves, or install a published version first.");
    }

    /// Pins a dependency to what the specification requires.
    ///
    /// npm versions are already exact in a profile. A git source names a branch or a tag, and a
    /// pack has to name the commit instead — a branch moves, and a pack that moved with it would
    /// not be a snapshot of anything.
    ///
    /// @param name     the package
    /// @param declared what the profile declares
    /// @param onStage  receives progress lines, or `null`
    /// @return the pinned version or commit
    /// @throws DshException when a git source cannot be resolved to a commit
    static String pin(String name, String declared, @Nullable Consumer<String> onStage) throws DshException {
        if (declared == null || declared.isBlank()) {
            throw new DshException("The plugin " + name + " has no version to pin");
        }
        if (!declared.startsWith("github:") && !declared.startsWith("git+")
                && !declared.contains("github.com/")) {
            return declared.trim();
        }

        String repository = declared.trim();
        if (repository.startsWith("github:")) {
            repository = "https://github.com/" + repository.substring("github:".length());
        }
        report(onStage, "Resolving " + name + " to a commit");
        try {
            DshCommand.Result result = DshCommand.run(
                    List.of("git", "ls-remote", repository), null, null);
            if (result.exitCode() != 0) {
                throw new DshException("git could not list " + repository + ": "
                        + String.join("\n", result.output()));
            }
            for (String line : result.output()) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[0].length() >= 40) {
                    return parts[0];
                }
            }
            throw new DshException("No branch or tag was found at " + repository);
        } catch (IOException e) {
            throw new DshException("Failed to run git for " + name, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DshException("Resolving " + name + " was interrupted", e);
        }
    }

    /// Returns the path of a file inside a root, with `/` separators.
    ///
    /// @param root the root
    /// @param file the file
    /// @return the relative path
    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /// Writes one entry of text.
    ///
    /// @param zip  the archive
    /// @param name the entry's name
    /// @param body the entry's text
    /// @throws IOException when the archive cannot be written
    private static void put(ZipOutputStream zip, String name, String body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /// Copies one file into the archive.
    ///
    /// @param zip  the archive
    /// @param file the file
    /// @param name the entry's name
    /// @throws IOException when the file cannot be read or the archive written
    private static void copy(ZipOutputStream zip, Path file, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream input = Files.newInputStream(file)) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    /// Returns a file's size.
    ///
    /// @param file the file
    /// @return the size, or zero
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /// Returns a file's SHA-256 digest, which is what a publisher writes beside a pack.
    ///
    /// @param file the file
    /// @return the digest in lower-case hexadecimal
    /// @throws DshException when the file cannot be read
    static String sha256(Path file) throws DshException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder text = new StringBuilder();
            for (byte value : digest.digest()) {
                text.append(String.format("%02x", value));
            }
            return text.toString();
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new DshException("Failed to hash " + file, e);
        }
    }

    /// Reports progress.
    ///
    /// @param onStage the sink, or `null`
    /// @param message the message
    private static void report(@Nullable Consumer<String> onStage, String message) {
        if (onStage != null) {
            onStage.accept(message);
        }
    }
}
