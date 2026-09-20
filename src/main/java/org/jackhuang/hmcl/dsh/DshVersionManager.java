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
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Installs, enumerates and removes DeepSeek Harness versions.
///
/// Every version lives in its own npm prefix under [DshPaths#VERSIONS]. The
/// manager never touches the user's global npm installation, so HMCL-DSH and a
/// hand-installed `dsh` cannot interfere with each other.
///
/// Discovery uses `npm view`, which upstream's own tooling relies on as well:
/// there is no version manager or release feed to query instead.
@NotNullByDefault
public final class DshVersionManager {
    private DshVersionManager() {
    }

    /// The npm package name of the DeepSeek Harness CLI.
    public static final String PACKAGE_NAME = "@deepseek-ai/dsh";

    /// Returns every DeepSeek Harness version installed under [DshPaths#VERSIONS].
    ///
    /// Directories that do not contain a usable package are skipped, so a
    /// partially removed or failed install never appears in the list.
    ///
    /// @return the installed versions, newest first
    public static List<DshVersion> listInstalled() {
        List<DshVersion> versions = new ArrayList<>();
        Path root = DshPaths.VERSIONS;
        if (!Files.isDirectory(root)) {
            return versions;
        }

        try (Stream<Path> entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                Path bin = directory.resolve(DshVersion.PACKAGE_PATH).resolve("lib/bin.js");
                if (!Files.isRegularFile(bin)) {
                    continue;
                }
                versions.add(new DshVersion(directory.getFileName().toString(), directory));
            }
        } catch (IOException e) {
            LOG.warning("Failed to enumerate installed DSH versions in " + root, e);
        }

        versions.sort(Comparator.comparing(DshVersion::version, DshVersionManager::compareVersions).reversed());
        return versions;
    }

    /// Finds a specific installed version.
    ///
    /// @param version the version string
    /// @return the installed version, or `null` when it is not installed
    public static @Nullable DshVersion findInstalled(String version) {
        for (DshVersion candidate : listInstalled()) {
            if (candidate.version().equals(version)) {
                return candidate;
            }
        }
        return null;
    }

    /// Queries the npm registry for every published DeepSeek Harness version.
    ///
    /// Dist-tags are fetched in a second call and merged in, so the caller can
    /// tell a stable release from an `alpha` or `rc` snapshot.
    ///
    /// @return the published releases, newest first
    /// @throws DshException when no usable npm is available or the query fails
    public static List<DshRelease> fetchReleases() throws DshException {
        DshNodeRuntime runtime = requireRuntime();
        if (!runtime.canInstall()) {
            throw new DshException("npm was not found on PATH; installing versions requires it");
        }

        Set<String> versions = queryVersions(runtime.npm());
        JsonObject distTags = queryDistTags(runtime.npm());

        List<DshRelease> releases = new ArrayList<>(versions.size());
        for (String version : versions) {
            Set<String> tags = new LinkedHashSet<>();
            for (var entry : distTags.entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive() && version.equals(value.getAsString())) {
                    tags.add(entry.getKey());
                }
            }
            releases.add(new DshRelease(version, Set.copyOf(tags)));
        }

        releases.sort(Comparator.comparing(DshRelease::version, DshVersionManager::compareVersions).reversed());
        return releases;
    }

    /// Installs one DeepSeek Harness version into its own npm prefix.
    ///
    /// The install is staged into a sibling directory and moved into place on
    /// success, so an interrupted install can never look like a usable version.
    ///
    /// @param version the version to install
    /// @param onLine  a consumer notified of npm output lines, or `null`
    /// @return the installed version
    /// @throws DshException when the runtime is missing or npm fails
    public static DshVersion install(String version, @Nullable Consumer<String> onLine) throws DshException {
        Path target = DshPaths.versionDirectory(version);
        if (Files.isDirectory(target) && findInstalled(version) != null) {
            throw new DshException("Version " + version + " is already installed");
        }

        DshNodeRuntime runtime = requireRuntime();
        if (!runtime.canInstall()) {
            throw new DshException("npm was not found on PATH; installing versions requires it");
        }

        Path staging = target.resolveSibling(target.getFileName() + ".installing");
        try {
            if (Files.exists(staging)) {
                FileUtils.deleteDirectory(staging);
            }
            Files.createDirectories(staging);
        } catch (IOException e) {
            throw new DshException("Failed to prepare " + staging, e);
        }

        List<String> command = List.of(
                runtime.npm().toString(),
                "install",
                "--prefix", staging.toString(),
                "--no-audit",
                "--no-fund",
                "--loglevel=error",
                PACKAGE_NAME + "@" + version);

        LOG.info("Installing DSH " + version + ": " + String.join(" ", command));

        int exitCode;
        try {
            exitCode = DshCommand.run(command, null, onLine).exitCode();
        } catch (IOException e) {
            throw new DshException("Failed to run npm", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DshException("Install was interrupted", e);
        }

        if (exitCode != 0) {
            deleteQuietly(staging);
            throw new DshException("npm exited with code " + exitCode + " while installing " + version);
        }

        Path bin = staging.resolve(DshVersion.PACKAGE_PATH).resolve("lib/bin.js");
        if (!Files.isRegularFile(bin)) {
            deleteQuietly(staging);
            throw new DshException("npm reported success but " + bin + " is missing");
        }

        try {
            if (Files.exists(target)) {
                FileUtils.deleteDirectory(target);
            }
            Files.createDirectories(target.getParent());
            Files.move(staging, target);
        } catch (IOException e) {
            deleteQuietly(staging);
            throw new DshException("Failed to move the staged install into " + target, e);
        }

        LOG.info("Installed DSH " + version + " into " + target);
        return new DshVersion(version, target);
    }

    /// Removes an installed version and everything under its prefix.
    ///
    /// @param version the version to remove
    /// @throws DshException when the version is not installed or cannot be removed
    public static void uninstall(String version) throws DshException {
        DshVersion installed = findInstalled(version);
        if (installed == null) {
            throw new DshException("Version " + version + " is not installed");
        }
        try {
            FileUtils.deleteDirectory(installed.directory());
        } catch (IOException e) {
            throw new DshException("Failed to remove " + installed.directory(), e);
        }
        LOG.info("Removed DSH " + version);
    }

    /// Requires a usable Node runtime.
    ///
    /// @return the detected runtime
    /// @throws DshException when no `node` was found at all
    public static DshNodeRuntime requireRuntime() throws DshException {
        return DshNodeRuntime.detect()
                .orElseThrow(() -> new DshException("Node.js was not found on PATH; " + DshNodeRuntime.requirement()));
    }

    /// Reads the published version list from the registry.
    ///
    /// @param npm the npm executable
    /// @return the published version strings
    /// @throws DshException when the query fails or returns unexpected data
    private static Set<String> queryVersions(Path npm) throws DshException {
        DshCommand.Result result = runNpmView(npm, "versions");
        JsonElement parsed = parseJson(result.text());
        if (parsed == null) {
            throw new DshException("npm returned no version list; check the network and the npm registry configuration");
        }
        Set<String> versions = new TreeSet<>();
        if (parsed.isJsonArray()) {
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    versions.add(element.getAsString());
                }
            }
        } else if (parsed.isJsonPrimitive()) {
            // A package with exactly one published version answers with a bare string.
            versions.add(parsed.getAsString());
        }
        return versions;
    }

    /// Reads the dist-tag table from the registry.
    ///
    /// @param npm the npm executable
    /// @return the tag-to-version mapping, empty when the query fails
    private static JsonObject queryDistTags(Path npm) {
        try {
            JsonElement parsed = parseJson(runNpmView(npm, "dist-tags").text());
            if (parsed != null && parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (DshException e) {
            LOG.warning("Failed to read npm dist-tags", e);
        }
        return new JsonObject();
    }

    /// Runs `npm view <package> <field> --json`.
    ///
    /// @param npm   the npm executable
    /// @param field the registry field to read
    /// @return the command result
    /// @throws DshException when npm cannot be run
    private static DshCommand.Result runNpmView(Path npm, String field) throws DshException {
        List<String> command = List.of(npm.toString(), "view", PACKAGE_NAME, field, "--json");
        try {
            DshCommand.Result result = DshCommand.run(command);
            if (!result.isSuccess()) {
                throw new DshException("npm view " + field + " exited with code " + result.exitCode()
                        + ": " + result.text());
            }
            return result;
        } catch (IOException e) {
            throw new DshException("Failed to run npm", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DshException("npm view was interrupted", e);
        }
    }

    /// Parses JSON, returning `null` rather than throwing on malformed input.
    ///
    /// @param text the JSON text
    /// @return the parsed element, or `null`
    private static @Nullable JsonElement parseJson(String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException e) {
            LOG.warning("Failed to parse npm output as JSON", e);
            return null;
        }
    }

    /// Deletes a directory, ignoring failures.
    ///
    /// @param directory the directory to remove
    private static void deleteQuietly(Path directory) {
        try {
            if (Files.exists(directory)) {
                FileUtils.deleteDirectory(directory);
            }
        } catch (IOException e) {
            LOG.warning("Failed to delete " + directory, e);
        }
    }

    /// Compares two version strings that may carry pre-release suffixes.
    ///
    /// This is deliberately a small numeric comparison rather than a full
    /// implementation of semantic-version precedence: the launcher only needs a
    /// stable, sensible ordering for its version list.
    ///
    /// @param left  the first version
    /// @param right the second version
    /// @return a negative value, zero or a positive value as `left` sorts before, with or after `right`
    static int compareVersions(String left, String right) {
        int[] a = numericPrefix(left);
        int[] b = numericPrefix(right);
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(a[i], b[i]);
            if (result != 0) {
                return result;
            }
        }
        boolean leftPre = left.contains("-");
        boolean rightPre = right.contains("-");
        if (leftPre != rightPre) {
            // A release outranks any pre-release with the same numeric prefix.
            return leftPre ? -1 : 1;
        }
        return left.compareTo(right);
    }

    /// Extracts up to three leading numeric components from a version string.
    ///
    /// @param version the version string
    /// @return a three-element array, zero-filled when components are missing
    private static int[] numericPrefix(String version) {
        int[] result = new int[3];
        String[] parts = version.split("[.\\-+]");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }
}
