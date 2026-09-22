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

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The settings that belong to one instance rather than to the launcher.
///
/// The original keeps a game's own settings beside the game, with each one either
/// following the launcher's value or overriding it, and this is the same idea for an
/// instance: what is stored here is only what somebody chose *for this instance*, and
/// anything absent follows the launcher. Keeping it in the instance's own directory
/// rather than in a table keyed by id means it travels with the instance when the
/// directory is moved, which is what a per-instance setting should do.
@NotNullByDefault
public final class DshInstanceSettings {
    /// The file's name inside an instance directory.
    private static final String FILE = "settings.json";

    private DshInstanceSettings() {
    }

    /// Reads whether install scripts may run without being asked about.
    ///
    /// @param instance the instance
    /// @return the choice made for this instance, or `null` to follow the launcher
    public static @Nullable Boolean approveBuildScripts(DshInstance instance) {
        JsonObject root = read(instance);
        if (root == null || !root.has("approveBuildScripts")
                || !root.get("approveBuildScripts").isJsonPrimitive()) {
            return null;
        }
        try {
            return root.get("approveBuildScripts").getAsBoolean();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// Records whether install scripts may run without being asked about.
    ///
    /// @param instance the instance
    /// @param value    the choice, or `null` to follow the launcher again
    /// @throws DshException when the file cannot be written
    public static void setApproveBuildScripts(DshInstance instance, @Nullable Boolean value)
            throws DshException {
        Path file = fileOf(instance);
        JsonObject root = read(instance);
        if (root == null) {
            root = new JsonObject();
        }
        if (value == null) {
            root.remove("approveBuildScripts");
        } else {
            root.addProperty("approveBuildScripts", value);
        }

        Path staging = file.resolveSibling(FILE + ".hdsl");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(staging, JsonUtils.GSON.toJson(root));
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DshException("Failed to write " + file, e);
        }
    }

    /// Reads the instance's settings file.
    ///
    /// @param instance the instance
    /// @return the object, or `null` when there is none or it cannot be read
    private static @Nullable JsonObject read(DshInstance instance) {
        Path file = fileOf(instance);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject root = JsonUtils.fromJsonFile(file, JsonObject.class);
            return root;
        } catch (Exception e) {
            LOG.warning("Failed to read " + file, e);
            return null;
        }
    }

    /// Returns an instance's settings file.
    ///
    /// @param instance the instance
    /// @return the file
    private static Path fileOf(DshInstance instance) {
        try {
            return instance.instanceDirectory().resolve(FILE);
        } catch (DshException e) {
            // An instance whose directory cannot be resolved has nowhere to keep
            // settings; a path that will fail to be written is the honest answer.
            return Path.of(FILE);
        }
    }
}
