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
import com.google.gson.JsonPrimitive;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// A description of an instance that is enough to build it again elsewhere.
///
/// The original's modpack is a manifest plus the files that go with it: what to
/// download, and what to copy in afterwards. This is that for an instance of
/// DeepSeek Harness, and it is deliberately *configuration only* — the version of
/// the harness, the boot library it is paired with, the profile's plugin list in
/// its load order, and the profile's own patch layer. Nothing installed travels:
/// an installed tree is a few hundred megabytes of packages resolved for one
/// machine's platform and collected by one machine's package-manager store, and a
/// released Desktop build only gets away with shipping one because it vendors
/// integrity-recorded archives and filters them per platform. What is reproducible
/// is the *recipe*, and the recipe is a few kilobytes.
///
/// The plugin list is the part worth being careful about, because the profile's
/// bundle list is the order its patch layers are applied in: a later bundle
/// overrides an earlier one's rows, and a patch replaces a row's whole
/// configuration rather than merging into it. So the order is recorded exactly as
/// it is, and restored by writing the profile manifest and then letting pnpm
/// resolve the whole list in one run — installing bundles one at a time would
/// append each at the end and lose the order the pack was made with.
///
/// Credentials never travel. The harness keeps provider keys in `.credentials.yaml`
/// beside the sessions, and this reads nothing from there; the profile's patch
/// layer is included because it is composition, and the launcher says so when it
/// writes a pack, since a patch is a file a person can put anything into.
@NotNullByDefault
public final class DshModpacks {
    /// What a pack says it is.
    public static final String FORMAT = "hdsl-modpack";

    /// The format's version.
    public static final int FORMAT_VERSION = 1;

    /// The manifest's name inside the archive.
    public static final String MANIFEST = "manifest.json";

    /// The profile patch layer's name inside the archive.
    private static final String PATCH = "cordis.patch.yml";

    private DshModpacks() {
    }

    /// One plugin the pack names.
    ///
    /// @param name    the package name
    /// @param version the declared version, or the path a locally installed plugin
    ///                was installed from
    /// @param active  whether the package is in the profile's bundle list
    /// @param local   whether the plugin was installed from a file this instance
    ///                keeps, and so cannot be fetched by anyone else
    public record Plugin(String name, String version, boolean active, boolean local) {
    }

    /// What a pack describes.
    ///
    /// @param format     the format identifier
    /// @param version    the format version
    /// @param createdAt  when the pack was written
    /// @param instanceId the instance it came from
    /// @param dshVersion the DeepSeek Harness version it pins
    /// @param appBoot    the boot library it is paired with, or `null` for the same version
    /// @param profile    the profile the plugins belong to
    /// @param plugins    the plugins, in the profile manifest's order
    /// @param bundles    the active bundle list, in load order
    /// @param hasPatch   whether the pack carries the profile's patch layer
    public record Manifest(String format, int version, String createdAt, String instanceId,
                           String dshVersion, @Nullable String appBoot, String profile,
                           List<Plugin> plugins, List<String> bundles, boolean hasPatch) {

        /// Returns the plugins that should be installed, in the order they are
        /// listed in.
        ///
        /// A pack can name a package that is not in the bundle list — an installed
        /// but switched-off plugin. It is still installed, because that is what
        /// the instance it came from had; whether the harness then contributes its
        /// layer is decided by the bundle list, which is what the pack restores
        /// separately.
        ///
        /// @return the installation specifications, in order
        public List<String> installSpecs() {
            List<String> specs = new ArrayList<>();
            for (Plugin plugin : plugins) {
                if (plugin.local()) {
                    continue;
                }
                specs.add(plugin.version() == null || plugin.version().isBlank()
                        ? plugin.name() : plugin.name() + "@" + plugin.version());
            }
            return specs;
        }

        /// Returns the plugins that were installed from a file rather than fetched.
        ///
        /// A profile records the path it installed a local plugin from, which is a
        /// path inside the instance that had it; another instance cannot install
        /// from it, and a pack that pretended otherwise would describe an
        /// installation that cannot be made.
        ///
        /// @return the local plugins
        public List<Plugin> localPlugins() {
            return plugins.stream().filter(Plugin::local).toList();
        }
    }

    /// What an export wrote.
    ///
    /// @param plugins how many plugins were recorded
    /// @param bytes   the pack's size
    public record ExportResult(int plugins, long bytes) {
    }

    /// What a load restored.
    ///
    /// @param instance   the instance that was built
    /// @param installed  whether the harness itself had to be installed
    /// @param plugins    how many plugins were resolved
    /// @param bundles    how many bundles are active
    public record InstallResult(DshInstance instance, boolean installed, int plugins, int bundles) {
    }

    /// Writes an instance's configuration into a pack.
    ///
    /// @param instance the instance to describe
    /// @param target   the archive to create
    /// @param onStage  receives progress lines, or `null`
    /// @return what was written
    /// @throws DshException when the configuration cannot be read or written
    public static ExportResult export(DshInstance instance, Path target,
                                      @Nullable Consumer<String> onStage) throws DshException {
        Path profileDirectory = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
        Map<String, String> dependencies = DshPluginInstaller.readDependencies(
                instance.homeDirectory(), instance.profile());
        List<String> bundles = DshPluginInstaller.readBundles(instance.homeDirectory(), instance.profile());

        List<Plugin> plugins = new ArrayList<>();
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            plugins.add(new Plugin(entry.getKey(), entry.getValue(), bundles.contains(entry.getKey()),
                    isLocalSpec(entry.getValue())));
        }

        Path patch = profileDirectory.resolve("cordis.patch.yml");
        boolean hasPatch = Files.isRegularFile(patch);
        String appBoot = DshVersionManager.readAppBoot(instance);
        if (appBoot != null && appBoot.equals(instance.version())) {
            // A pin equal to the version is what an instance has by default; a pack
            // does not need to say so, and saying it would make the pack claim a
            // choice nobody made.
            appBoot = null;
        }

        Manifest manifest = new Manifest(FORMAT, FORMAT_VERSION, Instant.now().toString(), instance.id(),
                instance.version(), appBoot, instance.profile(), List.copyOf(plugins), List.copyOf(bundles),
                hasPatch);

        report(onStage, "Recording " + plugins.size() + " plugin(s), " + bundles.size() + " active bundle(s)");
        Path parent = target.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
                if (hasPatch) {
                    byte[] body = Files.readAllBytes(patch);
                    zip.putNextEntry(new ZipEntry(PATCH));
                    zip.write(body);
                    zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry(MANIFEST));
                zip.write(JsonUtils.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new DshException("Failed to write " + target, e);
        }

        long size = sizeOf(target);
        report(onStage, "Wrote a pack describing DeepSeek Harness " + instance.version()
                + (appBoot == null ? "" : " with boot library " + appBoot)
                + ", " + plugins.size() + " plugin(s)");
        LOG.info("Wrote a modpack for " + instance.id() + " to " + target);
        return new ExportResult(plugins.size(), size);
    }

    /// Reads a pack's manifest.
    ///
    /// @param pack the archive
    /// @return the manifest
    /// @throws DshException when the archive holds no manifest
    public static Manifest readManifest(Path pack) throws DshException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (MANIFEST.equals(entry.getName())) {
                    JsonElement parsed = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    if (!parsed.isJsonObject()) {
                        throw new DshException("The pack's manifest is not an object");
                    }
                    return manifestOf(parsed.getAsJsonObject());
                }
            }
        } catch (IOException e) {
            throw new DshException("Failed to read " + pack, e);
        }
        throw new DshException("This file holds no modpack manifest");
    }

    /// Builds an instance from a pack.
    ///
    /// The harness is installed at the version the pack pins, held to the boot
    /// library it was paired with; the profile is then filled with the pack's
    /// plugins at their recorded versions and with its bundle list, in the order
    /// the pack recorded.
    ///
    /// @param pack      the archive
    /// @param id        the id to give the instance
    /// @param workspace the working directory the instance starts sessions in
    /// @param onStage   receives progress lines, or `null`
    /// @return what was built
    /// @throws DshException when the pack cannot be read, the id is taken, or an
    ///                      installation fails
    public static InstallResult install(Path pack, String id, Path workspace,
                                        @Nullable Consumer<String> onStage) throws DshException {
        Manifest manifest = readManifest(pack);
        requireFormat(manifest);

        DshInstance existing = DshInstanceManager.find(id);
        if (existing == null) {
            report(onStage, "Creating instance " + id);
            existing = DshInstanceManager.create(id, manifest.dshVersion(), manifest.profile(), workspace,
                    DshHomeMode.ISOLATED, null, List.of(), Map.of());
        }

        boolean installed = DshVersionManager.isInstalled(existing);
        if (!installed) {
            report(onStage, "Installing DeepSeek Harness " + manifest.dshVersion());
            DshVersionManager.install(existing, manifest.dshVersion(), onStage);
        } else {
            report(onStage, "Instance " + id + " already has its own DeepSeek Harness");
        }

        if (manifest.appBoot() != null && !manifest.appBoot().equals(existing.version())) {
            report(onStage, "Holding it to boot library " + manifest.appBoot());
            DshVersionManager.overrideAppBoot(existing, manifest.appBoot(), onStage);
        }

        int plugins = restoreProfile(existing, manifest, pack, onStage);
        return new InstallResult(existing, !installed, plugins, manifest.bundles().size());
    }

    /// Puts a pack's profile into an instance that already exists.
    ///
    /// The harness's own profile initialization is used — an empty `dsh plugin
    /// install` run creates the profile manifest, the patch template and the
    /// package-manager settings exactly as upstream writes them — and only the two
    /// fields a pack has something to say about are then replaced: the declared
    /// dependencies, at the versions the pack recorded, and the ordered bundle
    /// list. One resolve of that manifest installs everything and leaves the order
    /// alone, because reconciliation preserves the entries a manifest already
    /// lists and only appends what is missing.
    ///
    /// @param instance the instance to fill
    /// @param manifest the pack's manifest
    /// @param pack     the archive, for its patch layer
    /// @param onStage  receives progress lines, or `null`
    /// @return how many plugins were resolved
    /// @throws DshException when the profile cannot be written or the resolve fails
    public static int restoreProfile(DshInstance instance, Manifest manifest, Path pack,
                                     @Nullable Consumer<String> onStage) throws DshException {
        Path profileDirectory = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
        Path manifestFile = profileDirectory.resolve("package.json");

        if (!Files.isRegularFile(manifestFile)) {
            // The harness's own initialization writes the profile manifest, the
            // patch template and the package-manager settings; writing them here
            // would be reproducing upstream's file formats, which is exactly the
            // kind of guessing that has broken an instance before.
            report(onStage, "Initializing profile " + instance.profile());
            DshPluginInstaller.resolve(instance, onStage);
        }

        if (manifest.hasPatch()) {
            Path patch = profileDirectory.resolve("cordis.patch.yml");
            report(onStage, "Restoring the profile's patch layer");
            writePatch(pack, patch);
        }

        if (!manifest.plugins().isEmpty() || !manifest.bundles().isEmpty()) {
            report(onStage, "Writing the pack's plugin list");
            writeProfileManifest(manifestFile, manifest);
            if (!manifest.localPlugins().isEmpty()) {
                report(onStage, "Note: " + manifest.localPlugins().size()
                        + " plugin(s) were installed from a file on the instance this pack came from, and"
                        + " cannot be fetched here: " + String.join(", ",
                                manifest.localPlugins().stream().map(Plugin::name).toList()));
            }
            report(onStage, "Resolving " + manifest.installSpecs().size() + " plugin(s)");
            DshPluginInstaller.resolve(instance, onStage);
        }

        return manifest.plugins().size();
    }

    /// Writes a pack's dependencies and bundle list into a profile manifest.
    ///
    /// Only those two fields are touched: everything else in the file — the
    /// profile's name, and anything a harness version puts there that this launcher
    /// does not know about — is left as it is.
    ///
    /// @param manifestFile the profile's `package.json`
    /// @param pack         the pack's manifest
    /// @throws DshException when the file cannot be read or written
    private static void writeProfileManifest(Path manifestFile, Manifest pack) throws DshException {
        JsonObject manifest;
        try {
            manifest = JsonUtils.fromJsonFile(manifestFile, JsonObject.class);
            if (manifest == null) {
                manifest = new JsonObject();
            }
        } catch (Exception e) {
            throw new DshException("Failed to read " + manifestFile, e);
        }

        JsonObject dependencies = new JsonObject();
        Map<String, String> ordered = new LinkedHashMap<>();
        for (Plugin plugin : pack.plugins()) {
            ordered.put(plugin.name(), plugin.version() == null ? "" : plugin.version());
        }
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            dependencies.addProperty(entry.getKey(), entry.getValue());
        }
        manifest.add("dependencies", dependencies);

        JsonObject dsh = manifest.has("dsh") && manifest.get("dsh").isJsonObject()
                ? manifest.getAsJsonObject("dsh") : new JsonObject();
        JsonObject profile = dsh.has("profile") && dsh.get("profile").isJsonObject()
                ? dsh.getAsJsonObject("profile") : new JsonObject();
        JsonArray bundles = new JsonArray();
        pack.bundles().forEach(bundles::add);
        profile.add("bundles", bundles);
        dsh.add("profile", profile);
        manifest.add("dsh", dsh);

        Path staging = manifestFile.resolveSibling("package.json.pack");
        try {
            Files.writeString(staging, JsonUtils.GSON.toJson(manifest));
            Files.move(staging, manifestFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DshException("Failed to write " + manifestFile, e);
        }
        LOG.info("Restored the plugin list of " + manifestFile.getParent().getFileName()
                + ": " + pack.plugins().size() + " plugin(s), " + pack.bundles().size() + " bundle(s)");
    }

    /// Writes a pack's patch layer into a profile.
    ///
    /// @param pack   the archive
    /// @param target the file to write
    /// @throws DshException when the archive cannot be read or the file written
    private static void writePatch(Path pack, Path target) throws DshException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (PATCH.equals(entry.getName())) {
                    byte[] body = zip.readAllBytes();
                    Files.createDirectories(target.getParent());
                    Path staging = target.resolveSibling("cordis.patch.yml.pack");
                    Files.write(staging, body);
                    Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        } catch (IOException e) {
            throw new DshException("Failed to read " + pack, e);
        }
        throw new DshException("The pack says it carries a patch layer, but does not");
    }

    /// Reads a manifest object.
    ///
    /// @param root the manifest
    /// @return the manifest record
    private static Manifest manifestOf(JsonObject root) {
        List<Plugin> plugins = new ArrayList<>();
        JsonElement list = root.get("plugins");
        if (list != null && list.isJsonArray()) {
            for (JsonElement element : list.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String name = string(object, "name");
                if (name == null) {
                    continue;
                }
                plugins.add(new Plugin(name, string(object, "version"), bool(object, "active"),
                        bool(object, "local")));
            }
        }

        List<String> bundles = new ArrayList<>();
        JsonElement active = root.get("bundles");
        if (active != null && active.isJsonArray()) {
            for (JsonElement element : active.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    bundles.add(element.getAsString());
                }
            }
        }

        return new Manifest(
                string(root, "format") == null ? "" : string(root, "format"),
                integer(root, "version"),
                string(root, "createdAt") == null ? "" : string(root, "createdAt"),
                string(root, "instanceId") == null ? "" : string(root, "instanceId"),
                string(root, "dshVersion") == null ? "" : string(root, "dshVersion"),
                string(root, "appBoot"),
                string(root, "profile") == null ? DshInstance.DEFAULT_PROFILE : string(root, "profile"),
                List.copyOf(plugins), List.copyOf(bundles), bool(root, "hasPatch"));
    }

    /// Reports whether a declared version is really the path of a local file.
    ///
    /// A package manager records a local installation by the specification it was
    /// given, in the field a version would otherwise hold, so a profile that
    /// installed a packed plugin says `file:/…/plugins/x.tgz` where a version
    /// belongs. Reading that as a version would have a pack ask for a package
    /// called `x@file:/…`, which resolves to nothing.
    ///
    /// @param version the declared version
    /// @return whether it is a local installation's path
    static boolean isLocalSpec(@Nullable String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String value = version.trim().toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("file:") || value.startsWith("link:") || value.startsWith("/")
                || value.startsWith("./") || value.startsWith("../");
    }

    /// Refuses a pack this launcher cannot read.
    ///
    /// @param manifest the pack's manifest
    /// @throws DshException when the format or version is not one this reads
    private static void requireFormat(Manifest manifest) throws DshException {
        if (!FORMAT.equals(manifest.format())) {
            throw new DshException("This file is not an instance pack (" + manifest.format() + ")");
        }
        if (manifest.version() > FORMAT_VERSION) {
            throw new DshException("This pack was written by a newer launcher (format " + manifest.version()
                    + "), which this one cannot read");
        }
        if (manifest.dshVersion().isBlank()) {
            throw new DshException("This pack does not say which DeepSeek Harness version it wants");
        }
    }

    /// Returns a member as a primitive, or `null`.
    ///
    /// @param object the object
    /// @param name   the member
    /// @return the value, or `null`
    private static @Nullable JsonElement member(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? null : element;
    }

    /// Returns a string member, or `null`.
    ///
    /// @param object the object
    /// @param name   the member
    /// @return the string, or `null`
    private static @Nullable String string(JsonObject object, String name) {
        JsonElement element = member(object, name);
        return element == null || !((JsonPrimitive) element).isString() ? null : element.getAsString();
    }

    /// Returns a boolean member, or false.
    ///
    /// @param object the object
    /// @param name   the member
    /// @return the value, or false
    private static boolean bool(JsonObject object, String name) {
        JsonElement element = member(object, name);
        if (element == null) {
            return false;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /// Returns an integer member, or zero.
    ///
    /// @param object the object
    /// @param name   the member
    /// @return the number, or zero
    private static int integer(JsonObject object, String name) {
        JsonElement element = member(object, name);
        if (element == null || !((JsonPrimitive) element).isNumber()) {
            return 0;
        }
        return element.getAsInt();
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
}
