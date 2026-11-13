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
package com.mcxcc303.hdsl.launch;

import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.core.DshFiles;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mode a session's files and directories carry. Linux only
 */
@EnabledOnOs(OS.LINUX)
class DshInstanceTerminalPermissionsTest {
	private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(PosixFilePermission.OWNER_READ,
	                                                                      PosixFilePermission.OWNER_WRITE,
	                                                                      PosixFilePermission.OWNER_EXECUTE);

	private static final Set<PosixFilePermission> READABLE_BY_EVERYONE = EnumSet.of(PosixFilePermission.OWNER_READ,
	                                                                                PosixFilePermission.OWNER_WRITE,
	                                                                                PosixFilePermission.GROUP_READ,
	                                                                                PosixFilePermission.OTHERS_READ);

	private static final String INSTANCE_ID = "terminal-permissions";

	/** Where the instance's own files are made, one per test. */
	@TempDir
	Path folder;

	@AfterEach
	void cleanUp() throws Exception {
		DshFiles.deleteTree(session());
	}

	@Test
	void aSessionIsReadableOnlyByItsOwner() throws Exception {
		Path script = write();

		assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(script), script + " is its owner's alone");
		assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(shim()), shim() + " is its owner's alone");
		assertTrue(Files.isExecutable(script), "the script is handed to a terminal to run");
		assertTrue(Files.readString(script).contains("sk-test-session"),
		           "the account's key still travels with the session");
	}

	@Test
	void theSessionDirectoriesAreReadableOnlyByTheirOwner() throws Exception {
		write();

		assertEquals(OWNER_ONLY,
		             Files.getPosixFilePermissions(DshInstanceTerminal.getTermSessionsDir()),
		             "the sessions directory is its owner's alone");
		assertEquals(OWNER_ONLY,
		             Files.getPosixFilePermissions(session()),
		             "the session's own directory is its owner's alone");
		assertEquals(OWNER_ONLY,
		             Files.getPosixFilePermissions(bin()),
		             "the directory the shim sits in is its owner's alone");
	}

	@Test
	void aSessionWrittenOverALooseFileIsTightened() throws Exception {
		Files.createDirectories(bin());
		Path script = session().resolve("open.sh");
		Files.writeString(script, "written by an older launcher", StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(script, READABLE_BY_EVERYONE);

		write();

		assertEquals(OWNER_ONLY,
		             Files.getPosixFilePermissions(script),
		             "the file an older launcher left is tightened when the session is written again");
	}

	private Path write() throws Exception {
		return DshInstanceTerminal.write(instance(), account(), runtime());
	}

	private DshInstance instance() {
		return new DshInstance(INSTANCE_ID,
		                       "0.2.1",
		                       "web",
		                       folder.resolve("work").toString(),
		                       "system",
		                       DshHomeMode.CUSTOM,
		                       folder.resolve("dsh-home").toString(),
		                       List.of(),
		                       Map.of(),
		                       null,
		                       null,
		                       null,
		                       0,
		                       0L);
	}

	private DshAccount account() {
		return new DshAccount("deepseek", "sk-test-session", null, null);
	}

	private DshNodeRuntime runtime() {
		Path bin = folder.resolve("node").resolve("bin");
		return new DshNodeRuntime(bin.resolve("node"), "22.19.0", bin.resolve("npm"), "10.9.0");
	}

	private Path session() {
		return DshInstanceTerminal.getTermSessionsDir().resolve(INSTANCE_ID);
	}

	// The launcher's own names for the directory the shim sits in and for the shim itself.
	private Path bin() {
		return session().resolve("bin");
	}

	private Path shim() {
		return bin().resolve("dsh");
	}
}
