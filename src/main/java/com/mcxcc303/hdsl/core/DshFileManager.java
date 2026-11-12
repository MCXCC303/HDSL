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

import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.SystemUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * System File managers' Manager, handle the HDSL's "open in explorer" action of files and dirs.
 */
@NotNullByDefault
public final class DshFileManager {
	private DshFileManager() {}

	/**
	 * Opens a directory to show paths inside.
	 *
	 * @param directory the directory to open
	 * @return the directory exists or not
	 */
	public static boolean openDirectory(Path directory) {
		if (!Files.isDirectory(directory)) {
			LOG.warning("Cannot open " + directory + ": Directory not exists");
			return false;
		}

		Path absolute = directory.toAbsolutePath();
		launch(openCommand(absolute), absolute, () -> desktopOpen(absolute));
		return true;
	}

	/**
	 * Shows a path in the folder that holds it, with the path selected.
	 *
	 * @param path the path to show
	 * @return the path exists or not
	 */
	public static boolean reveal(Path path) {
		if (!Files.exists(path)) {
			LOG.warning("Cannot show " + path + ": File not exists");
			return false;
		}

		if (OperatingSystem.CURRENT_OS.isLinuxOrBSD() && SystemUtils.which("dbus-send") == null) {
			return openContainingDirectory(path);
		}

		Path absolute = path.toAbsolutePath();
		List<String> command = revealCommand(absolute);
		if (command.isEmpty()) return openContainingDirectory(absolute);

		launch(command, absolute, () -> openContainingDirectory(absolute));
		return true;
	}

	/**
	 * Use system default file manager to opens a path.
	 *
	 * @param path the path to open
	 * @return the path exists or not
	 */
	public static boolean open(Path path) {
		return switch (Files.isDirectory(path) ? Action.OPEN_DIRECTORY : Action.REVEAL) {
			case OPEN_DIRECTORY -> openDirectory(path);
			case REVEAL -> reveal(path);
		};
	}

	/**
	 * The command that opens a directory in the file manager of the system.
	 *
	 * @param directory the directory to open
	 * @return the command
	 */
	static List<String> openCommand(Path directory) {
		return switch (OperatingSystem.CURRENT_OS) {
			case OperatingSystem.WINDOWS -> List.of("explorer.exe", directory.toString());
			case OperatingSystem.MACOS -> List.of("/usr/bin/open", directory.toString());
			default -> List.of("xdg-open", directory.toString());
		};
	}

	/**
	 * The command that shows a path in the file manager of the system.
	 *
	 * @param path the path to show
	 * @return the command, or an empty list when the system has none
	 */
	static List<String> revealCommand(Path path) {
		return switch (OperatingSystem.CURRENT_OS) {
			case WINDOWS -> List.of("explorer.exe", "/select,", path.toString());
			case MACOS -> List.of("/usr/bin/open", "-R", path.toString());
			default -> OperatingSystem.CURRENT_OS.isLinuxOrBSD() ? List.of("dbus-send",
			                                                               "--print-reply",
			                                                               "--dest=org.freedesktop.FileManager1",
			                                                               "/org/freedesktop/FileManager1",
			                                                               "org.freedesktop.FileManager1.ShowItems",
			                                                               "array:string:" +
			                                                               path.toAbsolutePath().toUri(),
			                                                               "string:") : List.of();
		};
	}

	private static boolean openContainingDirectory(Path path) {
		Path parent = path.getParent();
		return openDirectory(parent != null ? parent : path);
	}

	// Runs a command, when it does not work run the fallback.
	private static void launch(List<String> command, Path target, Runnable fallback) {
		Lang.thread(() -> {
			try {
				int exitCode = SystemUtils.callExternalProcess(command);
				// explorer.exe always return 1
				if (exitCode == 0 || (exitCode == 1 && OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS)) return;
				LOG.warning("Opening " + target + " failed with code " + exitCode);
			} catch (Throwable e) {
				LOG.warning("Unable to open " + target + " by executing " + command.getFirst(), e);
			}
			fallback.run();
		});
	}

	private static void desktopOpen(Path path) {
		try {
			Desktop.getDesktop().open(path.toFile());
		} catch (Throwable e) {
			LOG.error("Unable to open " + path + " by java.awt.Desktop.getDesktop()::open", e);
		}
	}

	enum Action {
		OPEN_DIRECTORY, REVEAL
	}
}
