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
import java.nio.file.attribute.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Restricts file and directory access using POSIX permissions or Windows ACLs.<br>
 * Use {@link #SECRET_FILE} (0600) for secret files and {@link #SECRET_DIRECTORY} or
 * {@link #SECRET_EXECUTABLE} (0700) for directories and executables.
 */
@NotNullByDefault
public final class DshPermissions {
	public static final Set<PosixFilePermission> SECRET_FILE = PosixFilePermissions.fromString("rw-------"); // 0600
	public static final Set<PosixFilePermission> SECRET_DIRECTORY = PosixFilePermissions.fromString("rwx------"); // 0700
	public static final Set<PosixFilePermission> SECRET_EXECUTABLE = PosixFilePermissions.fromString("rwx------"); // 0700

	private DshPermissions() {}

	/**
	 * Restricts an existing path's permissions. Failures never propagate; a store without a
	 * usable view is skipped silently, other failures are logged.
	 * <p>
	 * Best-effort. Use the creating methods when a failure to restrict must stop the writes.
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
			if (view == null) return;
			if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) return;

			view.setPermissions(mode);
		} catch (IOException | UnsupportedOperationException e) {
			LOG.warning("Could not restrict the permissions of " + path, e);
		}
	}

	/**
	 * Creates a file with the specified permissions, or applies them to an existing file.
	 *
	 * @throws IOException if the file cannot be created or the store has no view to protect it with
	 */
	public static void createRestricted(Path file, Set<PosixFilePermission> mode) throws IOException {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			try {
				Files.createFile(file);
			} catch (FileAlreadyExistsException e) {
				// The file is already there, and is given the ACL below.
			}
			restrictToCurrentUser(file);
			return;
		}

		requirePosixView(file);
		try {
			Files.createFile(file, PosixFilePermissions.asFileAttribute(mode));
		} catch (FileAlreadyExistsException e) {
			// The file is already there, and is given the mode below.
		}
		restrict(file, mode);
	}

	/**
	 * Creates a directory and restricts its permissions. Windows & POSIX
	 *
	 * @throws IOException if the directory cannot be created or the store has no view to protect it with
	 */
	public static void createRestrictedDirectory(Path dir, Set<PosixFilePermission> mode) throws IOException {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			createRestrictedDirectory(dir);
			return;
		}

		requirePosixView(dir);
		Files.createDirectories(dir);
		restrict(dir, mode);
	}

	/**
	 * Creates or restricts a file before writing text into it.
	 *
	 * @param text    text to write
	 * @param charset encoding to use
	 * @throws IOException if the file cannot be created, the store has no view to protect it with,
	 *                     or the text cannot be written
	 */
	public static void writeRestricted(Path file, String text, Charset charset, Set<PosixFilePermission> mode)
	throws IOException {
		createRestricted(file, mode);
		Files.writeString(file, text, charset);
	}

	private static void requirePosixView(Path path) throws IOException {
		if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			throw new IOException("This file store has no POSIX permissions to protect " + path);
		}
	}

	/**
	 * Creates missing directories one at a time, protecting each before creating its children. Windows only.
	 * <p>
	 * This prevents newly created directories from inheriting broader access from their parents.
	 */
	private static void createRestrictedDirectory(Path dir) throws IOException {
		Path absolute = dir.toAbsolutePath().normalize();
		List<Path> missing = new ArrayList<>();
		for (Path path = absolute;
		     path != null && !Files.exists(path, LinkOption.NOFOLLOW_LINKS); path = path.getParent()) {
			missing.add(path);
		}
		for (int i = missing.size() - 1; i >= 0; i--) {
			Path path = missing.get(i);
			try {
				Files.createDirectory(path);
			} catch (FileAlreadyExistsException e) {
				// Another thread made it between the check above and this call.
			}
			if (!path.equals(absolute)) restrictToCurrentUser(path); // before the child exists, thus it cannot inherit
		}
		restrictToCurrentUser(dir);
	}

	/**
	 * Replaces a Windows path's ACL with entries for its owner and the current user.
	 * <p>
	 * Directory entries are inheritable so that children retain the same access restrictions.
	 *
	 * @throws IOException if the ACL cannot be read or updated
	 */
	private static void restrictToCurrentUser(Path path) throws IOException {
		AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
		if (acl == null) throw new IOException("This file store has no ACL view to protect " + path);
		try {
			boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
			UserPrincipal owner = acl.getOwner();
			UserPrincipal current = currentUser(path, owner);
			List<AclEntry> entries = new ArrayList<>();
			entries.add(allow(owner, directory));
			if (!owner.equals(current)) {
				entries.add(allow(current, directory));
			}
			acl.setAcl(entries);
		} catch (RuntimeException e) {
			throw new IOException("Could not restrict the permissions of " + path, e);
		}
	}

	/**
	 * Returns the principal of the user running the launcher, or the owner when it cannot be named.
	 */
	private static UserPrincipal currentUser(Path path, UserPrincipal fallback) {
		String name = System.getProperty("user.name");
		if (name == null || name.isBlank()) return fallback;

		try {
			return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);
		} catch (IOException | RuntimeException e) {
			return fallback;
		}
	}

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
