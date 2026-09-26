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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Creates, enumerates, edits and removes [DshInstance]s.
///
/// An instance is stored as `<folder>/<id>/instance.json`, one directory per
/// instance, named after it. The folder is one of the launcher's own: the one it
/// created and the ones the user added, of which the one being shown is where a
/// new instance goes. The instance's id doubles as its directory name, which is
/// what makes an isolated `DSH_HOME` trivially private — it lives at
/// `<folder>/<id>/home`.
///
/// The folder an instance is in is part of the instance, not a detail of how it
/// was listed: its copy of DeepSeek Harness, its local plugin files and its
/// isolated home are all inside `<folder>/<id>`, so [#find] searches every folder
/// the launcher knows and the directory it read is recorded on what it returns.
@NotNullByDefault
public final class DshInstanceManager {
    private DshInstanceManager() {
    }

    /// The manifest file inside an instance directory.
    public static final String MANIFEST_NAME = "instance.json";

    /// The folders the launcher looks for instances in, the one it is showing first.
    ///
    /// Which folders exist is a question only the settings can answer, and the
    /// settings live a layer above this package — so the default answer is asked
    /// of them, every time. It is asked lazily rather than installed once,
    /// because the launcher has three ways in and only one of them starts an
    /// interface: a command-line run that adds a folder and then makes an
    /// instance in it is two processes, and the second one never touches the
    /// interface. Answering from the settings is what makes the folder the user
    /// chose mean the same thing in all three.
    ///
    /// The one thing that keeps this from being a circular dependency is that
    /// the settings' own instance listing reads folders directly rather than
    /// through here.
    private static volatile java.util.function.Supplier<List<Path>> folders =
            DshInstanceManager::foldersFromTheSettings;

    /// Asks the settings which folders the launcher keeps instances in.
    ///
    /// A launcher whose settings cannot be read — a build with none, a test that
    /// has not set any up — still has the folder it owns, which is where its
    /// instances were before folders could be added at all.
    ///
    /// @return the folders, the one being shown first
    private static List<Path> foldersFromTheSettings() {
        try {
            return org.jackhuang.hmcl.setting.GameDirectoryManager.instanceFolders();
        } catch (RuntimeException | Error e) {
            LOG.warning("Could not read the instance folders from the settings; "
                    + "using the folder the launcher owns", e);
            return List.of(DshPaths.INSTANCES);
        }
    }

    /// Points the manager at the folders the launcher knows about.
    ///
    /// The settings are the answer by default; this is for a caller that wants a
    /// different one, and for a test, which has no settings to point at and
    /// folders of its own to use instead.
    ///
    /// @param supplier the folders, the one being shown first
    /// @return the supplier that was in place, so a caller passing through can
    ///         put it back
    public static java.util.function.Supplier<List<Path>> setFolders(
            java.util.function.Supplier<List<Path>> supplier) {
        java.util.function.Supplier<List<Path>> previous = folders;
        folders = supplier;
        return previous;
    }

    /// Returns the folders the launcher looks for instances in, the current one first.
    ///
    /// @return the folders, absolute and without duplicates
    public static List<Path> folders() {
        List<Path> resolved = new ArrayList<>();
        for (Path folder : folders.get()) {
            if (folder == null) {
                continue;
            }
            Path normalized = folder.toAbsolutePath().normalize();
            if (!resolved.contains(normalized)) {
                resolved.add(normalized);
            }
        }
        if (resolved.isEmpty()) {
            resolved.add(DshPaths.INSTANCES.toAbsolutePath().normalize());
        }
        return List.copyOf(resolved);
    }

    /// Returns the folder a new instance is made in.
    ///
    /// The one the interface is showing: adding a folder is how a person says
    /// where their instances belong, and installing one is the moment they mean
    /// it. Nothing is written anywhere else until they choose another folder.
    ///
    /// @return the folder
    public static Path currentFolder() {
        return folders().get(0);
    }

    /// Listeners notified after an instance is created, changed or removed.
    ///
    /// The manager is the only writer of instance manifests, so announcing
    /// writes here is what lets the interface observe the folder instead of
    /// polling it. Callers are notified on the thread that made the change.
    private static final java.util.List<Runnable> CHANGE_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

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

    /// Lists every readable instance, newest first.
    ///
    /// Directories without a manifest, or with an unreadable one, are skipped
    /// rather than failing the whole listing.
    ///
    /// @return the known instances
    public static List<DshInstance> listIn(java.nio.file.Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<DshInstance> instances = new ArrayList<>();
        try (var children = Files.list(root)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                DshInstance instance = read(child);
                if (instance != null) {
                    instances.add(instance);
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to read instances in " + root, e);
            return List.of();
        }
        instances.sort(java.util.Comparator.comparing(DshInstance::createdAt).reversed());
        return List.copyOf(instances);
    }

    public static List<DshInstance> list() {
        List<DshInstance> instances = new ArrayList<>();
        java.util.Set<Path> seen = new java.util.HashSet<>();
        for (Path root : folders()) {
            for (DshInstance instance : listIn(root)) {
                Path directory;
                try {
                    directory = instance.instanceDirectory();
                } catch (DshException e) {
                    continue;
                }
                if (seen.add(directory)) {
                    instances.add(instance);
                }
            }
        }
        instances.sort(Comparator.comparingLong(DshInstance::createdAt).reversed());
        return instances;
    }

    /// Finds an instance by id.
    ///
    /// Looked for in every folder the launcher knows about: an id names one
    /// instance, and a person who sees it in the list has to be able to open,
    /// launch or remove it without knowing which folder it came from. The folder
    /// being shown is searched first, so the common case reads one directory.
    ///
    /// @param id the instance id
    /// @return the instance, or `null` when it does not exist or is unreadable
    public static @Nullable DshInstance find(String id) {
        try {
            String segment = DshPaths.segment(id, "instance id");
            for (Path root : folders()) {
                DshInstance instance = read(root.resolve(segment));
                if (instance != null) {
                    return instance;
                }
            }
            return null;
        } catch (DshException e) {
            return null;
        }
    }

    /// Creates and persists a new instance.
    ///
    /// The isolated home directory is created eagerly so that a later launch
    /// cannot fail merely because the directory is missing.
    ///
    /// @param id            the instance id, which must be unique and a safe path segment
    /// @param version       the installed version to pin
    /// @param profile       the profile to boot
    /// @param workspace     the directory sessions are scoped to
    /// @param homeMode      how the `DSH_HOME` is resolved
    /// @param customHome    the custom home, required when `homeMode` is [DshHomeMode#CUSTOM]
    /// @param arguments     extra command-line arguments
    /// @param environment   extra environment variables
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
        return create(id, version, profile, workspace, DshNodeRuntime.SYSTEM, homeMode, customHome,
                arguments, environment);
    }

    /// Creates and persists a new instance pinned to a Node runtime.
    ///
    /// @param id            the instance id, which must be unique and a safe path segment
    /// @param version       the installed version to pin
    /// @param profile       the profile to boot
    /// @param workspace     the directory sessions are scoped to
    /// @param nodeRuntime   the Node runtime selection, or `null` for the system runtime
    /// @param homeMode      how the `DSH_HOME` is resolved
    /// @param customHome    the custom home, required when `homeMode` is [DshHomeMode#CUSTOM]
    /// @param arguments     extra command-line arguments
    /// @param environment   extra environment variables
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
        return create(id, version, profile, workspace, nodeRuntime, homeMode, customHome,
                arguments, environment, currentFolder());
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
    /// @param id            the instance id, which must be unique and a safe path segment
    /// @param version       the installed version to pin
    /// @param profile       the profile to boot
    /// @param workspace     the directory sessions are scoped to
    /// @param nodeRuntime   the Node runtime selection, or `null` for the system runtime
    /// @param homeMode      how the `DSH_HOME` is resolved
    /// @param customHome    the custom home, required when `homeMode` is [DshHomeMode#CUSTOM]
    /// @param arguments     extra command-line arguments
    /// @param environment   extra environment variables
    /// @param folder        the folder to make the instance in
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
        if (find(id) != null) {
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

        Path directory = folder.toAbsolutePath().normalize()
                .resolve(DshPaths.segment(id, "instance id"));

        DshInstance instance = new DshInstance(id, version, profile,
                workspace.toAbsolutePath().normalize().toString(),
                nodeRuntime,
                homeMode,
                customHome == null ? null : customHome.toAbsolutePath().normalize().toString(),
                List.copyOf(arguments), Map.copyOf(environment),
                DshInstanceIcon.DEFAULT.id(), null, DshPortMode.AUTO, port,
                System.currentTimeMillis(), directory.toString());

        try {
            Files.createDirectories(directory);
            if (homeMode == DshHomeMode.ISOLATED) {
                Files.createDirectories(instance.homeDirectory());
            }
        } catch (IOException e) {
            throw new DshException("Failed to create " + directory, e);
        }

        write(instance);
        LOG.info("Created instance " + id + " in " + directory
                + " (dsh " + version + ", home " + instance.homeMode() + ")");
        fireChanged();
        return instance;
    }

    /// Overwrites an existing instance definition.
    ///
    /// @param instance the instance to persist
    /// @throws DshException when the manifest cannot be written
    public static void update(DshInstance instance) throws DshException {
        if (!Files.isDirectory(instance.instanceDirectory())) {
            throw new DshException("Instance " + instance.id() + " does not exist");
        }
        write(instance);
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
        DshInstance instance = find(id);
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

        if (DshProcessManager.stateOf(id) != DshProcessManager.LaunchState.STOPPED) {
            LOG.info("Stopping instance " + id + " before removing it");
            DshProcessManager.stop(id);
        }

        try {
            DshFiles.deleteTree(directory);
        } catch (IOException e) {
            throw new DshException("Failed to remove " + directory
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
        }
        LOG.info("Removed instance " + id);
        fireChanged();
    }

    /// Reports whether an instance id is already taken.
    ///
    /// @param id the candidate id
    /// @return whether an instance with that id exists
    public static boolean exists(String id) {
        return find(id) != null;
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
        DshInstance existing = find(id);
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

        DshInstance renamed = existing.withId(normalized);
        // The instance's own directory, wherever it is: an instance in a folder
        // the user added moves inside that folder, and one in the folder the
        // launcher owns never leaves it.
        Path oldDirectory = existing.instanceDirectory();
        Path newDirectory = oldDirectory.resolveSibling(
                DshPaths.segment(normalized, "instance id"));
        renamed = renamed.withDirectory(newDirectory);

        boolean moved = false;
        if (Files.isDirectory(oldDirectory)) {
            try {
                deleteQuietly(newDirectory);
                Files.move(oldDirectory, newDirectory);
                moved = true;
            } catch (IOException e) {
                throw new DshException("Failed to move " + oldDirectory + ": " + e.getMessage(), e);
            }
        }

        try {
            write(renamed);
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
    public static DshInstance duplicate(String id, String newId,
                                        @Nullable java.util.function.Consumer<String> report)
            throws DshException {
        DshInstance source = find(id);
        if (source == null) {
            throw new DshException("Instance " + id + " does not exist");
        }
        String normalized = newId == null ? "" : newId.trim();
        if (normalized.isEmpty()) {
            throw new DshException("An instance needs a name");
        }

        DshInstance copy = find(normalized);
        if (copy == null) {
            // The copy carries the original's icon, and only its icon: a copy that
            // is indistinguishable from what it was copied from is a copy nobody can
            // find in the list. The port is deliberately not copied — two instances
            // may not be given the same one — and the write is what makes the icon
            // survive, because a copy built by `withIcon` alone is never persisted.
            copy = create(normalized, source.version(), source.profile(),
                    source.workspacePath(), source.nodeRuntime(), DshHomeMode.ISOLATED, null,
                    source.extraArguments(), source.environment())
                    .withIcon(source.iconOrDefault());
            update(copy);
        } else if (DshVersionManager.isInstalled(copy)) {
            // An id taken by an instance that is already whole is somebody else's. One that is
            // half-made is this call's own earlier attempt, kept because pnpm asked about an install
            // script: the answer goes into that instance's profile, so the work continues there
            // instead of starting over.
            throw new DshException("Instance " + normalized + " already exists");
        }

        Path pack = null;
        try {
            pack = temporaryPack();
            DshModpacks.export(source, pack, report);
            DshModpacks.install(pack, normalized, source.workspacePath(), source.instanceDirectory().getParent(), report);
            copyOwnLauncherSettings(source, copy);
            DshInstance filled = find(normalized);
            return filled == null ? copy : filled;
        } catch (DshException | RuntimeException failed) {
            if (failed instanceof DshPluginInstaller.DshBuildScriptApprovalRequired) {
                // The one failure that must keep what it made: the question pnpm raised is written
                // into the copy's profile, and removing the copy would take the question with it.
                throw failed;
            }
            LOG.warning("Could not copy " + id + " to " + normalized + "; removing the copy", failed);
            DshVersionManager.discardPartial(copy);
            try {
                delete(normalized);
            } catch (DshException | RuntimeException cleanupFailure) {
                LOG.warning("Could not remove the half-made copy " + normalized, cleanupFailure);
            }
            throw failed;
        } finally {
            deleteQuietly(pack);
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
            return Files.createTempFile("hdsl-duplicate-", DshModpacks.FILE_EXTENSION);
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
    private static void copyOwnLauncherSettings(DshInstance source, DshInstance copy)
            throws DshException {
        Path from = source.instanceDirectory().resolve("settings.json");
        if (!Files.isRegularFile(from)) {
            return;
        }
        Path to = copy.instanceDirectory().resolve("settings.json");
        try {
            Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

    /// Deletes a path, ignoring anything that goes wrong.
    ///
    /// Used for the copies a rename or delete leaves behind, where a failure to
    /// tidy up must not fail the operation that already succeeded.
    ///
    /// @param path the path to delete
    private static void deleteQuietly(Path path) {
        if (path != null) {
            DshFiles.deleteTreeQuietly(path);
        }
    }

    /// Reads the manifest inside an instance directory.
    ///
    /// The directory that was read is stamped onto the instance, and it wins
    /// over whatever the manifest records: the manifest is a file, and it can be
    /// copied to another folder, carried to another machine, or written by hand
    /// — while the folder the manifest was found in is a fact about right now.
    /// An instance read out of a folder the user added is in that folder, and
    /// every later operation on it — installing, launching, renaming, removing —
    /// has to name that folder rather than the one the launcher owns.
    ///
    /// @param directory the candidate instance directory
    /// @return the instance, or `null` when there is no readable manifest
    private static @Nullable DshInstance read(Path directory) {
        Path manifest = directory.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            DshInstance instance = JsonUtils.fromJsonFile(manifest, DshInstance.class);
            if (instance == null || instance.id() == null || instance.version() == null) {
                return null;
            }
            return instance.withDirectory(directory);
        } catch (Exception e) {
            LOG.warning("Failed to read instance manifest " + manifest, e);
            return null;
        }
    }

    /// Writes an instance manifest.
    ///
    /// @param instance the instance to persist
    /// @throws DshException when the manifest cannot be written
    private static void write(DshInstance instance) throws DshException {
        try {
            Path directory = instance.instanceDirectory();
            Files.createDirectories(directory);
            JsonUtils.writeToJsonFile(directory.resolve(MANIFEST_NAME), instance);
        } catch (IOException e) {
            throw new DshException("Failed to write the instance manifest for " + instance.id(), e);
        }
    }
}
