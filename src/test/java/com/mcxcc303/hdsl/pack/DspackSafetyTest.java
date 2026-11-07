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
package com.mcxcc303.hdsl.pack;

import com.mcxcc303.hdsl.core.DshException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a pack is not allowed to write.
 */
class DspackSafetyTest {

	@Test
	void aMemberThatWouldLeaveTheDestinationIsRefused(@TempDir Path root) {
		assertThrows(DshException.class, () -> DspackSafety.resolve(root, "../outside.txt"));
		assertThrows(DshException.class,
		             () -> DspackSafety.resolve(root, "a/../../outside.txt"),
		             "the escape is judged on the resolved path, not on how it is spelled");
		assertThrows(DshException.class, () -> DspackSafety.resolve(root, "a/./../../outside.txt"));
	}

	@Test
	void aMemberThatStaysInsideLandsWhereItSays(@TempDir Path root) throws Exception {
		Path base = root.toAbsolutePath().normalize();
		assertEquals(base.resolve("lib/index.js"), DspackSafety.resolve(root, "lib/index.js"));
		assertEquals(base.resolve("lib/index.js"),
		             DspackSafety.resolve(root, "lib/./index.js"),
		             "a step through the directory it is already in is not an escape");
	}

	@Test
	void theFilesThatHoldCredentialsAreRefused(@TempDir Path root) {
		for (String name : new String[]{
				".env",
				".npmrc",
				".netrc",
				"credentials.yaml",
				".credentials.yaml",
				"id_rsa",
				"id_rsa.pub",
				"id_ed25519"
		}) {
			assertThrows(DshException.class,
			             () -> DspackSafety.refuseIfUnsafe(name, root.resolve(name)),
			             name + " holds credentials and must never be installed from a pack");
		}
	}

	@Test
	void theFilesThatHoldKeysAreRefused(@TempDir Path root) {
		for (String name : new String[]{"server.pem", "store.p12", "client.pfx", "app.jks", "a.keystore", "x.KEY"}) {
			assertThrows(DshException.class,
			             () -> DspackSafety.refuseIfUnsafe(name, root.resolve(name)),
			             name + " is a key file and must never be installed from a pack");
		}
	}

	@Test
	void aNestedArchiveIsRefusedExceptWherePluginsLive(@TempDir Path root) {
		assertThrows(DshException.class,
		             () -> DspackSafety.refuseIfUnsafe("overrides/data.zip", root.resolve("data.zip")),
		             "what a nested archive holds cannot be checked before it is on the disk");
		assertThrows(DshException.class, () -> DspackSafety.refuseIfUnsafe("bundle.tgz", root.resolve("bundle.tgz")));
		// The one place an archive is expected: a plugin installed from a file is carried as one.
		Assertions.assertDoesNotThrow(() -> DspackSafety.refuseIfUnsafe("overrides/plugins/dsh-x-1.0.0.tgz",
		                                                                root.resolve("x.tgz")));
	}

	@Test
	void anOrdinaryMemberIsWritten(@TempDir Path root) {
		Assertions.assertDoesNotThrow(() -> DspackSafety.refuseIfUnsafe("overrides/plugins/package.json",
		                                                                root.resolve("package.json")));
	}

	@Test
	void theProfilesOwnFilesAreTheMachines(@TempDir Path root) {
		assertTrue(DspackSafety.isMachineFile("package.json"));
		assertTrue(DspackSafety.isMachineFile("pnpm-lock.yaml"));
		assertTrue(DspackSafety.isMachineFile("pnpm-workspace.yaml"));
		assertFalse(DspackSafety.isMachineFile("overrides/package.json"),
		            "a profile file under overrides/ arrives after the resolve, which is too late");
		assertFalse(DspackSafety.isMachineFile("overrides/plugins/x/index.js"));
	}
}
