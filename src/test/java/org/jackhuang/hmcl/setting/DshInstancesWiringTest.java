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
package org.jackhuang.hmcl.setting;

import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshPaths;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that making an instance reaches the interface.
///
/// This is the reported defect, pinned: an instance created by the wizard was
/// on disk but the list still said the folder held nothing, and the home page
/// still said nothing was selected, until somebody pressed refresh. Both are
/// consequences of a page having to go and look; here the page — represented by
/// the repository and the selected-instance property it publishes — is told.
class DshInstancesWiringTest {
    /// Creates an instance the way the install wizard does.
    ///
    /// @param id the instance id
    /// @return the instance
    private static DshInstance createAsWizard(String id) throws Exception {
        DshInstance instance = DshInstanceManager.create(id, "1.0.0", DshInstance.DEFAULT_PROFILE,
                Path.of(System.getProperty("java.io.tmpdir")), DshHomeMode.ISOLATED, null,
                List.of(), Map.of());
        assertNotNull(instance);
        return instance;
    }

    /// Removes an instance and everything it owns.
    ///
    /// @param id the instance id
    private static void remove(String id) throws Exception {
        DshInstanceManager.delete(id);
    }

    /// Reads the folder and waits for the read to be published.
    ///
    /// Publishing goes through the interface thread whenever one is running,
    /// so looking straight after reading races the publication. The latch is
    /// counted down by the publication itself, whichever thread it lands on.
    ///
    /// @param repository the repository to read
    private static void refreshAndWait(DshInstanceRepository repository) throws Exception {
        CountDownLatch published = new CountDownLatch(1);
        repository.snapshotProperty().addListener((observable, was, now) -> published.countDown());
        repository.refresh();
        if (!published.await(15, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for the read to be published");
        }
    }

    /// Waits until the folder publishes a snapshot that names — or stops
    /// naming — the given instance.
    ///
    /// Making and removing instances publishes through the interface thread
    /// whenever one is running, exactly as an explicit read does, so the same
    /// waiting applies. The listener goes on before the write is awaited, and
    /// the state is also read once through it, so a publication that already
    /// happened counts as much as one still to come; the
    /// `java.util.concurrent` hand-off carries the memory visibility a re-read
    /// of the property would not promise.
    ///
    /// @param repository the folder to watch
    /// @param id         the instance to wait for
    /// @param present    whether to wait for it to be listed or to be gone
    private static void awaitListing(DshInstanceRepository repository, String id, boolean present) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        repository.snapshotProperty().addListener((observable, was, now) -> {
            if (now.instances().stream().anyMatch(i -> i.id().equals(id)) == present) {
                done.countDown();
            }
        });
        if (repository.getSnapshot().instances().stream().anyMatch(i -> i.id().equals(id)) == present) {
            done.countDown();
        }
        if (!done.await(15, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for the folder to "
                    + (present ? "list " : "drop ") + id);
        }
    }

    /// Waits until something is selected.
    ///
    /// @see #awaitSelectionOf
    private static void awaitSelection() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        GameDirectoryManager.selectedInstanceProperty().addListener((observable, was, now) -> {
            if (now != null) {
                done.countDown();
            }
        });
        if (GameDirectoryManager.getSelectedInstance() != null) {
            done.countDown();
        }
        if (!done.await(15, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for a selection to be published");
        }
    }

    /// Waits until the selection is the given instance, or nothing.
    ///
    /// @param id the instance to wait for, or `null` to wait for no selection
    private static void awaitSelectionOf(@Nullable String id) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        GameDirectoryManager.selectedInstanceProperty().addListener((observable, was, now) -> {
            if (Objects.equals(now == null ? null : now.id(), id)) {
                done.countDown();
            }
        });
        DshInstance current = GameDirectoryManager.getSelectedInstance();
        if (Objects.equals(current == null ? null : current.id(), id)) {
            done.countDown();
        }
        if (!done.await(15, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for the selection to become " + id);
        }
    }

    @Test
    void anInstanceMadeByTheWizardIsListedWithoutBeingAskedFor() throws Exception {
        DshInstanceRepository repository = GameDirectoryManager.getSelectedRepository();
        refreshAndWait(repository);
        int before = repository.getInstances().size();

        DshInstance created = createAsWizard("wiring-made-by-the-wizard");
        try {
            awaitListing(repository, created.id(), true);
            assertEquals(before + 1, repository.getInstances().size(),
                    "the folder has to publish what was written to it, without a refresh");
            assertTrue(repository.getInstances().stream().anyMatch(i -> i.id().equals(created.id())));
        } finally {
            remove(created.id());
        }

        awaitListing(repository, created.id(), false);
        assertEquals(before, repository.getInstances().size(),
                "removing one has to reach the interface the same way");
    }

    @Test
    void aFolderWithNothingSelectedChoosesWhatTheWizardMade() throws Exception {
        // The launcher is started with an empty folder, which is the state the
        // report starts from: nothing is selected anywhere.
        settings().getSelectedInstance().clear();

        DshInstance created = createAsWizard("wiring-first-instance");
        try {
            awaitSelectionOf(created.id());
            DshInstance selected = GameDirectoryManager.getSelectedInstance();
            assertNotNull(selected, "the home page must have something to act on");
            assertEquals(created.id(), selected.id(),
                    "the instance just made is the one the launch button targets");
        } finally {
            remove(created.id());
        }

        awaitSelectionOf(null);
        assertNull(GameDirectoryManager.getSelectedInstance(),
                "an empty folder leaves nothing selected");
    }

    @Test
    void aSelectionMadeAnywhereReachesTheHomePage() throws Exception {
        DshInstance first = createAsWizard("wiring-selection-a");
        DshInstance second = createAsWizard("wiring-selection-b");
        try {
            // Something is chosen already — the newest instance, chosen by the
            // folder itself, once that choice has been published. What the
            // list's radio button then does is choose the other one, and the
            // home page is expected to follow without being navigated to again.
            awaitSelection();
            DshInstance current = GameDirectoryManager.getSelectedInstance();
            assertNotNull(current);
            DshInstance other = current.id().equals(first.id()) ? second : first;

            List<String> seen = new java.util.ArrayList<>();
            GameDirectoryManager.selectedInstanceProperty().addListener(
                    (observable, was, now) -> seen.add(now == null ? null : now.id()));

            GameDirectoryManager.setSelectedInstance(DshInstanceManager.find(other.id()));

            assertEquals(List.of(other.id()), seen);
        } finally {
            remove(first.id());
            remove(second.id());
        }
    }

    @Test
    void theFolderTheLauncherOwnsIsTheDefaultOne() {
        assertEquals(DshPaths.INSTANCES.toAbsolutePath().normalize(),
                GameDirectoryManager.selected().directory(),
                "with nothing configured, the launcher's own folder is what is shown");
    }

    @Test
    void theSelectionWrittenBeforeSelectionsWerePerFolderStillCounts() {
        LauncherSettings settings = new LauncherSettings();
        SettingsManager.applyLegacySelection(settings, "an-instance-from-an-older-launcher");

        assertEquals("an-instance-from-an-older-launcher",
                settings.getSelectedInstance(GameDirectory.DEFAULT_ID),
                "the one selection an older launcher kept belonged to the folder it owns");

        // A launcher that has moved on must not have the old value written back
        // over a choice made since.
        LauncherSettings migrated = new LauncherSettings();
        migrated.setSelectedInstance(GameDirectory.DEFAULT_ID, "chosen-since");
        SettingsManager.applyLegacySelection(migrated, "an-instance-from-an-older-launcher");
        assertEquals("chosen-since", migrated.getSelectedInstance(GameDirectory.DEFAULT_ID));
    }

    @Test
    void theDefaultFolderIsWhereAnInstanceGoes() throws Exception {
        assertTrue(Files.isDirectory(DshPaths.INSTANCES) || Files.createDirectories(DshPaths.INSTANCES) != null);
        Path instanceDirectory = DshPaths.instanceDirectory("wiring-path-check");
        assertEquals(DshPaths.INSTANCES.resolve("wiring-path-check"), instanceDirectory);
        FileUtils.deleteDirectory(instanceDirectory);
    }
}
