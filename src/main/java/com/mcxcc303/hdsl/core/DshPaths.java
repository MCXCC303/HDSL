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
 * Every instance keeps a copy of DeepSeek Harness in {@link #INSTANCES}.
 */
@NotNullByDefault
public final class DshPaths {
	/// The root of all HDSL user data.
	public static final Path ROOT = Metadata.HDSL_USER_HOME;
	/**
	 * One directory for each launcher instance.
	 */
	public static final Path INSTANCES = ROOT.resolve("instances");
	/**
	 * One home for each harness version, shared by every instance of that version.
	 */
	public static final Path HOMES = ROOT.resolve("homes");
	/**
	 * One directory for each launcher-installed Node.js runtime.
	 */
	public static final Path RUNTIMES = ROOT.resolve("runtimes");
	/**
	 * One directory for each launcher-installed pnpm version.
	 */
	public static final Path PNPM = ROOT.resolve("pnpm");
	/// Cached copies of remote catalogues such as the quick-install presets.
	public static final Path CATALOG = ROOT.resolve("catalog");
	/**
	 * Where the launcher keeps the log file and the logs exported from the log window.
	 * <p>
	 * The log file follows the naming and layout of
	 * {@link org.jackhuang.hmcl.util.logging.Logger}, and the logger compresses the file to {@code .xz}
	 * when the run ends.
	 * <p>
	 * Exported logs are written here too, not in the working directory of the process.
	 */
	public static final Path LOGS = ROOT.resolve("logs");

	private DshPaths() {
	}

	/**
	 * Returns the home directory of one harness version.
	 *
	 * @param version the harness version
	 * @return the home directory
	 * @throws DshException when the version is not usable as a path segment
	 */
	public static Path versionHomeDirectory(String version) throws DshException {
		return HOMES.resolve(segment(version, "version"));
	}

	/**
	 * Returns the DSH directory for one instance.
	 * <p>
	 * The directory holds the instance's copy of DeepSeek Harness. The boot library is installed
	 * into this directory.
	 *
	 * @param instanceId the instance id
	 * @return the instance's DSH directory
	 * @throws DshException if the id cannot be used as a path segment
	 */
	public static Path instanceVersionDirectory(String instanceId) throws DshException {
		return instanceDirectory(instanceId).resolve("dsh");
	}

	/**
	 * Returns the directory of a launcher-installed Node.js runtime.
	 *
	 * @param version the Node version
	 * @return the runtime directory
	 * @throws DshException when the version is not usable as a path segment
	 */
	public static Path runtimeDirectory(String version) throws DshException {
		return RUNTIMES.resolve(segment(version, "runtime version"));
	}

	/**
	 * Returns the npm prefix directory of a launcher-managed pnpm.
	 *
	 * @param version the pnpm version
	 * @throws DshException when the version is not usable as a path segment
	 */
	public static Path pnpmDirectory(String version) throws DshException {
		return PNPM.resolve(segment(version, "pnpm version"));
	}

	/**
	 * Returns the directory of one launcher instance.
	 * <p>
	 * The returned path need not exist yet.
	 *
	 * @param instanceId the instance id
	 * @return the instance directory
	 * @throws DshException when the id is not usable as a path segment
	 */
	public static Path instanceDirectory(String instanceId) throws DshException {
		return INSTANCES.resolve(segment(instanceId, "instance id"));
	}

	/**
	 * Reports whether a value can be used as one path segment.
	 * <p>
	 * Applies the same rule as {@link #segment(String, String)}.
	 *
	 * @param value the value to test
	 * @return whether the value is usable
	 */
	public static boolean isUsableSegment(@Nullable String value) {
		String trimmed = value == null ? "" : value.trim();
		return isSafeSegment(trimmed);
	}

	/**
	 * Reports whether a trimmed string is a usable single path segment.
	 * <p>
	 * The rule comes from {@link FileUtils#isNameValid(OperatingSystem, String)}, and the Windows
	 * rule applies on every platform. The three systems share one repository and one set of
	 * instance folders, and a name has to stay valid after a copy between them.
	 * <p>
	 * Refused are the reserved device names, the trailing dot or space Windows strips from
	 * a name, the drive colon, the separators, the control characters and the code points
	 * that are not characters.
	 *
	 * @param trimmed the value, already trimmed
	 * @return whether the value is usable
	 */
	private static boolean isSafeSegment(String trimmed) {
		if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\")) {
			return false;
		}
		return FileUtils.isNameValid(OperatingSystem.WINDOWS, trimmed);
	}

	/**
	 * Returns a value as a path segment, refusing one that cannot be a directory name.
	 *
	 * @param value the candidate segment
	 * @param what  a human-readable name for the value, used in the error message
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
