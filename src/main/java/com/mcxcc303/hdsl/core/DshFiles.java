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
 * File system utilities for recursively deleting directory trees safely across platforms.
 *
 * <p>The launcher deletes large nested trees created by package managers and Node runtimes.
 * Several platform-specific behaviors (measured on Windows) are handled:
 * <ul>
 * <li><strong>Read-only entries:</strong> Package managers unpack files with read-only attributes.
 * On Windows, {@link Files#delete} fails with {@link AccessDeniedException}. The read-only attribute
 * is cleared via {@link DosFileAttributeView} before retrying deletion.</li>
 * <li><strong>Directory Junctions ({@code mklink /J}):</strong> Junctions are reported as directories
 * by standard Java APIs ({@code isSymbolicLink} returns {@code false}, while {@code isDirectory} returns {@code true}).
 * Standard traversal would follow the junction and erase external target directories! Reparse points
 * are detected via {@link BasicFileAttributes#isOther()} with {@link LinkOption#NOFOLLOW_LINKS} so they are
 * unlinked rather than traversed.</li>
 * <li><strong>Pending file handles:</strong> Recently terminated processes take a moment to release open
 * handles; failed deletions are retried with incremental backoff.</li>
 * </ul>
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
	 * Reports whether a path is a symbolic link or Windows directory junction.
	 *
	 * <p>On Windows, a directory junction ({@code mklink /J}) reports {@code false} for
	 * {@link Files#isSymbolicLink(Path)} and {@code true} for {@link Files#isDirectory(Path, LinkOption...)}.
	 * Only {@link BasicFileAttributes#isOther()} correctly identifies it with {@link LinkOption#NOFOLLOW_LINKS}.
	 * Traversal without this check would descend through the junction and wipe the external target directory!
	 *
	 * <p>Also returns {@code true} when attributes cannot be read to prevent descending into unreadable paths.
	 *
	 * @param path the path to check
	 * @return {@code true} if the path is a symbolic link, directory junction, or unreadable
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
