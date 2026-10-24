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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// One operation at a time on an instance's own directory.
///
/// Installing, removing, renaming and updating all rewrite that directory, and none of them can be
/// left to overlap another: an install rebuilds dsh/, a removal deletes it, a rename moves it, and
/// the pair of any two of those leaves an instance whose directory is half of each. A user can ask
/// for two of them at once, and the launcher's task executor will run them on different threads.
///
/// The lock is a file lock, not only a map entry. The CLI runs before the launcher takes
/// LauncherLock, so a CLI command and the GUI can operate on one instance at the same time. The
/// file is kept outside the instance directory: on Windows an open handle prevents the directory
/// from being renamed or removed, which is exactly what some of these operations do. Its name is a
/// hash of the directory's path, so a rename does not move the lock with it; a rename takes the
/// old and the new name together.
///
/// The key is the **directory**, not the id, because the directory is what is being mutated and it
/// is what two callers would collide on: two ids can name the same folder if an instance manifest
/// was copied, and the id stops being meaningful the moment a rename is under way.
@NotNullByDefault
public final class InstanceLocks {
	/// The directory, beside the instance, that holds the lock files.
	private static final String LOCK_DIRECTORY = ".hdsl-locks";

	/// What each held directory is being, so that the refusal can say what is in the way rather
	/// than what the refused caller would have liked to do.
	private static final Map<String, Held> HELD = new ConcurrentHashMap<>();

	private InstanceLocks() {
	}

	/// Takes the lock on an instance's directory.
	///
	/// @param instance the instance
	/// @param what     what the caller is about to do, said in the refusal
	/// @throws DshException when the instance's directory cannot be resolved, or another operation
	///                       already holds it
	public static void acquire(DshInstance instance, String what) throws DshException {
		acquireKey(keyOf(instance), instance.id(), what);
	}

	/// Gives the lock on an instance's directory back.
	///
	/// Never throws: it runs out of a finally, where an exception would replace the one that is
	/// on its way out. A lock that could not be given back is worth a line in the log and nothing
	/// more.
	///
	/// @param instance the instance
	public static void release(DshInstance instance) {
		try {
			releaseKey(keyOf(instance));
		} catch (DshException e) {
			LOG.warning("Could not name the instance directory whose lock is being released: " + e.getMessage());
		}
	}

	/// Runs something with an instance's directory held.
	///
	/// The point of the wrapper rather than two calls is that the release is in a finally, which
	/// is what makes it impossible for a path that throws somewhere in the middle to leave an
	/// instance locked for ever.
	///
	/// The lock is reentrant for the thread that holds it: an upgrade takes it once and the
	/// install and the record update it calls take it again, each releasing only its own depth.
	///
	/// @param instance the instance
	/// @param what     what is about to be done, said in a refusal
	/// @param action   what to do
	/// @throws DshException when another operation holds the lock, or the action fails
	public static void withLock(DshInstance instance, String what, ThrowingRunnable action) throws DshException {
		String key = acquireKey(keyOf(instance), instance.id(), what);
		try {
			action.run();
		} finally {
			releaseKey(key);
		}
	}

	/// Runs something with the directories of several instances held.
	///
	/// The keys are taken in sorted order, so two renames that name each other's destination cannot
	/// deadlock. A rename passes the old and the new directory: another process can otherwise take
	/// the destination between the move and the manifest write.
	///
	/// @param instances the instances whose directories to hold
	/// @param what      what is about to be done, said in a refusal
	/// @param action    what to do
	/// @throws DshException when another operation holds one of the locks, or the action fails
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
			if (!known) {
				targets.add(new Target(key, instance.id()));
			}
		}
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

	/// Takes the lock for one directory key.
	private static String acquireKey(String key, String label, String what) throws DshException {
		synchronized (HELD) {
			Held held = HELD.get(key);
			if (held != null) {
				if (held.owner == Thread.currentThread()) {
					held.depth++;
					return key;
				}
				throw new DshException("Instance " + label + " is being " + held.what +
				                       " right now; wait for it to finish");
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
				lock = channel.tryLock();
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

	/// Gives the lock for one directory key back.
	private static void releaseKey(String key) {
		synchronized (HELD) {
			Held held = HELD.get(key);
			if (held == null) {
				return;
			}
			if (held.owner != Thread.currentThread()) {
				LOG.warning("A lock held by another thread was not released: " + key);
				return;
			}
			if (--held.depth > 0) {
				return;
			}
			HELD.remove(key);
			close(held.channel, held.lock);
		}
	}

	/// Closes a channel and its lock, reporting but never throwing.
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

	/// The refusal for a lock another operation holds.
	private static DshException busy(String label, String what) {
		return new DshException("Instance " + label + " is being " + what + " right now; wait for it to finish");
	}

	/// The key an instance's operations are held under.
	///
	/// Windows paths are case-insensitive, so the key is lower-cased there; two callers naming the
	/// same directory with different casing must not get two locks.
	private static String keyOf(DshInstance instance) throws DshException {
		String key = instance.instanceDirectory().toAbsolutePath().normalize().toString();
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? key.toLowerCase(Locale.ROOT) : key;
	}

	/// The lock file for a key, beside the instance directory.
	private static Path lockFile(String key) {
		Path directory = Path.of(key);
		Path parent = directory.getParent();
		if (parent == null) {
			parent = Path.of(System.getProperty("java.io.tmpdir"));
		}
		return parent.resolve(LOCK_DIRECTORY).resolve(hash(key) + ".lock");
	}

	/// A short, collision-resistant file name for a path.
	private static String hash(String key) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
		}
	}

	/// What is held for one key.
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

	/// A directory and the label used when it is busy.
	private record Target(String key, String label) {
	}

	/// What is run with an instance's directory held.
	@FunctionalInterface
	public interface ThrowingSupplier<T> {
		/// Runs it.
		///
		/// @return the result
		/// @throws DshException when it fails
		T get() throws DshException;
	}

	/// What is run with an instance's directory held.
	@FunctionalInterface
	public interface ThrowingRunnable {
		/// Runs it.
		///
		/// @throws DshException when it fails
		void run() throws DshException;
	}
}