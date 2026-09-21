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
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies what a pack of an instance records, and in which order.
///
/// A pack is configuration: the harness version, the boot library it is paired
/// with, and the profile's plugins. The plugin *order* is the part worth pinning
/// down, because it is the order the harness applies patch layers in — a later
/// bundle overrides an earlier one's rows, and a patch replaces a row's whole
/// configuration — so a pack that loses it does not reproduce the instance it
/// came from, it reproduces a different one.
class DshModpacksTest {
    /// The instance an export is made from.
    private static final String SOURCE_ID = "pack-config-source";

    /// The instance a pack is restored into.
    private static final String TARGET_ID = "pack-config-target";

    /// Removes the instances the tests made.
    @AfterEach
    void removeInstances() {
        for (String id : List.of(SOURCE_ID, TARGET_ID)) {
            try {
                if (DshInstanceManager.find(id) != null) {
                    DshInstanceManager.delete(id);
                }
            } catch (Exception ignored) {
                // The test's own cleanup: a failure here would hide the real one.
            }
        }
    }

    @Test
    void aPackRecordsTheVersionTheBootLibraryAndThePluginOrder() throws Exception {
        DshInstance instance = makeInstance(SOURCE_ID);
        writeProfile(instance, """
                {
                  "name": "dsh-profile-web",
                  "private": true,
                  "dependencies": {
                    "dshmarket": "1.52.0",
                    "dsh-context": "0.54.2"
                  },
                  "dsh": {"profile": {"bundles": [
                    "@deepseek-ai/dsh-base",
                    "@deepseek-ai/dsh-web-app",
                    "dsh-context",
                    "dshmarket"
                  ]}},
                  "somethingUpstreamAdded": {"kept": true}
                }
                """);
        Files.writeString(profileDirectory(instance).resolve("cordis.patch.yml"), "- id: title\n");

        Path pack = Files.createTempFile("modpack", ".zip");
        DshModpacks.ExportResult exported = DshModpacks.export(instance, pack, null);

        assertEquals(2, exported.plugins());
        DshModpacks.Manifest manifest = DshModpacks.readManifest(pack);
        assertEquals(DshModpacks.FORMAT, manifest.format());
        assertEquals("0.1.6-alpha.2", manifest.dshVersion());
        assertEquals(DshInstance.DEFAULT_PROFILE, manifest.profile());
        assertTrue(manifest.hasPatch(), "the profile's patch layer is composition, so it travels");
        assertEquals(List.of("dsh-context", "dshmarket"),
                manifest.bundles().stream().filter(bundle -> !bundle.startsWith("@deepseek-ai/")).toList(),
                "the bundle order is recorded as it is, not sorted");
        assertEquals(List.of("dshmarket", "dsh-context"),
                manifest.plugins().stream().map(DshModpacks.Plugin::name).toList(),
                "the dependencies keep the manifest's own order");
        assertEquals(List.of("dshmarket@1.52.0", "dsh-context@0.54.2"), manifest.installSpecs(),
                "versions are pinned to what the instance had");
        assertEquals(0, manifest.localPlugins().size());

        try (ZipFile zip = new ZipFile(pack.toFile())) {
            assertTrue(zip.getEntry("cordis.patch.yml") != null, "the patch layer is in the archive");
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().contains("node_modules")),
                    "nothing installed travels in a pack");
        }
        Files.deleteIfExists(pack);
    }

    @Test
    void restoringAPacksProfileKeepsItsOrderAndLeavesOtherFieldsAlone() throws Exception {
        DshInstance source = makeInstance(SOURCE_ID);
        writeProfile(source, """
                {
                  "name": "dsh-profile-web",
                  "private": true,
                  "dependencies": {"dshmarket": "1.52.0", "dsh-context": "0.54.2"},
                  "dsh": {"profile": {"bundles": ["dsh-context", "dshmarket"]}},
                  "somethingUpstreamAdded": {"kept": true}
                }
                """);
        Path pack = Files.createTempFile("modpack", ".zip");
        DshModpacks.export(source, pack, null);

        DshInstance target = makeInstance(TARGET_ID);
        writeProfile(target, """
                {
                  "name": "dsh-profile-web",
                  "private": true,
                  "dependencies": {},
                  "dsh": {"profile": {"bundles": ["@deepseek-ai/dsh-base"]}},
                  "somethingUpstreamAdded": {"kept": true}
                }
                """);

        DshModpacks.Manifest manifest = DshModpacks.readManifest(pack);
        // Only the two fields a pack has something to say about are written; the
        // resolve that follows is the harness's own and is exercised by the
        // command line, so it is not run here.
        writeProfileManifestForTest(target, manifest);

        JsonObject written = JsonUtils.fromJsonFile(
                profileDirectory(target).resolve("package.json"), JsonObject.class);
        assertEquals(List.of("dsh-context", "dshmarket"),
                written.getAsJsonObject("dsh").getAsJsonObject("profile")
                        .getAsJsonArray("bundles").asList().stream().map(com.google.gson.JsonElement::getAsString).toList(),
                "the restored bundle list is the pack's order");
        assertEquals("1.52.0", written.getAsJsonObject("dependencies").get("dshmarket").getAsString());
        assertEquals("dsh-profile-web", written.get("name").getAsString());
        assertTrue(written.has("somethingUpstreamAdded"),
                "a field this launcher does not know about is left where it was");
        Files.deleteIfExists(pack);
    }

    @Test
    void aPluginInstalledFromAFileIsRecordedAsLocalRatherThanAsAVersion() throws Exception {
        // The profile stores the specification a local install was made from,
        // which is a path inside the instance that made it. Read as a version, a
        // pack would ask for a package called `x@file:/…`, which resolves to
        // nothing at all.
        DshInstance instance = makeInstance(SOURCE_ID);
        writeProfile(instance, """
                {
                  "name": "dsh-profile-web",
                  "private": true,
                  "dependencies": {
                    "dshmarket": "1.52.0",
                    "dsh-hello-local": "file:/tmp/instance/plugins/dsh-hello-local-1.0.0.tgz"
                  },
                  "dsh": {"profile": {"bundles": ["dsh-hello-local", "dshmarket"]}}
                }
                """);

        Path pack = Files.createTempFile("modpack", ".zip");
        DshModpacks.export(instance, pack, null);
        DshModpacks.Manifest manifest = DshModpacks.readManifest(pack);

        assertEquals(List.of("dshmarket@1.52.0"), manifest.installSpecs(),
                "only what can be fetched is asked for");
        assertEquals(List.of("dsh-hello-local"),
                manifest.localPlugins().stream().map(DshModpacks.Plugin::name).toList(),
                "the local plugin is recorded, so the loader can say it cannot be restored");
        Files.deleteIfExists(pack);
    }

    @Test
    void somethingThatIsNotAPackIsRefused() throws Exception {
        Path notAPack = Files.createTempFile("modpack", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(notAPack))) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("not a pack".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThrows(DshException.class, () -> DshModpacks.readManifest(notAPack));
        Files.deleteIfExists(notAPack);
    }

    @Test
    void aPackThatNamesNoHarnessVersionIsRefused() throws Exception {
        Path pack = Files.createTempFile("modpack", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            zip.putNextEntry(new ZipEntry(DshModpacks.MANIFEST));
            zip.write("""
                    {"format":"hdsl-modpack","version":1,"createdAt":"now","instanceId":"x",
                     "dshVersion":"","profile":"web","plugins":[],"bundles":[]}
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThrows(DshException.class,
                () -> DshModpacks.install(pack, "no-version-pack", Path.of(System.getProperty("user.home")), null));
        Files.deleteIfExists(pack);
    }

    /// Uses the same write the loader uses, without the resolve that follows it.
    ///
    /// @param instance the instance
    /// @param manifest the pack's manifest
    private static void writeProfileManifestForTest(DshInstance instance, DshModpacks.Manifest manifest)
            throws Exception {
        java.lang.reflect.Method method = DshModpacks.class.getDeclaredMethod(
                "writeProfileManifest", Path.class, DshModpacks.Manifest.class);
        method.setAccessible(true);
        method.invoke(null, profileDirectory(instance).resolve("package.json"), manifest);
    }

    /// Returns an instance's profile directory.
    ///
    /// @param instance the instance
    /// @return the directory
    private static Path profileDirectory(DshInstance instance) throws Exception {
        return instance.homeDirectory().resolve("profiles").resolve(instance.profile());
    }

    /// Writes a profile manifest.
    ///
    /// @param instance the instance
    /// @param body     the manifest
    private static void writeProfile(DshInstance instance, String body) throws Exception {
        Path directory = profileDirectory(instance);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("package.json"), body);
    }

    /// Creates an instance whose home the tests can fill.
    ///
    /// @param id the instance id
    /// @return the instance
    private DshInstance makeInstance(String id) throws Exception {
        Path source = Files.createTempDirectory("modpack-home");
        return DshInstanceManager.create(id, "0.1.6-alpha.2", DshInstance.DEFAULT_PROFILE,
                source, DshHomeMode.ISOLATED, null, List.of(), Map.of());
    }
}
