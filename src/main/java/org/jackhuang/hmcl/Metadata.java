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
package org.jackhuang.hmcl;

import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/**
 * Application metadata, product naming, and launcher home directory paths.
 */
@NotNullByDefault
public final class Metadata {
	private Metadata() {
	}

	/**
	 * Short product name.
	 */
	public static final String NAME = "HDSL";

	/**
	 * Full product name shown in window titles.
	 */
	public static final String FULL_NAME = "Hello DeepSeek! Launcher";

	/**
	 * Running application version string.
	 */
	public static final String VERSION = resolveVersion();

	private static String resolveVersion() {
		String override = System.getProperty("hdsl.version.override");
		if (StringUtils.isNotBlank(override)) {
			return override;
		}
		String fromManifest = Metadata.class.getPackage().getImplementationVersion();
		return StringUtils.isNotBlank(fromManifest) ? fromManifest : "0.1.0-dev";
	}

	/**
	 * Window title prefix including short name and version.
	 */
	public static final String TITLE = NAME + " " + VERSION;

	/**
	 * Full window title including full name and version.
	 */
	public static final String FULL_TITLE = FULL_NAME + " v" + VERSION;

	/**
	 * Project home URL.
	 */
	public static final String HOMEPAGE_URL = "https://github.com/MCXCC303/HDSL";

	/**
	 * Desktop integration and application ID.
	 */
	public static final String APPLICATION_ID = "run.hdsl.HDSL";

	/**
	 * Windows Application User Model ID for taskbar grouping.
	 */
	public static final String WINDOWS_APP_USER_MODEL_ID = APPLICATION_ID;

	/**
	 * Directory from which the launcher process was started.
	 */
	public static final Path CURRENT_DIRECTORY = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

	/**
	 * User home directory holding shared HDSL data and settings.
	 */
	public static final Path HMCL_USER_HOME;

	/**
	 * Workspace home directory holding local state.
	 */
	public static final Path HMCL_LOCAL_HOME;

	/**
	 * Directory holding downloaded launcher dependencies.
	 */
	public static final Path DEPENDENCIES_DIRECTORY;

	static {
		String userHome = System.getProperty("hdsl.home", System.getenv("HDSL_USER_HOME"));
		if (StringUtils.isBlank(userHome)) {
			if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
				String xdgData = System.getenv("XDG_DATA_HOME");
				HMCL_USER_HOME = StringUtils.isNotBlank(xdgData)
						? Path.of(xdgData, "hdsl").toAbsolutePath().normalize()
						: Path.of(System.getProperty("user.home"), ".local", "share", "hdsl").toAbsolutePath().normalize();
			} else {
				HMCL_USER_HOME = OperatingSystem.getWorkingDirectory("hdsl");
			}
		} else {
			HMCL_USER_HOME = Path.of(userHome).toAbsolutePath().normalize();
		}

		String localHome = System.getProperty("hdsl.dir", System.getenv("HDSL_LOCAL_HOME"));
		HMCL_LOCAL_HOME = StringUtils.isNotBlank(localHome)
				? Path.of(localHome).toAbsolutePath().normalize()
				: HMCL_USER_HOME;

		String dependencies = System.getProperty("hdsl.dependencies.dir", System.getenv("HDSL_DEPENDENCIES_DIR"));
		DEPENDENCIES_DIRECTORY = StringUtils.isNotBlank(dependencies)
				? Path.of(dependencies).toAbsolutePath().normalize()
				: HMCL_USER_HOME.resolve("dependencies");
	}
}
