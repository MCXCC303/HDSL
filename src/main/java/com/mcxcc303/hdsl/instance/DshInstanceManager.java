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

/// Creates, enumerates, edits and removes [DshInstance]s.
///
/// Where an instance lives and how one is read is [InstanceStore]'s business, and which folders
/// the launcher looks in is [InstanceFolders]'s; what is left here is an instance's life —
/// creating one, changing it, renaming it, copying it and removing it — and telling the interface
/// when any of that happened.  A rename moves the directory inside its own folder and puts back
/// the plugin paths the profile recorded; a copy is made by describing the original as a pack and
/// installing it beside itself.
@NotNullByDefault
public final class DshInstanceManager {
	/// Listeners notified after an instance is created, changed or removed.
	///
	/// The manager is the only writer of instance manifests, so announcing
	/// writes here is what lets the interface observe the folder instead of
	/// polling it. Callers are notified on the thread that made the change.
	private static final List<Runnable> CHANGE_LISTENERS = new CopyOnWriteArrayList<>();

	private DshInstanceManager() {
	}

	/// Registers a listener notified after every write.
	///
	/// @param listener the listener
	public static void addChangeListener(Runnable listener) {
		CHANGE_LISTENERS.add(listener);
	}

	/// Removes a listener registered by [#addChangeListener].
	///
	/// @param listener the listener
	public static void removeChangeListener(Runnable listener) {
		CHANGE_LISTENERS.remove(listener);
	}

	/// Tells every listener that the instances on disk have changed.
	private static void fireChanged() {
		for (Runnable listener : CHANGE_LISTENERS) {
			try {
				listener.run();
			} catch (RuntimeException e) {
				LOG.warning("Instance change listener failed", e);
			}
		}
	}

	/// Creates and persists a new instance.
	///
	/// The isolated home directory is created eagerly so that a later launch
	/// cannot fail merely because the directory is missing.
	///
	/// @param id          the instance id, which must be unique and a safe path segment
	/// @param version     the installed version to pin
	/// @param profile     the profile to boot
	/// @param workspace   the directory sessions are scoped to
	/// @param homeMode    how the `DSH_HOME` is resolved
	/// @param customHome  the custom home, required when `homeMode` is [DshHomeMode#CUSTOM]
	/// @param arguments   extra command-line arguments
	/// @param environment extra environment variables
	/// @return the created instance
	/// @throws DshException when the id is taken, the version is not installed,
	///                       or the instance cannot be written
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

	/// Creates and persists a new instance pinned to a Node runtime.
	///
	/// @param id          the instance id, which must be unique and a safe path segment
	/// @param version     the installed version to pin
	/// @param profile     the profile to boot
	/// @param workspace   the directory sessions are scoped to
	/// @param nodeRuntime the Node runtime selection, or `null` for the system runtime
	/// @param homeMode    how the `DSH_HOME` is resolved
	/// @param customHome  the custom home, required when `homeMode` is [DshHomeMode#CUSTOM]
	/// @param arguments   extra command-line arguments
	/// @param environment extra environment variables
	/// @return the created instance
	/// @throws DshException when the id is taken, the version is not installed,
	///                       or the instance cannot be written
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

	/// Creates and persists a new instance in a folder of the caller's choosing.
	///
	/// The folder is where the instance's own directory is made: it holds the
	/// copy of DeepSeek Harness the instance runs, the plugin files it was given
	/// and, for an isolated home, its profile and sessions. A person who added a
	/// folder did so to keep instances there, so this is what "install an
	/// instance" has to be given — the folder they are looking at — rather than
	/// the one the launcher owns.
	///
	/// @param id          the instance id, which must be unique and a safe path segment
	/// @param version     the installed version to pin
	/// @param profile     the profile to boot
	/// @param workspace   the directory sessions are scoped to
	/// @param nodeRuntime the Node runtime selection, or `null` for the system runtime
	/// @param homeMode    how the `DSH_HOME` is resolved
	/// @param customHome  the custom home, required when `homeMode` is [DshHomeMode#CUSTOM]
	/// @param arguments   extra command-line arguments
	/// @param environment extra environment variables
	/// @param folder      the folder to make the instance in
	/// @return the created instance
	/// @throws DshException when the id is taken, the version is not installed,
	///                       or the instance cannot be written
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
			// An id names one instance. Two folders holding the same name would
			// make every lookup a coin toss, so the second one is refused here,
			// where the person can still be told which name is taken.
			throw new DshException("An instance named \"" + id + "\" already exists");
		}
		if (homeMode == DshHomeMode.CUSTOM && customHome == null) {
			throw new DshException("A custom DSH_HOME must be chosen for this instance");
		}

		// The port is reserved here, once, for the whole life of the instance:
		// the browser interface keys its state by origin, so an instance that
		// reached the same history through two ports would have two writers. A
		// surface that serves nothing needs none.
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
			// Never adopt a pre-existing folder: it may contain the user's own files.
			// createDirectory is atomic, unlike exists() followed by createDirectories().
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

	/// Overwrites an existing instance definition.
	///
	/// @param instance the instance to persist
	/// @throws DshException when the manifest cannot be written
	public static void update(DshInstance instance) throws DshException {
		InstanceLocks.withLock(instance, "updated", () -> {
			if (!Files.isDirectory(instance.instanceDirectory())) {
				throw new DshException("Instance " + instance.id() + " does not exist");
			}
			InstanceStore.write(instance);
		});
		fireChanged();
	}

	/// Removes an instance and, in isolated mode, everything it owns.
	///
	/// A shared or custom home is never deleted: other instances, or the user's
	/// own `dsh` installation, may still depend on it.
	///
	/// An instance that is running is stopped first, and the removal waits for
	/// it to exit. Windows will not remove a file that another process is
	/// executing or has open, and an instance that is up is holding its own copy
	/// of DeepSeek Harness and its own home open — so removing one that is
	/// running either fails halfway, leaving a folder that can neither be
	/// started nor removed, or succeeds against a live server whose files are
	/// disappearing underneath it. Removing an instance is an instruction about
	/// the instance, and an instance that is running is still that instance.
	///
	/// What is left when the removal still fails is named: "could not remove the
	/// instance" and "could not remove
	/// `…/instances/e2e1/dsh/node_modules/x/native.node` because another process
	/// has it open" are different answers to the person reading them, and only
	/// the second one says what to do next.
	///
	/// @param id the instance id
	/// @throws DshException when the instance does not exist or cannot be removed
	public static void delete(String id) throws DshException {
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			throw new DshException("Instance " + id + " does not exist");
		}
		Path directory = instance.instanceDirectory();
		// What is deleted is `<a folder the launcher lists>/<id>` and nothing
		// else, by construction rather than by a check here: [#read] stamps the
		// folder it read an instance from onto what it returns, and [#create]
		// refuses to make one anywhere but in a folder of the launcher's. A
		// manifest that names some other directory — hand-edited, or copied from
		// another machine — therefore cannot turn "remove this instance" into a
		// deletion somewhere the user never pointed the launcher at.

		// An install or a rename rewrites this directory; removal takes the same lock.
		InstanceLocks.withLock(instance, "removed", () -> {
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

	/// Reports whether an instance id is already taken.
	///
	/// @param id the candidate id
	/// @return whether an instance with that id exists
	public static boolean exists(String id) {
		return InstanceStore.find(id) != null;
	}

	/// Renames an instance.
	///
	/// The instance's own directory — the runtime it holds, the plugin files it keeps, and
	/// an isolated home if it has one — is named after the instance and moves with it. A
	/// home the instance shares, or one the user chose, lives elsewhere and is left alone —
	/// but it still records the instance's own files, so what it recorded moves too.
	///
	/// @param id    the current id
	/// @param newId the new id
	/// @return the renamed instance
	/// @throws DshException when either id is unusable or the move fails
	public static DshInstance rename(String id, String newId) throws DshException {
		DshInstance existing = InstanceStore.find(id);
		if (existing == null) {
			throw new DshException("Instance " + id + " does not exist");
		}
		String normalized = newId == null ? "" : newId.trim();
		if (normalized.isEmpty()) {
			throw new DshException("An instance needs a name");
		}
		if (normalized.equals(id)) {
			return existing;
		}
		if (exists(normalized)) {
			throw new DshException("Instance " + normalized + " already exists");
		}
		if (DshProcessManager.stateOf(id) != DshProcessManager.LaunchState.STOPPED) {
			throw new DshException("Cannot rename instance \"" + id + "\" while it is running or stopping");
		}

		// The instance's own directory, wherever it is: an instance in a folder
		// the user added moves inside that folder, and one in the folder the
		// launcher owns never leaves it.
		Path oldDirectory = existing.instanceDirectory();
		Path newDirectory = oldDirectory.resolveSibling(DshPaths.segment(normalized, "instance id"));
		DshInstance renamed = existing.withId(normalized).withDirectory(newDirectory);

		return InstanceLocks.withLocks(List.of(existing, renamed), "renamed", () -> {
			boolean moved = false;
			if (Files.isDirectory(oldDirectory)) {
				// Nothing at the destination is removed to make room for the move. The
				// destination is a sibling inside a folder of the launcher's — which for an
				// instance in a folder the user added is a directory they own, and may be
				// their own directory under the new name rather than an instance's. Renaming
				// over it would delete what is there, tree and all, with nothing said: the
				// name is refused instead, and the folder is named so it can be moved aside.
				if (Files.exists(newDirectory, LinkOption.NOFOLLOW_LINKS)) {
					throw new DshException(
							"Cannot rename \"" + id + "\" to \"" + normalized + "\": " + newDirectory +
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
				// A plugin installed from a file the instance holds is recorded by that file's
				// path, and every later operation on the profile resolves the path again — so a
				// rename that left those records alone would stop the instance installing or
				// removing any plugin at all. They move with the files they name.
				DshLocalPluginPaths.relocate(renamed, oldDirectory, newDirectory);
			} catch (DshException e) {
				// Put the directory back rather than leaving the home orphaned under
				// a name no instance answers to.
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

	/// Copies an instance into a new one, its configuration and all.
	///
	/// A copy is the original's **configuration**: the harness version it pins, its profile, the
	/// plugin list in its own order, the profile's patch layer, the plugins it installed from files,
	/// the settings its plugins keep, and its skill packs. What it is not is the original's
	/// **state**: session history and keys stay behind, because a copy of somebody's past is not what
	/// "copy this instance" asks for, and a key duplicated into a second home is a key nobody meant
	/// to make. The instance's own launcher settings — its install-script policy, the commands that
	/// run around it, which account it uses — come along, because those are what "this instance"
	/// means to the launcher rather than to the harness.
	///
	/// Both halves of that are already written down elsewhere: a pack *is* that description of an
	/// instance, and installing one *is* that rebuild — version, profile, patch, local plugin files,
	/// settings sections, skills, and nothing that looks like a key. So a duplicate writes a pack to
	/// a temporary file and installs it, rather than copying directories: copying would have to
	/// answer the same questions again — which of `node_modules`, `dsh/`, `sessions/` and
	/// `.credentials.yaml` may travel — and it would leave the copy's `file:` plugin paths pointing
	/// into the original's plugin directory.
	///
	/// **Resumable on purpose.** pnpm refuses to run a package's install script until it is told to,
	/// and it says so as a failure. The copy is kept in that case — the question is written into its
	/// own profile, so the answer has somewhere to go — and the work runs again from the copy it
	/// already made. Every other failure takes the half-made copy with it: an instance that appears
	/// in the list and cannot start is worse than one that never appeared.
	///
	/// @param id     the instance to copy
	/// @param newId  the new instance's id
	/// @param report receives progress lines, or `null`
	/// @return the copy
	/// @throws DshException when either id is unusable, or the copy cannot be made
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
			// The copy carries the original's icon, and only its icon: a copy that
			// is indistinguishable from what it was copied from is a copy nobody can
			// find in the list. The port is deliberately not copied — two instances
			// may not be given the same one — and the write is what makes the icon
			// survive, because a copy built by `withIcon` alone is never persisted.
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
			// An id taken by an instance that is already whole is somebody else's. One that is
			// half-made is this call's own earlier attempt, kept because pnpm asked about an install
			// script: the answer goes into that instance's profile, so the work continues there
			// instead of starting over.
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
				// The one failure that must keep what it made: the question pnpm raised is written
				// into the copy's profile, and removing the copy would take the question with it.
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

	/// Creates the temporary pack a duplicate describes itself into.
	///
	/// It goes through the system's temporary directory rather than the launcher's own, because it is
	/// not a thing the launcher keeps: it is written, read back, and deleted in the same call, and a
	/// copy that is interrupted leaves a file the system already knows how to sweep.
	///
	/// @return the file, which does not exist yet
	/// @throws DshException when the system cannot make one
	private static Path temporaryPack() throws DshException {
		try {
			return Files.createTempFile("hdsl-duplicate-", PackManifest.FILE_EXTENSION);
		} catch (IOException e) {
			throw new DshException("Could not create a file for the copy", e);
		}
	}

	/// Copies an instance's own launcher settings to another instance.
	///
	/// The file holds what somebody chose *for this instance*: its install-script policy, the
	/// commands that run around it, which account it uses, and how loudly it logs. None of that is a
	/// secret — the account is named, not keyed — and all of it is what a copy should inherit, which
	/// is why it is carried beside the pack rather than inside it.
	///
	/// @param source the instance copied from
	/// @param copy   the instance being filled
	/// @throws DshException when the file exists but cannot be copied
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

	/// Returns the next free id derived from a base name.
	///
	/// @param base the base name
	/// @return the id, suffixed with a number when needed
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
