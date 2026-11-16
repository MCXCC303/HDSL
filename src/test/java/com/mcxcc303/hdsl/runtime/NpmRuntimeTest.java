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

import com.mcxcc303.hdsl.runtime.npm.DshNpm;
import com.mcxcc303.hdsl.runtime.npm.NpmRuntime;
import com.mcxcc303.hdsl.runtime.npm.NpmRuntimeManager;
import com.mcxcc303.hdsl.runtime.node.NodeRuntime;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins how the npm of a Node.js distribution is found and run.
 */
class NpmRuntimeTest {

	@Test
	void thePackageRootFollowsTheDistributionLayout() {
		Path root = NpmRuntime.packageRootIn(Path.of("distribution"));
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			assertEquals(Path.of("distribution", "node_modules", "npm"), root);
		} else {
			assertEquals(Path.of("distribution", "lib", "node_modules", "npm"), root);
		}
	}

	@Test
	void theCliScriptIsWhatTheDistributionRuns() {
		assertEquals(NpmRuntime.packageRootIn(Path.of("distribution")).resolve("bin").resolve("npm-cli.js"),
		             NpmRuntime.cliIn(Path.of("distribution")));
	}

	@Test
	void aDirectoryWithoutAnNpmHasNone(@TempDir Path directory) {
		assertNull(NpmRuntimeManager.bundled(NodeRuntime.nodeIn(directory)));
	}

	@Test
	void theNpmOfADistributionRunsUnderTheDistributionNode(@TempDir Path directory) throws IOException {
		Path distribution = Files.createDirectories(directory.resolve("distribution"));
		Path node = writeNode(distribution);
		Path cli = writeCli(distribution);

		DshNpm npm = NpmRuntimeManager.bundled(node);

		assertEquals(List.of(node.toString(), cli.toString(), "view"), npm.commandFor(List.of("view")));
		assertEquals("11.20.0", npm.version());
		assertEquals(node.getParent(), npm.shimDirectory());
	}

	@Test
	void theNpmOnPathRunsItself(@TempDir Path directory) throws IOException {
		Path shim = writeNpm(directory);

		DshNpm npm = NpmRuntimeManager.onPath(shim);

		assertEquals(List.of(shim.toString(), "view"), npm.commandFor(List.of("view")));
		assertEquals("11.20.0", npm.version());
	}

	/**
	 * Writes a Node.js that answers with a version: a batch file on Windows, a shell script elsewhere.
	 */
	private static Path writeNode(Path distribution) throws IOException {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			return write(distribution.resolve("node.cmd"), "11.20.0");
		}
		Path file = Files.createDirectories(distribution.resolve("bin")).resolve("node");
		return write(file, "11.20.0");
	}

	private static Path writeCli(Path distribution) throws IOException {
		Path file = NpmRuntime.cliIn(distribution);
		Files.createDirectories(file.getParent());
		Files.write(file, List.of("// the npm of the distribution"));
		return file;
	}

	private static Path writeNpm(Path directory) throws IOException {
		return write(directory.resolve(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "npm.cmd" : "npm"),
		             "11.20.0");
	}

	private static Path write(Path file, String version) throws IOException {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			Files.write(file, List.of("@echo off", "echo " + version));
			return file;
		}
		Files.write(file, List.of("#!/bin/sh", "echo " + version));
		file.toFile().setExecutable(true);
		return file;
	}
}
