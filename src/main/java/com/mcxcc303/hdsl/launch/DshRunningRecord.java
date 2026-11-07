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
import com.mcxcc303.hdsl.core.DshPermissions;
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

/**
 * On-disk record of an actively running instance process.
 *
 * @param pid       the child's process ID
 * @param port      the port it was told to serve on
 * @param url       the address it reported, or null while starting
 * @param surface   the surface it was booted into
 * @param startedAt start timestamp in milliseconds since epoch
 */
@NotNullByDefault
record DshRunningRecord(long pid, int port, @Nullable String url, @Nullable String surface, long startedAt) {

	private static final String FILE_NAME = "running.json";
	private static final Duration START_TOLERANCE = Duration.ofMinutes(1);

	private static @Nullable Path fileOf(DshInstance instance) {
		try {
			return instance.instanceDirectory().resolve(FILE_NAME);
		} catch (DshException e) {
			LOG.warning("Could not work out where the running record of " + instance.id() + " goes", e);
			return null;
		}
	}

	/**
	 * Persists a running record for the given instance.
	 */
	static void write(DshInstance instance, long pid, int port, @Nullable URI url, DshSurface surface,
	                  long startedAt) {
		Path file = fileOf(instance);
		if (file == null) {
			return;
		}
		DshRunningRecord record = new DshRunningRecord(pid, port, url == null ? null : url.toString(),
		                                               surface.name(), startedAt);
		try {
			DshPermissions.createRestrictedDirectory(file.getParent(), DshPermissions.SECRET_DIRECTORY);
			DshPermissions.createRestricted(file, DshPermissions.SECRET_FILE);
			JsonUtils.writeToJsonFile(file, record);
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not write the running record " + file, e);
		}
	}

	/**
	 * Reads the running record for an instance, deleting it if stale.
	 *
	 * @param instance the instance
	 * @return active record, or null if absent or process has terminated
	 */
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

	/**
	 * Deletes the instance's running record file if present.
	 *
	 * @param instance the instance
	 */
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

	/**
	 * Reports whether the process named in this record is still alive.
	 *
	 * @return true if alive and within start tolerance
	 */
	boolean isRunning() {
		Optional<ProcessHandle> handle = ProcessHandle.of(pid);
		if (handle.isEmpty() || !handle.get().isAlive()) {
			return false;
		}
		Optional<Instant> began = handle.get().info().startInstant();
		if (began.isEmpty()) {
			return true;
		}
		Duration apart = Duration.between(began.get(), Instant.ofEpochMilli(startedAt)).abs();
		return apart.compareTo(START_TOLERANCE) < 0;
	}

	/**
	 * Returns the web interface address reported by the instance, if valid.
	 *
	 * @return optional URI of the web interface
	 */
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

	/**
	 * Returns how long the process has been running.
	 *
	 * @return uptime duration
	 */
	Duration uptime() {
		return Duration.ofMillis(Math.max(0, System.currentTimeMillis() - startedAt));
	}
}
