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
package com.mcxcc303.hdsl.launch;

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshFiles;
import com.mcxcc303.hdsl.harness.DshSurface;
import com.mcxcc303.hdsl.instance.DshInstance;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// What a launcher process leaves on disk about an instance it is running.
///
/// The registry in [DshProcessManager] is a map in one JVM, and that is exactly as far as it
/// reaches. A launcher started in a terminal while the window is up cannot see it, and answered
/// "not running" about an instance that was serving on a port at that moment: `instance list
/// --running` was empty, `instance stop` did nothing, and `instance launch --url` could not name
/// the address it serves. The record is the same fact written down — the pid, the port, the address
/// with its key in it, the surface, and when it started — so that any process can read it.
///
/// It is a hint, not a lock. What makes it true is the process, so a record whose process is gone is
/// stale: whoever finds it deletes it, and the instance counts as stopped. That is what keeps a
/// launcher that was killed, or a machine that was rebooted, from leaving an instance that looks
/// running for ever.
///
/// @param pid       the child's process id
/// @param port      the port it was told to serve on
/// @param url       the address it reported, or `null` while it is still starting
/// @param surface   the surface it was booted into, as [DshSurface] names it
/// @param startedAt when it was started, in milliseconds since the epoch
@NotNullByDefault
record DshRunningRecord(long pid, int port, @Nullable String url, @Nullable String surface, long startedAt) {

	/// The file's name in the instance's own directory.
	private static final String FILE_NAME = "running.json";

	/// How far apart the launcher's clock and the process's own start may be before the record is
	/// taken to describe somebody else.
	///
	/// A pid is reused. A record that outlived its process would otherwise point at whoever holds
	/// the number now, and the instance would look running for as long as that unrelated process
	/// lived. The tolerance is here because the record is written by the parent a moment before the
	/// child exists, so the two never agree exactly.
	private static final Duration START_TOLERANCE = Duration.ofMinutes(1);

	/// Returns the file an instance's record lives in, or `null` when its directory cannot be read.
	///
	/// @param instance the instance
	/// @return the file, or `null`
	private static @Nullable Path fileOf(DshInstance instance) {
		try {
			return instance.instanceDirectory().resolve(FILE_NAME);
		} catch (DshException e) {
			LOG.warning("Could not work out where the running record of " + instance.id() + " goes", e);
			return null;
		}
	}

	/// Writes down what this launcher is running, replacing what was there.
	///
	/// A record that cannot be written costs the other processes a view of this one and nothing
	/// else: the instance it describes is running either way, which is why this reports nothing.
	///
	/// @param instance  the instance
	/// @param pid       the child's process id
	/// @param port      the port it was told to serve on
	/// @param url       the address it reported, or `null` while it is still starting
	/// @param surface   the surface it was booted into
	/// @param startedAt when it was started, in milliseconds since the epoch
	static void write(DshInstance instance, long pid, int port, @Nullable URI url, DshSurface surface,
	                  long startedAt) {
		Path file = fileOf(instance);
		if (file == null) {
			return;
		}
		DshRunningRecord record = new DshRunningRecord(pid, port, url == null ? null : url.toString(),
		                                               surface.name(), startedAt);
		try {
			Files.createDirectories(file.getParent());
			JsonUtils.writeToJsonFile(file, record);
			// The address carries the launch token while the process is up.
			DshFiles.restrictToCurrentUser(file);
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not write the running record " + file, e);
		}
	}

	/// Reads what the instance's running process left, or `null` when nothing is running.
	///
	/// @param instance the instance
	/// @return the record, or `null` when there is none or the one there is describes a process
	///         that has gone
	static @Nullable DshRunningRecord read(DshInstance instance) {
		Path file = fileOf(instance);
		if (file == null || !Files.isRegularFile(file)) {
			return null;
		}
		DshRunningRecord record;
		try {
			record = JsonUtils.fromJsonFile(file, DshRunningRecord.class);
		} catch (Exception e) {
			LOG.warning("Could not read the running record " + file, e);
			record = null;
		}
		if (record == null || !record.isRunning()) {
			delete(instance);
			return null;
		}
		return record;
	}

	/// Deletes the record, if there is one.
	///
	/// @param instance the instance
	static void delete(DshInstance instance) {
		Path file = fileOf(instance);
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			LOG.warning("Could not delete the running record " + file, e);
		}
	}

	/// Reports whether the process this record names is still the one it names.
	///
	/// @return whether the process is alive and began when this record says it did
	boolean isRunning() {
		Optional<ProcessHandle> handle = ProcessHandle.of(pid);
		if (handle.isEmpty() || !handle.get().isAlive()) {
			return false;
		}
		Optional<Instant> began = handle.get().info().startInstant();
		if (began.isEmpty()) {
			// A platform that will not say when a process began leaves the pid as the whole answer.
			return true;
		}
		Duration apart = Duration.between(began.get(), Instant.ofEpochMilli(startedAt)).abs();
		return apart.compareTo(START_TOLERANCE) < 0;
	}

	/// Returns the address the harness reported, while there is one.
	///
	/// @return the address, empty while the instance is starting or when it reported none
	Optional<URI> webUrl() {
		if (url == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(new URI(url));
		} catch (URISyntaxException e) {
			LOG.warning("The running record carries an address that cannot be read: " + url, e);
			return Optional.empty();
		}
	}

	/// Returns how long the process has been running.
	///
	/// @return the time since it was started
	Duration uptime() {
		return Duration.ofMillis(Math.max(0, System.currentTimeMillis() - startedAt));
	}
}
