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

    /// The npm package that provides the application boot library.
    ///
    /// Held to the same version as DeepSeek Harness itself: the two are
    /// published together, and a mismatch between them is a failure at import
    /// rather than a degradation.
    public static final String APP_BOOT_PACKAGE = "@deepseek-ai/dsh-app-boot";

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
        if (!runtime.canManagePlugins()) {
            throw new DshException("pnpm was not found on PATH; installing versions requires it");
        }

        Set<String> versions = queryVersions(runtime.npm());
        JsonObject distTags = queryDistTags(runtime.npm());
        JsonObject times = queryTimes(runtime.npm());

        List<DshRelease> releases = new ArrayList<>(versions.size());
        for (String version : versions) {
            Set<String> tags = new LinkedHashSet<>();
            for (var entry : distTags.entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive() && version.equals(value.getAsString())) {
                    tags.add(entry.getKey());
                }
            }
            JsonElement published = times.get(version);
            releases.add(new DshRelease(version, Set.copyOf(tags),
                    published != null && published.isJsonPrimitive() ? published.getAsString() : null));
        }

        releases.sort(Comparator.comparing(DshRelease::version, DshVersionManager::compareVersions).reversed());
        return releases;
    }

    /// Writes the manifest npm installs from.
    ///
    /// The launcher states its dependencies rather than letting npm pick them,
    /// so a version is reproducible and the libraries stay with the launcher
    /// that expects them.
    ///
    /// @param prefix      the directory the version is installed into
    /// @param version     the DeepSeek Harness version
    /// @param appBoot     the application boot library version to hold it to
    /// @throws DshException when the manifest cannot be written
    static void writeManifest(Path prefix, String version, String appBoot) throws DshException {
        JsonObject dependencies = new JsonObject();
        dependencies.addProperty(PACKAGE_NAME, version);

        JsonObject overrides = new JsonObject();
        overrides.addProperty(APP_BOOT_PACKAGE, appBoot);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("private", true);
        manifest.add("dependencies", dependencies);
        manifest.add("overrides", overrides);

        try {
            Files.createDirectories(prefix);
            Files.writeString(prefix.resolve("package.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(manifest));
        } catch (IOException e) {
            throw new DshException("Failed to write the manifest for " + version, e);
        }
    }

    /// Removes a version that was being installed and did not finish.
    ///
    /// The half-written copy lives beside the version it was going to become, so
    /// `uninstall` cannot reach it: that looks up an installed version and finds
    /// none. This removes what is there, finished or not.
    ///
    /// @param version the version whose partial install to remove
    public static void discardPartial(String version) {
        Path staging = DshPaths.VERSIONS.resolve(version + ".installing");
        try {
            if (Files.exists(staging)) {
                FileUtils.deleteDirectory(staging);
                LOG.info("Removed the partial install of " + version);
            }
        } catch (IOException e) {
            LOG.warning("Could not remove the partial install of " + version, e);
        }
    }

    /// Holds a version's boot library to a different release.
    ///
    /// The pairing is not a preference — the two are published together, and a
    /// release whose libraries disagree with it fails at import rather than
    /// degrading — so this is written only when someone asked for it, and the
    /// caller warns first.
    ///
    /// The choice belongs to the installed version, not to an instance: every
    /// instance running this version shares the tree, and therefore shares this.
    ///
    /// @param version the DeepSeek Harness version to change
    /// @param appBoot the boot library version to hold it to
    /// @throws DshException when the manifest cannot be written or npm fails
    public static void overrideAppBoot(String version, String appBoot) throws DshException {
        Path target = DshPaths.versionDirectory(version);
        if (!Files.isDirectory(target)) {
            throw new DshException("Version " + version + " is not installed");
        }

        DshNodeRuntime runtime = requireRuntime();
        if (!runtime.canManagePlugins()) {
            throw new DshException("pnpm was not found on PATH; changing the boot library requires it");
        }

        writeManifest(target, version, appBoot);

        // pnpm rather than npm, for one reason: pnpm links a package into a
        // project from a store it keeps, so a second project using the same
        // package costs a fraction of the first. Measured on this launcher's own
        // runtime, a second copy of the same version adds 51 MB against the
        // 402 MB it appears to occupy — which is what makes giving every instance
        // its own copy affordable.
        List<String> command = buildInstallCommand(runtime, target);

        LOG.info("Holding " + version + " to boot library " + appBoot);
        int exitCode;
        try {
            exitCode = DshCommand.run(command, null, null).exitCode();
        } catch (IOException e) {
            throw new DshException("Failed to run pnpm", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DshException("The boot library change was interrupted", e);
        }
        if (exitCode != 0) {
            throw new DshException("pnpm exited with code " + exitCode
                    + " while changing the boot library of " + version);
        }
    }

    /// Builds the command that installs a project's dependencies with pnpm.
    ///
    /// Three decisions are in here.
    ///
    /// pnpm rather than npm, because pnpm links packages into a project from a
    /// store it keeps: a second project using the same packages costs a fraction
    /// of the first. Measured here, a second copy of the same version adds 51 MB
    /// against the 402 MB it appears to occupy.
    ///
    /// `append-only` because the progress tally the launcher reads is one line of
    /// the event-by-event output.
    ///
    /// And the build scripts are allowed. pnpm runs none of them by default and
    /// fails the install when it finds any, and several of DeepSeek Harness's
    /// dependencies build native modules — the spawn helper among them — so an
    /// install without them looks complete and fails the moment it is used. This
    /// is the same trust npm extends by default, which is what the upstream
    /// package is installed with.
    ///
    /// @param runtime the runtime to take pnpm from
    /// @param project the project directory
    /// @return the command
    private static List<String> buildInstallCommand(DshNodeRuntime runtime, Path project) {
        return List.of(
                runtime.pnpm().toString(),
                "install",
                "--dir", project.toString(),
                "--reporter=append-only",
                "--config.dangerously-allow-all-builds=true");
    }

    /// Installs one DeepSeek Harness version into its own prefix.
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
        if (!runtime.canManagePlugins()) {
            throw new DshException("pnpm was not found on PATH; installing versions requires it");
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

        // The manifest is written before npm runs, because npm would otherwise
        // choose the versions itself. Installing by name records a caret range,
        // and a caret cannot express what these packages actually promise: the
        // whole family is published in lockstep, one version for all of it. So
        // `^0.1.6-alpha.1` resolves to alpha.2's libraries, and a release whose
        // code and libraries disagree fails at import — which is exactly what
        // happened between 0.1.6-alpha.1 and alpha.2, where a library dropped an
        // export the launcher still imported.
        //
        // Pinning the launcher to its exact version and holding the application
        // boot library to the same one keeps a tree consistent. The override is
        // the value the create page offers, and it defaults to the matching one.
        writeManifest(staging, version, version);

        // pnpm rather than npm, for one reason: pnpm links packages into a
        // project from a store it keeps, so a second project using the same
        // packages costs a fraction of the first. Measured here, a second copy of
        // the same version adds 51 MB against the 402 MB it appears to occupy —
        // which is what makes giving every instance its own copy affordable.
        List<String> command = buildInstallCommand(runtime, staging);

        LOG.info("Installing DSH " + version + ": " + String.join(" ", command));

        int exitCode;
        try {
            exitCode = DshCommand.run(command, null, onLine).exitCode();
        } catch (IOException e) {
            throw new DshException("Failed to run pnpm", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DshException("Install was interrupted", e);
        }

        if (exitCode != 0) {
            deleteQuietly(staging);
            throw new DshException("pnpm exited with code " + exitCode + " while installing " + version);
        }

        Path bin = staging.resolve(DshVersion.PACKAGE_PATH).resolve("lib/bin.js");
        if (!Files.isRegularFile(bin)) {
            deleteQuietly(staging);
            throw new DshException("pnpm reported success but " + bin + " is missing");
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
        return queryVersions(npm, PACKAGE_NAME);
    }

    /// Reads a package's published version list from the registry.
    ///
    /// @param npm     the npm executable
    /// @param pkg     the package to read
    /// @return the published version strings, newest first
    /// @throws DshException when the query fails or returns unexpected data
    public static List<String> fetchPackageVersions(String pkg) throws DshException {
        DshNodeRuntime runtime = requireRuntime();
        if (!runtime.canInstall()) {
            throw new DshException("npm was not found on PATH; reading the registry requires it");
        }
        List<String> versions = new ArrayList<>(queryVersions(runtime.npm(), pkg));
        versions.sort((left, right) -> compareVersions(right, left));
        return List.copyOf(versions);
    }

    /// Reads the published version list of one package from the registry.
    ///
    /// @param npm the npm executable
    /// @param pkg the package to read
    /// @return the published version strings
    /// @throws DshException when the query fails or returns unexpected data
    private static Set<String> queryVersions(Path npm, String pkg) throws DshException {
        DshCommand.Result result = runNpmView(npm, pkg, "versions");
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
    /// Reads when each version was published.
    ///
    /// A failure is not fatal: the download page shows a version without its
    /// date rather than refusing to list it.
    ///
    /// @param npm the npm executable
    /// @return the version to publication-time map, empty when unavailable
    private static JsonObject queryTimes(Path npm) {
        try {
            return asObject(parseJson(runNpmView(npm, "time").text()));
        } catch (DshException e) {
            LOG.warning("Failed to read npm publication times", e);
            return new JsonObject();
        }
    }

    /// Returns the object npm answered with.
    ///
    /// `npm view <package> <field> --json` wraps its answer in an array when the
    /// field holds one value per package, which is the case for both `time` and
    /// `dist-tags`. Reading only a bare object silently yields nothing, and the
    /// symptom is an empty column rather than an error.
    ///
    /// @param parsed the parsed answer, possibly `null`
    /// @return the object, or an empty one when there is none
    private static JsonObject asObject(@Nullable JsonElement parsed) {
        if (parsed == null) {
            return new JsonObject();
        }
        if (parsed.isJsonArray()) {
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    return element.getAsJsonObject();
                }
            }
            return new JsonObject();
        }
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    private static JsonObject queryDistTags(Path npm) {
        try {
            return asObject(parseJson(runNpmView(npm, "dist-tags").text()));
        } catch (DshException e) {
            LOG.warning("Failed to read npm dist-tags", e);
            return new JsonObject();
        }
    }

    /// Runs `npm view <package> <field> --json`.
    ///
    /// @param npm   the npm executable
    /// @param field the registry field to read
    /// @return the command result
    /// @throws DshException when npm cannot be run
    private static DshCommand.Result runNpmView(Path npm, String field) throws DshException {
        return runNpmView(npm, PACKAGE_NAME, field);
    }

    /// Runs `npm view <package> <field> --json`.
    ///
    /// @param npm   the npm executable
    /// @param pkg   the package to read
    /// @param field the field to read
    /// @return the command result
    /// @throws DshException when the command cannot be run
    private static DshCommand.Result runNpmView(Path npm, String pkg, String field) throws DshException {
        List<String> command = List.of(npm.toString(), "view", pkg, field, "--json");
        try {
            DshCommand.Result result = DshCommand.run(command);
            if (!result.isSuccess()) {
                throw new DshException("npm view " + field + " exited with code " + result.exitCode()
                        + ": " + result.text());
            }
            return result;
        } catch (IOException e) {
            throw new DshException("Failed to run pnpm", e);
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
