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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Tests what tells a finished install from one that is not, and what an instance says when it
/// has no harness to run.
///
/// Both answers were wrong in the same way before. The install was staged in a directory that was
/// renamed into place, which broke every **absolute** link pnpm had just made —junctions, which is
/// what pnpm falls back to wherever Windows refuses it a symbolic link —and left an instance whose
/// entry script was unreachable while the tree around it looked complete. And the one sentence that
/// reported it could not tell that apart from never having installed anything, so the person reading
/// it was left guessing. What is installed is now a marker that says an install is outstanding, and
/// what is wrong is a question asked of the disk.
class HarnessInstallerTest {
	/// A temporary directory standing in for an instance directory.
	@TempDir
	private Path root;

	/// An instance in it, named rather than pointed at, so its own directory is the one under test.
	private DshInstance instance() throws DshException, IOException {
		Path directory = root.resolve("an-instance");
		Files.createDirectories(directory);
		return new DshInstance("an-instance", "0.2.0-rc.2", "web", root.toString(), null,
		                      DshHomeMode.ISOLATED, null, List.of(), Map.of(),
		                      "default", null, null, 0, 0L, directory.toString());
	}

	/// Writes the entry script, so that the instance looks installed.
	private void writeEntryPoint(DshInstance instance) throws IOException, DshException {
		Path script = instance.dshEntryPoint();
		Files.createDirectories(script.getParent());
		Files.writeString(script, "");
	}

	// ---- what counts as installed ------------------------------------------------

	/// An instance with its harness in place is installed.
	@Test
	void installedWhenTheEntryScriptIsThere() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);

		assertTrue(HarnessInstaller.isInstalled(instance));
		assertNull(HarnessInstaller.incompleteReason(instance));
		assertDoesNotThrow(() -> HarnessInstaller.requireInstalled(instance));
	}

	/// An instance that never had anything installed is not, and says so in words that are not the
	/// ones it uses for a broken one.
	@Test
	void notInstalledWhenNothingIsThere() throws Exception {
		DshInstance instance = instance();

		assertFalse(HarnessInstaller.isInstalled(instance));
		String reason = HarnessInstaller.incompleteReason(instance);
		assertNotNull(reason);
		assertTrue(reason.contains("nothing is installed in"), reason);
	}

	/// An install killed halfway leaves the marker behind, and the entry script it had **already
	/// linked** is not enough to call the instance installed: pnpm puts the package into
	/// `node_modules` while it links, long before the build scripts finish.
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

	/// The marker is a **file**, and the directory an older build staged into has the same stem.
	/// Asking for it without `NOFOLLOW_LINKS` would let that directory pass for one, and leave an
	/// instance reporting "not installed" for ever.
	@Test
	void aStagingDirectoryIsNotAMarker() throws Exception {
		DshInstance instance = instance();
		Files.createDirectories(instance.instanceDirectory().resolve("dsh.installing"));

		assertFalse(HarnessInstaller.isInstalling(instance));
		assertFalse(HarnessInstaller.isInstalled(instance));
	}

	/// Whatever sits at the marker's name is an outstanding install —a leftover file and a leftover
	/// directory must not differ, since neither may leave an instance looking installed.
	@Test
	void theMarkerIsWhateverSitsAtItsName() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Files.createDirectories(HarnessInstaller.pendingMarker(instance));

		assertTrue(HarnessInstaller.isInstalling(instance));
		assertFalse(HarnessInstaller.isInstalled(instance));
	}

	/// A marker that could not be cleared may only ever **block**. That costs a reinstall; the
	/// other answer costs an instance that boots half of something.
	@Test
	void aMarkerLeftBehindOnlyEverBlocks() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Files.writeString(HarnessInstaller.pendingMarker(instance), "");

		assertFalse(HarnessInstaller.isInstalled(instance));
		assertTrue(HarnessInstaller.incompleteReason(instance).contains("unfinished"));
	}

	// ---- the failure this whole change is about ---------------------------------

	/// The tree is complete-looking, the install that wrote it finished, and the package inside it
	/// is a link to a directory that is not there. Renaming the install directory left exactly
	/// this, and it must not be reported as "never installed".
	///
	/// The link is made as an **absolute** symbolic link because that is what a junction records —	/// and the test asks [DshFiles#isReparsePoint] rather than [Files#isSymbolicLink] about it,
	/// because a junction answers `false` to the latter and is the form this is actually about.
	@Test
	void aDanglingPackageLinkIsNamedAsBroken() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);

		Path packageLink = instance.dshDirectory().resolve(DshVersion.PACKAGE_PATH);
		Path gone = root.resolve("a-directory-that-was-renamed-away");
		try {
			Files.createSymbolicLink(packageLink, gone);
		} catch (IOException | UnsupportedOperationException e) {
			return; // this filesystem refuses symbolic links, so it cannot show this either
		}

		assertTrue(DshFiles.isReparsePoint(packageLink));
		assertTrue(Files.exists(packageLink, LinkOption.NOFOLLOW_LINKS));
		assertFalse(Files.exists(packageLink));

		String reason = HarnessInstaller.incompleteReason(instance);
		assertNotNull(reason);
		assertTrue(reason.contains("no longer exists"), reason);
		assertTrue(reason.contains("Reinstall"), reason);
		assertThrows(DshException.class, () -> HarnessInstaller.requireInstalled(instance));
	}

	/// Renaming an instance is the **same** hazard under another name. This builds it: a package
	/// link pointing, by absolute path, at a directory *inside* the tree —so moving the tree
	/// leaves the link naming a path that is no longer there.
	@Test
	void aTreeRenamedAwayLeavesItsLinksPointingAtNothing() throws Exception {
		DshInstance instance = instance();

		Path real = instance.dshDirectory().resolve(".pnpm").resolve("dsh@0.2.0-rc.2").resolve("node_modules")
		                                                     .resolve("@deepseek-ai").resolve("dsh");
		Files.createDirectories(real.resolve("lib"));
		Files.writeString(real.resolve("lib").resolve("bin.js"), "");

		Path packageLink = instance.dshDirectory().resolve(DshVersion.PACKAGE_PATH);
		try {
			Files.createSymbolicLink(packageLink, real.toAbsolutePath());
		} catch (IOException | UnsupportedOperationException e) {
			return;
		}
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

	// ---- the marker and the tree --------------------------------------------------

	/// Discarding a partial install removes the tree and the marker, so the instance reads as never
	/// installed and a reinstall starts from nothing.
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

	/// Discarding nothing does nothing. The instance was installed, the marker was never written,
	/// and a removal that ran anyway would be the one that breaks it.
	@Test
	void discardingAFinishedInstallLeavesItAlone() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);

		HarnessInstaller.discardPartial(instance);

		assertTrue(Files.exists(instance.dshEntryPoint()));
		assertTrue(HarnessInstaller.isInstalled(instance));
	}

	/// The install no longer renames anything: the only directory it makes under the instance is the
	/// one the instance runs from. A staging directory is what the rename needed, and it is what
	/// broke the links.
	@Test
	void theInstallMakesNoStagingDirectory() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Files.writeString(HarnessInstaller.pendingMarker(instance), "");

		try (var entries = Files.list(instance.instanceDirectory())) {
			assertTrue(entries.noneMatch(path -> path.getFileName().toString().endsWith(".installing")));
		}
	}

	// ---- one install at a time ----------------------------------------------------

	/// A second install into one instance would delete the directory the first is writing into and
	/// clear its marker. Nothing else is needed: a second launcher cannot start at all
	/// (`LauncherLock`), so the guard only has to hold inside this one.
	@Test
	void theSecondInstallIsRefusedWhileTheFirstRuns() throws Exception {
		DshInstance instance = instance();

		assertFalse(InstanceLocks.isHeld(instance));
		InstanceLocks.acquire(instance, "installed into");
		assertTrue(InstanceLocks.isHeld(instance));
		try {
			DshException refusal = assertThrows(DshException.class,
			                                    () -> HarnessInstaller.install(instance, instance.version(), null));
			assertTrue(refusal.getMessage().contains("is being installed into"), refusal.getMessage());
		} finally {
			InstanceLocks.release(instance);
		}
	}

	/// A refusal must not **steal** the guard. The caller that is turned away never acquired
	/// anything, so it must leave the key as it found it —dropping it on the way out would hand the
	/// next caller a lock nobody holds.
	///
	/// (The other half —that an install which *did* acquire the key gives it back however it ends —	/// is the `finally` in `install`. It is not driven here, because making an acquired install fail
	/// means either the network or a filesystem that says no.)
	@Test
	void aRefusalLeavesTheGuardAsItFoundIt() throws Exception {
		DshInstance instance = instance();

		assertFalse(InstanceLocks.isHeld(instance));
		InstanceLocks.acquire(instance, "installed into");
		assertTrue(InstanceLocks.isHeld(instance));
		try {
			assertThrows(DshException.class, () -> HarnessInstaller.install(instance, instance.version(), null));
			assertTrue(InstanceLocks.isHeld(instance));
		} finally {
			InstanceLocks.release(instance);
		}
	}

	// ---- when the install fails --------------------------------------------------
	/// A runtime whose every tool is a path nothing is at, so that an install fails where it would
	/// fail on a toolchain that cannot start: no network, no registry, no real pnpm.
	///
	/// The paths have to be ones that are *not* on `PATH`. `Path.of("pnpm")` names the real pnpm
	/// to the process launcher, which then installs for real, in this machine's registry.
	private static DshNodeRuntime absentTools(@Nullable String pnpmVersion) {
		return new DshNodeRuntime(Path.of("no-such-node"), "24.11.1", Path.of("no-such-npm"), null,
		                          Path.of("no-such-pnpm"), pnpmVersion);
	}

	/// A failed install puts the tree that was there back.
	///
	/// This is the reason the tree is moved aside rather than deleted, and it is the one part of
	/// the install that an end-to-end run cannot be relied on to keep testing: the failure it needs
	/// is a real pnpm failing, which means a registry and a network. A runtime that cannot start
	/// fails identically and needs neither.
	@Test
	void aFailedInstallPutsTheTreeThatWasThereBack() throws Exception {
		DshInstance instance = instance();
		writeEntryPoint(instance);
		Path entry = instance.dshDirectory().resolve("node_modules/@deepseek-ai/dsh/lib/bin.js");
		Files.writeString(entry, "the tree that was there");

		DshException failure = assertThrows(DshException.class,
		                                   () -> HarnessInstaller.install(instance, instance.version(), null,
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
		assertFalse(InstanceLocks.isHeld(instance));
	}

	/// A failed install over an instance that had nothing installed leaves nothing behind, and does
	/// not report a tree it never had.
	@Test
	void aFailedInstallOverNothingLeavesNothing() throws Exception {
		DshInstance instance = instance();

		assertThrows(DshException.class,
		             () -> HarnessInstaller.install(instance, instance.version(), null, absentTools(null)));

		assertFalse(Files.exists(instance.dshDirectory()));
		assertFalse(HarnessInstaller.isInstalling(instance));
		assertFalse(HarnessInstaller.isInstalled(instance));
	}

	// ---- how the install command is put together ----------------------------
	/// The build scripts are allowed whichever way the tree is put together. Without them the
	/// install finishes looking complete and fails the first time the spawn helper is used, and
	/// this flag is the whole reason it does not.
	@Test
	void theBuildScriptsAreAllowed() {
		DshNodeRuntime runtime = new DshNodeRuntime(Path.of("node"), "24.11.1", Path.of("npm"), null,
		                                          Path.of("pnpm"), "12.14.0");
		List<String> command = HarnessInstaller.buildInstallCommand(runtime, Path.of("project"));

		assertTrue(command.contains("--config.dangerously-allow-all-builds=true"), command.toString());
	}
}