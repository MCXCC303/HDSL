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

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Tests the pnpm version the launcher will install DeepSeek Harness with.
///
/// pnpm 8 and 9 read the workspace file as a workspace root's configuration and refuse the install
/// with `ERR_PNPM_INVALID_WORKSPACE_CONFIGURATION`; pnpm 10.0 through 10.8 do not know
/// `dangerouslyAllowAllBuilds`, so the harness's native modules are installed without being built.
/// Both look like an installed tree until it is used, which is why the version is refused by name.
class HarnessInstallerPnpmTest {
	/// A temporary directory standing in for an instance directory.
	@TempDir
	private Path root;

	/// An instance in it.
	private DshInstance instance() throws DshException, IOException {
		Path directory = root.resolve("an-instance");
		Files.createDirectories(directory);
		return new DshInstance("an-instance", "0.2.0-rc.2", "web", root.toString(), null,
		                      DshHomeMode.ISOLATED, null, List.of(), Map.of(),
		                      "default", null, null, 0, 0L, directory.toString());
	}

	/// A runtime whose every tool is a path nothing is at, so that an install fails where it would
	/// fail on a toolchain that cannot start: no network, no registry, no real pnpm.
	private static DshNodeRuntime absentTools(@Nullable String pnpmVersion) {
		return new DshNodeRuntime(Path.of("no-such-node"), "24.11.1", Path.of("no-such-npm"), null,
		                          Path.of("no-such-pnpm"), pnpmVersion);
	}

	@Test
	void anOldPnpmIsRefusedByName() throws Exception {
		DshInstance instance = instance();

		DshException refusal = assertThrows(DshException.class,
		                                   () -> HarnessInstaller.install(instance, instance.version(), null,
		                                                                          absentTools("9.15.0")));

		assertTrue(refusal.getMessage().contains("pnpm 9.15.0 is too old"), refusal.getMessage());
	}

	@Test
	void theOldestSupportedPnpmIsAccepted() throws Exception {
		DshInstance instance = instance();

		DshException failure = assertThrows(DshException.class,
		                                   () -> HarnessInstaller.install(instance, instance.version(), null,
		                                                                          absentTools("10.9.0")));

		// Refused for the missing pnpm rather than for its age: it got as far as running it.
		assertTrue(failure.getMessage().contains("Failed to run pnpm"), failure.getMessage());
	}

	@Test
	void aPnpmBeforeTheBuildApprovalSettingIsRefused() throws Exception {
		DshInstance instance = instance();

		DshException refusal = assertThrows(DshException.class,
		                                    () -> HarnessInstaller.install(instance, instance.version(), null,
		                                                                           absentTools("10.8.0")));

		assertTrue(refusal.getMessage().contains("pnpm 10.8.0 is too old"), refusal.getMessage());
	}

	@Test
	void aPnpmOfUnknownVersionIsNotRefused() throws Exception {
		DshInstance instance = instance();

		DshException failure = assertThrows(DshException.class,
		                                   () -> HarnessInstaller.install(instance, instance.version(), null,
		                                                                          absentTools(null)));

		assertFalse(failure.getMessage().contains("too old"), failure.getMessage());
		assertTrue(failure.getMessage().contains("Failed to run pnpm"), failure.getMessage());
	}
}
