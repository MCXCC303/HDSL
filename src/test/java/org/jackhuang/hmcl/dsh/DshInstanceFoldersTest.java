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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /// Points the manager at the two folders, the shown one first.
    @BeforeEach
    void pointTheLauncherAtTheFolders() {
        previous = DshInstanceManager.setFolders(() -> List.of(shown, other));
    }

    /// Puts the folders back for the next test.
    @AfterEach
    void restoreTheFolders() {
        DshInstanceManager.setFolders(previous);
    }

    /// Creates an instance in a folder.
    ///
    /// @param id     the instance id
    /// @param folder the folder to make it in
    /// @return the instance
    private static DshInstance create(String id, Path folder) throws DshException {
        return DshInstanceManager.create(id, "1.0.0", DshInstance.DEFAULT_PROFILE,
                Path.of(System.getProperty("java.io.tmpdir")), DshNodeRuntime.SYSTEM,
                DshHomeMode.ISOLATED, null, List.of(), Map.of(), folder);
    }

    @Test
    void aNewInstanceGoesIntoTheFolderTheLauncherIsShowing() throws Exception {
        DshInstance created = create("shown-folder-instance", DshInstanceManager.currentFolder());

        assertEquals(shown.resolve("shown-folder-instance"), created.instanceDirectory(),
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

        DshInstance found = DshInstanceManager.find("other-folder-instance");

        assertNotNull(found, "an instance in a folder the launcher looks in has to be found");
        assertEquals(directory, found.instanceDirectory(),
                "managing it means managing it where it is, not where the launcher owns");
        assertEquals(directory.resolve("dsh"), found.dshDirectory(),
                "the copy of DeepSeek Harness it runs is the one inside it");
        assertEquals(directory.resolve("home"), found.homeDirectory());
        assertTrue(DshInstanceManager.list().stream().anyMatch(i -> i.id().equals(created.id())),
                "every folder the launcher looks in is listed");
    }

    /// An instance made by another launcher, or by an older one, has a manifest
    /// this launcher never wrote. The folder it was found in is the answer.
    @Test
    void aManifestWithNoRecordedFolderIsTakenFromWhereItWasFound() throws Exception {
        DshInstance handWritten = new DshInstance("adopted", "1.0.0", DshInstance.DEFAULT_PROFILE,
                System.getProperty("java.io.tmpdir"), DshNodeRuntime.SYSTEM,
                DshHomeMode.ISOLATED, null, List.of(), Map.of(), null, null, null, 0, 0L);
        Path directory = other.resolve("adopted");
        Files.createDirectories(directory);
        JsonUtils.writeToJsonFile(directory.resolve(DshInstanceManager.MANIFEST_NAME), handWritten);

        DshInstance found = DshInstanceManager.find("adopted");

        assertNotNull(found);
        assertEquals(directory, found.instanceDirectory(),
                "a manifest with no folder of its own is in the folder it was found in");
    }

    @Test
    void theSameNameInTwoFoldersIsRefused() throws Exception {
        create("shared-name", shown);

        DshException refused = assertThrows(DshException.class, () -> create("shared-name", other));
        assertTrue(refused.getMessage().contains("already exists"), refused.getMessage());
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
    void removingTakesTheInstanceFromItsOwnFolder() throws Exception {
        create("removed-from-other", other);
        create("kept-in-shown", shown);

        DshInstanceManager.delete("removed-from-other");

        assertFalse(Files.exists(other.resolve("removed-from-other")));
        assertTrue(Files.isDirectory(shown.resolve("kept-in-shown")),
                "removing one instance leaves the other folder alone");
        assertNull(DshInstanceManager.find("removed-from-other"));
    }

}
