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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Tries to take an instance lock in a separate process, so the test is not only another thread.
 */
public final class InstanceLockProbe {
	private InstanceLockProbe() {
	}

	public static void main(String[] args) throws Exception {
		Path directory = Path.of(args[0]);
		DshInstance instance = new DshInstance("probe",
		                                       "0.2.0-rc.2",
		                                       "web",
		                                       directory.getParent().toString(),
		                                       null,
		                                       DshHomeMode.ISOLATED,
		                                       null,
		                                       List.of(),
		                                       Map.of(),
		                                       "default",
		                                       null,
		                                       null,
		                                       0,
		                                       0L,
		                                       directory.toString());
		try {
			InstanceLocks.acquire(instance, "probed");
			InstanceLocks.release(instance);
			System.out.println("ACQUIRED");
		} catch (DshException e) {
			System.out.println("REFUSED:" + e.getMessage());
		}
	}
}