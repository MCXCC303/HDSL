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

import com.mcxcc303.hdsl.runtime.DshPnpm;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that only Windows installs with the {@code hoisted} linker.
 */
class HarnessInstallerLinkerTest {
	@Test
	void onlyWindowsInstallsWithoutLinks() {
		DshPnpm pnpm = new DshPnpm(Path.of("pnpm"), null, "12.14.0", Path.of("bin"));
		List<String> command = HarnessInstaller.buildInstallCommand(pnpm, Path.of("project"));

		boolean linked = command.contains("--config.node-linker=hoisted");
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			assertTrue(linked, command.toString());
		} else {
			assertFalse(linked, command.toString());
		}
	}
}
