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

import com.mcxcc303.hdsl.core.DshPaths;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that renaming an instance takes everything named after it along.
 */
class DshInstanceRenameTest {
	private static void renamingTakesTheFolderAlong(DshHomeMode mode) throws Exception {
		String id = "test-rename-" + mode.name().toLowerCase() + "-" + Long.toHexString(System.nanoTime());
		String newId = id + "-renamed";
		Path customHome = mode == DshHomeMode.CUSTOM ? Files.createTempDirectory("test-rename-home-") : null;

		DshInstance created = DshInstanceManager.create(id,
		                                                "1.0.0",
		                                                DshInstance.DEFAULT_PROFILE,
		                                                Path.of(System.getProperty("java.io.tmpdir")),
		                                                mode,
		                                                customHome,
		                                                List.of(),
		                                                Map.of());
		assertNotNull(created, "the instance has to be made before it can be renamed");

		Path oldDirectory = DshPaths.instanceDirectory(id);
		Path newDirectory = DshPaths.instanceDirectory(newId);

		try {
			Files.createDirectories(oldDirectory.resolve("dsh"));
			Files.writeString(oldDirectory.resolve("dsh/marker.txt"), "runtime");

			DshInstance renamed = DshInstanceManager.rename(id, newId);

			assertEquals(newId, renamed.id(), "the instance has to come back under its new id");
			assertNotNull(InstanceStore.find(newId), "the new id has to be readable");
			assertNull(InstanceStore.find(id), "the old id must hold nothing");
			assertTrue(Files.isRegularFile(newDirectory.resolve(InstanceStore.MANIFEST_NAME)),
			           "the manifest has to move with the instance instead of being deleted");
			assertTrue(Files.isRegularFile(newDirectory.resolve("dsh/marker.txt")),
			           "the runtime has to move with the instance");
			assertFalse(Files.exists(oldDirectory), "the old folder has to be gone");
			assertTrue(InstanceListing.list().stream().anyMatch(i -> i.id().equals(newId)),
			           "the list has to show the new id");

			if (mode == DshHomeMode.ISOLATED) {
				assertTrue(Files.isDirectory(newDirectory.resolve("home")),
				           "an isolated home is part of the folder and moves with it");
			}
			if (mode == DshHomeMode.CUSTOM) {
				assertTrue(Files.exists(customHome), "a home the person chose is left where it is");
			}
		} finally {
			deleteQuietly(id);
			deleteQuietly(newId);
			if (customHome != null) {
				FileUtils.deleteDirectoryQuietly(customHome);
			}
		}
	}

	private static void deleteQuietly(String id) {
		try {
			DshInstanceManager.delete(id);
		} catch (Exception ignored) {
			// There was nothing left to remove.
		}
	}

	@Test
	void anIsolatedInstanceMovesItsOwnFolderAndHome() throws Exception {
		renamingTakesTheFolderAlong(DshHomeMode.ISOLATED);
	}

	@Test
	void aSharedHomeInstanceMovesOnlyItsOwnFolder() throws Exception {
		renamingTakesTheFolderAlong(DshHomeMode.GLOBAL);
	}

	@Test
	void aCustomHomeInstanceKeepsItsChosenHome() throws Exception {
		renamingTakesTheFolderAlong(DshHomeMode.CUSTOM);
	}
}
