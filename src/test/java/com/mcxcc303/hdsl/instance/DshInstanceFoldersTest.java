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
import com.mcxcc303.hdsl.runtime.node.DshNodeRuntime;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies that an instance belongs to the folder the user is looking at.
///
/// This pins the reported defect: a person added a folder for their instances,
/// installed one, and found it in the folder the launcher owns — while the
/// instances already in the folder they added were listed from a manifest the
/// launcher had never written and then managed as if they were somewhere else.
///
/// The two halves are one rule: which folder an instance is in is part of the
/// instance, and it is the folder the launcher was pointed at when it was made.
class DshInstanceFoldersTest {
	/// The folder the interface is showing.
	@TempDir
	Path shown;

	/// A second folder the launcher also looks in.
	@TempDir
	Path other;

	/// The supplier that was in place before this test replaced it.
	private Supplier<List<Path>> previous;

	/// Creates an instance in a folder.
	///
	/// @param id     the instance id
	/// @param folder the folder to make it in
	/// @return the instance
	private static DshInstance create(String id, Path folder) throws DshException {
		return DshInstanceManager.create(id,
		                                 "1.0.0",
		                                 DshInstance.DEFAULT_PROFILE,
		                                 Path.of(System.getProperty("java.io.tmpdir")),
		                                 DshNodeRuntime.SYSTEM,
		                                 DshHomeMode.ISOLATED,
		                                 null,
		                                 List.of(),
		                                 Map.of(),
		                                 folder);
	}

	/// Copies a folder, which is how a second instance with one name arrives.
	///
	/// @param from the folder to copy
	/// @param to   where to put the copy
	private static void copyFolder(Path from, Path to) throws Exception {
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path path : walk.toList()) {
				Path target = to.resolve(from.relativize(path).toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(path, target);
				}
			}
		}
	}

	/// Points the manager at the two folders, the shown one first.
	@BeforeEach
	void pointTheLauncherAtTheFolders() {
		previous = InstanceFolders.setFolders(() -> List.of(shown, other));
	}

	/// Puts the folders back for the next test.
	@AfterEach
	void restoreTheFolders() {
		InstanceFolders.setFolders(previous);
	}

	@Test
	void aNewInstanceGoesIntoTheFolderTheLauncherIsShowing() throws Exception {
		DshInstance created = create("shown-folder-instance", InstanceFolders.currentFolder());

		assertEquals(shown.resolve("shown-folder-instance"),
		             created.instanceDirectory(),
		             "install an instance means the folder on screen, not the one the launcher owns");
		assertTrue(Files.isRegularFile(shown.resolve("shown-folder-instance/instance.json")));
		assertTrue(created.homeDirectory().startsWith(shown.resolve("shown-folder-instance")),
		           "its private home is inside it, so it can be moved or thrown away whole");
	}

	@Test
	void anInstanceFoundInAFolderIsManagedWhereItIs() throws Exception {
		DshInstance created = create("other-folder-instance", other);
		Path directory = other.resolve("other-folder-instance");
		Files.createDirectories(directory.resolve("dsh"));

		DshInstance found = InstanceStore.find("other-folder-instance");

		assertNotNull(found, "an instance in a folder the launcher looks in has to be found");
		assertEquals(directory,
		             found.instanceDirectory(),
		             "managing it means managing it where it is, not where the launcher owns");
		assertEquals(directory.resolve("dsh"),
		             found.dshDirectory(),
		             "the copy of DeepSeek Harness it runs is the one inside it");
		assertEquals(directory.resolve("home"), found.homeDirectory());
		assertTrue(InstanceListing.list().stream().anyMatch(i -> i.id().equals(created.id())),
		           "every folder the launcher looks in is listed");
	}

	/// An instance made by another launcher, or by an older one, has a manifest
	/// this launcher never wrote. The folder it was found in is the answer.
	@Test
	void aManifestWithNoRecordedFolderIsTakenFromWhereItWasFound() throws Exception {
		DshInstance handWritten = new DshInstance("adopted",
		                                          "1.0.0",
		                                          DshInstance.DEFAULT_PROFILE,
		                                          System.getProperty("java.io.tmpdir"),
		                                          DshNodeRuntime.SYSTEM,
		                                          DshHomeMode.ISOLATED,
		                                          null,
		                                          List.of(),
		                                          Map.of(),
		                                          null,
		                                          null,
		                                          null,
		                                          0,
		                                          0L);
		Path directory = other.resolve("adopted");
		Files.createDirectories(directory);
		JsonUtils.writeToJsonFile(directory.resolve(InstanceStore.MANIFEST_NAME), handWritten);

		DshInstance found = InstanceStore.find("adopted");

		assertNotNull(found);
		assertEquals(directory,
		             found.instanceDirectory(),
		             "a manifest with no folder of its own is in the folder it was found in");
	}

	@Test
	void creatingAnInstanceNeverAdoptsAnUnrelatedExistingDirectory() throws Exception {
		Path occupied = Files.createDirectories(shown.resolve("occupied-for-create"));
		Path document = Files.writeString(occupied.resolve("notes.txt"), "my own document");

		DshException refused = assertThrows(DshException.class,
		                                    () -> create("occupied-for-create", shown));
		assertTrue(refused.getMessage().contains(occupied.toString()), refused.getMessage());
		assertEquals("my own document", Files.readString(document));
		assertFalse(Files.exists(occupied.resolve(InstanceStore.MANIFEST_NAME)),
		            "no instance manifest should be written into an unrelated folder");
		assertNull(InstanceStore.find("occupied-for-create"));
	}

	@Test
	void theSameNameInTwoFoldersIsRefused() throws Exception {
		create("shared-name", shown);

		DshException refused = assertThrows(DshException.class, () -> create("shared-name", other));
		assertTrue(refused.getMessage().contains("already exists"), refused.getMessage());
	}

	@Test
	void aNameTwoFoldersBothHoldIsOneRowAndTheShownFolderWins() throws Exception {
		// Making a second one is refused above, so what is left is the way a second one really
		// arrives: a folder copied, a backup unpacked beside the original, a folder added that holds
		// a copy of an instance the launcher already knows. The name is then held twice, and both
		// rows would answer to it while every lookup and every removal acted on whichever folder came
		// first — the row a person removed would not be the row they clicked.
		create("held-twice", other);
		Path copy = shown.resolve("held-twice");
		copyFolder(other.resolve("held-twice"), copy);

		List<DshInstance> listed = InstanceListing.list()
		                                          .stream()
		                                          .filter(instance -> "held-twice".equals(instance.id()))
		                                          .toList();
		assertEquals(1, listed.size(), "one name is one row, however many folders hold the name");
		assertEquals(copy,
		             listed.get(0).instanceDirectory(),
		             "the folder being shown comes first, so the row is about its copy");
		assertEquals(copy,
		             InstanceStore.find("held-twice").instanceDirectory(),
		             "and every lookup agrees with the row, rather than being a coin toss");

		DshInstanceManager.delete("held-twice");

		assertFalse(Files.exists(copy), "removing the instance removes the one the row was about");
		assertTrue(Files.isDirectory(other.resolve("held-twice")),
		           "the folder it shadows is left where it is; the log names it, the launcher does not" +
		           " delete something nobody pointed at");
	}

	@Test
	void renamingMovesItInsideItsOwnFolder() throws Exception {
		create("renamed-in-other", other);

		DshInstance renamed = DshInstanceManager.rename("renamed-in-other", "renamed-in-other-2");

		assertEquals(other.resolve("renamed-in-other-2"), renamed.instanceDirectory());
		assertTrue(Files.isDirectory(other.resolve("renamed-in-other-2")));
		assertFalse(Files.exists(other.resolve("renamed-in-other")));
		assertFalse(Files.exists(shown.resolve("renamed-in-other-2")),
		            "a rename never moves an instance between folders");
		DshInstanceManager.delete("renamed-in-other-2");
	}

	@Test
	void renamingNeverDeletesWhateverAlreadyHoldsTheNewName() throws Exception {
		// The instance's directory is inside a folder of the launcher's, and for an instance in a
		// folder the user added that folder is theirs: the sibling the new name resolves to can be
		// their own directory rather than an instance's. Renaming used to empty the destination
		// first, so that directory and everything in it was deleted with nothing said — the row
		// changed name and the user's files were gone.
		create("renaming-over", other);
		Path occupied = Files.createDirectories(other.resolve("occupied-name"));
		Path document = Files.writeString(occupied.resolve("notes.txt"), "the user's own file");

		DshException refused = assertThrows(DshException.class,
		                                    () -> DshInstanceManager.rename("renaming-over", "occupied-name"));

		assertTrue(refused.getMessage().contains(occupied.toString()),
		           "the refusal names the folder that is in the way: " + refused.getMessage());
		assertTrue(Files.isDirectory(occupied), "the folder that held the name is left where it is");
		assertTrue(Files.isRegularFile(document), "and so is everything inside it");
		assertTrue(Files.isDirectory(other.resolve("renaming-over")),
		           "the instance keeps its own directory, because the rename did not happen");
		assertEquals(other.resolve("renaming-over"), InstanceStore.find("renaming-over").instanceDirectory());
		DshInstanceManager.delete("renaming-over");
	}

	@Test
	void aDuplicateBeginsBesideItsSourceEvenWhenAnotherFolderIsSelected() throws Exception {
		DshInstance source = create("copy-source-in-other", other);
		Path expected = source.instanceDirectory().resolveSibling("copy-beside-source");
		Path[] seen = new Path[1];

		// Stop at the export progress callback: no registry or package manager needed.
		// The target has already been created, and the callback sees its actual folder.
		RuntimeException cancelled = new RuntimeException("test stops before installation");
		RuntimeException failed = assertThrows(RuntimeException.class,
		                                    () -> DshInstanceManager.duplicate(source.id(), "copy-beside-source",
		                                                                        message -> {
				                                                                        if (message.startsWith("Recording ")) {
					                                                                        DshInstance target = InstanceStore.find("copy-beside-source");
					                                                                        assertNotNull(target);
					                                                                        seen[0] = assertDoesNotThrow(target::instanceDirectory);
					                                                                        throw cancelled;
				                                                                        }
			                                                                        }));
		assertSame(cancelled, failed);
		assertEquals(expected, seen[0], "a copy belongs beside its source, not in the selected folder");
		assertFalse(Files.exists(shown.resolve("copy-beside-source")));
		assertNull(InstanceStore.find("copy-beside-source"), "an interrupted copy is cleaned up");
	}

	@Test
	void removingTakesTheInstanceFromItsOwnFolder() throws Exception {
		create("removed-from-other", other);
		create("kept-in-shown", shown);

		DshInstanceManager.delete("removed-from-other");

		assertFalse(Files.exists(other.resolve("removed-from-other")));
		assertTrue(Files.isDirectory(shown.resolve("kept-in-shown")),
		           "removing one instance leaves the other folder alone");
		assertNull(InstanceStore.find("removed-from-other"));
	}

}
