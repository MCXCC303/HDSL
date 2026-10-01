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
package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies who shares a `DSH_HOME`, which is the half of the home policy that a mode's name does not
/// say.
///
/// Reported as a question — two instances in two different folders turned out to be using one home,
/// and the launcher said nothing about it. It is the documented meaning of
/// [DshHomeMode#VERSION_SHARED] that they do: the home is keyed by the *version*, so every instance
/// pinned to that version uses one directory wherever it lives. What was missing is that anything
/// said so. The launcher now counts the instances sharing a home, and the instance settings page
/// names the folder and the count.
///
/// The count is pinned here rather than in the page, because the page is a label: what can be wrong is
/// the resolution of the home, which is what this asks about.
class DshInstanceHomeSharingTest {
    /// One folder of instances.
    @TempDir
    Path oneFolder;

    /// Another folder of instances.
    @TempDir
    Path anotherFolder;

    /// The instance ids the tests made, for cleanup.
    private final List<String> made = new ArrayList<>();

    /// The supplier that was in place before this test replaced it.
    private Supplier<List<Path>> previous;

    /// Points the manager at the two folders.
    @BeforeEach
    void pointTheLauncherAtTheFolders() {
        previous = DshInstanceManager.setFolders(() -> List.of(oneFolder, anotherFolder));
    }

    /// Removes what the tests made and puts the folders back.
    @AfterEach
    void cleanUp() {
        DshInstanceManager.setFolders(previous);
        for (String id : made) {
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
    void twoInstancesOfOneVersionInTwoFoldersShareOneHome() throws Exception {
        DshInstance here = make("sharing-one", oneFolder, "0.1.7-rc.2", DshHomeMode.VERSION_SHARED);
        DshInstance there = make("sharing-two", anotherFolder, "0.1.7-rc.2", DshHomeMode.VERSION_SHARED);

        assertEquals(there.homeDirectory(), here.homeDirectory(),
                "a home shared by version is one directory, in whichever folder the instances are");
        assertNotEquals(here.instanceDirectory(), there.instanceDirectory(),
                "the instances themselves are still two, each in its own folder");

        assertEquals(List.of(there.id()),
                DshInstanceManager.othersSharingHome(here).stream().map(DshInstance::id).toList());
        assertEquals(List.of(here.id()),
                DshInstanceManager.othersSharingHome(there).stream().map(DshInstance::id).toList());
    }

    @Test
    void twoInstancesOfOneVersionWithPrivateHomesShareNothing() throws Exception {
        DshInstance here = make("private-one", oneFolder, "0.1.7-rc.2", DshHomeMode.ISOLATED);
        make("private-two", anotherFolder, "0.1.7-rc.2", DshHomeMode.ISOLATED);

        assertTrue(DshInstanceManager.othersSharingHome(here).isEmpty(),
                "an isolated home is inside the instance's own folder, so nobody else can be using it");
    }

    @Test
    void instancesOfDifferentVersionsShareNothing() throws Exception {
        DshInstance here = make("version-one", oneFolder, "0.1.7-rc.2", DshHomeMode.VERSION_SHARED);
        make("version-two", anotherFolder, "0.1.6-alpha.2", DshHomeMode.VERSION_SHARED);

        assertTrue(DshInstanceManager.othersSharingHome(here).isEmpty(),
                "a home shared by version is keyed by the version, so another version is another home");
    }

    /// Makes an instance.
    ///
    /// @param id       the instance id
    /// @param folder   the folder to make it in
    /// @param version  the version to pin
    /// @param homeMode how its home is resolved
    /// @return the instance
    private DshInstance make(String id, Path folder, String version, DshHomeMode homeMode) throws Exception {
        made.add(id);
        return DshInstanceManager.create(id, version, DshInstance.DEFAULT_PROFILE,
                Path.of(System.getProperty("java.io.tmpdir")), DshNodeRuntime.SYSTEM,
                homeMode, null, List.of(), Map.of(), folder);
    }
}
