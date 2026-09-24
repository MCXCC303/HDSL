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

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Fetches a pack from the market and puts it where it belongs.
///
/// Three steps, and the specification is explicit that none of them may be skipped:
///
/// 1. **Download to a temporary file**, never straight into a profile. A half-written pack in a
///    profile is a broken installation; a half-written pack in the cache is a file to delete.
/// 2. **Check the length** against the index's `size`. This is the cheap check, and it catches the
///    common failure — a truncated transfer — before the expensive one.
/// 3. **Check the digest** against the index's `sha256`, byte for byte. A pack that fails either
///    check is **discarded**, not kept and reported: what the index described is the only thing worth
///    installing, and a file that is not it has no use.
///
/// The three values are the index's *pointers*, and they are the whole reason the market step and the
/// install step can be spoken of separately: the market's job ends by handing over a verified file.
///
/// ## Untrusted archives
///
/// A pack is somebody else's archive. Everything here treats its member names as hostile until shown
/// otherwise: an entry whose path climbs out of the destination is refused, an entry naming a file the
/// launcher must never accept is refused, and an entry that is a symbolic link is dropped rather than
/// followed. The export side already filters all of this, but a pack from the market was not
/// necessarily made by this launcher — and a previous bug in this very project (`sessions/../planted/x`
/// in a session pack) is why the rule is "never trust a member name".
@NotNullByDefault
public final class DshPackInstaller {
    /// The container's marker file, at the archive's root.
    private static final String MARKER = "dspack.json";

    /// The container format the marker must name.
    private static final String FORMAT = "dspack";

    /// The container version this launcher writes and reads.
    private static final int CURRENT_CONTAINER = 3;

    /// The container version still accepted, which carries manifest v4.
    private static final int OLD_CONTAINER = 2;

    /// Names a pack may not write, whatever it says about itself.
    ///
    /// The same list the export side refuses, kept here as well: the two sides guard different
    /// things — the exporter guards the author's secrets from being published, and this guards the
    /// person's machine from being written to — so neither can rely on the other having run.
    private static final List<String> DENY_NAMES = List.of(
            ".env", ".netrc", ".pypirc", ".npmrc", ".yarnrc", ".yarnrc.yml",
            "credentials.yaml", ".credentials.yaml", "id_rsa", "id_ed25519", "id_ecdsa", "id_ed448");

    /// Extensions a pack may not write.
    private static final List<String> DENY_EXTENSIONS = List.of(
            ".key", ".pem", ".p12", ".pfx", ".jks", ".keystore");

    private DshPackInstaller() {
    }

    /// What an archive turned out to be.
    ///
    /// @param containerVersion the container's own version
    /// @param manifestVersion  the manifest version the container carries
    /// @param manifest         the pack's manifest
    /// @param wholeHome        whether the pack replaces an entire `$DSH_HOME`
    public record Container(int containerVersion, int manifestVersion, JsonObject manifest,
                            boolean wholeHome) {

        /// Returns a string field of the manifest.
        ///
        /// @param key the field
        /// @return the text, or `null`
        public @Nullable String text(String key) {
            return manifest.has(key) && manifest.get(key).isJsonPrimitive()
                    ? manifest.get(key).getAsString() : null;
        }

        /// Returns what the pack is called.
        ///
        /// @param fallback what to answer when the manifest does not say
        /// @return the name
        public String name(String fallback) {
            String name = text("displayName");
            return name == null || name.isBlank() ? fallback : name;
        }

        /// Returns the profile the pack wants to be installed as.
        ///
        /// @param fallback what to answer when the manifest does not say
        /// @return the profile name
        public String profileName(String fallback) {
            String name = text("profileName");
            return name == null || name.isBlank() ? fallback : name;
        }
    }

    /// Where packs are kept between being downloaded and being installed.
    ///
    /// @return the directory
    public static Path cacheDirectory() {
        return DshPluginCatalog.cacheDirectory().resolve("packs");
    }

    /// Returns the file a pack's archive is kept in.
    ///
    /// Named from the pack's id and version rather than from its display name: the display name is
    /// the author's to change and may hold anything, and this has to be a file name.
    ///
    /// @param entry the pack
    /// @return the path
    public static Path archiveFile(DshPackMarket.Entry entry) {
        String safe = entry.id().replaceAll("[^A-Za-z0-9._-]", "-") + "-"
                + entry.version().replaceAll("[^A-Za-z0-9._-]", "-");
        return cacheDirectory().resolve(safe + ".dspack");
    }

    /// Fetches a pack into the cache and checks it against the index.
    ///
    /// The download goes to a file beside the target and is moved over it only once it has been
    /// verified, so an interrupted transfer cannot leave a file that looks like a fetched pack. The
    /// digest is computed **as the bytes arrive** rather than by reading the file again: the same
    /// work, done once, on the pass that is already touching every byte.
    ///
    /// Redirects are followed, and that is not optional here: the ecosystem's archives are GitHub
    /// release assets, and a release asset is a redirect to a storage host. A client that did not
    /// follow them would download a three-hundred-byte HTML page and then report a digest mismatch.
    ///
    /// @param entry  the pack
    /// @param report receives progress lines, or `null`
    /// @return the verified archive
    /// @throws DshException when the pack cannot be fetched or is not what the index described
    public static Path download(DshPackMarket.Entry entry,
                                @Nullable java.util.function.Consumer<String> report) throws DshException {
        Path target = archiveFile(entry);
        if (isCached(entry)) {
            return target;
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new DshException("Could not create " + target.getParent(), e);
        }
        Path staging = target.resolveSibling(target.getFileName() + ".hdsl-downloading");

        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(entry.downloadUrl()))
                    .timeout(java.time.Duration.ofMinutes(10))
                    .header("Accept", "application/octet-stream")
                    .GET()
                    .build();
            try (java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(20))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build()) {
                java.net.http.HttpResponse<InputStream> response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new DshException("The market's address for this pack answered HTTP "
                            + response.statusCode(), null);
                }
                java.security.MessageDigest digest = DigestUtils.getDigest("SHA-256");
                long written = 0;
                try (InputStream body = response.body();
                     java.io.OutputStream out = Files.newOutputStream(staging,
                             java.nio.file.StandardOpenOption.CREATE,
                             java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = body.read(buffer)) >= 0) {
                        out.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        written += read;
                        if (report != null && entry.size() > 0 && written % (1024 * 1024) < buffer.length) {
                            report.accept("Downloaded " + (written / 1024) + " KB of "
                                    + (entry.size() / 1024) + " KB");
                        }
                    }
                }
                String actual = hex(digest.digest());
                if (!entry.sha256().equalsIgnoreCase(actual)) {
                    throw new DshException("The pack does not match the market's SHA-256 (expected "
                            + entry.sha256() + ", got " + actual + ")", null);
                }
            }
            // Moved into place only now: everything before this line was a file that could be thrown
            // away, and the name the rest of the launcher reads is only ever the verified one.
            Files.move(staging, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            verify(entry, target);
            return target;
        } catch (IOException e) {
            throw new DshException("Could not fetch the pack: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DshException("The download was interrupted", e);
        } finally {
            // A failed download leaves nothing behind, not even the half-written file: the cache is
            // the launcher's own directory, and littering it is how a cache becomes a mess.
            try {
                Files.deleteIfExists(staging);
            } catch (IOException e) {
                LOG.warning("Could not remove the partly downloaded " + staging, e);
            }
        }
    }

    /// Writes a digest as lowercase hex.
    ///
    /// @param bytes the digest
    /// @return the text
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(String.format("%02x", value));
        }
        return text.toString();
    }

    /// Reports whether a pack has already been fetched and verified.
    ///
    /// Checked by digest rather than by presence: a file of the right name that is not the right
    /// bytes is worse than no file, because it would be installed without being fetched again.
    ///
    /// @param entry the pack
    /// @return whether the archive is already here and is the one the index described
    public static boolean isCached(DshPackMarket.Entry entry) {
        Path archive = archiveFile(entry);
        try {
            if (!Files.isRegularFile(archive) || Files.size(archive) != entry.size()) {
                return false;
            }
            return entry.sha256().equalsIgnoreCase(
                    DigestUtils.digestToString("SHA-256", archive));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /// Checks a downloaded archive against the two pointers the index gave.
    ///
    /// @param entry   the pack
    /// @param archive the file
    /// @throws DshException when it is not what the index described
    public static void verify(DshPackMarket.Entry entry, Path archive) throws DshException {
        long length;
        try {
            length = Files.size(archive);
        } catch (IOException e) {
            throw new DshException("Could not read " + archive, e);
        }
        // The length first: it is one call, and a truncated transfer is the failure that actually
        // happens. Reporting "the digest does not match" for a file that is obviously half there
        // sends somebody looking for a forger when the network dropped.
        if (entry.size() > 0 && length != entry.size()) {
            throw new DshException("The pack is " + length + " bytes and the market says it is "
                    + entry.size() + " bytes, so it did not arrive whole", null);
        }
        String actual;
        try {
            actual = DigestUtils.digestToString("SHA-256", archive);
        } catch (IOException e) {
            throw new DshException("Could not read " + archive, e);
        }
        if (!entry.sha256().equalsIgnoreCase(actual)) {
            throw new DshException("The pack does not match the market's SHA-256 (expected "
                    + entry.sha256() + ", got " + actual + ")", null);
        }
    }

    /// Works out what an archive is.
    ///
    /// The container is recognised by its marker at the archive root, and the container's own version
    /// is what decides which manifest versions are expected — **not** the manifest's own
    /// `manifestVersion` field, which an older writer set inconsistently. The specification's table:
    ///
    /// | marker | manifest | action |
    /// |---|---|---|
    /// | none | — | not a pack (a plain ZIP, or a broken file) |
    /// | `version` 3 | 5 | current |
    /// | `version` 2 | 4 | still read |
    /// | anything else | — | refused, by version |
    ///
    /// @param archive the file
    /// @return what it is
    /// @throws DshException when it is not a pack this launcher can install
    public static Container identify(Path archive) throws DshException {
        JsonObject marker;
        JsonObject manifest;
        int containerVersion;
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            marker = readJson(zip, MARKER);
            if (marker == null) {
                // Not a refusal of the *format*: a ZIP with no marker is simply not a pack, and the
                // person may have picked the wrong file.
                throw new DshException("That archive is not a DeepSeek Harness pack: it has no "
                        + MARKER + " at its root", null);
            }
            if (!FORMAT.equals(text(marker, "format"))) {
                throw new DshException("That archive says it is a "
                        + text(marker, "format") + " archive, not a pack", null);
            }
            Integer version = number(marker, "version");
            if (version == null || (version != CURRENT_CONTAINER && version != OLD_CONTAINER)) {
                throw new DshException("That pack is container version " + version
                        + ", and this launcher reads versions " + OLD_CONTAINER + " and "
                        + CURRENT_CONTAINER, null);
            }
            containerVersion = version;
            manifest = readJson(zip, "manifest.json");
            if (manifest == null) {
                throw new DshException("That pack has no manifest.json", null);
            }
        } catch (IOException e) {
            throw new DshException("Could not read " + archive + ": " + e.getMessage(), e);
        }

        Integer manifestVersion = number(manifest, "manifestVersion");
        if (manifestVersion == null) {
            manifestVersion = 5;
        }
        // The two versions have to be a pair the specification names. A container 3 holding a
        // manifest 4 is a file assembled wrongly — the writer took one half from each format — and
        // installing it would mean guessing which half to believe.
        int expectedContainer = containerFor(manifestVersion);
        if (expectedContainer != containerVersion) {
            throw new DshException("That pack's container is version " + containerVersion
                    + " and its manifest is version " + manifestVersion + ", which do not go together",
                    null);
        }
        // `collection` is reserved by the specification and refused by its validator; the same is
        // done here rather than pretending to understand it.
        String type = text(manifest, "type");
        if ("collection".equalsIgnoreCase(type)) {
            throw new DshException("That pack is a collection, which this launcher cannot install", null);
        }
        return new Container(containerVersion, manifestVersion, manifest,
                "dshhome".equalsIgnoreCase(type));
    }

    /// Returns the container version that carries a manifest version.
    ///
    /// The specification's pair table, and only those two pairs: an unknown manifest version has no
    /// container, which is what makes a mismatch detectable rather than merely unlikely.
    ///
    /// @param manifestVersion the manifest's version
    /// @return the container version, or `-1` when no container carries that manifest
    private static int containerFor(int manifestVersion) {
        return switch (manifestVersion) {
            case 4 -> OLD_CONTAINER;
            case 5 -> CURRENT_CONTAINER;
            default -> -1;
        };
    }

    /// Reads a JSON file out of an archive.
    ///
    /// @param zip the archive
    /// @param name the member's name
    /// @return the object, or `null` when it is not there or is not an object
    private static @Nullable JsonObject readJson(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream stream = zip.getInputStream(entry)) {
            JsonObject object = JsonUtils.fromJson(new String(stream.readAllBytes(),
                    StandardCharsets.UTF_8), JsonObject.class);
            return object;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /// Reads a number out of an object.
    ///
    /// @param object the object
    /// @param key    the field
    /// @return the number, or `null`
    private static @Nullable Integer number(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isNumber()
                ? object.get(key).getAsInt() : null;
    }

    /// Reads a string out of an object.
    ///
    /// @param object the object
    /// @param key    the field
    /// @return the text, or `null`
    private static @Nullable String text(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : null;
    }

    /// What landing a pack's files came to.
    ///
    /// @param files      how many files were written
    /// @param overrides  how many came from `overrides/`
    /// @param home       how many came from `home/`
    /// @param machine    how many were the archive's own machine files
    public record Landed(int files, int overrides, int home, int machine) {
    }

    /// Writes a pack's files into a destination.
    ///
    /// Two places, and which is which is the container's own layout:
    ///
    /// - `overrides/` goes to the **destination root**, which for a `profile` pack is the profile's
    ///   directory and for a `dshhome` pack is the whole home.
    /// - `home/` goes to the home, and is only meaningful for a `profile` pack — a `dshhome` pack
    ///   already covers the home with its overrides.
    ///
    /// The machine files at the archive's root (`package.json`, the lock file, the workspace file)
    /// are copied to the destination root as well. They are *not* part of `overrides/`, because they
    /// describe the pack rather than overriding anything — but a profile without them is not the
    /// profile the pack describes.
    ///
    /// The order is the specification's, and it matters: overrides are landed so that the user's own
    /// files — `cordis.patch.yml` among them — end up on top of whatever a dependency would install.
    ///
    /// @param archive     the pack
    /// @param destination where `overrides/` lands
    /// @param home        where `home/` lands, or `null` for a pack that has none
    /// @return what was written
    /// @throws DshException when the archive cannot be read, or holds a member it may not write
    public static Landed land(Path archive, Path destination, @Nullable Path home) throws DshException {
        return land(archive, destination, home, java.util.EnumSet.allOf(Part.class));
    }

    /// Which of a pack's three sets of files to write.
    ///
    /// They are separable because the specification's order interleaves them with work that is not
    /// file copying: the pack's dependencies are installed **between** its machine files and its
    /// overrides, so that a `cordis.patch.yml` the pack carries lands on top of whatever the
    /// dependency install wrote. Copying all three at once cannot express that order.
    public enum Part {
        /// The archive root's `package.json`, lock file and workspace file.
        MACHINE,
        /// `overrides/`, which lands at the destination root.
        OVERRIDES,
        /// `home/`, which lands at the `DSH_HOME` root.
        HOME
    }

    /// Writes the parts of a pack that were asked for.
    ///
    /// @param archive     the pack
    /// @param destination where `overrides/` lands
    /// @param home        where `home/` lands, or `null` for a pack that has none
    /// @param parts       which sets to write
    /// @return what was written
    /// @throws DshException when the archive cannot be read, or holds a member it may not write
    public static Landed land(Path archive, Path destination, @Nullable Path home,
                              java.util.Set<Part> parts) throws DshException {
        int files = 0;
        int overrides = 0;
        int fromHome = 0;
        int machine = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                // Symbolic links need no special handling here, and that is worth writing down
                // because it looks like an omission. A ZIP stores a link as an entry whose *content*
                // is the target's path and whose Unix mode marks it as a link; `java.util.zip` does
                // not expose the mode, so this cannot tell one from a small text file — and it does
                // not have to. Nothing here calls `Files.createSymbolicLink`, so such an entry is
                // written out as an ordinary file holding the text of a path. The link is never
                // created, and the path check below already refuses a member that would land outside
                // the destination whatever it points at.

                Path target;
                if (name.startsWith("overrides/")) {
                    if (!parts.contains(Part.OVERRIDES)) {
                        continue;
                    }
                    target = resolve(destination, name.substring("overrides/".length()));
                    overrides++;
                } else if (name.startsWith("home/") && home != null) {
                    if (!parts.contains(Part.HOME)) {
                        continue;
                    }
                    target = resolve(home, name.substring("home/".length()));
                    fromHome++;
                } else if (isMachineFile(name)) {
                    if (!parts.contains(Part.MACHINE)) {
                        continue;
                    }
                    target = resolve(destination, name);
                    machine++;
                } else {
                    // `manifest.json`, `dspack.json`, and anything else the pack carries for its own
                    // sake are not part of the installation.
                    continue;
                }

                refuseIfUnsafe(name, target);
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                files++;
            }
        } catch (IOException e) {
            throw new DshException("Could not unpack " + archive + ": " + e.getMessage(), e);
        }
        return new Landed(files, overrides, fromHome, machine);
    }

    /// Returns where a member lands, refusing anything that would leave the destination.
    ///
    /// The check is on the **resolved** path rather than on the name, because a name is what an
    /// attacker controls: `a/../../b` and `a/./../../b` are the same escape written two ways, and
    /// both resolve outside. Normalising first and comparing after is what catches both.
    ///
    /// @param root the directory the member must stay inside
    /// @param name the member's name, already stripped of its prefix
    /// @return where it lands
    /// @throws DshException when it would land outside
    private static Path resolve(Path root, String name) throws DshException {
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(name).normalize();
        if (!target.startsWith(base)) {
            throw new DshException("That pack tries to write outside the folder it is installing "
                    + "into (" + name + ")", null);
        }
        return target;
    }

    /// Refuses a member that names something a pack must never write.
    ///
    /// @param name   the member's name
    /// @param target where it would land
    /// @throws DshException when it may not be written
    private static void refuseIfUnsafe(String name, Path target) throws DshException {
        String file = target.getFileName().toString();
        String lower = file.toLowerCase(java.util.Locale.ROOT);
        if (DENY_NAMES.contains(lower) || lower.startsWith("id_rsa") || lower.startsWith("id_ed25519")
                || lower.startsWith("id_ecdsa") || lower.startsWith("id_ed448")) {
            throw new DshException("That pack tries to write " + file
                    + ", which holds credentials and is never installed from a pack", null);
        }
        for (String extension : DENY_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                throw new DshException("That pack tries to write " + file
                        + ", which is a key file and is never installed from a pack", null);
            }
        }
        // A nested archive is refused too: it is the one member whose contents cannot be checked
        // before they are on the disk, and nothing a pack needs is stored as one.
        for (String extension : List.of(".zip", ".tar", ".tgz", ".gz", ".bz2", ".xz", ".7z", ".rar")) {
            if (lower.endsWith(extension) && !name.startsWith("overrides/plugins/")) {
                throw new DshException("That pack holds a nested archive (" + name
                        + "), which is never installed from a pack", null);
            }
        }
    }

    /// Reports whether a name at the archive's root is one of the pack's machine files.
    ///
    /// @param name the member's name
    /// @return whether it is copied to the destination
    private static boolean isMachineFile(String name) {
        return name.equals("package.json") || name.equals("pnpm-lock.yaml")
                || name.equals("pnpm-workspace.yaml");
    }

    /// Puts a recognised pack into an instance that is already at the right version.
    ///
    /// **The order is the specification's, and each step is in it for a reason** (§3.2.2):
    ///
    /// 1. the pack's own machine files — without them there is no manifest to install from;
    /// 2. the dependency install, which is what turns a list of package names into a profile that
    ///    boots;
    /// 3. `overrides/` and `home/`, **last**, so a `cordis.patch.yml` the pack carries lands on top of
    ///    whatever the dependency install wrote rather than being replaced by it. Landing the
    ///    overrides first — which is what a single pass does — leaves the pack's own patch layer
    ///    overwritten by its own dependencies, and the pack then behaves as though the patch were not
    ///    there.
    ///
    /// @param archive the pack
    /// @param instance the instance to fill
    /// @param report  receives progress lines, or `null`
    /// @return what was written
    /// @throws DshException when the archive cannot be read, or the install fails
    public static Landed installInto(Path archive, DshInstance instance,
                                     @Nullable java.util.function.Consumer<String> report)
            throws DshException {
        Path home = instance.homeDirectory();
        Path profiles = home.resolve("profiles").resolve(instance.profile());

        Landed machine = land(archive, profiles, home, java.util.EnumSet.of(Part.MACHINE));
        if (machine.machine() > 0) {
            say(report, "Installing the pack's dependencies");
        } else {
            // A pack with no machine files leaves the profile to the harness, whose own
            // `dsh plugin install` writes the manifest, the patch template and the package-manager
            // settings exactly as upstream writes them. Writing them here would be reproducing
            // upstream's file formats, which is the kind of guessing that has broken an instance
            // before.
            say(report, "Initializing profile " + instance.profile());
        }
        DshPluginInstaller.resolve(instance, report);

        Landed rest = land(archive, profiles, home,
                java.util.EnumSet.of(Part.OVERRIDES, Part.HOME));

        // A pack that both lists a plugin as a bundle and inserts it applies that plugin twice, and a
        // plugin applied twice cannot claim its routes the second time — the profile then fails to
        // load at all. The duplicate is taken out here: after the patch has landed, so it is the
        // pack's own file being repaired, and before anything tries to boot the profile.
        java.nio.file.Path patch = profiles.resolve("cordis.patch.yml");
        java.util.List<String> keptWhole = DshProfilePatch.dropRedundantInserts(patch,
                DshPluginInstaller.readBundles(home, instance.profile()));
        if (!keptWhole.isEmpty()) {
            say(report, "These are both a bundle and an insert, and carry settings that would be "
                    + "lost if the duplicate were dropped: " + keptWhole);
        }

        return new Landed(machine.files() + rest.files(), rest.overrides(), rest.home(),
                machine.machine());
    }

    /// Says something if anybody is listening.
    ///
    /// @param report where to say it, or `null`
    /// @param line   what to say
    private static void say(@Nullable java.util.function.Consumer<String> report, String line) {
        if (report != null) {
            report.accept(line);
        }
    }

    /// Makes an instance for a pack and installs it into that instance.
    ///
    /// **One method rather than a sequence each caller repeats**, and the reason is a failure that was
    /// left on somebody's disk: the instance is created before the harness can be installed into it —
    /// it has to be, because the runtime goes inside it — so a failure between the two leaves an
    /// instance that is in the list and cannot start. The wizard has always cleaned that up; the two
    /// pages that install a pack from the market and from a file each wrote their own sequence and
    /// neither did, so a failed install left `pokemon` sitting in the list with no harness in it.
    ///
    /// A sequence written twice is a sequence fixed once, so it is written here once instead.
    ///
    /// @param archive the verified pack
    /// @param id      the instance id, which the caller has already made unique
    /// @param profile the profile the pack wants
    /// @param version the harness version the pack pins
    /// @param report  receives progress lines, or `null`
    /// @return the instance, filled in
    /// @throws DshException when the harness or the pack cannot be installed
    public static DshInstance installNew(Path archive, String id, String profile, String version,
                                         @Nullable java.util.function.Consumer<String> report)
            throws DshException {
        DshInstance instance = DshInstanceManager.create(id, version, profile,
                Path.of(System.getProperty("user.home")),
                org.jackhuang.hmcl.dsh.DshHomeMode.ISOLATED, null, List.of(), java.util.Map.of());
        try {
            say(report, "Installing DeepSeek Harness " + version);
            DshVersionManager.install(instance, version, report);

            say(report, "Writing the pack's files into "
                    + instance.homeDirectory().resolve("profiles").resolve(instance.profile()));
            installInto(archive, instance, report);
            return instance;
        } catch (DshException | RuntimeException failed) {
            // What was made for this attempt goes away with it. A half-made instance is worse than no
            // instance: it appears in the list, it can be launched, and it fails for a reason that is
            // no longer on screen.
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "Could not install the pack into " + id + "; removing the instance made for it", failed);
            DshVersionManager.discardPartial(instance);
            try {
                DshInstanceManager.delete(id);
            } catch (DshException | RuntimeException cleanupFailure) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                        "Could not remove the half-made instance " + id, cleanupFailure);
            }
            throw failed;
        }
    }

    /// Lists an archive's members, for a caller that wants to show what a pack holds.
    ///
    /// @param archive the file
    /// @return the names, in the archive's own order
    /// @throws DshException when it cannot be read
    public static List<String> list(Path archive) throws DshException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        } catch (IOException e) {
            throw new DshException("Could not read " + archive, e);
        }
        return List.copyOf(names);
    }
}
