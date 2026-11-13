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
 * The HDSL directory layout on disk.
 * <p>
 * Each instance keeps a copy of DeepSeek Harness under {@link #INSTANCES}.
 */
@NotNullByDefault
public final class DshPaths {
	// Root of all HDSL user data.
	public static final Path ROOT = Metadata.HDSL_USER_HOME;
	public static final Path INSTANCES = ROOT.resolve("instances");
	public static final Path HOMES = ROOT.resolve("homes");
	public static final Path RUNTIMES = ROOT.resolve("runtimes");
	public static final Path PNPM = ROOT.resolve("pnpm");
	public static final Path CATALOG = ROOT.resolve("catalog");
	/**
	 * Launcher log directory. The log file and logs exported from the log window are written here.
	 * <p>
	 * The log file uses the naming and layout of
	 * {@link org.jackhuang.hmcl.util.logging.Logger}.
	 * The logger compresses the file to {@code .xz} at the end of a run.
	 * <p>
	 * Exported logs are also written here. They are not written to the process working directory.
	 */
	public static final Path LOGS = ROOT.resolve("logs");

	private DshPaths() {}

	/**
	 * Returns the home directory for a harness version.
	 *
	 * @param version the harness version
	 * @return the home directory
	 * @throws DshException if version cannot be used as a path segment
	 */
	public static Path versionHomeDirectory(String version) throws DshException {
		return HOMES.resolve(segment(version, "version"));
	}

	/**
	 * Returns the DSH directory for an instance.
	 * <p>
	 * The directory holds the instance's DeepSeek Harness copy.
	 * The boot library is installed into this directory.
	 *
	 * @param instanceId the instance id
	 * @return the instance's DSH directory
	 * @throws DshException if instanceId cannot be used as a path segment
	 */
	public static Path instanceVersionDirectory(String instanceId) throws DshException {
		return instanceDirectory(instanceId).resolve("dsh");
	}

	/**
	 * Returns the directory for a launcher-installed Node.js runtime.
	 *
	 * @param version the Node version
	 * @return the runtime directory
	 * @throws DshException if version cannot be used as a path segment
	 */
	public static Path runtimeDirectory(String version) throws DshException {
		return RUNTIMES.resolve(segment(version, "runtime version"));
	}

	/**
	 * Returns the npm prefix directory for a launcher-managed pnpm.
	 *
	 * @param version the pnpm version
	 * @return the npm prefix directory
	 * @throws DshException if version cannot be used as a path segment
	 */
	public static Path pnpmDirectory(String version) throws DshException {
		return PNPM.resolve(segment(version, "pnpm version"));
	}

	/**
	 * Returns the directory for a launcher instance.
	 *
	 * @param instanceId the instance id
	 * @return the instance directory
	 * @throws DshException if instanceId cannot be used as a path segment
	 */
	public static Path instanceDirectory(String instanceId) throws DshException {
		return INSTANCES.resolve(segment(instanceId, "instance id"));
	}

	/**
	 * Reports whether value can be used as one path segment.
	 * <p>
	 * The rule is same as {@link segment(String, String)}.
	 *
	 * @param value the value to test
	 * @return true if the value is usable
	 */
	public static boolean isUsableSegment(@Nullable String value) {
		String trimmed = value == null ? "" : value.trim();
		return isSafeSegment(trimmed);
	}

	/**
	 * Reports whether a trimmed string is a usable single path
	 * segment. The rule comes from {@link FileUtils#isNameValid(OperatingSystem, String)}.
	 * <p>
	 * The Windows rule applies on every platform. Three systems
	 * share one repository and one set of instance folders.
	 * <p>
	 * A name must stay valid after a copy between them.
	 * The following values are refused: reserved device names, a trailing dot or space,
	 * the drive colon, path separators, control characters, and non-character code points.
	 *
	 * @param trimmed the value, already trimmed
	 * @return true if the value is usable
	 */
	private static boolean isSafeSegment(String trimmed) {
		if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\")) {
			return false;
		}
		return FileUtils.isNameValid(OperatingSystem.WINDOWS, trimmed);
	}

	/**
	 * Returns value as a path segment.
	 *
	 * @param value the candidate segment
	 * @param what  a human-readable name for value, used in the error message
	 * @return the trimmed segment
	 * @throws DshException if value cannot be used as a directory name
	 */
	public static String segment(@Nullable String value, String what) throws DshException {
		String trimmed = value == null ? "" : value.trim();
		if (!isSafeSegment(trimmed)) {
			throw new DshException("Invalid " + what + ": " + value);
		}
		return trimmed;
	}
}