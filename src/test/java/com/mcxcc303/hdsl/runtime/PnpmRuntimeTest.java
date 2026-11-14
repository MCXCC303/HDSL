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

import com.mcxcc303.hdsl.runtime.pnpm.PnpmRuntime;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins where a pnpm installation keeps the entry script and the shim. */
class PnpmRuntimeTest {

	@Test
	void thePackageRootFollowsNpmGlobalLayout() {
		Path root = PnpmRuntime.packageRootIn(Path.of("prefix"));
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			assertEquals(Path.of("prefix", "node_modules", "pnpm"), root);
		} else {
			assertEquals(Path.of("prefix", "lib", "node_modules", "pnpm"), root);
		}
	}

	@Test
	void theEntryScriptIsTheOneEveryPnpmHas() {
		// pnpm 10 ships {@code pnpm.cjs}; pnpm 11 ships that and {@code pnpm.mjs}.
		assertEquals(PnpmRuntime.packageRootIn(Path.of("prefix")).resolve("bin").resolve("pnpm.cjs"),
		             PnpmRuntime.cliIn(Path.of("prefix")));
	}

	@Test
	void theShimDirectoryIsWherePnpmAnswersToItsName() {
		Path prefix = Path.of("prefix");
		Path expected = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		                ? prefix
		                : prefix.resolve("bin");
		assertEquals(expected, PnpmRuntime.shimDirectoryIn(prefix));
	}
}
