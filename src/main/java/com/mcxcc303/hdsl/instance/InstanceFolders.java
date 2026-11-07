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

import com.mcxcc303.hdsl.core.DshPaths;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Resolves the directories configured to store launcher instances.
 */
@NotNullByDefault
public final class InstanceFolders {
	private static volatile Supplier<List<Path>> folders = InstanceFolders::foldersFromTheSettings;

	private InstanceFolders() {
	}

	private static List<Path> foldersFromTheSettings() {
		try {
			return GameDirectoryManager.instanceFolders();
		} catch (RuntimeException | Error e) {
			LOG.warning("Could not InstanceStore.read the instance folders from the settings; " +
			            "using the folder the launcher owns", e);
			return List.of(DshPaths.INSTANCES);
		}
	}

	/**
	 * Configures the supplier of instance folders.
	 *
	 * @param supplier the folder supplier, current folder first
	 * @return the previously installed supplier
	 */
	public static Supplier<List<Path>> setFolders(Supplier<List<Path>> supplier) {
		Supplier<List<Path>> previous = folders;
		folders = supplier;
		return previous;
	}

	/**
	 * Returns the folders the launcher looks for instances in, the current one first.
	 *
	 * @return the folders, absolute and without duplicates
	 */
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

	/**
	 * Returns the folder a new instance is created in.
	 *
	 * @return the current folder path
	 */
	public static Path currentFolder() {
		return folders().get(0);
	}
}
