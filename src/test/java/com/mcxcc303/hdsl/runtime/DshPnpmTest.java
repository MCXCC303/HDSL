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

import com.mcxcc303.hdsl.runtime.pnpm.DshPnpm;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins how a pnpm is run. */
class DshPnpmTest {
	@Test
	void aManagedPnpmIsRunByTheNodeThatWasNamed() {
		// Naming the Node.js makes the version that installs a tree the version that runs it.
		DshPnpm pnpm = new DshPnpm(Path.of("prefix", "pnpm.cjs"), Path.of("bin", "node"), "11.28.11",
		                           Path.of("prefix", "bin"));
		assertEquals(List.of("bin" + File.separator + "node",
		                     "prefix" + File.separator + "pnpm.cjs",
		                     "install"),
		             pnpm.commandFor(List.of("install")));
	}

	@Test
	void aSystemPnpmRunsItself() {
		DshPnpm pnpm = new DshPnpm(Path.of("pnpm"), null, "11.28.11", Path.of("usr", "bin"));
		assertEquals(List.of("pnpm", "install"), pnpm.commandFor(List.of("install")));
	}
}
