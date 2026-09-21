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

    /// Reports whether an instance's own DeepSeek Harness is in place.
    ///
    /// @param instance the instance
    /// @return whether its entry script is there
    public static boolean isInstalled(DshInstance instance) {
        try {
            return Files.isRegularFile(instance.dshEntryPoint());
        } catch (DshException e) {
            return false;
        }
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

        // The override goes in a file of its own, not in this one. npm reads an
        // `overrides` field here and pnpm does not: since pnpm 10 the settings it
        // used to take from `package.json` live in `pnpm-workspace.yaml`, and a
        // `pnpm` field here is ignored with a warning. Writing only npm's spelling
        // left the pinning silently not applied, and a runtime came out with the
        // launcher at one version and its boot library at another — a pairing that
        // fails at import rather than degrading. The tests cover the pair.
        String workspace = "overrides:\n"
                + "  '" + APP_BOOT_PACKAGE + "': " + appBoot + "\n";

        try {
            Files.createDirectories(prefix);
            var gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            Files.writeString(prefix.resolve("package.json"), gson.toJson(manifest));
            Files.writeString(prefix.resolve("pnpm-workspace.yaml"), workspace);
        } catch (IOException e) {
            throw new DshException("Failed to write the manifest for " + version, e);
        }
    }

    /// The directory an instance's copy is staged in while it is installed.
    ///
    /// Inside the instance, so that moving it into place is a rename within one
    /// filesystem rather than a copy across two.
    ///
    /// @param instance the instance
    /// @return the staging directory
    /// @throws DshException when the identifier is not usable as a path segment
    private static Path stagingDirectory(DshInstance instance) throws DshException {
        return instance.instanceDirectory().resolve("dsh.installing");
    }

    /// Removes a copy that was being installed and did not finish.
    ///
    /// @param instance the instance whose partial install to remove
    public static void discardPartial(DshInstance instance) {
        try {
            Path staging = stagingDirectory(instance);
            if (Files.exists(staging)) {
                FileUtils.deleteDirectory(staging);
                LOG.info("Removed the partial install for " + instance.id());
            }
        } catch (IOException | DshException e) {
            LOG.warning("Could not remove the partial install for " + instance.id(), e);
        }
    }

    /// Holds an instance's boot library to a different release.
    ///
    /// The pairing is not a preference — the two are published together, and a
    /// release whose libraries disagree with it fails at import rather than
    /// degrading — so this is written only when someone asked for it, and the
    /// caller warns first.
    ///
    /// The choice belongs to the instance, because the runtime it changes belongs
    /// to the instance: it is installed into the instance's own copy and reaches
    /// nothing else.
    ///
    /// @param instance the instance to change
    /// @param appBoot  the boot library version to hold it to
    /// @throws DshException when the manifest cannot be written or pnpm fails
    public static void overrideAppBoot(DshInstance instance, String appBoot) throws DshException {
        overrideAppBoot(instance, appBoot, null);
    }

    /// Holds an instance's copy to a boot library version, reporting the install.
    ///
    /// @param instance the instance to change
    /// @param appBoot  the boot library version to hold it to
    /// @param onLine   receives the package manager's output, or `null`
    /// @throws DshException when the manifest cannot be written or pnpm fails
    public static void overrideAppBoot(DshInstance instance, String appBoot,
                                       @Nullable Consumer<String> onLine) throws DshException {
        Path target = instance.dshDirectory();
        if (!Files.isDirectory(target)) {
            throw new DshException("Instance " + instance.id() + " has no DeepSeek Harness to change");
        }

        DshNodeRuntime runtime = requireRuntime();
        if (!runtime.canManagePlugins()) {
            throw new DshException("pnpm was not found on PATH; changing the boot library requires it");
        }

        writeManifest(target, instance.version(), appBoot);

        List<String> command = buildInstallCommand(runtime, target);

        LOG.info("Holding " + instance.id() + " to boot library " + appBoot);
        int exitCode;
        try {
            exitCode = DshCommand.run(command, null, onLine).exitCode();
        } catch (IOException e) {
            throw new DshException("Failed to run pnpm", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DshException("The boot library change was interrupted", e);
        }
        if (exitCode != 0) {
            throw new DshException("pnpm exited with code " + exitCode
                    + " while changing the boot library of " + instance.id());
        }
    }

    /// Returns the boot library version an instance is held to.
    ///
    /// The pin is read back from where it was written, because that is the
    /// instance's own record of the choice: asking pnpm which copy it linked
    /// would mean reproducing its store layout, and the version that matters to
    /// the page is the one the instance asks for.
    ///
    /// @param instance the instance
    /// @return the version, or `null` when the instance has no pin to read
    public static @Nullable String readAppBoot(DshInstance instance) {
        Path prefix;
        try {
            prefix = instance.dshDirectory();
        } catch (DshException e) {
            return null;
        }

        Path workspace = prefix.resolve("pnpm-workspace.yaml");
        if (Files.isRegularFile(workspace)) {
            try {
                for (String line : Files.readAllLines(workspace)) {
                    if (line.contains(APP_BOOT_PACKAGE)) {
                        String version = line.substring(line.indexOf(APP_BOOT_PACKAGE) + APP_BOOT_PACKAGE.length());
                        version = version.replaceFirst("^['\"]?\\s*:\\s*", "").trim().replaceAll("^['\"]|['\"]$", "");
                        if (!version.isEmpty()) {
                            return version;
                        }
                    }
                }
            } catch (IOException e) {
                LOG.warning("Failed to read the boot library pin of " + instance.id(), e);
            }
        }

        Path manifest = prefix.resolve("package.json");
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            JsonElement overrides = root.get("overrides");
            if (overrides == null || !overrides.isJsonObject()) {
                return null;
            }
            JsonElement version = overrides.getAsJsonObject().get(APP_BOOT_PACKAGE);
            return version == null || !version.isJsonPrimitive() ? null : version.getAsString();
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to read the boot library pin of " + instance.id(), e);
            return null;
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

    /// Installs the DeepSeek Harness an instance runs, into that instance.
    ///
    /// The install is staged inside the instance and moved into place on success,
    /// so an interrupted install can never look like a usable runtime.
    ///
    /// @param instance the instance to install for
    /// @param onLine   a consumer notified of pnpm output lines, or `null`
    /// @throws DshException when the runtime is missing or pnpm fails
    public static void install(DshInstance instance, @Nullable Consumer<String> onLine) throws DshException {
        install(instance, instance.version(), onLine);
    }

    /// Installs a version into an instance, which need not be the version it
    /// currently records.
    ///
    /// The two differ while an instance is being moved to another version: the
    /// new runtime has to be in place before the record can name it.
    ///
    /// @param instance the instance to install into
    /// @param version  the version to install
    /// @param onLine   a consumer notified of pnpm output lines, or `null`
    /// @throws DshException when the runtime is missing or pnpm fails
    public static void install(DshInstance instance, String version,
                               @Nullable Consumer<String> onLine) throws DshException {
        Path target = instance.dshDirectory();
        Path staging = stagingDirectory(instance);

        DshNodeRuntime runtime = requireRuntime();
        if (!runtime.canManagePlugins()) {
            throw new DshException("pnpm was not found on PATH; installing DeepSeek Harness requires it");
        }

        try {
            if (Files.exists(staging)) {
                FileUtils.deleteDirectory(staging);
            }
            Files.createDirectories(staging);
        } catch (IOException e) {
            throw new DshException("Failed to prepare " + staging, e);
        }

        // The manifest is written before pnpm runs, because pnpm would otherwise
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

        List<String> command = buildInstallCommand(runtime, staging);

        LOG.info("Installing DSH " + version + " for " + instance.id() + ": " + String.join(" ", command));

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
            throw new DshException("pnpm exited with code " + exitCode
                    + " while installing " + version + " for " + instance.id());
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

        LOG.info("Installed DSH " + version + " for " + instance.id() + " into " + target);
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
