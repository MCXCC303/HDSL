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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ACL a session's files and directories carry on Windows, where there is no mode.
 * <p>
 * The class is Windows-only; the POSIX mode is checked by [DshInstanceTerminalPermissionsTest].
 */
@EnabledOnOs(OS.WINDOWS)
class DshInstanceTerminalWindowsAclTest {
	private static final String INSTANCE_ID = "terminal-windows-acl";

	/** Where the instance's own files are made, one per test. */
	@TempDir
	Path folder;

	@AfterEach
	void cleanUp() throws Exception {
		DshFiles.deleteTree(session());
	}

	@Test
	void aSessionScriptNamesOnlyItsOwningAccounts() throws Exception {
		Path script = write();

		assertOnlyOwningAccounts(script);
		assertOnlyOwningAccounts(shim());
		assertTrue(Files.readString(script).contains("sk-test-session"),
		           "the account's key still travels with the session");
	}

	@Test
	void theSessionDirectoriesAreRestrictedToo() throws Exception {
		write();

		assertOnlyOwningAccounts(session());
		assertOnlyOwningAccounts(bin());
	}

	@Test
	void aSessionWrittenOverALooseFileIsTightened() throws Exception {
		Files.createDirectories(bin());
		Path script = session().resolve("open.cmd");
		Files.writeString(script, "written by an older launcher");

		write();

		assertOnlyOwningAccounts(script);
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
		return new DshNodeRuntime(bin.resolve("node"), "22.19.0", bin.resolve("npm"), "10.9.0", null, null);
	}

	private Path session() {
		return DshInstanceTerminal.getTermSessionsDir().resolve(INSTANCE_ID);
	}

	private Path bin() {
		return session().resolve("bin");
	}

	private Path shim() {
		return bin().resolve("dsh.cmd");
	}

	/// Asserts that the path's ACL names no more than the owner and the current user, and allows.
	private static void assertOnlyOwningAccounts(Path path) throws Exception {
		AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
		assertNotNull(view, "this test needs a file store with an ACL view to protect");
		List<AclEntry> entries = view.getAcl();
		assertEquals(0, entries.stream().filter(entry -> entry.type() != AclEntryType.ALLOW).count(),
		             "the protected list has no deny rules to reason about: " + entries);
		assertTrue(entries.size() <= 2,
		           "the inherited groups are gone, leaving only the owner and current user: " + entries);
	}
}
