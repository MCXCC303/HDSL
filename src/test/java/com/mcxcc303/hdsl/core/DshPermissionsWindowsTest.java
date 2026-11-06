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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ACL a file that may hold a secret carries on Windows, where there is no mode.
 * <p>
 * The class is Windows-only: the protection it checks is the Windows-side guard, and the POSIX mode is
 * a separate question.
 */
@EnabledOnOs(OS.WINDOWS)
class DshPermissionsWindowsTest {
	@TempDir
	Path folder;

	@Test
	void anExistingFileIsRestrictedToItsOwningAccounts() throws Exception {
		Path file = Files.writeString(folder.resolve("secret.txt"), "key");

		DshPermissions.restrict(file, DshPermissions.SECRET_FILE);

		assertOnlyOwningAccounts(file);
	}

	@Test
	void aCreatedFileIsRestrictedToItsOwningAccounts() throws Exception {
		Path file = folder.resolve("new.json");

		DshPermissions.createRestricted(file, DshPermissions.SECRET_FILE);

		assertTrue(Files.isRegularFile(file), "a missing secret file is created");
		assertOnlyOwningAccounts(file);
	}

	@Test
	void aWrittenFileIsRestrictedToItsOwningAccounts() throws Exception {
		Path file = folder.resolve("open.cmd");

		DshPermissions.writeRestricted(file, "set KEY=1", StandardCharsets.UTF_8, DshPermissions.SECRET_EXECUTABLE);

		assertEquals("set KEY=1", Files.readString(file), "the text is still written");
		assertOnlyOwningAccounts(file);
	}

	@Test
	void aStoreWithoutAnAclViewIsNotWrittenInto() throws Exception {
		Path archive = folder.resolve("store.zip");
		try (FileSystem store = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			Path file = store.getPath("/secret.txt");

			assertThrows(IOException.class,
			             () -> DshPermissions.writeRestricted(file, "sk-secret", StandardCharsets.UTF_8,
			                                                  DshPermissions.SECRET_FILE),
			             "the key is not written where the platform cannot protect it");
			assertFalse(Files.exists(file) && Files.size(file) > 0,
			            "the key is not left behind in the archive");
		}
	}

	@Test
	void aDirectoryIsRestrictedAndNewEntriesInheritIt() throws Exception {
		Path directory = folder.resolve("session");

		DshPermissions.createRestrictedDirectory(directory, DshPermissions.SECRET_DIRECTORY);

		assertOnlyOwningAccounts(directory);
		// A file written into the protected directory after it was made must not regain the
		// inherited group access the directory was protected from.
		Path entry = Files.writeString(directory.resolve("open.cmd"), "set KEY=1");
		assertOnlyOwningAccounts(entry);
	}

	/// Asserts that the path's ACL allows, and names no principal but the owner and the current user.
	private static void assertOnlyOwningAccounts(Path path) throws Exception {
		AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
		assertNotNull(view, "this test needs a file store with an ACL view to protect");
		List<AclEntry> entries = view.getAcl();
		assertTrue(entries.size() <= 2,
		           "the inherited groups are gone, leaving only the owner and current user: " + entries);
		assertTrue(entries.stream().allMatch(entry -> entry.type() == AclEntryType.ALLOW),
		           "the protected list has no deny rules to reason about: " + entries);
		// The count above alone would pass an ACL naming Everyone and Users, so the principals
		// themselves are checked.
		UserPrincipal owner = view.getOwner();
		UserPrincipal current = currentUser(path);
		assertTrue(entries.stream()
		                  .allMatch(entry -> entry.principal().equals(owner) ||
		                                     (current != null && entry.principal().equals(current))),
		           "every entry names the owner or the current user, nothing else: " + entries);
	}

	/// Resolves the user running the tests, or `null` when the name does not resolve.
	private static UserPrincipal currentUser(Path path) {
		String name = System.getProperty("user.name");
		if (name == null || name.isBlank()) {
			return null;
		}
		try {
			return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
