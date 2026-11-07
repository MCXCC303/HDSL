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
package com.mcxcc303.hdsl.core;

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Standard filesystem paths for HDSL user data, instances, and runtimes.
 */
@NotNullByDefault
public final class DshPaths {
	public static final Path ROOT = Metadata.HMCL_USER_HOME;
	public static final Path INSTANCES = ROOT.resolve("instances");
	public static final Path HOMES = ROOT.resolve("homes");
	public static final Path RUNTIMES = ROOT.resolve("runtimes");
	public static final Path CATALOG = ROOT.resolve("catalog");
	public static final Path LOGS = ROOT.resolve("logs");

	private DshPaths() {
	}

	/**
	 * Returns the home shared between instances running a specific version.
	 *
	 * @param version the version
	 * @return the home directory
	 * @throws DshException when the version is not usable as a path segment
	 */
	public static Path versionHomeDirectory(String version) throws DshException {
		return HOMES.resolve(segment(version, "version"));
	}

	/**
	 * Returns the directory holding an instance's copy of DeepSeek Harness.
	 *
	 * @param instanceId the instance id
	 * @return the directory path
	 * @throws DshException when the instance identifier is not usable as a path segment
	 */
	public static Path instanceVersionDirectory(String instanceId) throws DshException {
		return instanceDirectory(instanceId).resolve("dsh");
	}

	/**
	 * Returns the directory for a launcher-managed Node runtime.
	 *
	 * @param version the Node version
	 * @return the runtime directory
	 * @throws DshException when the version is not usable as a path segment
	 */
	public static Path runtimeDirectory(String version) throws DshException {
		return RUNTIMES.resolve(segment(version, "runtime version"));
	}

	/**
	 * Returns the directory for an instance id.
	 *
	 * @param instanceId the instance identifier
	 * @return the instance directory
	 * @throws DshException when the identifier cannot be used as a directory name
	 */
	public static Path instanceDirectory(String instanceId) throws DshException {
		return INSTANCES.resolve(segment(instanceId, "instance id"));
	}

	/**
	 * Reports whether a value can be used as a single path segment.
	 *
	 * @param value the candidate name
	 * @return whether it is safe to use as a directory name
	 */
	public static boolean isUsableSegment(@Nullable String value) {
		String trimmed = value == null ? "" : value.trim();
		return isSafeSegment(trimmed);
	}

	private static boolean isSafeSegment(String trimmed) {
		if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\")) {
			return false;
		}
		return FileUtils.isNameValid(OperatingSystem.WINDOWS, trimmed);
	}

	/**
	 * Validates and trims a path segment name.
	 *
	 * @param value the candidate segment
	 * @param what  descriptive label for error reporting
	 * @return the trimmed segment
	 * @throws DshException when the value cannot be used as a directory name
	 */
	public static String segment(@Nullable String value, String what) throws DshException {
		String trimmed = value == null ? "" : value.trim();
		if (!isSafeSegment(trimmed)) {
			throw new DshException("Invalid " + what + ": " + value);
		}
		return trimmed;
	}
}
