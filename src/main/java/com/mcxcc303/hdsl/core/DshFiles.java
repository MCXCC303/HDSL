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
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/// Removes a tree of files the launcher owns.
///
/// The launcher removes whole trees it made itself: an instance, the copy of
/// DeepSeek Harness inside one, a staged install that did not finish, a Node
/// runtime it downloaded. Those trees come from a package manager, so they are
/// large, they are nested, and their entries carry the modes the packages that
/// shipped them declared — which is where the platform differences below come
/// from. Each of them was measured on Windows 11 rather than assumed.
///
/// - **A read-only entry cannot be deleted at all.** A package manager writes
///   the modes it unpacked, so a package that ships a read-only file leaves one
///   in the tree. `Files#delete` on it raises `AccessDeniedException`, the walk
///   stops there, and what is left behind is a half-deleted instance that can
///   neither be started nor removed — the failure the person sees is "could not
///   delete the instance" with no hint of which file stopped it. The attribute
///   is cleared here, and only for the entry that refused.
///
/// - **A junction must be removed, not entered.** A junction (`mklink /J`) — the
///   form a package manager and this launcher's own Node handling fall back to
///   where symbolic links are refused — is a *directory* to every Java API:
///   measured, `Files#isSymbolicLink` answers `false` for it, `Files#isDirectory`
///   answers `true`, and only `BasicFileAttributes#isOther` says what it is. A
///   walk that asks whether an entry is a directory therefore descends through
///   it and deletes what is on the *other* side: measured, deleting a tree that
///   held one junction to a directory outside it left that outside directory
///   empty. Nothing else in the launcher would do that, and no permission check
///   would see it happen.
///
/// - **A handle that is closing takes a moment to close.** The trees here are
///   held open by the process that owns them — an instance's own harness, a
///   package manager that has just finished. A removal that fails once is
///   therefore tried again after a short wait, which is the difference between
///   reporting a failure and having done the work.
@NotNullByDefault
public final class DshFiles {
	/// How many times a removal is attempted before it is reported as failed.
	private static final int ATTEMPTS = 4;

	/// How long the first wait between attempts is; each attempt waits longer.
	private static final long WAIT_MILLIS = 120;

	private DshFiles() {
	}

	/// Gives an entry a protected ACL that names only the people who already own it.
	///
	/// The Windows profile directory inherits access for groups such as the local Users group, and a
	/// terminal session's script can contain an account key while an exported log can contain the
	/// harness's launch token. A file that inherits a group's read access therefore hands those
	/// secrets to every member of that group. This replaces the inherited list with the owner and the
	/// current user, and marks directory entries inheritable so files created under them stay protected.
	///
	/// Filesystems without an ACL view are left alone: this is the Windows-side guard, and the POSIX
	/// permissions are a separate question.
	///
	/// @param path the file or directory to protect
	/// @throws IOException when the entry or its ACL cannot be read or written
	public static void restrictToCurrentUser(Path path) throws IOException {
		if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) {
			return;
		}
		AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
		if (acl == null) {
			return;
		}
		boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
		UserPrincipal owner = acl.getOwner();
		UserPrincipal current = currentUser(path, owner);
		List<AclEntry> entries = new ArrayList<>();
		entries.add(allow(owner, directory));
		if (!owner.equals(current)) {
			entries.add(allow(current, directory));
		}
		acl.setAcl(entries);
	}

	/// Returns the principal of the user running the launcher, or the owner when it cannot be named.
	private static UserPrincipal currentUser(Path path, UserPrincipal fallback) {
		String name = System.getProperty("user.name");
		if (name == null || name.isBlank()) {
			return fallback;
		}
		try {
			return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);
		} catch (IOException | RuntimeException e) {
			return fallback;
		}
	}

	/// Builds one allow entry for an owner or the current user.
	private static AclEntry allow(UserPrincipal principal, boolean directory) {
		EnumSet<AclEntryPermission> permissions = EnumSet.allOf(AclEntryPermission.class);
		EnumSet<AclEntryFlag> flags = directory
		                              ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
		                              : EnumSet.noneOf(AclEntryFlag.class);
		return AclEntry.newBuilder()
		               .setType(AclEntryType.ALLOW)
		               .setPrincipal(principal)
		               .setPermissions(permissions)
		               .setFlags(flags)
		               .build();
	}

	/// Removes a directory and everything under it.
	///
	/// A path that is not there is not an error: the caller asked for it to be
	/// gone, and it is.
	///
	/// @param root the directory to remove
	/// @throws IOException when an entry cannot be removed, naming that entry
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

	/// Removes a directory and everything under it, ignoring a failure.
	///
	/// Used where the removal is tidying up after an operation that has already
	/// succeeded, and where failing it would turn a success into a failure.
	///
	/// @param root the directory to remove
	/// @return whether it is gone
	public static boolean deleteTreeQuietly(Path root) {
		try {
			deleteTree(root);
			return true;
		} catch (IOException | RuntimeException e) {
			Logger.LOG.warning("Failed to remove " + root, e);
			return false;
		}
	}

	/// Removes one entry, taking its children first.
	///
	/// @param path the entry
	/// @throws IOException when it cannot be removed
	private static void remove(Path path) throws IOException {
		// A reparse point is deleted rather than walked into, whatever it points
		// at. It is asked before the directory question, because that is the
		// question a junction answers wrongly.
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

	/// Removes one entry, clearing the attribute that stops Windows from doing it.
	///
	/// @param path the entry
	/// @throws IOException when it still cannot be removed
	private static void delete(Path path) throws IOException {
		try {
			Files.deleteIfExists(path);
		} catch (AccessDeniedException refused) {
			// Only the entry that refused is touched, and only once: a tree of
			// fifty thousand files must not pay two extra calls each for the
			// handful that are read-only.
			if (!clearReadOnly(path)) {
				throw refused;
			}
			Files.deleteIfExists(path);
		}
	}

	/// Clears an entry's read-only attribute.
	///
	/// @param path the entry
	/// @return whether the attribute was cleared, which is impossible on a
	///         filesystem that has no such attribute
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
	 * Whether an entry is a link of some kind, including a junction (which
	 * {@link Files#isSymbolicLink} answers {@code false} for).
	 *
	 * <p>Also {@code true} when the entry cannot be read: nothing unreadable may be walked into.
	 */
	public static boolean isReparsePoint(Path path) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(path,
			                                                      BasicFileAttributes.class,
			                                                      LinkOption.NOFOLLOW_LINKS);
			return attributes.isSymbolicLink() || attributes.isOther();
		} catch (IOException | RuntimeException e) {
			// Something that cannot even be asked about is not something to walk
			// into: it is deleted, and the failure that follows names it.
			return true;
		}
	}

	/// Waits a while, giving a closing handle time to close.
	///
	/// @param millis how long to wait
	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
