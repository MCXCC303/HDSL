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
package com.mcxcc303.hdsl.launch;

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshFiles;
import com.mcxcc303.hdsl.core.DshPaths;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.runtime.DshRuntimeLocation;
import com.mcxcc303.hdsl.runtime.DshToolchain;
import com.mcxcc303.hdsl.runtime.node.DshNodeRuntime;
import com.mcxcc303.hdsl.runtime.pnpm.PnpmRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
public final class DshLaunchToolchainTest {
	@TempDir
	private Path home;
	private final List<String> created = new ArrayList<>();

	@AfterEach
	public void removeTheInstances() {
		for (String id : created) {
			try {
				DshInstanceManager.delete(id);
			} catch (DshException e) {
			}
		}
	}

	@Test
	public void runsWithThePnpmTheLauncherSelected() throws Exception {
		Assumptions.assumeTrue(DshNodeRuntime.which("node").isPresent());

		Path pnpm = installPnpm("11.29.0");
		String node = DshToolchain.nodeSelection();
		String selected = DshToolchain.pnpmSelection();
		try {
			DshToolchain.install(() -> node, () -> DshRuntimeLocation.selectionOf(pnpm));
			String path = DshLauncher.plan(instance("pnpm")).environment().get("PATH");

			assertTrue(path.contains(PnpmRuntime.shimDirectoryIn(pnpm).toString()));
		} finally {
			DshToolchain.install(() -> node, () -> selected);
			DshFiles.deleteTreeQuietly(pnpm);
		}
	}

	@Test
	public void startsWithoutAPnpmTheLauncherCouldFind() throws Exception {
		Assumptions.assumeTrue(DshNodeRuntime.which("node").isPresent());

		String node = DshToolchain.nodeSelection();
		String selected = DshToolchain.pnpmSelection();
		Path missing = DshPaths.pnpmDirectory("9.9.9");
		try {
			DshToolchain.install(() -> node, () -> missing.toString());
			String path = DshLauncher.plan(instance("no-pnpm")).environment().get("PATH");
			Path bin = DshToolchain.resolve(node).node().getParent();

			assertTrue(path.startsWith(bin + File.pathSeparator));
			assertTrue(path.endsWith(File.pathSeparator + System.getenv("PATH")));
		} finally {
			DshToolchain.install(() -> node, () -> selected);
		}
	}

	private DshInstance instance(String id) throws Exception {
		if (InstanceStore.find(id) != null) {
			DshInstanceManager.delete(id);
		}
		created.add(id);
		DshInstance instance = DshInstanceManager.create(id,
		                                                 "1.2.3",
		                                                 "web",
		                                                 home.resolve("work"),
		                                                 DshHomeMode.ISOLATED,
		                                                 null,
		                                                 List.of(),
		                                                 Map.of());
		Path script = instance.dshEntryPoint();
		Files.createDirectories(script.getParent());
		Files.writeString(script, "console.log('STARTED');");
		return instance;
	}

	private static Path installPnpm(String version) throws Exception {
		Path prefix = DshPaths.pnpmDirectory(version);
		Path cli = PnpmRuntime.cliIn(prefix);
		Files.createDirectories(cli.getParent());
		Files.createFile(cli);
		return prefix;
	}
}
