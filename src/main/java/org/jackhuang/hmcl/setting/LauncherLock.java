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

/// The lock that keeps one launcher to one home.
///
/// The settings file is written whole, out of the copy of it a process read when it started. Two
/// processes writing therefore means the second undoes whatever the first changed in between — and
/// the window holds its copy for as long as it is open, so a folder added in a terminal was gone at
/// the window's next save, and an account key changed in the window was rolled back by a command run
/// in a terminal. The lock is what makes that impossible rather than unlikely: the window holds it
/// while it is open, a command that writes holds it while it writes, and a command that cannot get it
/// says so instead of publishing a copy that is already out of date. Reading never takes it.
///
/// It is a file of its own rather than the settings file, for two reasons: the settings file is
/// written by being replaced, so a lock on it would guard an inode nobody reads again, and the same
/// lock is what keeps a second window from opening beside the first — one launcher to a home is one
/// settings file, one instance store and one set of running harnesses, and two windows would each
/// have their own idea of all three.
///
/// The system releases the lock when the process ends, so a launcher that was killed leaves nothing
/// behind, which a pid file would not.
///
/// Who holds it is written down **beside** the lock rather than in it, because on Windows a lock on
/// a file is mandatory: any other handle that reads the locked range is refused. A note inside it
/// could be written — the holder writes through the handle it locked — and could then be read by
/// nobody, which is exactly what a refusal wants to read it for.
@NotNullByDefault
public final class LauncherLock {

	/// The file the lock is taken on, in the launcher's own home.
	private static final String FILE_NAME = "launcher.lock";

	/// The lock of this launcher's home.
	private static final LauncherLock SHARED = new LauncherLock(Metadata.HDSL_USER_HOME.resolve(FILE_NAME));

	/// The file this lock is taken on.
	private final Path file;

	/// The channel the lock is held through, while it is held.
	private @Nullable FileChannel channel;

	/// The lock itself, while it is held.
	private @Nullable FileLock lock;

	/// Creates a lock on one file.
	///
	/// @param file the file to take the lock on
	LauncherLock(Path file) {
		this.file = file;
	}

	/// Returns the lock of this launcher's home.
	///
	/// @return the shared lock
	public static LauncherLock shared() {
		return SHARED;
	}

	/// Takes the lock for this process, or reports that somebody else holds it.
	///
	/// @return whether this process holds it now
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
			// A lock this same process holds through another one of these — a second window's worth
			// of it. What is being asked is "may I write", and the answer to that is no either way.
			close();
			return false;
		} catch (IOException e) {
			LOG.warning("Could not take the launcher lock " + file, e);
			close();
			return false;
		}
	}

	/// Reports whether this process holds the lock.
	///
	/// @return whether it does
	public boolean held() {
		return lock != null && lock.isValid();
	}

	/// Releases the lock, if this process holds it.
	public void release() {
		close();
	}

	/// Returns the process holding the lock, as the note beside it describes it.
	///
	/// Read only to say who to somebody who was refused: an empty answer means the note does not say,
	/// not that nobody holds the lock.
	///
	/// @return the holder, or empty when the note does not say
	public Optional<Holder> holder() {
		Path note = ownerFile();
		if (!Files.isRegularFile(note)) {
			return Optional.empty();
		}
		try {
			Holder holder = JsonUtils.fromJsonFile(note, Holder.class);
			return holder == null || holder.pid() <= 0 ? Optional.empty() : Optional.of(holder);
		} catch (Exception e) {
			// Named, and unreadable: whoever was refused gets no pid, and the reason is in the log
			// rather than nowhere at all.
			LOG.warning("Could not read who holds the launcher lock from " + note, e);
			return Optional.empty();
		}
	}

	/// Returns the file the note about the holder goes in.
	///
	/// Beside the lock and never locked itself: see the class comment for why it cannot be the lock.
	///
	/// @return the file
	private Path ownerFile() {
		return file.resolveSibling(file.getFileName() + ".owner");
	}

	/// Writes down who holds the lock, so that a refusal can name the process.
	///
	/// A note that cannot be written costs a refusal its pid and nothing else, which is why this
	/// reports nothing and never stands between a launcher and its lock.
	private void writeOwner() {
		Holder holder = new Holder(ProcessHandle.current().pid(), System.currentTimeMillis());
		try {
			JsonUtils.writeToJsonFile(ownerFile(), holder);
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not write who holds the launcher lock to " + ownerFile(), e);
		}
	}

	/// Lets go of the lock and of the file it went through.
	private void close() {
		FileLock held = lock;
		lock = null;
		if (held != null) {
			try {
				held.release();
			} catch (IOException e) {
				LOG.info("Could not release the launcher lock " + file, e);
			}
			// Only when this process was the holder: a failed attempt to take the lock must not take
			// away the note of whoever does hold it.
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

	/// The process holding the lock, as the note beside it describes it.
	///
	/// @param pid       the process
	/// @param startedAt when it took the lock, in milliseconds since the epoch
	public record Holder(long pid, long startedAt) {
	}
}
