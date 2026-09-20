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
package org.jackhuang.hmcl;

import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// Stores metadata about the HMCL-DSH application and the directories it owns.
///
/// All launcher-owned data lives under a single user home so that HMCL-DSH can
/// be installed next to a real HMCL without either application seeing the
/// other's settings.
@NotNullByDefault
public final class Metadata {
    private Metadata() {
    }

    /// The short product name.
    public static final String NAME = "HMCL-DSH";

    /// The full product name shown in window titles.
    public static final String FULL_NAME = "HMCL-DSH";

    /// The running version.
    ///
    /// Resolution order: an explicit override (used by `gradlew run`, which has
    /// no packaged manifest to read), then the jar manifest written by the
    /// shadow task, then a development placeholder.
    public static final String VERSION = resolveVersion();

    /// Resolves the running version.
    ///
    /// Order: an explicit override, which `gradlew run` passes because a class
    /// directory has no manifest; then the jar manifest written by the shadow
    /// task; then a development placeholder.
    ///
    /// @return the version string
    private static String resolveVersion() {
        String override = System.getProperty("hmcldsh.version.override");
        if (StringUtils.isNotBlank(override)) {
            return override;
        }
        String fromManifest = Metadata.class.getPackage().getImplementationVersion();
        return StringUtils.isNotBlank(fromManifest) ? fromManifest : "0.1.0-dev";
    }

    /// The window title including the version.
    public static final String TITLE = NAME + " " + VERSION;

    /// The full window title including the version.
    public static final String FULL_TITLE = FULL_NAME + " v" + VERSION;

    /// The documentation and help entry point shown in the title bar.
    public static final String CONTACT_URL = "https://github.com/";

    /// The identifier used for Linux desktop integration and window grouping.
    public static final String APPLICATION_ID = "org.jackhuang.hmcl.dsh";

    /// The identifier used for Windows taskbar grouping.
    ///
    /// Retained only so the transplanted window code keeps compiling; HMCL-DSH
    /// does not target Windows.
    public static final String WINDOWS_APP_USER_MODEL_ID = APPLICATION_ID;

    /// The directory the launcher was started from.
    public static final Path CURRENT_DIRECTORY = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    /// The per-user directory holding shared HMCL-DSH data.
    public static final Path HMCL_USER_HOME;

    /// The per-workspace directory holding HMCL-DSH configuration and state.
    public static final Path HMCL_LOCAL_HOME;

    /// The directory holding runtime dependencies downloaded by the launcher.
    public static final Path DEPENDENCIES_DIRECTORY;

    static {
        String userHome = System.getProperty("hmcldsh.home", System.getenv("HMCLDSH_USER_HOME"));
        if (StringUtils.isBlank(userHome)) {
            if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
                String xdgData = System.getenv("XDG_DATA_HOME");
                HMCL_USER_HOME = StringUtils.isNotBlank(xdgData)
                        ? Path.of(xdgData, "hmcl-dsh").toAbsolutePath().normalize()
                        : Path.of(System.getProperty("user.home"), ".local", "share", "hmcl-dsh").toAbsolutePath().normalize();
            } else {
                HMCL_USER_HOME = OperatingSystem.getWorkingDirectory("hmcl-dsh");
            }
        } else {
            HMCL_USER_HOME = Path.of(userHome).toAbsolutePath().normalize();
        }

        String localHome = System.getProperty("hmcldsh.dir", System.getenv("HMCLDSH_LOCAL_HOME"));
        HMCL_LOCAL_HOME = StringUtils.isNotBlank(localHome)
                ? Path.of(localHome).toAbsolutePath().normalize()
                : HMCL_USER_HOME;

        String dependencies = System.getProperty("hmcldsh.dependencies.dir", System.getenv("HMCLDSH_DEPENDENCIES_DIR"));
        DEPENDENCIES_DIRECTORY = StringUtils.isNotBlank(dependencies)
                ? Path.of(dependencies).toAbsolutePath().normalize()
                : HMCL_USER_HOME.resolve("dependencies");
    }
}
