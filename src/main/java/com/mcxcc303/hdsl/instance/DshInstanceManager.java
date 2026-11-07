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
import com.mcxcc303.hdsl.core.DshFiles;
import com.mcxcc303.hdsl.core.DshPaths;
import com.mcxcc303.hdsl.harness.DshSurface;
import com.mcxcc303.hdsl.harness.HarnessInstaller;
import com.mcxcc303.hdsl.launch.DshProcessManager;
import com.mcxcc303.hdsl.pack.ModpackInstaller;
import com.mcxcc303.hdsl.pack.PackExporter;
import com.mcxcc303.hdsl.pack.PackManifest;
import com.mcxcc303.hdsl.plugin.DshLocalPluginPaths;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Creates, enumerates, edits and removes {@link DshInstance}s.
 */
@NotNullByDefault
public final class DshInstanceManager {
	private static final List<Runnable> CHANGE_LISTENERS = new CopyOnWriteArrayList<>();

	private DshInstanceManager() {
	}

	/**
	 * Registers a listener notified after every write.
	 *
	 * @param listener the listener
	 */
	public static void addChangeListener(Runnable listener) {
		CHANGE_LISTENERS.add(listener);
	}

	/**
	 * Removes a listener registered by {@link #addChangeListener}.
	 *
	 * @param listener the listener
	 */
	public static void removeChangeListener(Runnable listener) {
		CHANGE_LISTENERS.remove(listener);
	}

	private static void fireChanged() {
		for (Runnable listener : CHANGE_LISTENERS) {
			try {
				listener.run();
			} catch (RuntimeException e) {
				LOG.warning("Instance change listener failed", e);
			}
		}
	}

	/**
	 * Creates and persists a new instance.
	 *
	 * @param id          the instance id
	 * @param version     the installed version to pin
	 * @param profile     the profile to boot
	 * @param workspace   the directory sessions are scoped to
	 * @param homeMode    how the {@code DSH_HOME} is resolved
	 * @param customHome  the custom home, required when {@code homeMode} is {@link DshHomeMode#CUSTOM}
	 * @param arguments   extra command-line arguments
	 * @param environment extra environment variables
	 * @return the created instance
	 * @throws DshException when the id is taken, the version is not installed, or the instance cannot be written
	 */
	public static DshInstance create(String id,
	                                 String version,
	                                 String profile,
	                                 Path workspace,
	                                 DshHomeMode homeMode,
	                                 @Nullable Path customHome,
	                                 List<String> arguments,
	                                 Map<String, String> environment) throws DshException {
		return create(id,
		              version,
		              profile,
		              workspace,
		              DshNodeRuntime.SYSTEM,
		              homeMode,
		              customHome,
		              arguments,
		              environment);
	}

	/**
	 * Creates and persists a new instance pinned to a Node runtime.
	 *
	 * @param id          the instance id
	 * @param version     the installed version to pin
	 * @param profile     the profile to boot
	 * @param workspace   the directory sessions are scoped to
	 * @param nodeRuntime the Node runtime selection, or {@code null} for the system runtime
	 * @param homeMode    how the {@code DSH_HOME} is resolved
	 * @param customHome  the custom home, required when {@code homeMode} is {@link DshHomeMode#CUSTOM}
	 * @param arguments   extra command-line arguments
	 * @param environment extra environment variables
	 * @return the created instance
	 * @throws DshException when the id is taken, the version is not installed, or the instance cannot be written
	 */
	public static DshInstance create(String id,
	                                 String version,
	                                 String profile,
	                                 Path workspace,
	                                 @Nullable String nodeRuntime,
	                                 DshHomeMode homeMode,
	                                 @Nullable Path customHome,
	                                 List<String> arguments,
	                                 Map<String, String> environment) throws DshException {
		return create(id,
		              version,
		              profile,
		              workspace,
		              nodeRuntime,
		              homeMode,
		              customHome,
		              arguments,
		              environment,
		              InstanceFolders.currentFolder());
	}

	/**
	 * Creates and persists a new instance in a folder of the caller's choosing.
	 *
	 * @param id          the instance id
	 * @param version     the installed version to pin
	 * @param profile     the profile to boot
	 * @param workspace   the directory sessions are scoped to
	 * @param nodeRuntime the Node runtime selection, or {@code null} for the system runtime
	 * @param homeMode    how the {@code DSH_HOME} is resolved
	 * @param customHome  the custom home, required when {@code homeMode} is {@link DshHomeMode#CUSTOM}
	 * @param arguments   extra command-line arguments
	 * @param environment extra environment variables
	 * @param folder      the folder to make the instance in
	 * @return the created instance
	 * @throws DshException when the id is taken, the version is not installed, or the instance cannot be written
	 */
	public static DshInstance create(String id,
	                                 String version,
	                                 String profile,
	                                 Path workspace,
	                                 @Nullable String nodeRuntime,
	                                 DshHomeMode homeMode,
	                                 @Nullable Path customHome,
	                                 List<String> arguments,
	                                 Map<String, String> environment,
	                                 Path folder) throws DshException {
		if (InstanceStore.find(id) != null) {
			// Instance IDs must be unique across all folders.
			throw new DshException("An instance named \"" + id + "\" already exists");
		}
		if (homeMode == DshHomeMode.CUSTOM && customHome == null) {
			throw new DshException("A custom DSH_HOME must be chosen for this instance");
		}

		// Reserve a persistent port for web surface instances.
		int port = DshSurface.ofProfile(profile).isWeb() ? DshPorts.reserve(id) : 0;

		Path directory = folder.toAbsolutePath().normalize().resolve(DshPaths.segment(id, "instance id"));

		DshInstance instance = new DshInstance(id,
		                                       version,
		                                       profile,
		                                       workspace.toAbsolutePath().normalize().toString(),
		                                       nodeRuntime,
		                                       homeMode,
		                                       customHome == null
		                                       ? null
		                                       : customHome.toAbsolutePath().normalize().toString(),
		                                       List.copyOf(arguments),
		                                       Map.copyOf(environment),
		                                       DshInstanceIcon.DEFAULT.id(),
		                                       null,
		                                       DshPortMode.AUTO,
		                                       port,
		                                       System.currentTimeMillis(),
		                                       directory.toString());

		try {
			// Never adopt a pre-existing folder: it may contain user files.
			Files.createDirectories(directory.getParent());
			Files.createDirectory(directory);
			if (homeMode == DshHomeMode.ISOLATED) {
				Files.createDirectories(instance.homeDirectory());
			}
		} catch (FileAlreadyExistsException e) {
			throw new DshException("Cannot create instance \"" + id + "\": " + directory + " already exists", e);
		} catch (IOException e) {
			throw new DshException("Failed to create " + directory, e);
		}

		InstanceStore.write(instance);
		LOG.info("Created instance " + id + " in " + directory + " (dsh " + version + ", home " + instance.homeMode() +
		         ")");
		fireChanged();
		return instance;
	}

	/**
	 * Overwrites an existing instance definition.
	 *
	 * @param instance the instance to persist
	 * @throws DshException when the manifest cannot be written
	 */
	public static void update(DshInstance instance) throws DshException {
		InstanceLocks.withLock(instance, "updated", () -> {
			if (!Files.isDirectory(instance.instanceDirectory())) {
				throw new DshException("Instance " + instance.id() + " does not exist");
			}
			InstanceStore.write(instance);
		});
		fireChanged();
	}

	/**
	 * Removes an instance, including its isolated home.
	 *
	 * @param id the instance id
	 * @throws DshException when the instance does not exist or cannot be removed
	 */
	public static void delete(String id) throws DshException {
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) throw new DshException("Instance " + id + " does not exist");

		Path directory = instance.instanceDirectory();

		// installation/rename/remove write-touch this directory, take this lock
		InstanceLocks.withLock(instance, "removed", () -> {
			// A running instance may hold files under the instance directory
			if (DshProcessManager.stateOf(id) != DshProcessManager.LaunchState.STOPPED) {
				LOG.info("Stopping instance " + id + " before removing it");
				DshProcessManager.stop(id);
			}

			try {
				DshFiles.deleteTree(directory);
			} catch (IOException e) {
				throw new DshException(
						"Failed to remove " + directory + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
			}
			LOG.info("Removed instance " + id);
			fireChanged();
		});
	}

	/**
	 * Reports whether an instance id is already taken.
	 *
	 * @param id the candidate id
	 * @return whether an instance with that id exists
	 */
	public static boolean exists(String id) {
		return InstanceStore.find(id) != null;
	}

	/**
	 * Renames an instance.
	 * <p>
	 * Moves instance dir to the new id's name. <br>
	 * The instance has to be stopped first.
	 *
	 * @param id    the current id
	 * @param newId the new id
	 * @return the renamed instance
	 * @throws DshException when the rename is refused or the directory cannot be moved
	 */
	public static DshInstance rename(String id, String newId) throws DshException {
		DshInstance existing = InstanceStore.find(id);
		if (existing == null) throw new DshException("Instance " + id + " does not exist");

		String normalized = newId.trim();
		if (normalized.isEmpty()) throw new DshException("An instance needs a name");
		if (normalized.equals(id)) return existing;
		if (exists(normalized)) throw new DshException("Instance " + normalized + " already exists");

		if (DshProcessManager.stateOf(id) != DshProcessManager.LaunchState.STOPPED) {
			throw new DshException("Cannot rename instance \"" + id + "\" while it is running or stopping");
		}

		// an instance in a folder added by the user stays in that folder
		Path oldDirectory = existing.instanceDirectory();
		Path newDirectory = oldDirectory.resolveSibling(DshPaths.segment(normalized, "instance id"));
		DshInstance renamed = existing.withId(normalized).withDirectory(newDirectory);

		return InstanceLocks.withLocks(List.of(existing, renamed), "renamed", () -> {
			boolean moved = false;
			if (Files.isDirectory(oldDirectory)) {
				// The destination may be user's directory, refuse to overwrite
				if (Files.exists(newDirectory, LinkOption.NOFOLLOW_LINKS)) {
					throw new DshException("Cannot rename \"" + id + "\" to \"" + normalized + "\": " + newDirectory +
					                       " already exists");
				}
				try {
					Files.move(oldDirectory, newDirectory);
					moved = true;
				} catch (IOException e) {
					throw new DshException("Failed to move " + oldDirectory + ": " + e.getMessage(), e);
				}
			}

			try {
				InstanceStore.write(renamed);
				// Local plugin entries store paths under the instance; relocate them after the move.
				DshLocalPluginPaths.relocate(renamed, oldDirectory, newDirectory);
			} catch (DshException e) { // Put the directory back
				if (moved) {
					try {
						Files.move(newDirectory, oldDirectory);
					} catch (IOException rollback) {
						LOG.warning("Failed to undo the rename of " + id, rollback);
					}
				}
				throw e;
			}

			fireChanged();
			return renamed;
		});
	}

	/**
	 * Copies an instance into a new one, including its configuration.
	 *
	 * @param id     the instance to copy
	 * @param newId  the new instance's id
	 * @param report receives progress lines, or {@code null}
	 * @return the copy
	 * @throws DshException when either id is unusable, or the copy cannot be made
	 */
	public static DshInstance duplicate(String id, String newId, @Nullable Consumer<String> report)
	throws DshException {
		DshInstance source = InstanceStore.find(id);
		if (source == null) {
			throw new DshException("Instance " + id + " does not exist");
		}
		String normalized = newId == null ? "" : newId.trim();
		if (normalized.isEmpty()) {
			throw new DshException("An instance needs a name");
		}
		if (normalized.equals(id)) {
			throw new DshException("Cannot duplicate instance \"" + id + "\" onto itself");
		}

		DshInstance copy = InstanceStore.find(normalized);
		if (copy == null) {
			// Preserve icon on the copy while assigning a fresh port.
			copy = create(normalized,
			              source.version(),
			              source.profile(),
			              source.workspacePath(),
			              source.nodeRuntime(),
			              DshHomeMode.ISOLATED,
			              null,
			              source.extraArguments(),
			              source.environment(),
			              source.instanceDirectory().getParent()).withIcon(source.iconOrDefault());
			update(copy);
		} else if (HarnessInstaller.isInstalled(copy)) {
			// Refuse duplicate ID unless resuming an uninstalled earlier attempt.
			throw new DshException("Instance " + normalized + " already exists");
		}

		Path pack = null;
		try {
			pack = temporaryPack();
			PackExporter.export(source, pack, report);
			ModpackInstaller.install(pack,
			                         normalized,
			                         source.workspacePath(),
			                         source.instanceDirectory().getParent(),
			                         report);
			copyOwnLauncherSettings(source, copy);
			DshInstance filled = InstanceStore.find(normalized);
			return filled == null ? copy : filled;
		} catch (DshException | RuntimeException failed) {
			if (failed instanceof DshPluginInstaller.DshBuildScriptApprovalRequired) {
				// Keep partial copy if build script approval is required to retain pending state.
				throw failed;
			}
			LOG.warning("Could not copy " + id + " to " + normalized + "; removing the copy", failed);
			HarnessInstaller.discardPartial(copy);
			try {
				delete(normalized);
			} catch (DshException | RuntimeException cleanupFailure) {
				LOG.warning("Could not remove the half-made copy " + normalized, cleanupFailure);
			}
			throw failed;
		} finally {
			InstanceStore.deleteQuietly(pack);
		}
	}

	private static Path temporaryPack() throws DshException {
		try {
			return Files.createTempFile("hdsl-duplicate-", PackManifest.FILE_EXTENSION);
		} catch (IOException e) {
			throw new DshException("Could not create a file for the copy", e);
		}
	}

	private static void copyOwnLauncherSettings(DshInstance source, DshInstance copy) throws DshException {
		Path from = source.instanceDirectory().resolve("settings.json");
		if (!Files.isRegularFile(from)) {
			return;
		}
		Path to = copy.instanceDirectory().resolve("settings.json");
		try {
			Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new DshException("Failed to copy " + from + " to " + to, e);
		}
	}

	/**
	 * Returns the next free id derived from a base name.
	 *
	 * @param base the base name
	 * @return the id, suffixed with a number when needed
	 */
	public static String nextId(String base) {
		String candidate = base + "-copy";
		int suffix = 2;
		while (exists(candidate)) {
			candidate = base + "-copy" + suffix;
			suffix++;
		}
		return candidate;
	}
}
