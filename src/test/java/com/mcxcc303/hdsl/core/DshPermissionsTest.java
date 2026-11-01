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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The mode a file that may hold a secret carries, and the one it keeps.
 * <p>
 * The class is Linux-only because the mode is a POSIX one.
 */
@EnabledOnOs(OS.LINUX)
class DshPermissionsTest {
	private static final Set<PosixFilePermission> OWNER_ONLY_FILE = EnumSet.of(PosixFilePermission.OWNER_READ,
	                                                                           PosixFilePermission.OWNER_WRITE);

	private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = EnumSet.of(PosixFilePermission.OWNER_READ,
	                                                                                PosixFilePermission.OWNER_WRITE,
	                                                                                PosixFilePermission.OWNER_EXECUTE);

	private static final Set<PosixFilePermission> READABLE_BY_EVERYONE = EnumSet.of(PosixFilePermission.OWNER_READ,
	                                                                                PosixFilePermission.OWNER_WRITE,
	                                                                                PosixFilePermission.GROUP_READ,
	                                                                                PosixFilePermission.OTHERS_READ);

	@TempDir
	Path folder;

	@Test
	void aNewFileIsCreatedWithTheMode() throws Exception {
		Path file = folder.resolve("new.json");
		DshPermissions.createRestricted(file, DshPermissions.SECRET_FILE);
		assertEquals(OWNER_ONLY_FILE, Files.getPosixFilePermissions(file));
	}

	@Test
	void anExistingFileIsGivenTheMode() throws Exception {
		Path file = folder.resolve("old.json");
		Files.writeString(file, "{}", StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(file, READABLE_BY_EVERYONE);
		DshPermissions.createRestricted(file, DshPermissions.SECRET_FILE);
		assertEquals(OWNER_ONLY_FILE,
		             Files.getPosixFilePermissions(file),
		             "the file an older launcher left is tightened");
	}

	@Test
	void aWrittenFileIsItsOwners() throws Exception {
		Path file = folder.resolve("open.sh");
		DshPermissions.writeRestricted(file, "export KEY=1", StandardCharsets.UTF_8, DshPermissions.SECRET_EXECUTABLE);
		assertEquals(OWNER_ONLY_DIRECTORY, Files.getPosixFilePermissions(file), "a script keeps its execute bit");
		assertEquals("export KEY=1", Files.readString(file));
	}

	@Test
	void aDirectoryIsGivenTheMode() throws Exception {
		Path directory = Files.createDirectories(folder.resolve("logs"));
		DshPermissions.restrict(directory, DshPermissions.SECRET_DIRECTORY);
		assertEquals(OWNER_ONLY_DIRECTORY, Files.getPosixFilePermissions(directory));
	}
}
