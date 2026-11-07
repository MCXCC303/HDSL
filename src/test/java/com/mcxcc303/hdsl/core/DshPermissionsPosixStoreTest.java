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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that a store without POSIX permissions rejects restricted writes. e.g. a zip
 */
@DisabledOnOs(OS.WINDOWS)
class DshPermissionsPosixStoreTest {
	@TempDir
	Path folder;

	@Test
	void restrictedWriteIsRejectedWithoutPosixView() throws Exception {
		Path archive = folder.resolve("store.zip");
		try (FileSystem store = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			Path file = store.getPath("/secret.txt");

			assertThrows(IOException.class,
			             () -> DshPermissions.writeRestricted(file,
			                                                  "sk-secret",
			                                                  StandardCharsets.UTF_8,
			                                                  DshPermissions.SECRET_FILE));
			assertFalse(Files.exists(file));
		}
	}

	@Test
	void restrictedDirectoryIsNotCreatedWithoutPosixView() throws Exception {
		Path archive = folder.resolve("store.zip");
		try (FileSystem store = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			Path dir = store.getPath("/logs");

			assertThrows(IOException.class,
			             () -> DshPermissions.createRestrictedDirectory(dir, DshPermissions.SECRET_DIRECTORY));
			assertFalse(Files.exists(dir));
		}
	}
}
