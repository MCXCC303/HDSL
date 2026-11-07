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
package com.mcxcc303.hdsl.instance;

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.launch.DshSessions;
import com.mcxcc303.hdsl.pack.PackExporter;
import com.mcxcc303.hdsl.pack.PackManifest;
import com.mcxcc303.hdsl.skill.DshSkillManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies duplicating an instance by describing it as a pack.
 */
class DshInstanceDuplicateTest {
	private static final String SOURCE_ID = "duplicate-source";
	private static final String TARGET_ID = "duplicate-target";
	private static final String SLUG = "-home-user-project";

	private static DshInstance makeInstance(String id) throws Exception {
		Path workspace = Files.createTempDirectory("duplicate-home");
		return DshInstanceManager.create(id,
		                                 "0.1.6-alpha.2",
		                                 DshInstance.DEFAULT_PROFILE,
		                                 workspace,
		                                 DshHomeMode.ISOLATED,
		                                 null,
		                                 List.of(),
		                                 Map.of());
	}

	private static Path profileDirectory(DshInstance instance) throws Exception {
		return instance.homeDirectory().resolve("profiles").resolve(instance.profile());
	}

	private static void writeProfile(DshInstance instance, String body) throws Exception {
		Path directory = profileDirectory(instance);
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("package.json"), body);
	}

	private static void writeSession(Path home) throws Exception {
		Path directory = home.resolve("sessions").resolve(SLUG).resolve("session-1");
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("session.v4.jsonl.zstd"), "fixture-log");
		Files.writeString(directory.resolve("session.lock"), "");
	}

	@AfterEach
	void removeInstances() {
		for (String id : List.of(SOURCE_ID, TARGET_ID)) {
			try {
				if (InstanceStore.find(id) != null) {
					DshInstanceManager.delete(id);
				}
			} catch (Exception ignored) {
				// The test's own cleanup: a failure here would hide the real one.
			}
		}
	}

	@Test
	void aCopyIsTheConfigurationAndNotTheSessions() throws Exception {
		DshInstance source = makeInstance(SOURCE_ID);
		writeProfile(source, """
				{
				  "name": "dsh-profile-web",
				  "private": true,
				  "dependencies": {"dsh-context": "0.54.2", "dshmarket": "1.52.0"},
				  "dsh": {"profile": {"bundles": ["dsh-context", "dshmarket"]}}
				}
				""");
		Files.writeString(profileDirectory(source).resolve("cordis.patch.yml"), "- id: title\n");
		// A plugin's own settings, which is most of what "the same environment" means for it.
		Files.writeString(source.homeDirectory().resolve("settings.yaml"), """
				dshmarket:
				  registry: https://example.invalid
				""");
		Path skills = DshSkillManager.directory(source.homeDirectory());
		Files.createDirectories(skills.resolve("alpha"));
		Files.writeString(skills.resolve("alpha").resolve("SKILL.md"), "---\nname: alpha\ndescription: First\n---\n");
		writeSession(source.homeDirectory());
		assertFalse(DshSessions.list(source.homeDirectory()).isEmpty(), "the source has something to leave behind");

		Path pack = Files.createTempFile("duplicate", PackManifest.FILE_EXTENSION);
		try {
			// Exactly what a duplicate writes: `export(instance, target, report)` is `Options.of(instance)`.
			PackExporter.export(source, pack, null);
			PackManifest.Manifest manifest = PackManifest.readManifest(pack);

			assertEquals(List.of("dsh-context", "dshmarket"),
			             manifest.bundles(),
			             "the load order the original boots in is the order the copy boots in");
			assertTrue(manifest.hasPatch(), "the patch layer is composition, so the copy gets it");
			assertEquals(List.of("alpha"), manifest.skills(), "the skill packs travel");
			assertEquals(List.of("dshmarket"),
			             manifest.settings(),
			             "a plugin's own settings travel, or the copy is not the same environment");
			assertEquals(0, manifest.sessionCount(), "session history stays behind");
			try (ZipFile zip = new ZipFile(pack.toFile())) {
				assertNotNull(zip.getEntry("cordis.patch.yml"), "and the patch layer is really in the archive");
			}
		} finally {
			Files.deleteIfExists(pack);
		}
	}

	@Test
	void copyingAnUninstalledInstanceOntoItselfNeverDeletesItsOriginal() throws Exception {
		DshInstance source = makeInstance(SOURCE_ID);
		Path manifest = source.instanceDirectory().resolve(InstanceStore.MANIFEST_NAME);
		Path personalFile = Files.writeString(source.instanceDirectory().resolve("keep.txt"), "do not delete");

		// Even when the harness has not yet been installed, a copy cannot adopt its source.
		// Interrupting the export exposes the old catch-and-cleanup path without using npm.
		DshException refusal = assertThrows(DshException.class,
		                                 () -> DshInstanceManager.duplicate(SOURCE_ID, SOURCE_ID,
		                                                                     message -> {
				                                                                     throw new IllegalStateException("test interruption");
			                                                                     }));
		assertTrue(refusal.getMessage().contains(SOURCE_ID), refusal.getMessage());
		assertTrue(Files.isRegularFile(manifest), "a failed self-copy must preserve the source manifest");
		assertEquals("do not delete", Files.readString(personalFile),
		             "a failed self-copy must never delete the original instance's data");
	}

	@Test
	void anIdTakenByAnInstanceThatIsWholeIsRefused() throws Exception {
		// What must not happen: a duplicate quietly adopting an instance that is already somebody's,
		// and installing over it. Only a half-made copy — this call's own earlier attempt, kept
		// because pnpm asked about an install script — is continued.
		makeInstance(SOURCE_ID);
		DshInstance taken = makeInstance(TARGET_ID);
		Path entry = taken.dshEntryPoint();
		Files.createDirectories(entry.getParent());
		Files.writeString(entry, "// the harness that was already there\n");

		DshException refused = assertThrows(DshException.class,
		                                    () -> DshInstanceManager.duplicate(SOURCE_ID, TARGET_ID, null));

		assertTrue(refused.getMessage().contains("already exists"), refused.getMessage());
		assertEquals("// the harness that was already there\n", Files.readString(entry),
		             "and nothing was written over it");
	}
}
