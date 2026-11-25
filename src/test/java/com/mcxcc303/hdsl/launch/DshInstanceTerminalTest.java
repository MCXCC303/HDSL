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

import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.account.DshAccountRoute;
import com.mcxcc303.hdsl.core.DshFiles;
import com.mcxcc303.hdsl.core.DshPaths;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.runtime.DshRuntimeLocation;
import com.mcxcc303.hdsl.runtime.DshToolchain;
import com.mcxcc303.hdsl.runtime.node.DshNodeRuntime;
import com.mcxcc303.hdsl.runtime.pnpm.PnpmRuntime;
import com.mcxcc303.hdsl.runtime.npm.DshNpm;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DshInstanceTerminalTest {
	private static final boolean WINDOWS = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS;
	@TempDir
	private Path home;

	private static DshNodeRuntime runtime(Path bin) {
		return new DshNodeRuntime(bin.resolve(WINDOWS ? "node.exe" : "node"),
		                          "22.19.0",
		                          new DshNpm(bin.resolve(WINDOWS ? "npm.cmd" : "npm"), null, "10.9.0", bin));
	}

	private static String assignment(String name, String value) {
		return WINDOWS ? "set \"" + name + "=" + value + "\"" : "export " + name + "='" + value + "'";
	}

	private static String pathLine(String script) {
		String pathAssignment = WINDOWS ? "set \"PATH=" : "export PATH='";
		return script.lines().filter(line -> line.startsWith(pathAssignment)).findFirst().orElse("");
	}

	private static List<String> sessionPath(String script) {
		String line = pathLine(script);
		assertFalse(line.isEmpty(), script);
		String pathAssignment = WINDOWS ? "set \"PATH=" : "export PATH='";
		return List.of(line.substring(pathAssignment.length(), line.length() - 1).split(WINDOWS ? ";" : ":"));
	}

	private DshInstance instance(Map<String, String> environment) {
		return new DshInstance("test",
		                       "0.1.6-alpha.2",
		                       "web",
		                       home.resolve("work").toString(),
		                       "system",
		                       DshHomeMode.CUSTOM,
		                       home.resolve("dsh-home").toString(),
		                       List.of(),
		                       environment,
		                       null,
		                       null,
		                       null,
		                       0,
		                       0L);
	}

	@Test
	void quotesAValueForAShell() {
		assertEquals("'plain'", DshInstanceTerminal.quote("plain"));
		assertEquals("'it'\\''s'", DshInstanceTerminal.quote("it's"), DshInstanceTerminal.quote("it's"));
	}

	@Test
	void aSessionOnAnInstanceInAnAddedFolderOpensThere() throws Exception {
		Path bin = Files.createDirectories(home.resolve("node").resolve("bin"));
		Path added = Files.createDirectories(home.resolve("added-folder").resolve("moved"));
		DshInstance moved = new DshInstance("moved",
		                                    "0.1.6-alpha.2",
		                                    "web",
		                                    home.resolve("work").toString(),
		                                    "system",
		                                    DshHomeMode.ISOLATED,
		                                    null,
		                                    List.of(),
		                                    Map.of(),
		                                    null,
		                                    null,
		                                    null,
		                                    0,
		                                    0L).withDirectory(added);
		String script = DshInstanceTerminal.scriptText(moved, null, runtime(bin));
		assertTrue(script.contains(WINDOWS ? "cd /d \"" + added + "\"" : "cd '" + added + "'"), script);
		assertFalse(script.contains(DshPaths.instanceDirectory("moved").toString()),
		            "the session stands in the instance's own folder, not the launcher's folder of " +
		            "the same name:\n" + script);

		String shim = DshInstanceTerminal.shimText(moved, runtime(bin));
		assertTrue(shim.contains(moved.dshEntryPoint().toString()), shim);
	}

	@Test
	void theScriptStandsWhereTheInstanceStands() throws Exception {
		Path bin = Files.createDirectories(home.resolve("node").resolve("bin"));
		String script = DshInstanceTerminal.scriptText(instance(Map.of()), null, runtime(bin));
		assertTrue(script.contains(WINDOWS
		                           ? "cd /d \"" + DshPaths.instanceDirectory("test") + "\""
		                           : "cd '" + DshPaths.instanceDirectory("test") + "'"), script);
		assertTrue(script.contains(assignment("DSH_HOME", home.resolve("dsh-home").toString())), script);

		String line = pathLine(script);
		List<String> path = sessionPath(script);
		String shim = DshInstanceTerminal.getTermSessionsDir().resolve("test").resolve("bin").toString();

		assertEquals(shim, path.get(0), line);
		assertEquals(bin.toString(), path.get(1), line);

		assertFalse(script.contains(DshAccountRoute.KEY_ENVIRONMENT_VARIABLE), script);
	}

	@Test
	void theSessionFindsThePnpmTheLauncherSelected() throws Exception {
		Path bin = Files.createDirectories(home.resolve("node").resolve("bin"));
		Path prefix = Files.createDirectories(DshPaths.pnpmDirectory("11.29.0"));
		Path cli = PnpmRuntime.cliIn(prefix);
		Files.createDirectories(cli.getParent());
		Files.createFile(cli);

		String node = DshToolchain.nodeSelection();
		String selected = DshToolchain.pnpmSelection();
		try {
			DshToolchain.install(() -> node, () -> DshRuntimeLocation.selectionOf(prefix));

			String script = DshInstanceTerminal.scriptText(instance(Map.of()), null, runtime(bin));

			List<String> path = sessionPath(script);
			assertEquals(PnpmRuntime.shimDirectoryIn(prefix).toString(), path.get(2), pathLine(script));
		} finally {
			DshToolchain.install(() -> node, () -> selected);
			DshFiles.deleteTreeQuietly(prefix);
		}
	}

	@Test
	void theAccountKeyTravelsWithTheSession() throws Exception {
		Path bin = Files.createDirectories(home.resolve("node").resolve("bin"));
		DshAccount account = new DshAccount("deepseek", "sk-test", null, null);
		String script = DshInstanceTerminal.scriptText(instance(Map.of()), account, runtime(bin));

		assertTrue(script.contains(assignment(DshAccountRoute.environmentVariable(account.displayName()), "sk-test")),
		           script);
	}

	@Test
	void aNameAShellCannotHoldIsLeftOut() throws Exception {
		Path bin = Files.createDirectories(home.resolve("node").resolve("bin"));
		String script = DshInstanceTerminal.scriptText(instance(Map.of("GOOD_NAME", "yes", "not a name", "no")),
		                                               null,
		                                               runtime(bin));

		assertTrue(script.contains(assignment("GOOD_NAME", "yes")), script);
		assertFalse(script.contains("not a name"), script);
	}

	@Test
	void theShimRunsTheInstancesOwnEntryScript() throws Exception {
		Path bin = Files.createDirectories(home.resolve("node").resolve("bin"));
		DshInstance instance = instance(Map.of());
		String shim = DshInstanceTerminal.shimText(instance, runtime(bin));

		if (WINDOWS) {
			assertTrue(shim.contains("\"" + bin.resolve("node.exe") + "\" \"" + instance.dshEntryPoint() + "\""), shim);
			assertTrue(shim.contains("%*"), shim);
		} else {
			assertTrue(shim.contains("exec '" + bin.resolve("node") + "' '" + instance.dshEntryPoint() + "'"), shim);
			assertTrue(shim.contains("\"$@\""), shim);
		}
	}

	@Test
	void aCommandLineReachesTheSystemShell() {
		List<String> command = DshCustomCommands.shellCommand("echo hello");
		if (WINDOWS) {
			assertTrue(command.size() >= 4, command.toString());
			assertTrue(command.get(command.size() - 4).equalsIgnoreCase("/d"), command.toString());
			assertTrue(command.get(command.size() - 3).equalsIgnoreCase("/s"), command.toString());
			assertTrue(command.get(command.size() - 2).equalsIgnoreCase("/c"), command.toString());
			assertEquals("echo hello", command.getLast(), command.toString());
		} else {
			assertEquals(List.of("/bin/sh", "-c", "echo hello"), command, command.toString());
		}
	}
}
