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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies what a DSH-PackForge pack carries, and what it refuses to.
///
/// A pack is something people publish, so the filter is the part that matters most: an installed
/// tree, a credential, a key or a nested archive that travelled would be a leak, and one that did
/// not travel would only be a smaller pack. The container's shape matters too — the marker at the
/// root and the files under `overrides/` are what an importer looks for.
class DshPackForgeTest {
    @Test
    void whatNeverTravels() {
        // Installed trees and their lock files.
        assertEquals("name", DshPackForge.exclusionOf("node_modules/dshmarket/package.json"));
        assertEquals("name", DshPackForge.exclusionOf("dist/index.js"));
        assertEquals("name", DshPackForge.exclusionOf("pnpm-lock.yaml".equals("x") ? "x" : "package-lock.json"));

        // Credentials, by name and by shape.
        assertEquals("name", DshPackForge.exclusionOf(".env"));
        assertEquals("credentials", DshPackForge.exclusionOf(".env.local"));
        assertEquals("credentials", DshPackForge.exclusionOf("plugins/credentials.yaml"));
        assertEquals("credentials", DshPackForge.exclusionOf("id_rsa"));
        assertEquals("credentials", DshPackForge.exclusionOf("secrets.json"));
        assertEquals("credentials", DshPackForge.exclusionOf("my-token.txt"));
        assertEquals("credentials", DshPackForge.exclusionOf("OPENAI_API_KEY"));

        // Keys by extension.
        assertEquals("extension", DshPackForge.exclusionOf("server.key"));
        assertEquals("extension", DshPackForge.exclusionOf("cert.pem"));

        // Archives, whatever they are.
        assertEquals("archive", DshPackForge.exclusionOf("plugin.tgz"));
        assertEquals("archive", DshPackForge.exclusionOf("nested.zip"));
        assertEquals("archive", DshPackForge.exclusionOf("other.dspack"));

        // Runtime directories that belong to the home rather than to a profile.
        assertEquals("runtime", DshPackForge.exclusionOf("attachments/aa/bb"));
        assertEquals("runtime", DshPackForge.exclusionOf("profiles/web/package.json"));

        // What does travel.
        assertNull(DshPackForge.exclusionOf("cordis.patch.yml"));
        assertNull(DshPackForge.exclusionOf("package.json"));
        assertNull(DshPackForge.exclusionOf("skills/my-skill/SKILL.md"));
    }

    @Test
    void aScanSkipsSymlinksAndSaysWhy(@TempDir Path profile) throws Exception {
        Files.writeString(profile.resolve("package.json"), "{}");
        Files.writeString(profile.resolve("cordis.patch.yml"), "[]\n");
        Files.createDirectories(profile.resolve("node_modules/dshmarket"));
        Files.writeString(profile.resolve("node_modules/dshmarket/package.json"), "{}");
        Files.writeString(profile.resolve("secret.pem"), "not really a key");
        try {
            Files.createSymbolicLink(profile.resolve("link"), profile.resolve("package.json"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // A filesystem without links cannot test that part.
        }

        DshPackForge.Scan scan = DshPackForge.scan(profile);

        assertEquals(java.util.List.of("cordis.patch.yml", "package.json"),
                scan.files().stream().map(DshPackForge.Entry::relative).toList());
        assertEquals("name", scan.excluded().get("node_modules/"));
        assertEquals("extension", scan.excluded().get("secret.pem"));
        if (Files.exists(profile.resolve("link"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            assertEquals("symlink", scan.excluded().get("link"));
        }
    }

    @Test
    void theManifestSaysWhatTheSpecificationRequires() throws Exception {
        DshInstance instance = DshInstanceManager.find("packforge-test");
        Path home = Files.createTempDirectory("packforge-home");
        if (instance == null) {
            instance = DshInstanceManager.create("packforge-test", "0.1.6-alpha.2",
                    DshInstance.DEFAULT_PROFILE, home, DshHomeMode.ISOLATED, null,
                    java.util.List.of(), java.util.Map.of());
        }
        Path profile = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("package.json"), """
                {"name":"dsh-profile-web","private":true,
                 "dependencies":{"dshmarket":"1.52.0"},
                 "dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","dshmarket"]}}}
                """);

        try {
        JsonObject manifest = DshPackForge.buildManifest(instance,
                new DshPackForge.Options("My Pack", "2.1.0", "我的整合包", "a description", "someone"),
                null);

        assertEquals(5, manifest.get("manifestVersion").getAsInt());
        assertEquals("profile", manifest.get("type").getAsString());
        assertEquals("my-pack", manifest.get("name").getAsString(), "names have to be kebab-case");
        assertEquals("2.1.0", manifest.get("version").getAsString());
        assertEquals("我的整合包", manifest.get("displayName").getAsString());
        assertEquals(instance.version(), manifest.get("dshVersion").getAsString(),
                "the harness version is exact");
        assertEquals(instance.profile(), manifest.get("profileName").getAsString());
        assertEquals(java.util.List.of("@deepseek-ai/dsh-base", "dshmarket"),
                manifest.getAsJsonArray("bundles").asList().stream()
                        .map(com.google.gson.JsonElement::getAsString).toList(),
                "the bundle order is what the profile loads in");
        assertEquals("1.52.0", manifest.getAsJsonObject("dependencies").get("dshmarket").getAsString());

        } finally {
            DshInstanceManager.delete("packforge-test");
        }
    }

    /// The profile's own files sit at the root, and everything else under `overrides/`.
    ///
    /// The two places are not a style: the installer copies the root files **before** it resolves
    /// the dependencies and the overrides afterwards, so a `package.json` written under
    /// `overrides/` arrives after the install that needed it — the pack then installs cleanly and
    /// boots with none of its plugins.
    ///
    /// @throws Exception when the pack cannot be written, read, or landed
    @Test
    void theProfilesOwnFilesSitAtTheRootAndTheRestUnderOverrides() throws Exception {
        String id = "packforge-layout";
        DshInstance instance = DshInstanceManager.find(id);
        if (instance == null) {
            instance = DshInstanceManager.create(id, "0.1.6-alpha.2",
                    DshInstance.DEFAULT_PROFILE, Files.createTempDirectory("packforge-layout-home"),
                    DshHomeMode.ISOLATED, null, java.util.List.of(), java.util.Map.of());
        }
        Path profile = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("package.json"), """
                {"name":"dsh-profile-web","private":true,
                 "dependencies":{"dshmarket":"1.52.0"},
                 "dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","dshmarket"]}}}
                """);
        Files.writeString(profile.resolve("pnpm-lock.yaml"), "lockfileVersion: '9.0'\n");
        Files.writeString(profile.resolve("cordis.patch.yml"), "- id: title\n");

        Path pack = Files.createTempFile("packforge-layout", ".dspack");
        try {
            DshPackForge.export(instance, pack, DshPackForge.Options.of(instance), null);

            java.util.Set<String> members = new java.util.TreeSet<>();
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(pack.toFile())) {
                zip.stream().map(java.util.zip.ZipEntry::getName).forEach(members::add);
            }
            assertTrue(members.contains("package.json"),
                    "the manifest belongs at the root, where the resolve reads it: " + members);
            assertTrue(members.contains("pnpm-lock.yaml"), "and so does the lock file");
            assertTrue(members.contains("overrides/cordis.patch.yml"),
                    "the patch is composition, so it is an override");
            assertFalse(members.contains("overrides/package.json"),
                    "a manifest under overrides lands after the install that needed it");

            // And what the installer does with each place, in the order it does it.
            Path destination = Files.createTempDirectory("packforge-landed");
            DshPackInstaller.Landed machine = DshPackInstaller.land(pack, destination,
                    Files.createTempDirectory("packforge-target-home"),
                    java.util.EnumSet.of(DshPackInstaller.Part.MACHINE));
            assertEquals(2, machine.machine(), "the pack carries two machine files");
            assertTrue(Files.isRegularFile(destination.resolve("package.json")),
                    "they land first, because the dependency install reads them");
            assertFalse(Files.exists(destination.resolve("cordis.patch.yml")),
                    "and the overrides do not land with them");

            DshPackInstaller.land(pack, destination, null,
                    java.util.EnumSet.of(DshPackInstaller.Part.OVERRIDES));
            assertTrue(Files.isRegularFile(destination.resolve("cordis.patch.yml")),
                    "the composition lands afterwards, on top of what the install wrote");
        } finally {
            Files.deleteIfExists(pack);
            DshInstanceManager.delete(id);
        }
    }

    @Test
    void aNpmVersionNeedsNoResolving() throws Exception {
        assertEquals("1.2.3", DshPackForge.pin("anything", "1.2.3", null));
        assertEquals("^1.2.3", DshPackForge.pin("anything", "^1.2.3", null));
    }

    @Test
    void hashingMatchesWhatAPublisherWouldWrite(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pack.dspack");
        Files.writeString(file, "hello");
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                DshPackForge.sha256(file));
        assertEquals("pack", DshPackForge.Options.kebab(""),
                "an instance whose name has nothing usable still gets an identifier");
    }
}
