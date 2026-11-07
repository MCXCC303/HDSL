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

import com.google.gson.annotations.SerializedName;
import com.mcxcc303.hdsl.core.DshPaths;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Represents a directory location managed by the launcher containing instances.
 *
 * @param id         stable unique identifier
 * @param path       file system path string
 * @param customName optional display name override
 */
@NotNullByDefault
public record GameDirectory(
		@SerializedName("id") String id,
		@SerializedName("path") String path,
		@SerializedName("customName") @Nullable String customName) {

	/**
	 * Identifier for the default instance directory.
	 */
	public static final String DEFAULT_ID = "default";

	/**
	 * Returns the normalized absolute path to this directory.
	 *
	 * @return the directory path
	 */
	public Path directory() {
		return Path.of(path).toAbsolutePath().normalize();
	}

	/**
	 * Returns the display name for this directory.
	 *
	 * @return the display name
	 */
	public String displayName() {
		if (customName != null && !customName.isBlank()) {
			return customName;
		}
		Path name = directory().getFileName();
		return name == null ? path : name.toString();
	}

	/**
	 * Reports whether this is the default directory.
	 *
	 * @return true if this is the default directory
	 */
	public boolean isDefault() {
		return DEFAULT_ID.equals(id);
	}

	/**
	 * Returns the default directory configuration.
	 *
	 * @return default game directory
	 */
	public static GameDirectory defaultDirectory() {
		return new GameDirectory(DEFAULT_ID, DshPaths.INSTANCES.toString(), null);
	}

	/**
	 * Creates a new directory entry with a generated ID.
	 *
	 * @param path the folder path
	 * @return new directory entry
	 */
	public static GameDirectory of(Path path) {
		return of(path, null);
	}

	/**
	 * Creates a new directory entry with a generated ID and custom name.
	 *
	 * @param path       the folder path
	 * @param customName custom display name, or {@code null}
	 * @return new directory entry
	 */
	public static GameDirectory of(Path path, @Nullable String customName) {
		return new GameDirectory(UUID.randomUUID().toString(), path.toString(),
				customName == null || customName.isBlank() ? null : customName);
	}
}
