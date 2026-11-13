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
package com.mcxcc303.hdsl.runtime;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the environment a child process gets from the launcher.
 */
class DshToolchainTest {

	@Test
	void theSelectionsAreReadThroughTheSuppliers() {
		String node = DshToolchain.nodeSelection();
		String pnpm = DshToolchain.pnpmSelection();
		try {
			DshToolchain.install(() -> "24.1.0", () -> "11.28.11");
			assertEquals("24.1.0", DshToolchain.nodeSelection());
			assertEquals("11.28.11", DshToolchain.pnpmSelection());
		} finally {
			DshToolchain.install(() -> node, () -> pnpm);
		}
	}

	@Test
	void thePathPutsTheNodeAndThePnpmFirst() {
		// The Node.js directory comes first, and the shim finds Node.js on PATH.
		DshPnpm pnpm = new DshPnpm(Path.of("prefix", "pnpm.cjs"), Path.of("bin", "node"), "11.28.11",
		                           Path.of("prefix", "bin"));
		Map<String, String> environment = DshToolchain.pathEnvironment(Path.of("bin", "node"), pnpm);
		String[] entries = environment.get("PATH").split(Pattern.quote(File.pathSeparator));
		assertEquals("bin", entries[0]);
		assertEquals("prefix" + File.separator + "bin", entries[1]);
	}
}
