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

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Gives a file or directory the specified mode.<br>
 * These files/directories may hold secrets, 0600->configFile and 0700->dir/executable is suggested.
 */
@NotNullByDefault
public final class DshPermissions {
	public static final Set<PosixFilePermission> SECRET_FILE = PosixFilePermissions.fromString("rw-------"); // 0600
	public static final Set<PosixFilePermission> SECRET_DIRECTORY = PosixFilePermissions.fromString("rwx------"); // 0700
	public static final Set<PosixFilePermission> SECRET_EXECUTABLE = PosixFilePermissions.fromString("rwx------"); // 0700

	private DshPermissions() {}

	/**
	 * Gives a path the specified mode.
	 */
	public static void restrict(Path path, Set<PosixFilePermission> mode) {
		try {
			PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
			if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
				return; // exclude windows and some special url
			}

			view.setPermissions(mode);
		} catch (IOException | UnsupportedOperationException e) {
			LOG.info("Could not restrict the permissions of " + path, e);
		}
	}

	/**
	 * Creates a file with the mode if it is not there, and gives an existing file the mode.
	 *
	 * @throws IOException when the file cannot be created
	 */
	public static void createRestricted(Path file, Set<PosixFilePermission> mode) throws IOException {
		Files.getFileAttributeView(file, PosixFileAttributeView.class);
		if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) return;

		try {
			Files.createFile(file, PosixFilePermissions.asFileAttribute(mode));
		} catch (FileAlreadyExistsException e) {
			// The file is already there, and is given the mode below.
		}
		restrict(file, mode);
	}

	/**
	 * Creates a permission restricted directory.
	 *
	 * @throws IOException when the dir cannot be created
	 */
	public static void createRestrictedDirectory(Path dir, Set<PosixFilePermission> mode) throws IOException {
		Files.createDirectories(dir);
		restrict(dir, mode);
	}

	/**
	 * Writes text into a file with spec mode.
	 *
	 * @param text    what to write into it
	 * @param charset the charset to write it in
	 * @throws IOException when it cannot be written
	 */
	public static void writeRestricted(Path file, String text, Charset charset, Set<PosixFilePermission> mode)
	throws IOException {
		createRestricted(file, mode);
		Files.writeString(file, text, charset);
	}
}
