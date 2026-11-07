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

import com.mcxcc303.hdsl.core.DshPaths;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.instance.InstanceStore;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Manages the game directories available to the launcher.
 *
 * <p>The launcher creates and owns its default directory, but can also use directories managed by the user.<br>
 * Adding a directory does not move or modify its instances.
 *
 * <p>Each directory has its own {@link DshInstanceRepository}, which owns the directory's instance snapshot and selection state.<br>
 * Pages observe the repository associated with the currently selected directory.
 */
@NotNullByDefault
public final class GameDirectoryManager {
	private GameDirectoryManager() {
	}

	/**
	 * Whether {@link #init()} has run
	 */
	private static boolean initialized;

	/**
	 * The folders the launcher knows about, in the order they are shown
	 */
	private static final ObservableList<GameDirectory> mergedGameDirectories = FXCollections.observableArrayList();

	/**
	 * Read-only view of {@link #mergedGameDirectories} handed to callers
	 */
	private static final @UnmodifiableView ObservableList<GameDirectory> mergedGameDirectoriesView = FXCollections.unmodifiableObservableList(
			mergedGameDirectories);

	/**
	 * The repository of every folder, created on first use
	 */
	private static final Map<String, DshInstanceRepository> repositories = new HashMap<>();

	/**
	 * The folder whose instances the interface shows
	 */
	private static final ObjectProperty<@Nullable GameDirectory> selectedGameDirectory = new SimpleObjectProperty<>(
			GameDirectoryManager.class,
			"selectedGameDirectory");

	/**
	 * The repository of the selected folder, or {@code null} before one is resolved
	 */
	private static @Nullable DshInstanceRepository selectedRepository;

	/**
	 * The instance selected in the selected folder.
	 *
	 * <p>Projected from the selected repository instead of kept per directory.<br>
	 * A page binds to one property that survives a switch of folder.
	 */
	private static final javafx.beans.property.ReadOnlyObjectWrapper<@Nullable DshInstance> selectedInstance = new javafx.beans.property.ReadOnlyObjectWrapper<>(
			GameDirectoryManager.class,
			"selectedInstance");

	/**
	 * Keeps the projection above in step with the selected repository
	 */
	private static final javafx.beans.value.ChangeListener<@Nullable DshInstance> selectedInstanceListener = (observable, was, now) -> selectedInstance.set(
			now);

	/**
	 * Listeners notified whenever the selected folder publishes a snapshot
	 */
	private static final List<Consumer<DshInstanceRepository>> versionsListeners = new ArrayList<>(4);

	/**
	 * Keeps the registered listeners in step with the selected folder's snapshot.
	 *
	 * <p>Held as a field rather than written inline.<br>
	 * A listener is removed by identity, so a method reference written twice is two objects.<br>
	 * The previous folder would then keep talking to the interface.
	 */
	private static final javafx.beans.value.ChangeListener<DshInstanceRepository.Snapshot> snapshotListener = (observable, was, now) -> {
		for (Consumer<DshInstanceRepository> listener : List.copyOf(versionsListeners)) {
			// Caught one by one: a listener that throws must not leave the ones after it showing the previous snapshot
			try {
				listener.accept(selectedRepository);
			} catch (RuntimeException e) {
				LOG.warning("A listener failed to handle a new snapshot", e);
			}
		}
	};

	/**
	 * Initializes the folder list and the selected folder from the settings.
	 *
	 * <p>Called by the launcher at start-up; every other entry point calls it on first use.<br>
	 * A command-line run that never starts the interface still sees the folders it was given.
	 */
	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;

		selectedGameDirectory.addListener((observable, was, now) -> {
			if (now == null) {
				throw new IllegalStateException("selectedGameDirectory cannot be null");
			}
			settings().selectedGameDirectoryIdProperty().set(now.id());
			SettingsManager.save();

			DshInstanceRepository repository = getOrCreateRepository(now);
			installRepository(repository);

			// The folder is re-read on every switch, as HMCL re-reads a game directory when it becomes the selected one.
			// A folder not looked at while it was not selected may well have changed.
			repository.refresh();
		});

		settings().gameDirectoriesProperty().addListener((observable, was, now) -> rebuildDirectories());

		rebuildDirectories();
		selectedGameDirectory.set(resolveSelectedDirectory());

		// Every write to an instance manifest comes through the manager.
		// One listener there is enough to keep every folder's snapshot current.
		DshInstanceManager.addChangeListener(GameDirectoryManager::refreshRepositories);
	}

	/**
	 * Returns the folders the launcher keeps instances in, the shown one first.
	 *
	 * <p>The domain layer holds no settings, so the folders are resolved here.<br>
	 * The first folder is where a new instance is created.
	 *
	 * @return the folders
	 */
	public static List<Path> instanceFolders() {
		List<Path> all = new ArrayList<>();
		all.add(selected().directory());
		for (GameDirectory directory : getGameDirectories()) {
			all.add(directory.directory());
		}
		return List.copyOf(all);
	}

	/**
	 * Returns the folders the launcher knows about.
	 *
	 * <p>The list is observable, so a list showing the folders follows an addition or a removal without being rebuilt by hand.
	 *
	 * @return the folders, in the order they are shown
	 */
	public static @UnmodifiableView ObservableList<GameDirectory> getGameDirectories() {
		ensureInitialized();
		return mergedGameDirectoriesView;
	}

	/**
	 * Returns the configured directories.
	 *
	 * @return the folders, in the order they are shown
	 */
	public static List<GameDirectory> directories() {
		return getGameDirectories();
	}

	/**
	 * Returns the property holding the folder whose instances are shown
	 *
	 * @return the selected-folder property
	 */
	public static ObjectProperty<@Nullable GameDirectory> selectedGameDirectoryProperty() {
		ensureInitialized();
		return selectedGameDirectory;
	}

	/**
	 * Returns the folder whose instances the list shows
	 *
	 * @return the selected directory, falling back to the first
	 */
	public static GameDirectory selected() {
		ensureInitialized();
		GameDirectory directory = selectedGameDirectory.get();
		return directory != null ? directory : resolveSelectedDirectory();
	}

	/**
	 * Selects the directory whose instances the list shows
	 *
	 * @param id the directory identifier
	 */
	public static void select(String id) {
		GameDirectory directory = find(id);
		if (directory != null) {
			selectedGameDirectoryProperty().set(directory);
		}
	}

	/**
	 * Selects the folder whose instances the list shows
	 *
	 * @param directory the folder, which must be one of {@link #getGameDirectories()}
	 */
	public static void setSelectedGameDirectory(GameDirectory directory) {
		if (!getGameDirectories().contains(directory)) {
			throw new IllegalArgumentException("Unknown game directory: " + directory);
		}
		selectedGameDirectoryProperty().set(directory);
	}

	/**
	 * Returns the repository of the selected folder
	 *
	 * @return the repository
	 */
	public static DshInstanceRepository getSelectedRepository() {
		ensureInitialized();
		DshInstanceRepository repository = selectedRepository;
		if (repository == null) {
			repository = getOrCreateRepository(selected());
			installRepository(repository);
			repository.refresh();
		}
		return repository;
	}

	/**
	 * Returns the instance selected in the selected folder
	 *
	 * @return the instance, or {@code null} when the folder holds none
	 */
	public static @Nullable DshInstance getSelectedInstance() {
		return getSelectedRepository().getSelectedInstance();
	}

	/**
	 * Returns the property holding the instance selected in the selected folder.
	 *
	 * <p>The value follows a switch of folder, a change of selection, and a change to the folder's contents.<br>
	 * A page shows the right instance without ever asking whether it should.
	 *
	 * @return the selected-instance property
	 */
	public static javafx.beans.property.ReadOnlyObjectProperty<@Nullable DshInstance> selectedInstanceProperty() {
		getSelectedRepository();
		return selectedInstance.getReadOnlyProperty();
	}

	/**
	 * Records the instance selected in the selected folder
	 *
	 * @param instance the instance to select, or {@code null} to select nothing
	 */
	public static void setSelectedInstance(@Nullable DshInstance instance) {
		getSelectedRepository().setSelectedInstance(instance);
	}

	/**
	 * Registers a listener for changes to the selected folder's instances.
	 *
	 * <p>If the repository is already loaded, the listener is notified immediately.
	 *
	 * @param listener the listener to register
	 */
	public static void registerVersionsListener(Consumer<DshInstanceRepository> listener) {
		DshInstanceRepository repository = getSelectedRepository();
		if (repository.isLoaded()) {
			listener.accept(repository);
		}
		versionsListeners.add(listener);
	}

	/**
	 * Re-reads every folder the launcher has looked at.
	 *
	 * <p>Called after a write so that a folder which is not the selected one is current by the time it is selected.
	 */
	public static void refreshRepositories() {
		ensureInitialized();
		for (DshInstanceRepository repository : List.copyOf(repositories.values())) {
			repository.refresh();
		}
	}

	/**
	 * Finds a directory by identifier
	 *
	 * @param id the identifier
	 * @return the directory, or {@code null} when there is none
	 */
	public static @Nullable GameDirectory find(String id) {
		for (GameDirectory directory : getGameDirectories()) {
			if (directory.id().equals(id)) {
				return directory;
			}
		}
		return null;
	}

	/**
	 * Adds a folder to the list.
	 *
	 * @param path the folder to add
	 * @return the directory entry, existing or new
	 * @throws IllegalArgumentException when the path is not a directory
	 */
	public static GameDirectory add(Path path) {
		return add(path, null);
	}

	/**
	 * Adds a folder, under a name of its own if one is given.
	 *
	 * <p>The path is recorded as given: a folder chosen relative to the launcher stays relative, and one chosen by its absolute path stays absolute.<br>
	 * Both are resolved the same way when the folder is read back.
	 *
	 * @param path       the folder to add
	 * @param customName the name to show, or {@code null} to derive one from the path
	 * @return the directory entry, existing or new
	 * @throws IllegalArgumentException when the path is not a directory
	 */
	public static GameDirectory add(Path path, @Nullable String customName) {
		ensureInitialized();
		Path normalized = path.toAbsolutePath().normalize();
		if (!Files.isDirectory(normalized)) {
			throw new IllegalArgumentException(normalized + " is not a directory");
		}
		List<GameDirectory> updated = new ArrayList<>(directories());
		int known = updated.size();
		GameDirectory added = entryFor(updated, path, customName);
		if (updated.size() > known) {
			settings().gameDirectoriesProperty().set(List.copyOf(updated));
		}
		return added;
	}

	/**
	 * Returns the entry for one folder, adding it to the list when it is not there yet.
	 *
	 * <p>A folder is identified by its path, not by its entry.<br>
	 * A folder already in the list returns the entry the list holds for it rather than a second one.
	 *
	 * <p>{@link LauncherSettings#getSelectedInstance()} is keyed by the entry's id.<br>
	 * A second entry for one folder is therefore a second folder to everything that reads it.
	 *
	 * @param directories the entries, which a new one is added to
	 * @param path        the folder
	 * @param customName  the name to show, or {@code null} to derive one from the path
	 * @return the entry for the folder
	 */
	static GameDirectory entryFor(List<GameDirectory> directories, Path path, @Nullable String customName) {
		Path normalized = path.toAbsolutePath().normalize();
		for (GameDirectory directory : directories) {
			if (directory.directory().equals(normalized)) {
				return directory;
			}
		}
		GameDirectory added = GameDirectory.of(path, customName);
		directories.add(added);
		return added;
	}

	/**
	 * Removes a folder from the list.
	 *
	 * <p>Nothing is deleted: the instances inside keep their files and simply stop being listed.<br>
	 * The directory the launcher owns cannot be removed, because it is where a new instance goes when nowhere else is chosen.
	 *
	 * @param id the directory identifier
	 * @throws IllegalArgumentException when the default directory is named
	 */
	public static void remove(String id) {
		ensureInitialized();
		if (GameDirectory.DEFAULT_ID.equals(id)) {
			throw new IllegalArgumentException("The default directory cannot be removed");
		}
		List<GameDirectory> updated = new ArrayList<>(directories());
		updated.removeIf(directory -> directory.id().equals(id));
		settings().gameDirectoriesProperty().set(List.copyOf(updated));
		if (id.equals(settings().selectedGameDirectoryIdProperty().get())) {
			select(GameDirectory.DEFAULT_ID);
		}
	}

	/**
	 * Counts the instances a folder holds.
	 *
	 * <p>Used to tell the user what adding a folder found.
	 *
	 * @param directory the folder
	 * @return how many instances it holds
	 */
	public static int countInstances(GameDirectory directory) {
		Path root = directory.directory();
		if (!Files.isDirectory(root)) {
			return 0;
		}
		try (var children = Files.list(root)) {
			return (int) children.filter(Files::isDirectory)
			                     .filter(child -> Files.isRegularFile(child.resolve(InstanceStore.MANIFEST_NAME)))
			                     .count();
		} catch (java.io.IOException e) {
			return 0;
		}
	}

	/**
	 * Reports whether this folder is the one the launcher owns
	 *
	 * @param directory the directory
	 * @return whether the launcher writes its own instances there
	 */
	public static boolean isOwned(GameDirectory directory) {
		return DshPaths.INSTANCES.toAbsolutePath().normalize().equals(directory.directory());
	}

	/**
	 * Returns the repository of a folder, creating it on first use
	 *
	 * @param directory the folder
	 * @return the repository
	 */
	private static DshInstanceRepository getOrCreateRepository(GameDirectory directory) {
		return repositories.computeIfAbsent(directory.id(), id -> new DshInstanceRepository(directory));
	}

	/**
	 * Makes a repository the selected one and wires it to the listeners.
	 *
	 * <p>The listeners are moved rather than added again, so switching folders does not leave the previous one talking to the interface.<br>
	 * The projection is set immediately; a repository that has already been read holds its selection.
	 *
	 * @param repository the repository that becomes the selected one
	 */
	private static void installRepository(DshInstanceRepository repository) {
		if (selectedRepository == repository) {
			return;
		}
		if (selectedRepository != null) {
			selectedRepository.snapshotProperty().removeListener(snapshotListener);
			selectedRepository.selectedInstanceProperty().removeListener(selectedInstanceListener);
		}
		selectedRepository = repository;
		repository.snapshotProperty().addListener(snapshotListener);
		repository.selectedInstanceProperty().addListener(selectedInstanceListener);
		selectedInstance.set(repository.getSelectedInstance());
	}

	/**
	 * Rebuilds the folder list from the settings.
	 *
	 * <p>A launcher that has never been given a folder reports the one it owns, so the default needs no entry in the settings and no migration to introduce.
	 */
	private static void rebuildDirectories() {
		List<GameDirectory> configured = settings().gameDirectoriesProperty().get();
		List<GameDirectory> resolved = configured == null || configured.isEmpty()
		                               ? List.of(GameDirectory.defaultDirectory())
		                               : List.copyOf(configured);
		mergedGameDirectories.setAll(resolved);

		// The folder that was selected may have just been removed; the settings hold the answer, and the property follows it
		GameDirectory next = resolveSelectedDirectory();
		if (!next.equals(selectedGameDirectory.get())) {
			selectedGameDirectory.set(next);
		}
	}

	/**
	 * Resolves the remembered selected folder against the current list
	 *
	 * @return the selected folder, falling back to the first
	 */
	private static GameDirectory resolveSelectedDirectory() {
		String id = settings().selectedGameDirectoryIdProperty().get();
		for (GameDirectory directory : mergedGameDirectories) {
			if (directory.id().equals(id)) {
				return directory;
			}
		}
		return mergedGameDirectories.get(0);
	}

	/**
	 * Initializes this class on first use
	 */
	private static void ensureInitialized() {
		if (!initialized) {
			init();
		}
	}
}
