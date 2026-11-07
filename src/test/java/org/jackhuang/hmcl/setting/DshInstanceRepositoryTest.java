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
package org.jackhuang.hmcl.setting;

import com.mcxcc303.hdsl.instance.InstanceStore;

import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import com.mcxcc303.hdsl.instance.DshPortMode;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link DshInstanceRepository} selection and snapshot publication behavior.
 * <p>
 * Verifies instance sorting (newest first), selection persistence per game directory,
 * automatic fallback selection when instances are deleted, and snapshot publication
 * behavior across threads.
 */
class DshInstanceRepositoryTest {

	@TempDir
	Path root;

	@TempDir
	Path otherRoot;

	@BeforeEach
	void clearSelection() {
		settings().getSelectedInstance().clear();
	}

	private static DshInstance write(Path folder, String id, long createdAt) throws IOException {
		DshInstance instance = new DshInstance(id, "1.0.0", DshInstance.DEFAULT_PROFILE,
				folder.toString(), DshNodeRuntime.SYSTEM, DshHomeMode.ISOLATED, null,
				List.of(), Map.of(), DshInstanceIcon.DEFAULT.id(), null,
				DshPortMode.AUTO, 0, createdAt);
		Path directory = folder.resolve(id);
		Files.createDirectories(directory);
		JsonUtils.writeToJsonFile(directory.resolve(InstanceStore.MANIFEST_NAME), instance);
		return instance;
	}

	private static DshInstanceRepository repository(Path folder) {
		return new DshInstanceRepository(GameDirectory.of(folder));
	}

	/**
	 * Scans the repository and waits for publication and selection reconciliation to settle.
	 * <p>
	 * When a JavaFX UI thread is active, publication is marshaled to the UI thread via
	 * {@link javafx.application.Platform#runLater}. The latch ensures deterministic test
	 * synchronization before asserting state.
	 */
	private static void refresh(DshInstanceRepository repository) throws Exception {
		CountDownLatch published = new CountDownLatch(1);
		repository.snapshotProperty().addListener((observable, was, now) -> published.countDown());
		repository.refresh();
		if (!published.await(15, TimeUnit.SECONDS)) {
			throw new AssertionError("Timed out waiting for the read to be published");
		}
		waitUntilSettled(repository);
	}

	private static void waitUntilSettled(DshInstanceRepository repository) throws Exception {
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
		while (System.currentTimeMillis() < deadline) {
			if (repository.getSelectedInstance() != null) {
				return;
			}
			if (repository.getInstances().isEmpty()
					&& settings().getSelectedInstance(repository.getDirectory().id()) == null) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Timed out waiting for the choice to be published");
	}

	@Test
	void aSnapshotHoldsTheInstancesTheFolderHolds() throws Exception {
		write(root, "alpha", 1L);
		write(root, "beta", 2L);

		DshInstanceRepository repository = repository(root);
		refresh(repository);

		assertEquals(List.of("beta", "alpha"),
				repository.getInstances().stream().map(DshInstance::id).toList(),
				"the newest instance leads, as the manager's own listing has it");
	}

	/**
	 * Verifies that successive rescans of an identical directory still publish distinct snapshots.
	 * <p>
	 * {@link DshInstanceRepository.Snapshot} incorporates a monotonically increasing {@code revision}
	 * counter specifically so Java records (which compare structurally) do not suppress JavaFX
	 * property invalidation listeners when directory contents remain unchanged.
	 */
	@Test
	void aFolderReadAgainIsPublishedAgain() throws Exception {
		write(root, "alpha", 1L);

		DshInstanceRepository repository = repository(root);
		List<Long> published = new java.util.ArrayList<>();
		repository.snapshotProperty().addListener(
				(observable, was, now) -> published.add(now.revision()));

		refresh(repository);
		refresh(repository);

		assertEquals(2, published.size(),
				"every read has to reach the pages, even when the folder holds exactly what it held");
	}

	@Test
	void aFolderWithNothingChosenChoosesItsFirstInstance() throws Exception {
		write(root, "alpha", 1L);
		write(root, "beta", 2L);

		DshInstanceRepository repository = repository(root);
		refresh(repository);

		DshInstance selected = repository.getSelectedInstance();
		assertNotNull(selected, "a folder holding instances must have one selected");
		assertEquals("beta", selected.id());
		assertEquals("beta", settings().getSelectedInstance(repository.getDirectory().id()),
				"the choice has to be written down, or the next start forgets it");
	}

	@Test
	void anEmptyFolderSelectsNothing() throws Exception {
		DshInstanceRepository repository = repository(root);
		refresh(repository);

		assertNull(repository.getSelectedInstance());
		assertNull(settings().getSelectedInstance(repository.getDirectory().id()));
	}

	@Test
	void aSelectionThatIsGoneIsReplaced() throws Exception {
		write(root, "alpha", 1L);
		DshInstance beta = write(root, "beta", 2L);

		DshInstanceRepository repository = repository(root);
		refresh(repository);
		repository.setSelectedInstance(repository.getSnapshot().findInstance("alpha"));
		assertEquals("alpha", repository.getSelectedInstance().id());

		Files.delete(root.resolve("alpha").resolve(InstanceStore.MANIFEST_NAME));
		refresh(repository);

		assertNotNull(repository.getSelectedInstance());
		assertEquals(beta.id(), repository.getSelectedInstance().id());
	}

	@Test
	void everyFolderRemembersItsOwnSelection() throws Exception {
		DshInstance alpha = write(root, "alpha", 1L);
		DshInstance gamma = write(otherRoot, "gamma", 2L);

		DshInstanceRepository first = repository(root);
		refresh(first);
		DshInstanceRepository second = repository(otherRoot);
		refresh(second);

		first.setSelectedInstance(alpha);
		second.setSelectedInstance(gamma);

		assertEquals(alpha.id(), first.getSelectedInstance().id());
		assertEquals(gamma.id(), second.getSelectedInstance().id(),
				"one folder's choice must not be read as the other's");
	}

	@Test
	void choosingAnInstancePublishesIt() throws Exception {
		DshInstance alpha = write(root, "alpha", 1L);
		write(root, "beta", 2L);

		DshInstanceRepository repository = repository(root);
		refresh(repository);
		assertEquals("beta", repository.getSelectedInstance().id(), "the newest one is chosen first");

		List<String> seen = new java.util.ArrayList<>();
		repository.selectedInstanceProperty().addListener(
				(observable, was, now) -> seen.add(now == null ? null : now.id()));

		repository.setSelectedInstance(alpha);

		assertEquals(List.of("alpha"), seen,
				"the selection has to reach whoever is showing it, without being asked for");
	}
}
