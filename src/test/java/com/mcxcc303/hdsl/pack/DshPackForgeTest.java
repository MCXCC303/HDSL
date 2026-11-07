/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
package com.mcxcc303.hdsl.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.instance.InstanceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies what a DSH-PackForge pack carries, and what it refuses to.
 */
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
		} catch (UnsupportedOperationException | IOException e) {
			// A filesystem without links cannot test that part.
		}

		DshPackForge.Scan scan = DshPackForge.scan(profile);

		assertEquals(List.of("cordis.patch.yml", "package.json"),
		             scan.files().stream().map(DshPackForge.Entry::relative).toList());
		assertEquals("name", scan.excluded().get("node_modules/"));
		assertEquals("extension", scan.excluded().get("secret.pem"));
		if (Files.exists(profile.resolve("link"), LinkOption.NOFOLLOW_LINKS)) {
			assertEquals("symlink", scan.excluded().get("link"));
		}
	}

	@Test
	void theManifestSaysWhatTheSpecificationRequires() throws Exception {
		DshInstance instance = InstanceStore.find("packforge-test");
		Path home = Files.createTempDirectory("packforge-home");
		if (instance == null) {
			instance = DshInstanceManager.create("packforge-test",
			                                     "0.1.6-alpha.2",
			                                     DshInstance.DEFAULT_PROFILE,
			                                     home,
			                                     DshHomeMode.ISOLATED,
			                                     null,
			                                     List.of(),
			                                     Map.of());
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
			                                                 new DshPackForge.Options("My Pack",
			                                                                          "2.1.0",
			                                                                          "我的整合包",
			                                                                          "a description",
			                                                                          "someone"),
			                                                 null);

			assertEquals(5, manifest.get("manifestVersion").getAsInt());
			assertEquals("profile", manifest.get("type").getAsString());
			assertEquals("my-pack", manifest.get("name").getAsString(), "names have to be kebab-case");
			assertEquals("2.1.0", manifest.get("version").getAsString());
			assertEquals("我的整合包", manifest.get("displayName").getAsString());
			assertEquals(instance.version(), manifest.get("dshVersion").getAsString(), "the harness version is exact");
			assertEquals(instance.profile(), manifest.get("profileName").getAsString());
			assertEquals(List.of("@deepseek-ai/dsh-base", "dshmarket"),
			             manifest.getAsJsonArray("bundles").asList().stream().map(JsonElement::getAsString).toList(),
			             "the bundle order is what the profile loads in");
			assertEquals("1.52.0", manifest.getAsJsonObject("dependencies").get("dshmarket").getAsString());

		} finally {
			DshInstanceManager.delete("packforge-test");
		}
	}

	@Test
	void theProfilesOwnFilesSitAtTheRootAndTheRestUnderOverrides() throws Exception {
		String id = "packforge-layout";
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			instance = DshInstanceManager.create(id,
			                                     "0.1.6-alpha.2",
			                                     DshInstance.DEFAULT_PROFILE,
			                                     Files.createTempDirectory("packforge-layout-home"),
			                                     DshHomeMode.ISOLATED,
			                                     null,
			                                     List.of(),
			                                     Map.of());
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

			Set<String> members = new TreeSet<>();
			try (ZipFile zip = new ZipFile(pack.toFile())) {
				zip.stream().map(ZipEntry::getName).forEach(members::add);
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
			DspackLander.Landed machine = DspackLander.land(pack,
			                                                destination,
			                                                Files.createTempDirectory("packforge-target-home"),
			                                                EnumSet.of(DspackLander.Part.MACHINE));
			assertEquals(2, machine.machine(), "the pack carries two machine files");
			assertTrue(Files.isRegularFile(destination.resolve("package.json")),
			           "they land first, because the dependency install reads them");
			assertFalse(Files.exists(destination.resolve("cordis.patch.yml")),
			            "and the overrides do not land with them");

			DspackLander.land(pack, destination, null, EnumSet.of(DspackLander.Part.OVERRIDES));
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
		assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", DshPackForge.sha256(file));
		assertEquals("pack",
		             DshPackForge.Options.kebab(""),
		             "an instance whose name has nothing usable still gets an identifier");
	}
}
