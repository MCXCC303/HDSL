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

import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;

/**
 * File system utilities for recursively deleting directory trees.
 */
@NotNullByDefault
public final class DshFiles {
	private static final int ATTEMPTS = 4;
	private static final long WAIT_MILLIS = 120;

	private DshFiles() {
	}

	/**
	 * Removes a directory and all its contents recursively.
	 *
	 * @param root the directory to remove
	 * @throws IOException when an entry cannot be removed
	 */
	public static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		IOException failure = null;
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			try {
				remove(root);
				return;
			} catch (IOException e) {
				failure = e;
				sleep(WAIT_MILLIS * (attempt + 1));
			}
		}
		throw failure;
	}

	/**
	 * Removes a directory and its contents recursively, logging failures instead of throwing.
	 *
	 * @param root the directory to remove
	 * @return {@code true} if deleted successfully, {@code false} otherwise
	 */
	public static boolean deleteTreeQuietly(Path root) {
		try {
			deleteTree(root);
			return true;
		} catch (IOException | RuntimeException e) {
			Logger.LOG.warning("Failed to remove " + root, e);
			return false;
		}
	}

	private static void remove(Path path) throws IOException {
		// Reparse points (junctions/symlinks) must be unlinked rather than traversed.
		if (isReparsePoint(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			delete(path);
			return;
		}
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
			for (Path entry : entries) {
				remove(entry);
			}
		}
		delete(path);
	}

	private static void delete(Path path) throws IOException {
		try {
			Files.deleteIfExists(path);
		} catch (AccessDeniedException refused) {
			// Clear read-only attribute on Windows before retrying deletion.
			if (!clearReadOnly(path)) {
				throw refused;
			}
			Files.deleteIfExists(path);
		}
	}

	private static boolean clearReadOnly(Path path) {
		try {
			DosFileAttributeView attributes = Files.getFileAttributeView(path,
			                                                             DosFileAttributeView.class,
			                                                             LinkOption.NOFOLLOW_LINKS);
			if (attributes == null) {
				return false;
			}
			attributes.setReadOnly(false);
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * Reports whether a path is a symbolic link or junction.
	 *
	 * @param path the path to check
	 * @return {@code true} if the path is a link, junction, or unreadable
	 */
	public static boolean isReparsePoint(Path path) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(path,
			                                                      BasicFileAttributes.class,
			                                                      LinkOption.NOFOLLOW_LINKS);
			return attributes.isSymbolicLink() || attributes.isOther();
		} catch (IOException | RuntimeException e) {
			return true;
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
