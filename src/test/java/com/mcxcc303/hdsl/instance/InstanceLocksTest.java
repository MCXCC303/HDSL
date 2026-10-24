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
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the lock that keeps two operations from rewriting one instance's directory at once.
 */
class InstanceLocksTest {
	@TempDir
	private Path root;

	private DshInstance[] pair() throws DshException, IOException {
		DshInstance[] made = new DshInstance[2];
		for (int i = 0; i < made.length; i++) {
			Path directory = root.resolve("instance-" + i);
			Files.createDirectories(directory);
			made[i] = new DshInstance("instance-" + i,
			                          "0.2.0-rc.2",
			                          "web",
			                          root.toString(),
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
		}
		return made;
	}

	/**
	 * Asks for the lock from another thread, so the owner thread's reentrancy cannot hide the refusal.
	 */
	private static DshException refusalFromAnotherThread(DshInstance instance, String what) throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<DshException> future = pool.submit(() -> assertThrows(DshException.class,
			                                                             () -> InstanceLocks.acquire(instance, what)));
			return future.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aSecondOperationIsRefused() throws Exception {
		DshInstance instance = pair()[0];
		InstanceLocks.acquire(instance, "installed into");
		try {
			DshException refusal = refusalFromAnotherThread(instance, "removed");
			// What the person needs is what is **in the way**, not what they asked for.
			assertTrue(refusal.getMessage().contains("is being installed into"), refusal.getMessage());
			assertTrue(refusal.getMessage().contains(instance.id()), refusal.getMessage());
		} finally {
			InstanceLocks.release(instance);
		}
	}

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

	@Test
	void theLockIsGivenBackWhenTheOperationFails() throws Exception {
		DshInstance instance = pair()[0];
		assertThrows(DshException.class, () -> InstanceLocks.withLock(instance, "installed into", () -> {
			throw new DshException("pnpm exited with code 1");
		}));
		assertDoesNotThrow(() -> InstanceLocks.acquire(instance, "installed into"));
		InstanceLocks.release(instance);
	}

	/**
	 * A separate JVM is what a CLI command is; the file lock has to refuse it too.
	 */
	@Test
	void anotherProcessIsRefused() throws Exception {
		DshInstance instance = pair()[0];
		Path java = Path.of(System.getProperty("java.home"),
		                    "bin",
		                    OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "java.exe" : "java");
		String classpath = System.getProperty("java.class.path");
		Assumptions.assumeTrue(Files.isRegularFile(java), "no java executable to start: " + java);
		Assumptions.assumeTrue(classpath.length() < 30000, "classpath too long for a child process on Windows");

		InstanceLocks.acquire(instance, "installed into");
		try {
			Process process = new ProcessBuilder(java.toString(),
			                                     "-cp",
			                                     classpath,
			                                     InstanceLockProbe.class.getName(),
			                                     instance.instanceDirectory().toString()).redirectErrorStream(true)
			                                                                             .start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the child did not exit");
			assertEquals(0, process.exitValue(), output);
			assertTrue(output.contains("REFUSED"), output);
		} finally {
			InstanceLocks.release(instance);
		}
	}

	@Test
	void theLockIsReentrantForItsOwner() throws Exception {
		DshInstance instance = pair()[0];
		assertDoesNotThrow(() -> InstanceLocks.withLock(instance, "upgraded", () -> {
			InstanceLocks.withLock(instance, "installed into", () -> {
			});
		}));
		assertDoesNotThrow(() -> InstanceLocks.acquire(instance, "installed into"));
		InstanceLocks.release(instance);
	}

	@Test
	void twoCopiesOfOneInstanceShareTheLock() throws Exception {
		DshInstance[] instances = pair();
		// A different id, naming the other one's directory: what a hand-copied instance manifest looks like.
		DshInstance copy = new DshInstance("a-copy",
		                                   "0.2.0-rc.2",
		                                   "web",
		                                   root.toString(),
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
		                                   instances[1].instanceDirectory().toString());
		assertNotEquals(instances[1].id(), copy.id());

		InstanceLocks.acquire(instances[1], "installed into");
		try {
			refusalFromAnotherThread(copy, "removed");
		} finally {
			InstanceLocks.release(instances[1]);
		}
	}
}