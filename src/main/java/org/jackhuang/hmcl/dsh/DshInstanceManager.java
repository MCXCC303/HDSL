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
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Creates, enumerates, edits and removes [DshInstance]s.
///
/// Each instance is stored as `instances/<id>/instance.json`. The instance id
/// doubles as the directory name, which is what makes an isolated `DSH_HOME`
/// trivially private: it lives at `instances/<id>/home`.
@NotNullByDefault
public final class DshInstanceManager {
    private DshInstanceManager() {
    }

    /// The manifest file inside an instance directory.
    public static final String MANIFEST_NAME = "instance.json";

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
        Path root = DshPaths.INSTANCES;
        if (!Files.isDirectory(root)) {
            return instances;
        }

        try (Stream<Path> entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                DshInstance instance = read(directory);
                if (instance != null) {
                    instances.add(instance);
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to enumerate instances in " + root, e);
        }

        instances.sort(Comparator.comparingLong(DshInstance::createdAt).reversed());
        return instances;
    }

    /// Finds an instance by id.
    ///
    /// @param id the instance id
    /// @return the instance, or `null` when it does not exist or is unreadable
    public static @Nullable DshInstance find(String id) {
        try {
            return read(DshPaths.instanceDirectory(id));
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
        if (find(id) != null) {
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

        DshInstance instance = new DshInstance(id, version, profile,
                workspace.toAbsolutePath().normalize().toString(),
                nodeRuntime,
                homeMode,
                customHome == null ? null : customHome.toAbsolutePath().normalize().toString(),
                List.copyOf(arguments), Map.copyOf(environment),
                DshInstanceIcon.DEFAULT.id(), null, DshPortMode.AUTO, port, System.currentTimeMillis());

        Path directory = instance.instanceDirectory();
        try {
            Files.createDirectories(directory);
            if (homeMode == DshHomeMode.ISOLATED) {
                Files.createDirectories(instance.homeDirectory());
            }
        } catch (IOException e) {
            throw new DshException("Failed to create " + directory, e);
        }

        write(instance);
        LOG.info("Created instance " + id + " (dsh " + version + ", home " + instance.homeMode() + ")");
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
    /// @param id the instance id
    /// @throws DshException when the instance does not exist or cannot be removed
    public static void delete(String id) throws DshException {
        DshInstance instance = find(id);
        if (instance == null) {
            throw new DshException("Instance " + id + " does not exist");
        }
        Path directory = instance.instanceDirectory();
        if (instance.homeMode() != DshHomeMode.ISOLATED && !directory.startsWith(DshPaths.INSTANCES)) {
            throw new DshException("Refusing to delete " + directory + " because it is outside the instance directory");
        }
        try {
            FileUtils.deleteDirectory(directory);
        } catch (IOException e) {
            throw new DshException("Failed to remove " + directory, e);
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
        Path oldDirectory = DshPaths.instanceDirectory(id);
        Path newDirectory = DshPaths.instanceDirectory(normalized);

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

    /// Copies an instance's configuration into a new one.
    ///
    /// Only the configuration is copied. A copy gets its own isolated home
    /// rather than a duplicate of the original's, because the home holds the
    /// sessions and credentials — duplicating those silently is not what
    /// "copy this instance" should mean.
    ///
    /// @param id    the instance to copy
    /// @param newId the new instance's id
    /// @return the copy
    /// @throws DshException when either id is unusable
    public static DshInstance duplicate(String id, String newId) throws DshException {
        DshInstance source = find(id);
        if (source == null) {
            throw new DshException("Instance " + id + " does not exist");
        }
        String normalized = newId == null ? "" : newId.trim();
        if (normalized.isEmpty()) {
            throw new DshException("An instance needs a name");
        }
        if (exists(normalized)) {
            throw new DshException("Instance " + normalized + " already exists");
        }

        // The copy carries the original's icon, and only its icon: a copy that
        // is indistinguishable from what it was copied from is a copy nobody can
        // find in the list. The port is deliberately not copied — two instances
        // may not be given the same one — and the write is what makes the icon
        // survive, because a copy built by `withIcon` alone is never persisted.
        DshInstance copy = create(normalized, source.version(), source.profile(),
                source.workspacePath(), source.nodeRuntime(), DshHomeMode.ISOLATED, null,
                source.extraArguments(), source.environment())
                .withIcon(source.iconOrDefault());
        update(copy);
        return copy;
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
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException e) {
            LOG.warning("Failed to delete " + path, e);
        }
    }

    /// Reads the manifest inside an instance directory.
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
            return instance;
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
