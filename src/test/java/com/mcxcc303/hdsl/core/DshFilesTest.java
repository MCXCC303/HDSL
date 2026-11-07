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
package com.mcxcc303.hdsl.core;

import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies deleting directory trees containing read-only files and Windows junctions.
 * <p>
 * Ensures recursive deletion correctly clears read-only attributes before deleting files,
 * and deletes junction points without recursively deleting target directories on the other side.
 */
class DshFilesTest {
	private static Path instanceTree(Path root) throws IOException {
		Path instance = root.resolve("instance");
		Path packageDirectory = instance.resolve("dsh/node_modules/pkg");
		Files.createDirectories(packageDirectory);
		for (int i = 0; i < 5; i++) {
			Files.writeString(packageDirectory.resolve("file" + i + ".js"), "// x");
		}
		Files.writeString(instance.resolve("instance.json"), "{}");
		return instance;
	}

	private static void markReadOnly(Path path) throws IOException {
		Assumptions.assumeTrue(Files.getFileStore(path).supportsFileAttributeView("dos"),
		                       "this file store has no DOS attribute view to mark an entry read-only with");
		Files.setAttribute(path, "dos:readonly", true);
	}

	@Test
	void aTreeWithAReadOnlyFileIsRemoved(@TempDir Path root) throws Exception {
		Path instance = instanceTree(root);
		Path readOnly = instance.resolve("dsh/node_modules/pkg/file0.js");
		markReadOnly(readOnly);

		DshFiles.deleteTree(instance);

		assertFalse(Files.exists(instance), "a read-only file must not leave the whole instance behind");
	}

	@Test
	void aTreeWithAReadOnlyDirectoryIsRemoved(@TempDir Path root) throws Exception {
		Path instance = instanceTree(root);
		markReadOnly(instance.resolve("dsh/node_modules/pkg"));

		DshFiles.deleteTree(instance);

		assertFalse(Files.exists(instance), "a read-only directory must not stop the removal");
	}

	@Test
	void aJunctionInsideTheTreeIsNotFollowed(@TempDir Path root) throws Exception {
		Assumptions.assumeTrue(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
		                       "junctions are a Windows feature");

		Path outside = Files.createDirectories(root.resolve("outside"));
		Files.writeString(outside.resolve("keep.txt"), "somebody else's file");

		Path instance = instanceTree(root);
		Path junction = instance.resolve("link");
		Process link = new ProcessBuilder("cmd.exe",
		                                  "/c",
		                                  "mklink",
		                                  "/J",
		                                  junction.toString(),
		                                  outside.toString()).redirectErrorStream(true).start();
		try (var reader = link.inputReader()) {
			while (reader.readLine() != null) {
				// Drain, so the process is not left blocked on a full pipe.
			}
		}
		Assumptions.assumeTrue(link.waitFor() == 0, "this machine will not make a junction");

		DshFiles.deleteTree(instance);

		assertFalse(Files.exists(instance), "the instance has to be gone");
		assertTrue(Files.exists(outside.resolve("keep.txt")), "what the junction pointed at is not ours to delete");
	}

	@Test
	void aPathThatIsNotThereIsNotAFailure(@TempDir Path root) throws Exception {
		DshFiles.deleteTree(root.resolve("nothing-here"));
		assertTrue(DshFiles.deleteTreeQuietly(root.resolve("nothing-here")));
	}

	@Test
	void aSingleFileIsRemovedToo(@TempDir Path root) throws Exception {
		Path file = Files.writeString(root.resolve("archive.zip"), "x");
		DshFiles.deleteTree(file);
		assertFalse(Files.exists(file));
	}

	@Test
	void whatIsLeftIsNestedAsDeeplyAsAPackageTree(@TempDir Path root) throws Exception {
		Path instance = root.resolve("instance");
		StringBuilder deep = new StringBuilder();
		for (int i = 0; i < 6; i++) {
			deep.append(
					"/node_modules/.pnpm/@deepseek-ai+dsh-client-ui-sidebar-documentpreview@0.1.7-rc.2/node_modules/@deepseek-ai");
		}
		Path leaf = Path.of(instance + deep.toString());
		Files.createDirectories(leaf);
		Files.writeString(leaf.resolve("index.js"), "// x");
		assertTrue(leaf.toString().length() > 260, "the tree has to be past the legacy limit");

		DshFiles.deleteTree(instance);

		assertFalse(Files.exists(instance), "a deep tree is a package manager's normal tree");
	}

	@Test
	void anInstanceFolderIsRemovedWhole(@TempDir Path root) throws Exception {
		DshInstance instance = DshInstanceManager.create("dsh-files-test",
		                                                 "1.0.0",
		                                                 DshInstance.DEFAULT_PROFILE,
		                                                 root,
		                                                 DshHomeMode.ISOLATED,
		                                                 null,
		                                                 List.of(),
		                                                 Map.of());
		try {
			Path directory = instance.instanceDirectory();
			Files.createDirectories(directory.resolve("dsh/node_modules/pkg"));
			Files.writeString(directory.resolve("dsh/node_modules/pkg/index.js"), "// x");
			markReadOnly(directory.resolve("dsh/node_modules/pkg/index.js"));

			DshInstanceManager.delete(instance.id());

			assertFalse(Files.exists(directory), "the instance has to be gone, home and all");
			assertFalse(DshInstanceManager.exists(instance.id()));
		} finally {
			// Crucial cleanup: this instance is created in the shared suite folder (DshPaths.INSTANCES).
			// Leaking an instance folder on disk causes subsequent tests in the suite (notably
			// org.jackhuang.hmcl.setting.DshInstancesWiringTest, which expects an empty folder to select null)
			// to time out after 15 seconds. On macOS CI runners, failure to clean up here previously caused
			// 15-second timeouts in DshInstancesWiringTest that were easily misdiagnosed as UI deadlocks.
			if (DshInstanceManager.exists(instance.id())) {
				try {
					DshInstanceManager.delete(instance.id());
				} catch (DshException ignored) {
				}
			}
		}
	}
}
