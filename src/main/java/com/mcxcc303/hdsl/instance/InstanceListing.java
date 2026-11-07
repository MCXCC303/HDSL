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
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Lists instances across all configured instance folders.
 */
@NotNullByDefault
public final class InstanceListing {
	private static final Set<String> REPORTED_SHARED_NAMES = ConcurrentHashMap.newKeySet();

	private InstanceListing() {
	}

	/**
	 * Returns all discovered instances sorted newest first.
	 *
	 * @return the list of instances
	 */
	public static List<DshInstance> list() {
		List<DshInstance> instances = new ArrayList<>();
		Map<String, Path> taken = new HashMap<>();
		for (Path root : InstanceFolders.folders()) {
			for (DshInstance instance : InstanceStore.listIn(root)) {
				Path directory;
				try {
					directory = instance.instanceDirectory();
				} catch (DshException e) {
					continue;
				}
				Path previous = taken.get(instance.id());
				if (previous == null) {
					taken.put(instance.id(), directory);
					instances.add(instance);
				} else if (!previous.equals(directory)) {
					warnAboutASharedName(instance.id(), previous, directory);
				}
			}
		}
		instances.sort(Comparator.comparingLong(DshInstance::createdAt).reversed());
		return instances;
	}

	/**
	 * Logs a warning once per run when an instance ID is present in multiple folders.
	 *
	 * @param id      the shared instance ID
	 * @param used    the folder being used
	 * @param ignored the shadowed folder
	 */
	static void warnAboutASharedName(String id, Path used, Path ignored) {
		if (!REPORTED_SHARED_NAMES.add(id)) {
			return;
		}
		LOG.warning("Instance \"" + id + "\" is in two InstanceFolders.folders: " + used +
		            " is used, because the folder being shown comes first, and " + ignored +
		            " is left alone. Rename or remove one of them: one name is one instance.");
	}
}
