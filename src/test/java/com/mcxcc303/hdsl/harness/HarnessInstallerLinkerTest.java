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
package com.mcxcc303.hdsl.harness;

import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Windows installs no links, because an absolute junction names the path it was made at and any
/// rename above it breaks; everywhere else keeps the shared layout, where the relative links pnpm
/// makes there already survive a rename.
class HarnessInstallerLinkerTest {
	@Test
	void onlyWindowsInstallsWithoutLinks() {
		DshNodeRuntime runtime = new DshNodeRuntime(Path.of("node"), "24.11.1", Path.of("npm"), null,
		                                          Path.of("pnpm"), "12.14.0");
		List<String> command = HarnessInstaller.buildInstallCommand(runtime, Path.of("project"));

		boolean linked = command.contains("--config.node-linker=hoisted");
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			assertTrue(linked, command.toString());
		} else {
			assertFalse(linked, command.toString());
		}
	}
}
