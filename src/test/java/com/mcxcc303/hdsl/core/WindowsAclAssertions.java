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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Assertions about the Windows ACL the launcher writes on a protected path.
 * Only the owner and the current user may be named.
 */
public final class WindowsAclAssertions {
	private WindowsAclAssertions() {}

	/**
	 * Asserts that the path's ACL names only the owner and the current user
	 * and asserts that every entry is an {@link AclEntryType#ALLOW}.
	 *
	 * @param path the protected file or directory
	 * @throws IOException when the ACL cannot be read
	 */
	public static void assertOnlyOwningAccounts(Path path) throws IOException {
		AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
		// Without ACLs the store cannot hold this protection
		assumeTrue(view != null, "this file store has no ACL view to inspect");

		UserPrincipal owner = view.getOwner();
		Set<UserPrincipal> expected = new HashSet<>();
		expected.add(owner);

		String name = System.getProperty("user.name");
		if (name != null && !name.isBlank()) {
			try {
				expected.add(path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name));
			} catch (IOException | RuntimeException e) {
				// The implementation falls back to the owner when the current user cannot be named.
			}
		}

		List<AclEntry> entries = view.getAcl();
		Set<UserPrincipal> actual = entries.stream().map(AclEntry::principal).collect(Collectors.toSet());
		assertEquals(expected, actual, "the ACL names only the owner and the current user: " + entries);
		assertEquals(expected.size(), entries.size(), "no principal appears twice: " + entries);
		assertTrue(entries.stream().allMatch(entry -> entry.type() == AclEntryType.ALLOW),
		           "the protected list has no deny rules to reason about: " + entries);
	}
}
