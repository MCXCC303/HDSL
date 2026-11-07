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
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.plugin.DshPluginBundle;
import com.mcxcc303.hdsl.skill.DshSkillManager;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies what a pack of an instance records, and in which order.
 */
class DshModpacksTest {
	private static final String SOURCE_ID = "pack-config-source";
	private static final String TARGET_ID = "pack-config-target";

	private static void writeProfileManifestForTest(DshInstance instance, PackManifest.Manifest manifest)
	throws Exception {
		ModpackInstaller.writeProfileManifest(profileDirectory(instance).resolve("package.json"), manifest, Map.of());
	}

	private static Path profileDirectory(DshInstance instance) throws Exception {
		return instance.homeDirectory().resolve("profiles").resolve(instance.profile());
	}

	private static void writeProfile(DshInstance instance, String body) throws Exception {
		Path directory = profileDirectory(instance);
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("package.json"), body);
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
		PackExporter.ExportResult exported = PackExporter.export(instance, pack, null);

		assertEquals(2, exported.plugins());
		PackManifest.Manifest manifest = PackManifest.readManifest(pack);
		assertEquals(PackManifest.FORMAT, manifest.format());
		assertEquals("0.1.6-alpha.2", manifest.dshVersion());
		assertEquals(DshInstance.DEFAULT_PROFILE, manifest.profile());
		assertTrue(manifest.hasPatch(), "the profile's patch layer is composition, so it travels");
		assertEquals(List.of("dsh-context", "dshmarket"),
		             manifest.bundles().stream().filter(bundle -> !bundle.startsWith("@deepseek-ai/")).toList(),
		             "the bundle order is recorded as it is, not sorted");
		assertEquals(List.of("dshmarket", "dsh-context"),
		             manifest.plugins().stream().map(PackManifest.Plugin::name).toList(),
		             "the dependencies keep the manifest's own order");
		assertEquals(List.of("dshmarket@1.52.0", "dsh-context@0.54.2"),
		             manifest.installSpecs(),
		             "versions are pinned to what the instance had");
		assertEquals(0, manifest.localPlugins().size());

		try (ZipFile zip = new ZipFile(pack.toFile())) {
			assertNotNull(zip.getEntry("cordis.patch.yml"), "the patch layer is in the archive");
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
		PackExporter.export(source, pack, null);

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

		PackManifest.Manifest manifest = PackManifest.readManifest(pack);
		// Only the two fields a pack has something to say about are written; the
		// resolve that follows is the harness's own and is exercised by the
		// command line, so it is not run here.
		writeProfileManifestForTest(target, manifest);

		JsonObject written = JsonUtils.fromJsonFile(profileDirectory(target).resolve("package.json"), JsonObject.class);
		assertEquals(List.of("dsh-context", "dshmarket"),
		             written.getAsJsonObject("dsh")
		                    .getAsJsonObject("profile")
		                    .getAsJsonArray("bundles")
		                    .asList()
		                    .stream()
		                    .map(JsonElement::getAsString)
		                    .toList(),
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
		PackExporter.export(instance, pack, null);
		PackManifest.Manifest manifest = PackManifest.readManifest(pack);

		assertEquals(List.of("dshmarket@1.52.0"), manifest.installSpecs(), "only what can be fetched is asked for");
		assertEquals(List.of("dsh-hello-local"),
		             manifest.localPlugins().stream().map(PackManifest.Plugin::name).toList(),
		             "the local plugin is recorded, so the loader can say it cannot be restored");
		Files.deleteIfExists(pack);
	}

	@Test
	void aPluginSomebodyIsWorkingOnIsNamedBeforeTheExportAndNotCarriedByIt() throws Exception {
		// A `link:` install is a link in the profile's `node_modules`, and what is behind it is
		// somebody's working tree rather than a copy the instance owns. The pack does not carry it,
		// so the interface has to say which plugins those are while there is still time to do
		// something about it — and the pack must not hold a package with no code in it, which is
		// what reading through the link would produce.
		DshInstance instance = makeInstance(SOURCE_ID);
		Path working = Files.createTempDirectory("dsh-fork");
		// The path goes into a JSON string, and a Windows path is full of backslashes: written as
		// they are, `C:\Users` is an escape JSON refuses, the whole profile reads as holding no
		// dependencies, and the test then asks about a plugin that is not there.
		String linked = working.toString().replace("\\", "\\\\");
		Files.createDirectories(working.resolve("lib"));
		Files.writeString(working.resolve("package.json"), """
				{"name":"dsh-fork","version":"2.1.0"}
				""");
		Files.writeString(working.resolve("lib/index.js"), "export const x = 1;\n");

		writeProfile(instance, """
				{
				  "name": "dsh-profile-web",
				  "private": true,
				  "dependencies": {
				    "dshmarket": "1.52.0",
				    "dsh-fork": "link:%s"
				  },
				  "dsh": {"profile": {"bundles": ["dsh-fork", "dshmarket"]}}
				}
				""".formatted(linked));

		Path installed = profileDirectory(instance).resolve("node_modules/dsh-fork");
		Files.createDirectories(installed.getParent());
		try {
			Files.createSymbolicLink(installed, working);
		} catch (UnsupportedOperationException | IOException e) {
			// A filesystem without links cannot test this part.
			return;
		}

		assertEquals(List.of(new PackExporter.LocalPlugin("dsh-fork", true)),
		             PackExporter.localPlugins(instance),
		             "the interface is told which plugins a pack cannot carry, before the export");

		Path pack = Files.createTempFile("modpack", ".zip");
		PackExporter.export(instance, pack, null);
		PackManifest.Manifest manifest = PackManifest.readManifest(pack);

		assertEquals(List.of("dshmarket@1.52.0"), manifest.installSpecs(), "only what can be fetched is asked for");
		assertEquals(List.of("dsh-fork"),
		             manifest.localPlugins().stream().map(PackManifest.Plugin::name).toList(),
		             "the plugin is recorded, so the loader can say it cannot be restored");
		assertFalse(DshPluginBundle.carried(pack).stream().anyMatch(name -> name.contains("dsh-fork")),
		            "and no part of somebody's working tree travels: " + DshPluginBundle.carried(pack));
		Files.deleteIfExists(pack);
	}

	@Test
	void aPluginInstalledFromAFileIsNamedWithoutBeingOneThePackCannotCarry() throws Exception {
		// What the export asks about first is the plugins that came from this machine rather than
		// from a registry. A packed plugin the launcher copied into the instance travels as the
		// file it is; a directory the profile only links to does not travel at all. Telling them
		// apart is the difference between a caution and a promise the pack will not keep.
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

		assertEquals(List.of(new PackExporter.LocalPlugin("dsh-hello-local", false)),
		             PackExporter.localPlugins(instance),
		             "a file the instance keeps is local, and is not a link out of the profile");
	}

	@Test
	void somethingThatIsNotAPackIsRefused() throws Exception {
		Path notAPack = Files.createTempFile("modpack", ".zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(notAPack))) {
			zip.putNextEntry(new ZipEntry("readme.txt"));
			zip.write("not a pack".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		assertThrows(DshException.class, () -> PackManifest.readManifest(notAPack));
		Files.deleteIfExists(notAPack);
	}

	@Test
	void aPackThatNamesNoHarnessVersionIsRefused() throws Exception {
		Path pack = Files.createTempFile("modpack", ".zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
			zip.putNextEntry(new ZipEntry(PackManifest.MANIFEST));
			zip.write("""
					          {"format":"hdsl-modpack","version":1,"createdAt":"now","instanceId":"x",
					           "dshVersion":"","profile":"web","plugins":[],"bundles":[]}
					          """.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		assertThrows(DshException.class,
		             () -> ModpackInstaller.install(pack,
		                                            "no-version-pack",
		                                            Path.of(System.getProperty("user.home")),
		                                            null));
		Files.deleteIfExists(pack);
	}

	@Test
	void theSkillPacksAnInstanceHasTravelInsideThePack() throws Exception {
		DshInstance instance = makeInstance(SOURCE_ID);
		writeProfile(instance, "{}");
		Path skills = DshSkillManager.directory(instance.homeDirectory());
		Files.createDirectories(skills.resolve("alpha"));
		Files.writeString(skills.resolve("alpha").resolve("SKILL.md"), "---\nname: alpha\ndescription: First\n---\n");
		Files.writeString(skills.resolve("alpha").resolve("notes.txt"), "extra");
		Path shipped = skills.resolve(".system").resolve("shipped");
		Files.createDirectories(shipped);
		Files.writeString(shipped.resolve("SKILL.md"), "---\nname: shipped\ndescription: x\n---\n");

		Path pack = Files.createTempFile("modpack", ".zip");
		PackExporter.export(instance, pack, null);

		PackManifest.Manifest manifest = PackManifest.readManifest(pack);
		assertEquals(3, manifest.version(), "skills are what the third version of the format added");
		assertEquals(List.of("alpha"),
		             manifest.skills(),
		             "the manifest names what travels, and only what a person could have chosen");
		try (ZipFile zip = new ZipFile(pack.toFile())) {
			assertNotNull(zip.getEntry("skills/alpha/SKILL.md"), "the skill is in the archive");
			assertNotNull(zip.getEntry("skills/alpha/notes.txt"),
			              "and so is the rest of the pack, which is not a skill file by name");
			assertFalse(zip.stream().anyMatch(entry -> entry.getName().contains(".system")),
			            "the skills the harness ships stay where they are");
		}

		Path restored = Files.createTempDirectory("modpack-target-home");
		assertEquals(2, DshSkillManager.restoreInto(pack, restored, null));
		assertTrue(Files.isRegularFile(DshSkillManager.directory(restored).resolve("alpha").resolve("SKILL.md")));
		Files.deleteIfExists(pack);
	}

	@Test
	void aSkillThePersonLeftOutDoesNotTravel() throws Exception {
		DshInstance instance = makeInstance(SOURCE_ID);
		writeProfile(instance, "{}");
		Path skills = DshSkillManager.directory(instance.homeDirectory());
		for (String name : List.of("kept", "dropped")) {
			Files.createDirectories(skills.resolve(name));
			Files.writeString(skills.resolve(name).resolve("SKILL.md"),
			                  "---\nname: " + name + "\ndescription: x\n---\n");
		}

		Path pack = Files.createTempFile("modpack", ".zip");
		PackManifest.Options options = new PackManifest.Options("chosen",
		                                                        "1.0",
		                                                        "",
		                                                        "",
		                                                        "",
		                                                        "",
		                                                        false,
		                                                        Set.of(),
		                                                        Set.of(),
		                                                        Set.of("kept"));
		PackExporter.export(instance, pack, options, null);

		assertEquals(List.of("kept"), PackManifest.readManifest(pack).skills());
		try (ZipFile zip = new ZipFile(pack.toFile())) {
			assertNotNull(zip.getEntry("skills/kept/SKILL.md"));
			assertNull(zip.getEntry("skills/dropped/SKILL.md"), "a skill nobody ticked is not in the pack");
		}
		Files.deleteIfExists(pack);
	}

	@Test
	void aPackFromANewerLauncherIsRefused() throws Exception {
		Path pack = Files.createTempFile("modpack", ".zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
			zip.putNextEntry(new ZipEntry(PackManifest.MANIFEST));
			zip.write("""
					          {"format":"hdsl-modpack","version":4,"createdAt":"now","instanceId":"x",
					           "dshVersion":"0.1.6-alpha.2","profile":"web","plugins":[],"bundles":[]}
					          """.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		assertThrows(DshException.class,
		             () -> ModpackInstaller.install(pack,
		                                            "newer-pack",
		                                            Path.of(System.getProperty("user.home")),
		                                            null));
		Files.deleteIfExists(pack);
	}

	private DshInstance makeInstance(String id) throws Exception {
		Path source = Files.createTempDirectory("modpack-home");
		return DshInstanceManager.create(id,
		                                 "0.1.6-alpha.2",
		                                 DshInstance.DEFAULT_PROFILE,
		                                 source,
		                                 DshHomeMode.ISOLATED,
		                                 null,
		                                 List.of(),
		                                 Map.of());
	}
}
