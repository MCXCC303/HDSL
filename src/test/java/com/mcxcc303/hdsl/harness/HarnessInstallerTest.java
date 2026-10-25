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
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins what counts as an installed harness, what an instance says when it has none, and the
 * install-failure paths.
 */
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
	void installedWhenTheEntryScriptIsThere() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);

		assertTrue(HarnessInstaller.isInstalled(instance));
		assertNull(HarnessInstaller.incompleteReason(instance));
		assertDoesNotThrow(() -> HarnessInstaller.requireInstalled(instance));
	}

	@Test
	void notInstalledWhenNothingIsThere() throws Exception {
		DshInstance instance = instance();

		assertFalse(HarnessInstaller.isInstalled(instance));
		String reason = HarnessInstaller.incompleteReason(instance);
		assertNotNull(reason);
		assertTrue(reason.contains("nothing is installed in"), reason);
	}

	@Test
	void notInstalledWhileAnInstallIsOutstanding() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Files.writeString(HarnessInstaller.pendingMarker(instance), "");

		assertTrue(HarnessInstaller.isInstalling(instance));
		assertFalse(HarnessInstaller.isInstalled(instance));
		String reason = HarnessInstaller.incompleteReason(instance);
		assertNotNull(reason);
		assertTrue(reason.contains("unfinished"), reason);
	}

	@Test
	void aStagingDirectoryIsNotAMarker() throws Exception {
		DshInstance instance = instance();
		Files.createDirectories(instance.instanceDirectory().resolve("dsh.installing"));

		assertFalse(HarnessInstaller.isInstalling(instance));
		assertFalse(HarnessInstaller.isInstalled(instance));
	}

	@Test
	void theMarkerIsWhateverSitsAtItsName() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Files.createDirectories(HarnessInstaller.pendingMarker(instance));

		assertTrue(HarnessInstaller.isInstalling(instance));
		assertFalse(HarnessInstaller.isInstalled(instance));
	}

	@Test
	void aDanglingPackageLinkIsNamedAsBroken() throws Exception {
		DshInstance instance = instance();

		Path packageLink = instance.dshDirectory().resolve(DshVersion.PACKAGE_PATH);
		Path gone = Files.createDirectories(root.resolve("a-directory-that-was-renamed-away"));
		link(packageLink, gone);
		Files.delete(gone);

		assertTrue(DshFiles.isReparsePoint(packageLink));
		assertTrue(Files.exists(packageLink, LinkOption.NOFOLLOW_LINKS));
		assertFalse(Files.exists(packageLink));

		String reason = HarnessInstaller.incompleteReason(instance);
		assertNotNull(reason);
		assertTrue(reason.contains("no longer exists"), reason);
		assertTrue(reason.contains("Reinstall"), reason);
		assertThrows(DshException.class, () -> HarnessInstaller.requireInstalled(instance));
	}

	@Test
	void aTreeRenamedAwayLeavesItsLinksPointingAtNothing() throws Exception {
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

		assertFalse(HarnessInstaller.isInstalled(renamed));
		String reason = HarnessInstaller.incompleteReason(renamed);
		assertNotNull(reason);
		assertTrue(reason.contains("no longer exists"), reason);
		assertTrue(reason.contains("Reinstall"), reason);
	}

	/**
	 * Makes the link pnpm makes: a junction on Windows, a symbolic link elsewhere.
	 *
	 * <p>The test is skipped when the machine refuses the link, rather than returning before its
	 * assertions.
	 */
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
	void discardingAPartialInstallClearsBoth() throws Exception {
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
	void discardingAFinishedInstallLeavesItAlone() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);

		HarnessInstaller.discardPartial(instance);

		assertTrue(Files.exists(instance.dshEntryPoint()));
		assertTrue(HarnessInstaller.isInstalled(instance));
	}

	@Test
	void theInstallMakesNoStagingDirectory() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Files.writeString(HarnessInstaller.pendingMarker(instance), "");

		try (var entries = Files.list(instance.instanceDirectory())) {
			assertTrue(entries.noneMatch(path -> path.getFileName().toString().endsWith(".installing")));
		}
	}

	/**
	 * Asks for the lock from another thread, so the owner thread's reentrancy cannot hide the refusal.
	 */
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
	void theSecondInstallIsRefusedWhileTheFirstRuns() throws Exception {
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
	void aRefusalLeavesTheGuardAsItFoundIt() throws Exception {
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

	/**
	 * A runtime whose tools do not exist, so an install fails without a registry or a real pnpm.
	 *
	 * <p>The names must not be on {@code PATH}; {@code Path.of("pnpm")} would run the real one.
	 */
	private static DshNodeRuntime absentTools(@Nullable String pnpmVersion) {
		return new DshNodeRuntime(Path.of("no-such-node"),
		                          "24.11.1",
		                          Path.of("no-such-npm"),
		                          null,
		                          Path.of("no-such-pnpm"),
		                          pnpmVersion);
	}

	@Test
	void aFailedInstallPutsTheTreeThatWasThereBack() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Path entry = instance.dshDirectory().resolve("node_modules/@deepseek-ai/dsh/lib/bin.js");
		Files.writeString(entry, "the tree that was there");

		DshException failure = assertThrows(DshException.class,
		                                    () -> HarnessInstaller.install(instance,
		                                                                   instance.version(),
		                                                                   null,
		                                                                   absentTools(null)));
		assertTrue(failure.getMessage().contains("Failed to run pnpm"), failure.getMessage());

		// Back where it was, contents and all: a rename, so a junction naming the final path
		// resolves again exactly as it did before.
		assertEquals("the tree that was there", Files.readString(entry));
		assertTrue(HarnessInstaller.isInstalled(instance));
		// Nothing left over that would make the next call think an install is still going.
		assertFalse(HarnessInstaller.isInstalling(instance));
		assertFalse(Files.exists(HarnessInstaller.setAsideDirectory(instance)));
		// The lock is given up on the way out, however the install ended.
		assertDoesNotThrow(() -> InstanceLocks.acquire(instance, "installed into"));
		InstanceLocks.release(instance);
	}

	/**
	 * A retry over an install that never finished must not treat the half-built tree as the
	 * previous runtime. Restoring it on failure would clear the marker and leave an instance
	 * that looks installed while its tree is still incomplete.
	 */
	@Test
	void aRetryOverAnUnfinishedInstallDoesNotRestoreThePartialTree() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Files.writeString(HarnessInstaller.pendingMarker(instance), "");
		Files.writeString(instance.dshDirectory().resolve("partial-tree"), "not a runtime");

		assertThrows(DshException.class,
		             () -> HarnessInstaller.install(instance, instance.version(), null, absentTools(null)));

		assertFalse(Files.exists(instance.dshDirectory()));
		assertFalse(HarnessInstaller.isInstalling(instance));
		assertFalse(HarnessInstaller.isInstalled(instance));
		assertFalse(Files.exists(HarnessInstaller.setAsideDirectory(instance)));
	}

	@Test
	void aFailedInstallOverNothingLeavesNothing() throws Exception {
		DshInstance instance = instance();

		assertThrows(DshException.class,
		             () -> HarnessInstaller.install(instance, instance.version(), null, absentTools(null)));

		assertFalse(Files.exists(instance.dshDirectory()));
		assertFalse(HarnessInstaller.isInstalling(instance));
		assertFalse(HarnessInstaller.isInstalled(instance));
	}

	@Test
	void theBuildScriptsAreAllowed() {
		DshNodeRuntime runtime = new DshNodeRuntime(Path.of("node"),
		                                            "24.11.1",
		                                            Path.of("npm"),
		                                            null,
		                                            Path.of("pnpm"),
		                                            "12.14.0");
		List<String> command = HarnessInstaller.buildInstallCommand(runtime, Path.of("project"));

		assertTrue(command.contains("--config.dangerously-allow-all-builds=true"), command.toString());
	}
}