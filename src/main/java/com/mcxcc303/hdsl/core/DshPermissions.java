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

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Gives a file or directory the specified mode.<br>
 * These files/directories may hold secrets, 0600->configFile and 0700->dir/executable is suggested.
 * <p>
 * The mode is a POSIX one. On Windows, where the filesystem has no mode, the same calls give the entry a
 * protected ACL instead: the inherited entries are replaced with the owner and the current user, so a
 * file that would otherwise inherit a local group's read access names only the accounts that own it.
 * See [#restrictToCurrentUser(Path)].
 */
@NotNullByDefault
public final class DshPermissions {
	public static final Set<PosixFilePermission> SECRET_FILE = PosixFilePermissions.fromString("rw-------"); // 0600
	public static final Set<PosixFilePermission> SECRET_DIRECTORY = PosixFilePermissions.fromString("rwx------"); // 0700
	public static final Set<PosixFilePermission> SECRET_EXECUTABLE = PosixFilePermissions.fromString("rwx------"); // 0700

	private DshPermissions() {}

	/**
	 * Gives a path the specified mode. POSIX only; on Windows it gives the path a protected ACL.
	 * <p>
	 * A failure is reported and otherwise ignored: this is also the call that tightens a directory an
	 * earlier launcher left loose, and a caller that cannot tighten it must still not fail its own work.
	 * Calls that are about to write a secret use the creating methods below, which do fail.
	 */
	public static void restrict(Path path, Set<PosixFilePermission> mode) {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			try {
				restrictToCurrentUser(path);
			} catch (IOException | RuntimeException e) {
				LOG.warning("Could not restrict the permissions of " + path, e);
			}
			return;
		}
		try {
			PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
			if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
				return; // exclude windows and some special url
			}

			view.setPermissions(mode);
		} catch (IOException | UnsupportedOperationException e) {
			LOG.warning("Could not restrict the permissions of " + path, e);
		}
	}

	/**
	 * Creates a file with the mode if it is not there, and gives an existing file the mode. POSIX only;
	 * on Windows the file is created and then given a protected ACL.
	 *
	 * @throws IOException when the file cannot be created, or its ACL cannot be written
	 */
	public static void createRestricted(Path file, Set<PosixFilePermission> mode) throws IOException {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			try {
				Files.createFile(file);
			} catch (FileAlreadyExistsException e) {
				// The file is already there, and is given the ACL below.
			}
			// Written via the failing form: a secret must not be written into a file whose ACL the
			// launcher could not replace.
			restrictToCurrentUser(file);
			return;
		}
		if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) return;

		try {
			Files.createFile(file, PosixFilePermissions.asFileAttribute(mode));
		} catch (FileAlreadyExistsException e) {
			// The file is already there, and is given the mode below.
		}
		restrict(file, mode);
	}

	/**
	 * Creates a permission restricted directory. POSIX only; on Windows every directory this call
	 * creates is given a protected ACL before the next one is made.
	 *
	 * @throws IOException when the dir cannot be created, or its ACL cannot be written
	 */
	public static void createRestrictedDirectory(Path dir, Set<PosixFilePermission> mode) throws IOException {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			createDirectoriesRestricted(dir);
			return;
		}
		Files.createDirectories(dir);
		restrict(dir, mode);
	}

	/**
	 * Writes text into a file with spec mode. POSIX only; on Windows the file is created and protected
	 * before the text is written into it.
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

	/**
	 * Creates every missing directory on the way to the one asked for, protecting each as it is made.
	 * <p>
	 * {@link Files#createDirectories(Path)} would make the whole chain first, and on Windows the ones
	 * above the leaf would inherit the profile directory's group access until the caller tightened
	 * them. Making them one at a time keeps the window closed: each is protected before anything can
	 * be put into it, and before the next one is made.
	 *
	 * @param dir the directory wanted
	 * @throws IOException when a directory cannot be created or its ACL cannot be written
	 */
	private static void createDirectoriesRestricted(Path dir) throws IOException {
		Path absolute = dir.toAbsolutePath().normalize();
		List<Path> missing = new ArrayList<>();
		for (Path path = absolute;
		     path != null && !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
		     path = path.getParent()) {
			missing.add(path);
		}
		for (int i = missing.size() - 1; i >= 0; i--) {
			Path path = missing.get(i);
			try {
				Files.createDirectory(path);
			} catch (FileAlreadyExistsException e) {
				// Another thread made it between the check above and this call.
			}
			if (!path.equals(absolute)) {
				// Protected before the child is made, so the child cannot inherit the profile
				// directory's group access from it.
				restrictToCurrentUser(path);
			}
		}
		// The leaf itself, and a directory that was already there: an older launcher left the
		// profile directory's inherited group access on it.
		restrictToCurrentUser(dir);
	}

	/**
	 * Replaces a path's ACL with one that names only the accounts that own it.
	 * <p>
	 * The Windows profile directory inherits access for groups such as the local Users group, and a
	 * terminal session's script can contain an account key while an exported log can contain the
	 * harness's launch token. A file that inherits a group's read access therefore hands those
	 * secrets to every member of that group. The owner and the user running the launcher get the
	 * entry; a directory's entry is inheritable, so files created under it stay protected too.
	 * <p>
	 * The POSIX mode is a separate question: this is the Windows-side guard.
	 *
	 * @param path the file or directory to protect
	 * @throws IOException when the entry or its ACL cannot be read or written
	 */
	private static void restrictToCurrentUser(Path path) throws IOException {
		AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
		if (acl == null) {
			return; // this file store has no ACL to protect with
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
}
