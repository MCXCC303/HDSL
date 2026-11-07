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

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Single-instance file lock mechanism protecting launcher settings and state from concurrent access.
 */
@NotNullByDefault
public final class LauncherLock {

	private static final String FILE_NAME = "launcher.lock";
	private static final LauncherLock SHARED = new LauncherLock(Metadata.HMCL_USER_HOME.resolve(FILE_NAME));

	private final Path file;
	private @Nullable FileChannel channel;
	private @Nullable FileLock lock;

	LauncherLock(Path file) {
		this.file = file;
	}

	/**
	 * Returns the shared lock instance for the launcher user home.
	 *
	 * @return the shared lock
	 */
	public static LauncherLock shared() {
		return SHARED;
	}

	/**
	 * Attempts to acquire the file lock for this process.
	 *
	 * @return true if the lock was acquired or already held
	 */
	public boolean acquire() {
		if (held()) {
			return true;
		}
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			lock = channel.tryLock();
			if (lock == null) {
				close();
				return false;
			}
			writeOwner();
			return true;
		} catch (OverlappingFileLockException e) {
			close();
			return false;
		} catch (IOException e) {
			LOG.warning("Could not take the launcher lock " + file, e);
			close();
			return false;
		}
	}

	/**
	 * Reports whether this process currently holds the lock.
	 *
	 * @return true if the lock is held and valid
	 */
	public boolean held() {
		return lock != null && lock.isValid();
	}

	/**
	 * Releases the lock and removes the owner note file.
	 */
	public void release() {
		close();
	}

	/**
	 * Reads the PID and start time of the process currently holding the lock, if recorded.
	 *
	 * @return optional lock holder info
	 */
	public Optional<Holder> holder() {
		Path note = ownerFile();
		if (!Files.isRegularFile(note)) {
			return Optional.empty();
		}
		try {
			Holder holder = JsonUtils.fromJsonFile(note, Holder.class);
			return holder == null || holder.pid() <= 0 ? Optional.empty() : Optional.of(holder);
		} catch (Exception e) {
			LOG.warning("Could not read who holds the launcher lock from " + note, e);
			return Optional.empty();
		}
	}

	private Path ownerFile() {
		return file.resolveSibling(file.getFileName() + ".owner");
	}

	private void writeOwner() {
		Holder holder = new Holder(ProcessHandle.current().pid(), System.currentTimeMillis());
		try {
			JsonUtils.writeToJsonFile(ownerFile(), holder);
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not write who holds the launcher lock to " + ownerFile(), e);
		}
	}

	private void close() {
		FileLock held = lock;
		lock = null;
		if (held != null) {
			try {
				held.release();
			} catch (IOException e) {
				LOG.info("Could not release the launcher lock " + file, e);
			}
			try {
				Files.deleteIfExists(ownerFile());
			} catch (IOException e) {
				LOG.info("Could not delete the note about the launcher lock " + ownerFile(), e);
			}
		}
		FileChannel open = channel;
		channel = null;
		if (open != null) {
			try {
				open.close();
			} catch (IOException e) {
				LOG.info("Could not close the launcher lock " + file, e);
			}
		}
	}

	/**
	 * Metadata identifying a process holding the launcher lock.
	 *
	 * @param pid       process ID of the lock holder
	 * @param startedAt epoch timestamp when the lock was acquired
	 */
	public record Holder(long pid, long startedAt) {
	}
}
