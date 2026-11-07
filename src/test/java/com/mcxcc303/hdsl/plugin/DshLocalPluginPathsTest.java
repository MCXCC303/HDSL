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
package com.mcxcc303.hdsl.plugin;

import com.mcxcc303.hdsl.core.DshPaths;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that renaming an instance moves the paths it recorded for its local plugins. */
class DshLocalPluginPathsTest {
	private static final String INSIDE = "dsh-example";
	private static final String OUTSIDE = "dsh-elsewhere";
	private static final String REGISTRY = "dsh-registry";
	private static final Path ELSEWHERE = Path.of("/opt/plugins/dsh-elsewhere-9.9.9.tgz");

	private static String installLocalPlugin(DshInstance instance, Path oldDirectory) throws Exception {
		Path profile = profileOf(instance);
		Path plugins = oldDirectory.resolve(DshLocalPlugins.DIRECTORY);
		Files.createDirectories(plugins);
		Files.writeString(plugins.resolve(INSIDE + "-1.0.0.tgz"), "not really a package");

		String manifest = """
				{
				  "name": "dsh-profile-web",
				  "private": true,
				  "dependencies": {
				    "%s": "file:%s",
				    "%s": "file:%s",
				    "%s": "1.2.3"
				  },
				  "dsh": {
				    "profile": {
				      "bundles": [
				        "%s",
				        "%s"
				      ]
				    }
				  }
				}
				""".formatted(INSIDE, jsonEscaped(plugins.resolve(INSIDE + "-1.0.0.tgz").toString()),
		                      OUTSIDE, jsonEscaped(ELSEWHERE.toString()), REGISTRY, INSIDE, REGISTRY);
		Files.createDirectories(profile);
		Files.writeString(profile.resolve("package.json"), manifest);
		return manifest;
	}

	private static Path profileOf(DshInstance instance) throws Exception {
		return instance.homeDirectory().resolve("profiles").resolve(instance.profile());
	}

	private static String lockfileText(Path directory) {
		return """
				lockfileVersion: '9.0'
				importers:
				  .:
				    dependencies:
				      %s:
				        specifier: file:%s
				        version: file:../../../plugins/%s-1.0.0.tgz
				      %s:
				        specifier: file:%s
				packages:
				  %s@file:../../../plugins/%s-1.0.0.tgz:
				    resolution: {tarball: file:../../../plugins/%s-1.0.0.tgz}
				""".formatted(INSIDE, directory.resolve("plugins/" + INSIDE + "-1.0.0.tgz"), INSIDE,
		                      OUTSIDE, ELSEWHERE, INSIDE, INSIDE, INSIDE);
	}

	private static String expected(String text, Path from, Path to) {
		String moved = text.replace(from.toString(), to.toString());
		String escapedFrom = jsonEscaped(from.toString());
		if (!escapedFrom.equals(from.toString())) {
			moved = moved.replace(escapedFrom, jsonEscaped(to.toString()));
		}
		return moved;
	}

	private static String jsonEscaped(String path) {
		return path.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static void assertResolves(Path manifest, Path directory) throws Exception {
		String marker = "file:" + directory.toString().replace('\\', '/') + "/";
		int checked = 0;
		for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
			// One or more backslashes become one slash, so the JSON-escaped spelling
			// (`C:\\…`) and the plain one (`C:\…`) read the same way.
			String readable = line.replaceAll("\\\\+", "/");
			int at = readable.indexOf(marker);
			if (at < 0) {
				continue;
			}
			String spec = readable.substring(at + "file:".length()).replaceAll("[\",\\s]+$", "");
			assertTrue(Files.isRegularFile(Path.of(spec)),
			           "the profile has to keep naming a file that is there, not " + spec);
			checked++;
		}
		assertTrue(checked > 0, "the manifest has to keep naming the plugin the instance holds");
	}

	private static void remove(String... ids) {
		for (String id : ids) {
			try {
				DshInstanceManager.delete(id);
			} catch (Exception ignored) {
				// There was nothing under that id, which is what a rename leaves behind.
			}
		}
	}

	private static String newId(String what) {
		return "test-local-paths-" + what + "-" + Long.toHexString(System.nanoTime());
	}

	@Test
	void aPluginTheInstanceHoldsFollowsItsRename() throws Exception {
		String id = newId("isolated");
		String renamed = id + "-renamed";
		try {
			DshInstance instance = DshInstanceManager.create(id,
			                                                 "1.0.0",
			                                                 DshInstance.DEFAULT_PROFILE,
			                                                 Path.of(System.getProperty("java.io.tmpdir")),
			                                                 DshHomeMode.ISOLATED,
			                                                 null,
			                                                 List.of(),
			                                                 Map.of());
			Path oldDirectory = instance.instanceDirectory();
			Path newDirectory = DshPaths.instanceDirectory(renamed);

			String manifest = installLocalPlugin(instance, oldDirectory);
			Path profile = profileOf(instance);
			Files.writeString(profile.resolve("pnpm-lock.yaml"), lockfileText(oldDirectory));
			Path mirror = profile.resolve("node_modules/.pnpm/lock.yaml");
			Files.createDirectories(mirror.getParent());
			Files.writeString(mirror, lockfileText(oldDirectory));

			Path movedProfile = profileOf(DshInstanceManager.rename(id, renamed));

			assertEquals(expected(manifest, oldDirectory, newDirectory),
			             Files.readString(movedProfile.resolve("package.json")),
			             "the manifest is moved as text: everything but the path comes back unchanged");
			assertEquals(expected(lockfileText(oldDirectory), oldDirectory, newDirectory),
			             Files.readString(movedProfile.resolve("pnpm-lock.yaml")),
			             "the lockfile's specification is the same path, so it moves with it");
			assertEquals(expected(lockfileText(oldDirectory), oldDirectory, newDirectory),
			             Files.readString(movedProfile.resolve("node_modules/.pnpm/lock.yaml")),
			             "the package manager's copy of the lockfile names the path too, until it next runs");

			assertTrue(Files.isRegularFile(newDirectory.resolve("plugins/" + INSIDE + "-1.0.0.tgz")),
			           "the file itself moves with the instance");
			assertResolves(movedProfile.resolve("package.json"), newDirectory);
		} finally {
			remove(id, renamed);
		}
	}

	@Test
	void aHomeThatStaysPutStillLearnsWhereThePluginWent() throws Exception {
		String id = newId("custom");
		String renamed = id + "-renamed";
		Path home = Files.createTempDirectory("test-local-paths-home-");
		try {
			DshInstance instance = DshInstanceManager.create(id,
			                                                 "1.0.0",
			                                                 DshInstance.DEFAULT_PROFILE,
			                                                 Path.of(System.getProperty("java.io.tmpdir")),
			                                                 DshHomeMode.CUSTOM,
			                                                 home,
			                                                 List.of(),
			                                                 Map.of());
			Path oldDirectory = instance.instanceDirectory();
			Path newDirectory = DshPaths.instanceDirectory(renamed);

			String recorded = installLocalPlugin(instance, oldDirectory);
			DshInstance after = DshInstanceManager.rename(id, renamed);

			Path manifest = home.resolve("profiles").resolve(after.profile()).resolve("package.json");
			assertEquals(expected(recorded, oldDirectory, newDirectory),
			             Files.readString(manifest),
			             "a chosen home records the instance's own files just the same, so what it recorded moves");
			assertResolves(manifest, newDirectory);
		} finally {
			remove(id, renamed);
			FileUtils.deleteDirectoryQuietly(home);
		}
	}

	@Test
	void aProfileWithNoLocalPluginIsNotTouched() throws Exception {
		String id = newId("untouched");
		String renamed = id + "-renamed";
		try {
			DshInstance instance = DshInstanceManager.create(id,
			                                                 "1.0.0",
			                                                 DshInstance.DEFAULT_PROFILE,
			                                                 Path.of(System.getProperty("java.io.tmpdir")),
			                                                 DshHomeMode.ISOLATED,
			                                                 null,
			                                                 List.of(),
			                                                 Map.of());
			Path profile = profileOf(instance);
			Files.createDirectories(profile);
			Path manifest = profile.resolve("package.json");
			Files.writeString(manifest, """
					{
					  "name": "dsh-profile-web",
					  "dependencies": {
					    "%s": "1.2.3"
					  }
					}
					""".formatted(REGISTRY));
			var written = Files.getLastModifiedTime(manifest);

			DshInstance after = DshInstanceManager.rename(id, renamed);

			assertEquals(written,
			             Files.getLastModifiedTime(profileOf(after).resolve("package.json")),
			             "a record with no path in it is not a record to rewrite");
		} finally {
			remove(id, renamed);
		}
	}

	@Test
	void onlyThePluginsTheInstanceHoldsAreReported() throws Exception {
		String id = newId("reported");
		String renamed = id + "-renamed";
		try {
			DshInstance instance = DshInstanceManager.create(id,
			                                                 "1.0.0",
			                                                 DshInstance.DEFAULT_PROFILE,
			                                                 Path.of(System.getProperty("java.io.tmpdir")),
			                                                 DshHomeMode.ISOLATED,
			                                                 null,
			                                                 List.of(),
			                                                 Map.of());
			Path oldDirectory = instance.instanceDirectory();
			installLocalPlugin(instance, oldDirectory);

			List<String> moved = DshLocalPluginPaths.relocate(instance,
			                                                  oldDirectory,
			                                                  DshPaths.instanceDirectory(renamed));

			assertEquals(List.of(INSIDE),
			             moved,
			             "the file outside the instance and the registry package are not this instance's to move");
		} finally {
			remove(id, renamed);
		}
	}

	@Test
	void aDirectoryThatMerelyBeginsWithTheOldNameIsLeftAlone() {
		String from = "/home/someone/.local/share/hdsl/instances/a";
		String to = "/home/someone/.local/share/hdsl/instances/a-renamed";

		assertEquals("file:" + to + "/plugins/x.tgz",
		             DshLocalPluginPaths.rewrite("file:" + from + "/plugins/x.tgz", from, to),
		             "a record that names the instance itself moves");
		assertEquals("file:" + from + "-b/plugins/x.tgz",
		             DshLocalPluginPaths.rewrite("file:" + from + "-b/plugins/x.tgz", from, to),
		             "a record that names a neighbouring instance does not");
		assertEquals("specifier: file:" + to + "\n",
		             DshLocalPluginPaths.rewrite("specifier: file:" + from + "\n", from, to),
		             "a record that names the directory and nothing in it moves too");
		assertEquals("", DshLocalPluginPaths.rewrite("", from, to));
	}
}
