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
import com.mcxcc303.hdsl.core.DshFiles;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.InstanceLocks;
import com.mcxcc303.hdsl.runtime.node.DshNodeRuntime;
import com.mcxcc303.hdsl.runtime.npm.DshNpm;
import com.mcxcc303.hdsl.runtime.pnpm.DshPnpm;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HarnessInstallerTest {
	@TempDir
	private Path root;

	private DshInstance instance() throws DshException, IOException {
		Path directory = root.resolve("an-instance");
		Files.createDirectories(directory);
		return new DshInstance("an-instance",
				"0.2.0-rc.2",
				"web",
				root.toString(),
				null,
				DshHomeMode.ISOLATED,
				null,
				List.of(),
				Map.of(),
				"default",
				null,
				null,
				0,
				0L,
				directory.toString());
	}

	private void writeEntryPoint(DshInstance instance) throws IOException, DshException {
		Path script = instance.dshEntryPoint();
		Files.createDirectories(script.getParent());
		Files.writeString(script, "");
	}

	@Test
	void anInstanceWithAnEntryScriptCountsAsInstalled() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);

		assertTrue(HarnessInstaller.isInstalled(instance));
		assertNull(HarnessInstaller.incompleteReason(instance));
		assertDoesNotThrow(() -> HarnessInstaller.requireInstalled(instance));
	}

	@Test
	void anInstanceWithoutAHarnessIsRefused() throws Exception {
		DshInstance instance = instance();

		DshException refusal = assertThrows(DshException.class, () -> HarnessInstaller.requireInstalled(instance));
		assertTrue(refusal.getMessage().contains("nothing is installed in"), refusal.getMessage());
	}

	@Test
	void anInstanceWithAnOutstandingInstallIsRefused() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Files.writeString(HarnessInstaller.pendingMarker(instance), "");

		DshException refusal = assertThrows(DshException.class, () -> HarnessInstaller.requireInstalled(instance));
		assertTrue(refusal.getMessage().contains("unfinished"), refusal.getMessage());
	}

	@Test
	void aStagingDirectoryIsNotAnInstallInProgress() throws Exception {
		DshInstance instance = instance();
		Files.createDirectories(instance.instanceDirectory().resolve("dsh.installing"));

		assertFalse(HarnessInstaller.isInstalling(instance));
		assertFalse(HarnessInstaller.isInstalled(instance));
	}

	@Test
	void anInstallMarkerIsAnOutstandingInstallWhicheverKindItIs() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Files.createDirectories(HarnessInstaller.pendingMarker(instance));

		assertTrue(HarnessInstaller.isInstalling(instance));
		assertFalse(HarnessInstaller.isInstalled(instance));
	}

	@Test
	void aDanglingPackageLinkIsReportedAsBroken() throws Exception {
		DshInstance instance = instance();

		Path packageLink = instance.dshDirectory().resolve(DshVersion.PACKAGE_PATH);
		Path gone = Files.createDirectories(root.resolve("a-directory-that-was-renamed-away"));
		link(packageLink, gone);
		Files.delete(gone);

		assertTrue(DshFiles.isReparsePoint(packageLink));
		assertTrue(Files.exists(packageLink, LinkOption.NOFOLLOW_LINKS));
		assertFalse(Files.exists(packageLink));

		DshException refusal = assertThrows(DshException.class, () -> HarnessInstaller.requireInstalled(instance));
		assertTrue(refusal.getMessage().contains("no longer exists"), refusal.getMessage());
		assertTrue(refusal.getMessage().contains("Reinstall"), refusal.getMessage());
	}

	@Test
	void anInstanceWhoseTreeWasRenamedAwayIsRefused() throws Exception {
		DshInstance instance = instance();

		Path real = instance.dshDirectory()
				.resolve(".pnpm")
				.resolve("dsh@0.2.0-rc.2")
				.resolve("node_modules")
				.resolve("@deepseek-ai")
				.resolve("dsh");
		Files.createDirectories(real.resolve("lib"));
		Files.writeString(real.resolve("lib").resolve("bin.js"), "");

		Path packageLink = instance.dshDirectory().resolve(DshVersion.PACKAGE_PATH);
		link(packageLink, real);
		assertTrue(HarnessInstaller.isInstalled(instance));

		Path moved = instance.instanceDirectory().resolveSibling(instance.id() + "-renamed");
		Files.move(instance.instanceDirectory(), moved);
		DshInstance renamed = instance.withDirectory(moved);

		DshException refusal = assertThrows(DshException.class, () -> HarnessInstaller.requireInstalled(renamed));
		assertTrue(refusal.getMessage().contains("no longer exists"), refusal.getMessage());
		assertTrue(refusal.getMessage().contains("Reinstall"), refusal.getMessage());
	}

	private void link(Path link, Path target) throws Exception {
		Files.createDirectories(link.getParent());
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			Process process = new ProcessBuilder("cmd.exe",
					"/c",
					"mklink",
					"/J",
					link.toString(),
					target.toAbsolutePath().toString()).redirectErrorStream(true).start();
			try (var reader = process.inputReader()) {
				while (reader.readLine() != null) {
					// Drain, so the process is not left blocked on a full pipe.
				}
			}
			Assumptions.assumeTrue(process.waitFor() == 0, "this machine will not make a junction");
		} else {
			try {
				Files.createSymbolicLink(link, target.toAbsolutePath());
			} catch (IOException | UnsupportedOperationException e) {
				Assumptions.assumeTrue(false, "this filesystem will not make a symbolic link: " + e);
			}
		}
	}

	@Test
	void discardingAPartialInstallRemovesTheTreeAndTheMarker() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Files.writeString(HarnessInstaller.pendingMarker(instance), "");
		Files.createDirectories(instance.dshDirectory().resolve("node_modules"));

		HarnessInstaller.discardPartial(instance);

		assertFalse(HarnessInstaller.isInstalling(instance));
		assertFalse(Files.exists(instance.dshDirectory()));
		assertFalse(HarnessInstaller.isInstalled(instance));
	}

	@Test
	void discardingAFinishedInstallKeepsTheHarness() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);

		HarnessInstaller.discardPartial(instance);

		assertTrue(Files.exists(instance.dshEntryPoint()));
		assertTrue(HarnessInstaller.isInstalled(instance));
	}

	@Test
	void theInstallLeavesNoStagingDirectoryAndNoMarker(@TempDir Path directory) throws Exception {
		Installers installers = installers(directory);
		DshInstance instance = instance();

		HarnessInstaller.install(instance, "0.2.2-rc.3", null, installers.runtime(), installers.pnpm());

		try (var entries = Files.list(instance.instanceDirectory())) {
			assertTrue(entries.noneMatch(path -> path.getFileName().toString().endsWith(".installing")),
					"an installation that finished has to leave no staging directory behind");
		}
		assertFalse(Files.exists(HarnessInstaller.pendingMarker(instance)),
				"an installation that finished has to leave no marker behind");
	}

	private static DshException refusalFromAnotherThread(DshInstance instance) throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<DshException> future = pool.submit(() -> assertThrows(DshException.class,
					() -> HarnessInstaller.install(instance,
							instance.version(),
							null)));
			return future.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aSecondInstallIsRefusedWhileTheFirstOneRuns() throws Exception {
		DshInstance instance = instance();
		InstanceLocks.acquire(instance, "installed into");
		try {
			DshException refusal = refusalFromAnotherThread(instance);
			assertTrue(refusal.getMessage().contains("is being installed into"), refusal.getMessage());
		} finally {
			InstanceLocks.release(instance);
		}
	}

	@Test
	void anInstallCanBeTriedAgainAfterARefusal() throws Exception {
		DshInstance instance = instance();
		InstanceLocks.acquire(instance, "installed into");
		try {
			refusalFromAnotherThread(instance);
		} finally {
			InstanceLocks.release(instance);
		}
		assertDoesNotThrow(() -> InstanceLocks.acquire(instance, "installed into"));
		InstanceLocks.release(instance);
	}

	private static DshNodeRuntime absentTools() {
		return new DshNodeRuntime(Path.of("no-such-node"),
				"24.11.1",
				new DshNpm(Path.of("no-such-npm"), null, null, Path.of("no-such-bin")));
	}

	private static DshPnpm absentPnpm() {
		return new DshPnpm(Path.of("no-such-pnpm"), null, null, Path.of("no-such-bin"));
	}

	@Test
	void theBuildScriptsOfTheDependenciesFindTheNodeOfTheRuntime(@TempDir Path directory) throws Exception {
		Installers installers = installers(directory);
		DshInstance instance = instance();

		HarnessInstaller.install(instance, "0.2.2-rc.3", null, installers.runtime(), installers.pnpm());

		assertTrue(Files.isRegularFile(instance.dshEntryPoint()),
				"the build scripts pnpm runs have to find the node of the runtime");
	}

	@Test
	void aBootLibraryChangeRunsTheBuildScriptsUnderTheNodeOfTheRuntime(@TempDir Path directory) throws Exception {
		Installers installers = installers(directory);
		DshInstance instance = instance();
		writeEntryPoint(instance);

		AppBootPin.overrideAppBoot(instance, "0.2.0-rc.1", null, installers.runtime(), installers.pnpm());

		String workspace = Files.readString(instance.dshDirectory().resolve("pnpm-workspace.yaml"));
		assertEquals("0.2.0-rc.1", AppBootPin.appBootPin(workspace),
				"the boot library has to be the version the user chose");
	}

	private record Installers(DshNodeRuntime runtime, DshPnpm pnpm) {
	}

	private static Installers installers(Path directory) throws IOException {
		Path bin = Files.createDirectories(directory.resolve("runtime").resolve("bin"));
		Path node = program(bin, "node", List.of("echo v99.0.0"));
		Path pnpm = program(directory.resolve("pnpm-home"), "pnpm", buildScriptPnpm());
		return new Installers(new DshNodeRuntime(node, "99.0.0", null),
				new DshPnpm(pnpm, null, "12.20.0", directory.resolve("pnpm-home").resolve("bin")));
	}

	private static List<String> buildScriptPnpm() {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			return List.of(
					"set target=",
					":parse",
					"if \"%1\"==\"\" goto run",
					"if \"%1\"==\"--dir\" (set target=%2 & shift)",
					"shift",
					"goto parse",
					":run",
					"node -v | findstr /c:\"v99.0.0\" > nul",
					"if errorlevel 1 (echo node was not found & exit /b 127)",
					"mkdir \"%target%\\node_modules\\@deepseek-ai\\dsh\\lib\" > nul 2>&1",
					"echo.> \"%target%\\node_modules\\@deepseek-ai\\dsh\\lib\\bin.js\"");
		}
		return List.of(
				"target=",
				"while [ $# -gt 0 ]; do",
				"  if [ \"$1\" = \"--dir\" ]; then shift; target=\"$1\"; fi",
				"  shift",
				"done",
				"if ! node -v | grep -q v99.0.0; then",
				"  echo \"node: command not found\" >&2",
				"  exit 127",
				"fi",
				"mkdir -p \"$target/node_modules/@deepseek-ai/dsh/lib\"",
				"echo \"\" > \"$target/node_modules/@deepseek-ai/dsh/lib/bin.js\"");
	}

	private static Path program(Path directory, String name, List<String> lines) throws IOException {
		Files.createDirectories(directory);
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			Path file = directory.resolve(name + ".cmd");
			Files.write(file, prepend("@echo off", lines));
			return file;
		}
		Path file = directory.resolve(name);
		Files.write(file, prepend("#!/bin/sh", lines));
		file.toFile().setExecutable(true);
		return file;
	}

	private static List<String> prepend(String first, List<String> lines) {
		ArrayList<String> all = new ArrayList<>();
		all.add(first);
		all.addAll(lines);
		return all;
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void aFailedInstallKeepsTheTreeThatWasThere() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Path entry = instance.dshDirectory().resolve("node_modules/@deepseek-ai/dsh/lib/bin.js");
		Files.writeString(entry, "the tree that was there");

		DshException failure = assertThrows(DshException.class,
				() -> HarnessInstaller.install(instance,
						instance.version(),
						null,
						absentTools(), absentPnpm()));
		assertTrue(failure.getMessage().contains("Failed to run pnpm"), failure.getMessage());

		assertEquals("the tree that was there", Files.readString(entry));
		assertTrue(HarnessInstaller.isInstalled(instance));

		assertFalse(HarnessInstaller.isInstalling(instance));
		assertFalse(Files.exists(HarnessInstaller.setAsideDirectory(instance)));

		assertDoesNotThrow(() -> InstanceLocks.acquire(instance, "installed into"));
		InstanceLocks.release(instance);
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void aRetryMovesThePartialTreeAsideInsteadOfRestoringIt() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Files.writeString(HarnessInstaller.pendingMarker(instance), "");
		Files.writeString(instance.dshDirectory().resolve("partial-tree"), "not a runtime");
		Path previous = HarnessInstaller.setAsideDirectory(instance);
		Files.createDirectories(previous);
		Files.writeString(previous.resolve("old-tree"), "the old tree");

		assertThrows(DshException.class,
				() -> HarnessInstaller.install(instance, instance.version(), null, absentTools(), absentPnpm()));

		assertFalse(Files.exists(instance.dshDirectory()));
		assertTrue(HarnessInstaller.isInstalling(instance));
		assertFalse(HarnessInstaller.isInstalled(instance));
		assertEquals("the old tree", Files.readString(previous.resolve("old-tree")));

		Path recovered;
		try (var entries = Files.list(instance.instanceDirectory())) {
			recovered = entries.filter(path -> path.getFileName().toString().startsWith("dsh.previous."))
					.findFirst()
					.orElseThrow();
		}
		assertEquals("not a runtime", Files.readString(recovered.resolve("partial-tree")));
	}

	@Test
	void installingIntoADeletedInstanceIsRefused() throws Exception {
		DshInstance instance = instance();
		DshFiles.deleteTree(instance.instanceDirectory());

		DshException refusal = assertThrows(DshException.class,
				() -> HarnessInstaller.install(instance,
						instance.version(),
						null,
						absentTools(), absentPnpm()));

		assertTrue(refusal.getMessage().contains("no longer exists"), refusal.getMessage());
		assertFalse(Files.exists(instance.instanceDirectory()));
	}

	@Test
	void aFailedInstallOverAnEmptyInstanceLeavesNothingBehind() throws Exception {
		DshInstance instance = instance();

		assertThrows(DshException.class,
				() -> HarnessInstaller.install(instance, instance.version(), null, absentTools(), absentPnpm()));

		assertFalse(Files.exists(instance.dshDirectory()));
		assertFalse(HarnessInstaller.isInstalling(instance));
		assertFalse(HarnessInstaller.isInstalled(instance));
	}

	@Test
	void theBuildScriptsOfTheDependenciesAreAllowed() {
		DshPnpm pnpm = new DshPnpm(Path.of("pnpm"), null, "12.14.0", Path.of("bin"));
		List<String> command = HarnessInstaller.buildInstallCommand(pnpm, Path.of("project"));

		assertTrue(command.contains("--config.dangerously-allow-all-builds=true"), command.toString());
	}
}