/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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
package org.jackhuang.hmcl.dsh;

import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// The directory layout HMCL-DSH owns on disk.
///
/// Two distinct kinds of state live here and must not be confused:
///
/// - [#VERSIONS] holds DeepSeek Harness *installations* (code). Several
///   versions coexist, each in its own npm prefix.
/// - [#INSTANCES] holds launcher *instances*. An instance owns a `DSH_HOME`
///   and a profile, and references one installed version.
///
/// Keeping them separate is what lets two instances share one version while
/// still keeping their profiles, sessions and credentials apart.
@NotNullByDefault
public final class DshPaths {
    private DshPaths() {
    }

    /// The root of all HMCL-DSH user data.
    public static final Path ROOT = Metadata.HMCL_USER_HOME;

    /// One directory per installed DeepSeek Harness version, each an npm prefix.
    public static final Path VERSIONS = ROOT.resolve("versions");

    /// One directory per launcher instance.
    public static final Path INSTANCES = ROOT.resolve("instances");

    /// Cached copies of remote catalogues such as the quick-install presets.
    public static final Path CATALOG = ROOT.resolve("catalog");

    /// Returns the npm prefix directory for a DeepSeek Harness version.
    ///
    /// The directory is returned whether or not it exists.
    ///
    /// @param version the version string, which must be a safe path segment
    /// @return the version's prefix directory
    /// @throws DshException when the version string cannot be used as a directory name
    public static Path versionDirectory(String version) throws DshException {
        return VERSIONS.resolve(requireSafeSegment(version, "version"));
    }

    /// Returns the launcher instance directory for an instance id.
    ///
    /// The directory is returned whether or not it exists.
    ///
    /// @param instanceId the instance identifier
    /// @return the instance directory
    /// @throws DshException when the identifier cannot be used as a directory name
    public static Path instanceDirectory(String instanceId) throws DshException {
        return INSTANCES.resolve(requireSafeSegment(instanceId, "instance id"));
    }

    /// Validates a string for use as a single path segment.
    ///
    /// @param value the candidate segment
    /// @param what  a human-readable name for the value, used in the error message
    /// @return the trimmed segment
    /// @throws DshException when the value is empty or contains path separators
    private static String requireSafeSegment(String value, String what) throws DshException {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()
                || trimmed.equals(".")
                || trimmed.equals("..")
                || trimmed.contains("/")
                || trimmed.contains("\\")
                || trimmed.indexOf('\0') >= 0) {
            throw new DshException("Invalid " + what + ": " + value);
        }
        return trimmed;
    }
}
