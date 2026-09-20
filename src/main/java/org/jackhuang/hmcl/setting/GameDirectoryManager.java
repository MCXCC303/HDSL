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

import org.jackhuang.hmcl.dsh.DshPaths;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;

/// The folders the launcher looks for instances in.
///
/// The launcher owns one directory — the one it created — and can be pointed at
/// others. It never moves instances between them: adding a folder makes the
/// launcher look inside, and that is all. HMCL behaves the same way, and the
/// reasoning holds here: the files are the user's, and relocating them is not
/// what "add this folder" asked for.
@NotNullByDefault
public final class GameDirectoryManager {
    private GameDirectoryManager() {
    }

    /// Returns the configured directories.
    ///
    /// A launcher that has never been given one reports the directory it owns,
    /// so the default needs no entry in the settings and no migration to
    /// introduce.
    ///
    /// @return the directories, the default first
    public static @Unmodifiable List<GameDirectory> directories() {
        List<GameDirectory> configured = settings().gameDirectoriesProperty().get();
        if (configured == null || configured.isEmpty()) {
            return List.of(GameDirectory.defaultDirectory());
        }
        return List.copyOf(configured);
    }

    /// Returns the directory whose instances the list shows.
    ///
    /// @return the selected directory, falling back to the first
    public static GameDirectory selected() {
        String id = settings().selectedGameDirectoryIdProperty().get();
        for (GameDirectory directory : directories()) {
            if (directory.id().equals(id)) {
                return directory;
            }
        }
        return directories().get(0);
    }

    /// Selects the directory whose instances the list shows.
    ///
    /// @param id the directory identifier
    public static void select(String id) {
        settings().selectedGameDirectoryIdProperty().set(id);
        SettingsManager.save();
    }

    /// Finds a directory by identifier.
    ///
    /// @param id the identifier
    /// @return the directory, or `null` when there is none
    public static @Nullable GameDirectory find(String id) {
        for (GameDirectory directory : directories()) {
            if (directory.id().equals(id)) {
                return directory;
            }
        }
        return null;
    }

    /// Adds a folder to the list.
    ///
    /// A folder already present is returned as it stands rather than added
    /// twice: the identity is the path, not the entry.
    ///
    /// @param path the folder to add
    /// @return the directory entry, existing or new
    /// @throws IllegalArgumentException when the path is not a directory
    public static GameDirectory add(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(normalized + " is not a directory");
        }
        for (GameDirectory directory : directories()) {
            if (directory.directory().equals(normalized)) {
                return directory;
            }
        }

        GameDirectory added = GameDirectory.of(normalized);
        List<GameDirectory> updated = new ArrayList<>(directories());
        updated.add(added);
        settings().gameDirectoriesProperty().set(List.copyOf(updated));
        SettingsManager.save();
        return added;
    }

    /// Removes a folder from the list.
    ///
    /// Nothing is deleted: the instances inside keep their files and simply stop
    /// being listed. The directory the launcher owns cannot be removed, because
    /// it is where a new instance goes when nowhere else is chosen.
    ///
    /// @param id the directory identifier
    /// @throws IllegalArgumentException when the default directory is named
    public static void remove(String id) {
        if (GameDirectory.DEFAULT_ID.equals(id)) {
            throw new IllegalArgumentException("The default directory cannot be removed");
        }
        List<GameDirectory> updated = new ArrayList<>(directories());
        updated.removeIf(directory -> directory.id().equals(id));
        settings().gameDirectoriesProperty().set(List.copyOf(updated));
        if (id.equals(settings().selectedGameDirectoryIdProperty().get())) {
            settings().selectedGameDirectoryIdProperty().set(GameDirectory.DEFAULT_ID);
        }
        SettingsManager.save();
    }

    /// Counts the instances a folder holds.
    ///
    /// Used to tell the user what adding a folder found, which is the whole
    /// point of adding one.
    ///
    /// @param directory the folder
    /// @return how many instances it holds
    public static int countInstances(GameDirectory directory) {
        Path root = directory.directory();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (var children = Files.list(root)) {
            return (int) children
                    .filter(Files::isDirectory)
                    .filter(child -> Files.isRegularFile(child.resolve("instance.json")))
                    .count();
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    /// Reports whether this folder is the one the launcher owns.
    ///
    /// @param directory the directory
    /// @return whether the launcher writes its own instances there
    public static boolean isOwned(GameDirectory directory) {
        return DshPaths.INSTANCES.toAbsolutePath().normalize().equals(directory.directory());
    }
}
