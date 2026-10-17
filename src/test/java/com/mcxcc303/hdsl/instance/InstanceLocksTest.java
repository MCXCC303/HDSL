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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Tests the lock that keeps two operations from rewriting one instance's directory at once.
///
/// Installing, removing and renaming all rewrite the directory, and the launcher's task executor
/// will run two of them on different threads if a person asks while the first is still going. What
/// is being checked here is the contract the three of them share, not each one's own work.
class InstanceLocksTest {
	/// A temporary directory standing in for the folder instances live in.
	@TempDir
	private Path root;

	/// Two instances in it.
	private DshInstance[] pair() throws DshException, IOException {
		DshInstance[] made = new DshInstance[2];
		for (int i = 0; i < made.length; i++) {
			Path directory = root.resolve("instance-" + i);
			Files.createDirectories(directory);
			made[i] = new DshInstance("instance-" + i, "0.2.0-rc.2", "web", root.toString(), null,
			                         DshHomeMode.ISOLATED, null, List.of(), Map.of(),
			                         "default", null, null, 0, 0L, directory.toString());
		}
		return made;
	}

	/// One operation at a time on one instance: a second is refused, and says what is holding it.
	@Test
	void aSecondOperationIsRefused() throws Exception {
		DshInstance instance = pair()[0];
		InstanceLocks.acquire(instance, "installed into");
		try {
			DshException refusal = assertThrows(DshException.class,
			                                    () -> InstanceLocks.acquire(instance, "removed"));
			// What the person needs is what is **in the way**, not what they asked for.
			assertTrue(refusal.getMessage().contains("is being installed into"), refusal.getMessage());
			assertTrue(refusal.getMessage().contains(instance.id()), refusal.getMessage());
		} finally {
			InstanceLocks.release(instance);
		}
	}

	/// Another instance is not in the way. The key is the directory, not the id, and two instances
	/// have two directories.
	@Test
	void anotherInstanceIsNotInTheWay() throws Exception {
		DshInstance[] instances = pair();
		InstanceLocks.acquire(instances[0], "installed into");
		try {
			InstanceLocks.acquire(instances[1], "installed into");
			assertDoesNotThrow(() -> InstanceLocks.release(instances[1]));
		} finally {
			InstanceLocks.release(instances[0]);
		}
	}

	/// The release happens however the operation ended. These operations throw — an install fails on
	/// pnpm, a removal on a file in use, a rename on a destination that exists — and a lock that
	/// outlived the throw would refuse every later operation on that instance for ever.
	@Test
	void theLockIsGivenBackWhenTheOperationFails() throws Exception {
		DshInstance instance = pair()[0];
		assertThrows(DshException.class, () -> InstanceLocks.withLock(instance, "installed into", () -> {
			throw new DshException("pnpm exited with code 1");
		}));
		assertFalse(InstanceLocks.isHeld(instance));
		assertDoesNotThrow(() -> InstanceLocks.acquire(instance, "installed into"));
		InstanceLocks.release(instance);
	}

	/// Two copies of one instance — the same manifest read out of two folders, which is what an
	/// instance directory that was copied by hand gives — are the same directory to the lock, so
	/// they cannot be worked on at once even though their ids differ.
	@Test
	void twoCopiesOfOneInstanceShareTheLock() throws Exception {
		DshInstance[] instances = pair();
		// A different id, naming the other one's directory: what a hand-copied instance manifest
		// looks like.
		DshInstance copy = new DshInstance("a-copy", "0.2.0-rc.2", "web", root.toString(), null,
		                                   DshHomeMode.ISOLATED, null, List.of(), Map.of(),
		                                   "default", null, null, 0, 0L,
		                                   instances[1].instanceDirectory().toString());
		assertNotEquals(instances[1].id(), copy.id());

		InstanceLocks.acquire(instances[1], "installed into");
		try {
			assertThrows(DshException.class, () -> InstanceLocks.acquire(copy, "removed"));
		} finally {
			InstanceLocks.release(instances[1]);
		}
	}
}