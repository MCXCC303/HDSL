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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kala.compress.archivers.ArchiveEntry;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.tree.ArchiveFileTree;
import org.jackhuang.hmcl.util.tree.TarFileTree;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.tukaani.xz.XZInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Installs and enumerates the Node.js runtimes HMCL-DSH owns.
///
/// This mirrors HMCL's Java runtime management: instead of depending on
/// whatever the distribution ships, the launcher can fetch a specific Node
/// release into `runtimes/<version>/` and pin an instance to it. That matters
/// because DeepSeek Harness declares `engines.node` as `^22.19.0 || >=24.0.0`.
///
/// Runtimes come from the official `nodejs.org` distribution, so extraction has
/// to reproduce the archive faithfully — including the symlinks that make
/// `bin/npm` and `bin/npx` work.
@NotNullByDefault
public final class NodeRuntimeManager {
    private NodeRuntimeManager() {
    }

    /// The Node distribution index listing every published release.
    /// The download base for a specific release.
    /// Returns the platform tag used in Node distribution file names.
    ///
    /// @return `linux-x64` or `linux-arm64`
    /// @throws DshException when the current architecture has no Node build
    public static String platformTag() throws DshException {
        return switch (Architecture.SYSTEM_ARCH) {
            case X86_64 -> "linux-x64";
            case ARM64 -> "linux-arm64";
            default -> throw new DshException(
                    "Node.js publishes no build for " + Architecture.SYSTEM_ARCH + " on Linux");
        };
    }

    /// Lists the Node runtimes installed under [DshPaths#RUNTIMES].
    ///
    /// @return the installed runtimes, newest first
    public static List<NodeRuntime> listInstalled() {
        List<NodeRuntime> runtimes = new ArrayList<>();
        Path root = DshPaths.RUNTIMES;
        if (!Files.isDirectory(root)) {
            return runtimes;
        }
        try (Stream<Path> entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                NodeRuntime runtime = new NodeRuntime(directory.getFileName().toString(), directory);
                if (runtime.isUsable()) {
                    runtimes.add(runtime);
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to enumerate installed Node runtimes in " + root, e);
        }
        runtimes.sort(Comparator.comparing(NodeRuntime::version, DshVersionManager::compareVersions).reversed());
        return runtimes;
    }

    /// Finds an installed Node runtime by version.
    ///
    /// @param version the version string
    /// @return the runtime, or `null` when it is not installed
    public static @Nullable NodeRuntime findInstalled(String version) {
        for (NodeRuntime runtime : listInstalled()) {
            if (runtime.version().equals(version)) {
                return runtime;
            }
        }
        return null;
    }

    /// Fetches the published Node releases that have a build for this platform.
    ///
    /// @param source where to read the index from
    /// @return the available releases, newest first
    /// @throws DshException when the index cannot be read
    public static List<NodeRelease> fetchReleases(NodeSource source) throws DshException {
        String platform = platformTag();
        String url = source.indexUrl();

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(NetworkUtils.doGet(URI.create(url)));
        } catch (IOException | RuntimeException e) {
            // The URL is in the message because the failure is almost always the
            // route to that host rather than anything about the index: a network
            // that reaches npm through a mirror may not reach this at all, and
            // "which host" is the one thing that says so.
            throw new DshException("Failed to read the Node.js release index from " + url
                    + " (" + source.host() + " could not be reached: " + e.getMessage() + ")", e);
        }
        if (!parsed.isJsonArray()) {
            throw new DshException("The Node.js release index had an unexpected shape");
        }

        List<NodeRelease> releases = new ArrayList<>();
        JsonArray array = parsed.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String version = asString(object, "version");
            if (version == null || !version.startsWith("v")) {
                continue;
            }
            if (!hasPlatform(object, platform)) {
                continue;
            }
            String lts = null;
            JsonElement ltsElement = object.get("lts");
            if (ltsElement != null && ltsElement.isJsonPrimitive() && ltsElement.getAsJsonPrimitive().isString()) {
                lts = ltsElement.getAsString();
            }
            releases.add(new NodeRelease(version.substring(1), lts, asString(object, "date")));
        }

        releases.sort(Comparator.comparing(NodeRelease::version, DshVersionManager::compareVersions).reversed());
        return releases;
    }

    /// Installs a Node release into its own directory.
    ///
    /// The archive is downloaded and unpacked into a staging directory first, so
    /// an interrupted install can never be mistaken for a usable runtime.
    ///
    /// @param version the version to install, with or without a leading `v`
    /// @param source  where to fetch the archive from
    /// @param onStage receives a short progress description, or `null`
    /// @return the installed runtime
    /// @throws DshException when the download or extraction fails
    public static NodeRuntime install(String version, NodeSource source,
                                      @Nullable Consumer<String> onStage) throws DshException {
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        if (findInstalled(normalized) != null) {
            throw new DshException("Node.js " + normalized + " is already installed");
        }

        String platform = platformTag();
        Path target = DshPaths.runtimeDirectory(normalized);
        Path staging = target.resolveSibling(target.getFileName() + ".installing");
        Path archive = DshPaths.RUNTIMES.resolve("node-v" + normalized + "-" + platform + ".tar.xz");

        try {
            Files.createDirectories(DshPaths.RUNTIMES);
            deleteQuietly(staging);
            Files.createDirectories(staging);
        } catch (IOException e) {
            throw new DshException("Failed to prepare " + staging, e);
        }

        String fileName = "node-v" + normalized + "-" + platform + ".tar.xz";
        String url = source.archiveUrl(normalized, fileName);

        try {
            stage(onStage, "Downloading " + fileName);
            download(url, archive, onStage);

            stage(onStage, "Unpacking " + fileName);
            extractTarXz(archive, staging);
        } catch (IOException e) {
            deleteQuietly(staging);
            deleteQuietly(archive);
            throw new DshException("Failed to install Node.js " + normalized + ": " + e.getMessage(), e);
        }

        // The archive contains a single top-level directory; move its contents up
        // so the runtime directory is the distribution root.
        Path unpacked = staging.resolve("node-v" + normalized + "-" + platform);
        if (!Files.isDirectory(unpacked)) {
            deleteQuietly(staging);
            throw new DshException("The archive did not contain node-v" + normalized + "-" + platform);
        }

        try {
            deleteQuietly(target);
            Files.move(unpacked, target, StandardCopyOption.ATOMIC_MOVE);
            deleteQuietly(staging);
            Files.deleteIfExists(archive);
        } catch (IOException e) {
            deleteQuietly(staging);
            throw new DshException("Failed to move the Node runtime into " + target, e);
        }

        NodeRuntime runtime = new NodeRuntime(normalized, target);
        if (!runtime.isUsable()) {
            throw new DshException("Node.js " + normalized + " unpacked without a node executable");
        }

        provisionPnpm(runtime, onStage);

        LOG.info("Installed Node.js " + normalized + " into " + target);
        return runtime;
    }

    /// Installs the pnpm a managed runtime needs to run DeepSeek Harness.
    ///
    /// A Node distribution carries corepack but no `pnpm` binary, and corepack
    /// left to itself picks the newest pnpm — while DeepSeek Harness is written
    /// against pnpm 11, down to how that major reports blocked dependency
    /// scripts. The major is therefore pinned here so a profile behaves the same
    /// on a managed runtime as on the system one.
    ///
    /// A failure is reported but does not fail the install: the runtime is
    /// usable without pnpm, and the plugin installer explains what is missing.
    ///
    /// @param runtime the freshly installed runtime
    /// @param onStage receives progress lines, or `null`
    private static void provisionPnpm(NodeRuntime runtime, @Nullable Consumer<String> onStage) {
        Path npm = runtime.directory().resolve("bin").resolve("npm");
        if (!Files.isExecutable(npm)) {
            stage(onStage, "npm is missing, so pnpm was not installed");
            return;
        }

        stage(onStage, "Installing pnpm " + PNPM_MAJOR + ".x");
        List<String> command = List.of(npm.toString(), "install", "--global", "pnpm@" + PNPM_MAJOR);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PATH",
                runtime.directory().resolve("bin") + java.io.File.pathSeparator
                        + String.valueOf(System.getenv("PATH")));
        builder.redirectErrorStream(true);

        try {
            Process process = builder.start();
            try (var reader = process.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stage(onStage, line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                LOG.warning("Installing pnpm into " + runtime.directory() + " exited with " + exit);
                stage(onStage, "pnpm could not be installed (npm exited with " + exit + ")");
            }
        } catch (IOException e) {
            LOG.warning("Failed to install pnpm into " + runtime.directory(), e);
            stage(onStage, "pnpm could not be installed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// The pnpm major DeepSeek Harness is written against.
    private static final String PNPM_MAJOR = "11";

    /// Reads the version of a Node installation in a directory.
    ///
    /// The version is asked of the binary rather than parsed from the directory
    /// name, because a directory the user chose has no naming contract.
    ///
    /// @param directory the directory holding `bin/node`
    /// @return the version, or `null` when there is no usable node there
    public static @Nullable String versionOfDirectory(Path directory) {
        Path node = directory.resolve("bin").resolve("node");
        if (!Files.isExecutable(node)) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(node.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var reader = process.inputReader()) {
                output = reader.readLine();
            }
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }
            return output == null || output.isBlank() ? null : output.trim().replaceFirst("^v", "");
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /// Adopts a Node installation the user already has.
    ///
    /// The directory is copied into the managed runtimes rather than referenced
    /// in place, so an instance pinned to it does not depend on where the
    /// installation happened to live — the same reasoning behind HMCL copying a
    /// chosen Java home into its own store.
    ///
    /// @param source  the directory holding `bin/node`
    /// @param version the version the directory reports
    /// @return the managed runtime
    /// @throws DshException when the directory is unusable or already managed
    public static NodeRuntime adopt(Path source, String version) throws DshException {
        if (findInstalled(version) != null) {
            throw new DshException("Node.js " + version + " is already managed");
        }

        Path target = DshPaths.runtimeDirectory(version);
        Path staging = target.resolveSibling(target.getFileName() + ".adopting");
        try {
            deleteQuietly(staging);
            copyTree(source, staging);
            deleteQuietly(target);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(staging);
            throw new DshException("Failed to adopt " + source + ": " + e.getMessage(), e);
        }

        NodeRuntime runtime = new NodeRuntime(version, target);
        if (!runtime.isUsable()) {
            throw new DshException("The chosen directory does not contain a usable Node.js installation");
        }
        provisionPnpm(runtime, null);
        return runtime;
    }

    /// Copies a directory tree.
    ///
    /// @param source      the directory to copy
    /// @param destination the destination
    /// @throws IOException when the copy fails
    private static void copyTree(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (Files.isSymbolicLink(path)) {
                    // Preserve links: a Node distribution links npm and npx into
                    // lib/node_modules, and copying the targets would break them.
                    Files.createSymbolicLink(target, Files.readSymbolicLink(path));
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /// Removes an installed Node runtime.
    ///
    /// @param version the version to remove
    /// @throws DshException when the runtime is not installed or cannot be removed
    public static void uninstall(String version) throws DshException {
        NodeRuntime runtime = findInstalled(version);
        if (runtime == null) {
            throw new DshException("Node.js " + version + " is not installed");
        }
        try {
            FileUtils.deleteDirectory(runtime.directory());
        } catch (IOException e) {
            throw new DshException("Failed to remove " + runtime.directory(), e);
        }
        LOG.info("Removed Node.js " + version);
    }

    /// Streams a URL to a file, reporting progress.
    ///
    /// @param url      the download URL
    /// @param target   the destination file
    /// @param onStage  receives progress descriptions, or `null`
    /// @throws IOException when the transfer fails
    private static void download(String url, Path target, @Nullable Consumer<String> onStage) throws IOException {
        HttpURLConnection connection = NetworkUtils.createHttpConnection(url);
        connection.setInstanceFollowRedirects(true);
        try {
            connection.connect();
            int status = connection.getResponseCode();
            if (status / 100 != 2) {
                throw new IOException("HTTP " + status + " for " + url);
            }
            long total = connection.getContentLengthLong();
            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[1 << 16];
                long copied = 0;
                int read;
                int lastReported = -1;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    copied += read;
                    if (onStage != null && total > 0) {
                        int percent = (int) (copied * 100 / total);
                        if (percent / 10 != lastReported / 10) {
                            lastReported = percent;
                            onStage.accept("Downloading " + percent + "%");
                        }
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    /// Unpacks a `.tar.xz` archive into a directory, preserving symlinks.
    ///
    /// Recreating symlinks matters: the Node distribution reaches `npm` and
    /// `npx` through links into `lib/node_modules`, so a naive extraction that
    /// materialised them as copies would leave a subtly broken runtime.
    ///
    /// @param archive the `.tar.xz` file
    /// @param target  the directory to unpack into
    /// @throws IOException when decompression or extraction fails
    private static void extractTarXz(Path archive, Path target) throws IOException {
        Path tarFile = Files.createTempFile("hdsl-node", ".tar");
        try {
            try (InputStream in = Files.newInputStream(archive);
                 XZInputStream xz = new XZInputStream(in)) {
                Files.copy(xz, tarFile, StandardCopyOption.REPLACE_EXISTING);
            }
            try (TarFileTree tree = TarFileTree.open(tarFile)) {
                extract(tree, tree.getRoot(), target);
            }
        } finally {
            Files.deleteIfExists(tarFile);
        }
    }

    /// Recursively unpacks one directory of an archive tree.
    ///
    /// @param tree   the archive being read
    /// @param dir    the directory to unpack
    /// @param target the destination directory
    /// @param <R>    the reader type
    /// @param <E>    the entry type
    /// @throws IOException when an entry cannot be written
    private static <R, E extends ArchiveEntry> void extract(ArchiveFileTree<R, E> tree,
                                                            ArchiveFileTree.Dir<E> dir,
                                                            Path target) throws IOException {
        for (var entry : dir.getFiles().entrySet()) {
            Path destination = target.resolve(entry.getKey());
            E archiveEntry = entry.getValue();
            if (tree.isLink(archiveEntry)) {
                Files.deleteIfExists(destination);
                Files.createSymbolicLink(destination, Path.of(tree.getLink(archiveEntry)));
                continue;
            }
            Files.createDirectories(destination.getParent());
            tree.extractTo(archiveEntry, destination);
            if (tree.isExecutable(archiveEntry)) {
                destination.toFile().setExecutable(true, false);
            }
        }
        for (var subDirectory : dir.getSubDirs().entrySet()) {
            Path destination = target.resolve(subDirectory.getKey());
            Files.createDirectories(destination);
            extract(tree, subDirectory.getValue(), destination);
        }
    }

    /// Reports a progress stage when a consumer is attached.
    ///
    /// @param onStage the consumer, or `null`
    /// @param message the message
    private static void stage(@Nullable Consumer<String> onStage, String message) {
        if (onStage != null) {
            onStage.accept(message);
        }
    }

    /// Reads a string field from a JSON object.
    ///
    /// @param object the object
    /// @param name   the field name
    /// @return the string value, or an empty string when absent
    private static String asString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    /// Reports whether a release publishes a build for a platform tag.
    ///
    /// @param object   the release object
    /// @param platform the platform tag
    /// @return whether the `files` array contains the tag
    private static boolean hasPlatform(JsonObject object, String platform) {
        JsonElement files = object.get("files");
        if (files == null || !files.isJsonArray()) {
            return false;
        }
        for (JsonElement file : files.getAsJsonArray()) {
            if (file.isJsonPrimitive() && platform.equalsIgnoreCase(file.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /// Deletes a path, ignoring failures and absence.
    ///
    /// @param path the path to remove
    private static void deleteQuietly(Path path) {
        try {
            if (Files.isDirectory(path)) {
                FileUtils.deleteDirectory(path);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOG.warning("Failed to delete " + path, e);
        }
    }
}
