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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Serializes the launcher's lifecycle operations on an instance directory across threads and launcher processes.
 */
@NotNullByDefault
public final class InstanceLocks {
	private static final String LOCK_DIRECTORY = ".hdsl-locks";

	private static final Map<String, Held> HELD = new ConcurrentHashMap<>();

	private InstanceLocks() {}

	/**
	 * Takes the lock on the instance's directory.
	 *
	 * @param what what the caller is about to do, said in the refusal
	 * @throws DshException when the directory cannot be resolved or another operation holds it
	 */
	public static void acquire(DshInstance instance, String what) throws DshException {
		acquireKey(keyOf(instance), instance.id(), what);
	}

	/**
	 * Releases the instance lock.
	 */
	public static void release(DshInstance instance) {
		try {
			releaseKey(keyOf(instance));
		} catch (DshException e) {
			LOG.warning("Could not name the instance directory whose lock is being released: " + e.getMessage());
		}
	}

	/**
	 * Runs the action while holding the instance's directory lock.
	 *
	 * @param what what is about to be done, said in a refusal
	 * @throws DshException when another operation holds the lock, or the action fails
	 */
	public static void withLock(DshInstance instance, String what, ThrowingRunnable action) throws DshException {
		String key = acquireKey(keyOf(instance), instance.id(), what);
		try {
			action.run();
		} finally {
			releaseKey(key);
		}
	}

	/**
	 * Runs the action while holding several instance directory locks.
	 *
	 * @param what what is about to be done, said in a refusal
	 * @throws DshException when another operation holds one of the locks, or the action fails
	 */
	public static <T> T withLocks(List<DshInstance> instances, String what, ThrowingSupplier<T> action)
	throws DshException {
		List<Target> targets = new ArrayList<>();
		for (DshInstance instance : instances) {
			String key = keyOf(instance);
			boolean known = false;
			for (Target target : targets) {
				if (target.key.equals(key)) {
					known = true;
					break;
				}
			}
			if (!known) targets.add(new Target(key, instance.id()));
		}

		// Sorted acquisition keeps the lock order stable.
		targets.sort(Comparator.comparing(Target::key));
		List<String> acquired = new ArrayList<>();
		try {
			for (Target target : targets) {
				acquireKey(target.key, target.label, what);
				acquired.add(target.key);
			}
			return action.get();
		} finally {
			for (int i = acquired.size() - 1; i >= 0; i--) {
				releaseKey(acquired.get(i));
			}
		}
	}

	private static String acquireKey(String key, String label, String what) throws DshException {
		synchronized (HELD) {
			Held held = HELD.get(key);
			if (held != null) {
				// Reentrant for the thread that already owns the lock.
				if (held.owner == Thread.currentThread()) {
					held.depth++;
					return key;
				}
				throw new DshException(
						"Instance " + label + " is being " + held.what + " right now; wait for it to finish");
			}

			Path lockFile = lockFile(key);
			try {
				Files.createDirectories(lockFile.getParent());
			} catch (IOException e) {
				throw new DshException("Failed to prepare the instance lock " + lockFile, e);
			}

			FileChannel channel = null;
			FileLock lock = null;
			try {
				channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				lock = channel.tryLock(); // a lock held by another process is reported as busy
			} catch (OverlappingFileLockException e) {
				close(channel, null);
				throw busy(label, what);
			} catch (IOException e) {
				close(channel, null);
				throw new DshException("Failed to lock " + lockFile, e);
			}

			if (lock == null) {
				close(channel, null);
				throw busy(label, what);
			}

			HELD.put(key, new Held(what, channel, lock, Thread.currentThread()));
			LOG.debug("Locked " + key + " for " + what);
			return key;
		}
	}

	private static void releaseKey(String key) {
		synchronized (HELD) {
			Held held = HELD.get(key);
			if (held == null) return;

			if (held.owner != Thread.currentThread()) {
				LOG.warning("A lock held by another thread was not released: " + key);
				return;
			}
			if (--held.depth > 0) return;

			HELD.remove(key);
			close(held.channel, held.lock);
		}
	}

	private static void close(@Nullable FileChannel channel, @Nullable FileLock lock) {
		if (lock != null) {
			try {
				lock.release();
			} catch (IOException e) {
				LOG.warning("Could not release an instance lock", e);
			}
		}
		if (channel != null) {
			try {
				channel.close();
			} catch (IOException e) {
				LOG.warning("Could not close an instance lock file", e);
			}
		}
	}

	private static DshException busy(String label, String what) {
		return new DshException("Instance " + label + " is being " + what + " right now; wait for it to finish");
	}

	/**
	 * The key an instance's operations are held under: its directory, lower-cased on Windows
	 */
	private static String keyOf(DshInstance instance) throws DshException {
		String key = instance.instanceDirectory().toAbsolutePath().normalize().toString();
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? key.toLowerCase(Locale.ROOT) : key;
	}

	/**
	 * The lock file for a key. Kept beside the instance: on Windows an open handle inside the
	 * instance would block the rename or removal the lock protects.
	 */
	private static Path lockFile(String key) {
		Path directory = Path.of(key);
		Path parent = directory.getParent();
		if (parent == null) {
			parent = Path.of(System.getProperty("java.io.tmpdir"));
		}
		return parent.resolve(LOCK_DIRECTORY).resolve(hash(key) + ".lock");
	}

	private static String hash(String key) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
		}
	}

	private static final class Held {
		private final String what;
		private final FileChannel channel;
		private final FileLock lock;
		private final Thread owner;
		private int depth = 1;

		private Held(String what, FileChannel channel, FileLock lock, Thread owner) {
			this.what = what;
			this.channel = channel;
			this.lock = lock;
			this.owner = owner;
		}
	}

	private record Target(String key, String label) {}

	/**
	 * What is run with an instance's directory held.
	 */
	@FunctionalInterface
	public interface ThrowingSupplier<T> {
		/**
		 * Runs it.
		 *
		 * @return the result
		 * @throws DshException when it fails
		 */
		T get() throws DshException;
	}

	/**
	 * What is run with an instance's directory held.
	 */
	@FunctionalInterface
	public interface ThrowingRunnable {
		/**
		 * Runs it.
		 *
		 * @throws DshException when it fails
		 */
		void run() throws DshException;
	}
}