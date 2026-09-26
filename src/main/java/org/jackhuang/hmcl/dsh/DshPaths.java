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
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

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
    /// One directory per launcher instance.
    public static final Path INSTANCES = ROOT.resolve("instances");

    /// Holds the homes that are shared between instances of one version.
    public static final Path HOMES = ROOT.resolve("homes");

    /// One directory per Node.js runtime the launcher installed.
    ///
    /// Kept separate from [#VERSIONS] because a DeepSeek Harness version and a
    /// Node runtime are independent choices: many instances share one runtime,
    /// and one instance can be repinned without touching the other.
    public static final Path RUNTIMES = ROOT.resolve("runtimes");

    /// Cached copies of remote catalogues such as the quick-install presets.
    public static final Path CATALOG = ROOT.resolve("catalog");

    /// Where the launcher writes its own log, one file per run.
    ///
    /// The launcher's log is a file rather than only a stream because the thing it is for is
    /// reading after the fact: the switch that turns the debug lines on says "in the launcher's
    /// log", and a person who turns it on to diagnose something wants to find the file
    /// afterwards. The name and the layout are [#org.jackhuang.hmcl.util.logging.Logger]'s, which
    /// is the transplanted HMCL logger and writes `2026-09-26T23-34-07.log` here, compressed to
    /// `.xz` when the run ends.
    public static final Path LOGS = ROOT.resolve("logs");

    /// Returns the npm prefix directory for a DeepSeek Harness version.
    ///
    /// The directory is returned whether or not it exists.
    ///
    /// @param version the version string, which must be a safe path segment
    /// @return the version's prefix directory
    /// @throws DshException when the version string cannot be used as a directory name
    /// The home shared between every instance running one version.
    ///
    /// Outside the version's own files: each instance has its own copy of the
    /// runtime now, and a home that two instances share cannot live inside one
    /// of them.
    ///
    /// @param version the version
    /// @return the home directory
    /// @throws DshException when the version is not usable as a path segment
    public static Path versionHomeDirectory(String version) throws DshException {
        return HOMES.resolve(segment(version, "version"));
    }

    /// The directory holding an instance's own copy of DeepSeek Harness.
    ///
    /// Its own rather than shared between every instance that runs the same
    /// version. Sharing is cheaper on paper and worse in practice: the boot
    /// library is installed into the copy, so changing it for one instance
    /// changed it for all of them, and an instance that carries its own copy is
    /// an instance that can be moved, exported or thrown away whole. pnpm links
    /// a second copy of the same packages from a store it keeps, so the second
    /// one costs a fraction of the first.
    ///
    /// @param instanceId the instance
    /// @return the directory
    /// @throws DshException when the identifier is not usable as a path segment
    public static Path instanceVersionDirectory(String instanceId) throws DshException {
        return instanceDirectory(instanceId).resolve("dsh");
    }

    /// Returns the directory for a launcher-managed Node runtime.
    ///
    /// @param version the Node version, which must be a safe path segment
    /// @return the runtime directory
    /// @throws DshException when the version cannot be used as a directory name
    public static Path runtimeDirectory(String version) throws DshException {
        return RUNTIMES.resolve(segment(version, "runtime version"));
    }

    /// Returns the launcher instance directory for an instance id.
    ///
    /// The directory is returned whether or not it exists.
    ///
    /// @param instanceId the instance identifier
    /// @return the instance directory
    /// @throws DshException when the identifier cannot be used as a directory name
    public static Path instanceDirectory(String instanceId) throws DshException {
        return INSTANCES.resolve(segment(instanceId, "instance id"));
    }

    /// Validates a string for use as a single path segment.
    ///
    /// @param value the candidate segment
    /// @param what  a human-readable name for the value, used in the error message
    /// @return the trimmed segment
    /// @throws DshException when the value is empty or contains path separators
    /// Reports whether a value can be used as one segment of a path.
    ///
    /// The same rule [#segment(String, String)] enforces, asked in advance so that a
    /// page can refuse a name before it is used to make a directory.
    ///
    /// @param value the value to test
    /// @return whether it is usable
    public static boolean isUsableSegment(@Nullable String value) {
        String trimmed = value == null ? "" : value.trim();
        return isSafeSegment(trimmed);
    }

    /// Reports whether a trimmed string is a usable single path segment.
    ///
    /// The rule is the one the transplanted HMCL has always used for the names
    /// it makes directories from, [FileUtils#isNameValid(OperatingSystem, String)],
    /// and it covers everything a hand-written list here used to: the reserved
    /// device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1`…`COM9`, `LPT1`…`LPT9`,
    /// and the superscript spellings, with or without an extension), the
    /// trailing dot or space Windows strips off a name, the drive-separated
    /// colon, the separators, the control characters and the code points that
    /// are not characters.
    ///
    /// Windows' answer is the one asked for, on every platform, and that is
    /// deliberate: the three systems this launcher runs on share one repository
    /// and one set of instance folders, and a name one of them accepts and
    /// another cannot even write is a folder that stops working the moment it
    /// is copied to the other. Separators are refused by that rule on Windows
    /// and are named here as well, because they are what would turn one segment
    /// into two — the one failure the caller can never recover from.
    ///
    /// @param trimmed the value, already trimmed
    /// @return whether it is usable
    private static boolean isSafeSegment(String trimmed) {
        if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\")) {
            return false;
        }
        return FileUtils.isNameValid(OperatingSystem.WINDOWS, trimmed);
    }

    /// Returns a value as a path segment, refusing one that cannot be a directory name.
    ///
    /// Public because a caller that has a folder of its own — the folder the
    /// user is looking at, which is where a new instance goes — has to apply the
    /// same rule this class applies to the folders it owns.
    ///
    /// @param value the candidate segment
    /// @param what  a human-readable name for the value, used in the error message
    /// @return the trimmed segment
    /// @throws DshException when the value cannot be used as a directory name
    public static String segment(@Nullable String value, String what) throws DshException {
        String trimmed = value == null ? "" : value.trim();
        if (!isSafeSegment(trimmed)) {
            throw new DshException("Invalid " + what + ": " + value);
        }
        return trimmed;
    }
}
