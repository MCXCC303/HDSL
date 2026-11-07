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
import com.mcxcc303.hdsl.instance.*;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the commands somebody asks to run around an instance really run on Windows.
 */
@EnabledOnOs(OS.WINDOWS)
class DshCustomCommandsWindowsTest {
	private static final String INSTANCE_ID = "commands-windows";

	@TempDir
	Path folder;

	@TempDir
	Path workspace;

	private Supplier<List<Path>> previousFolders;

	private static String interpreter() {
		String interpreter = System.getenv("ComSpec");
		return interpreter == null || interpreter.isBlank() ? "cmd.exe" : interpreter;
	}

	private static List<String> output(DshInstance instance, String line) throws DshException {
		List<String> lines = new ArrayList<>();
		DshCustomCommands.run(instance, line, "pre-launch", lines::add);
		return lines;
	}

	private static void assertSays(List<String> lines, String text, String what) {
		assertTrue(lines.stream().anyMatch(line -> line.contains(text)), what + ": " + lines);
	}

	@BeforeEach
	void makeItIsolate() {
		previousFolders = InstanceFolders.setFolders(() -> List.of(folder));
	}

	@AfterEach
	void putItBack() {
		InstanceFolders.setFolders(previousFolders);
	}

	private DshInstance makeInstance(Map<String, String> environment) throws DshException {
		return DshInstanceManager.create(INSTANCE_ID,
		                                 "1.2.3",
		                                 DshInstance.DEFAULT_PROFILE,
		                                 workspace,
		                                 DshNodeRuntime.SYSTEM,
		                                 DshHomeMode.ISOLATED,
		                                 null,
		                                 List.of(),
		                                 environment,
		                                 folder);
	}

	@Test
	void theSystemsOwnInterpreterIsWhatRunsTheLine() throws Exception {
		String line = "echo hello | findstr hello";
		String interpreter = interpreter();

		assertEquals(List.of(interpreter, "/d", "/s", "/c", line),
		             DshCustomCommands.shellCommand(line),
		             "the line goes to the interpreter as one argument, not split into program and flags");
		assertTrue(Files.isRegularFile(Path.of(interpreter)), "ComSpec names a real interpreter: " + interpreter);

		DshInstance instance = makeInstance(Map.of());
		assertSays(output(instance, line), "hello", "a pipe is the shell's business and the shell ran it");
	}

	@Test
	void aLineWithPipesAndRedirectsAndQuotedPathsRunsAsWritten() throws Exception {
		DshInstance instance = makeInstance(Map.of());
		Path spaced = workspace.resolve("dir with space");
		Files.createDirectories(spaced);

		assertSays(output(instance, "echo one && echo two"),
		           "two",
		           "the second half of an `&&` chain runs when the first half succeeds");

		output(instance, "echo written> plain.txt");
		assertEquals("written",
		             Files.readString(workspace.resolve("plain.txt")).trim(),
		             "a redirection writes where it names, relative to the instance's workspace");

		output(instance, "echo spaced> \"dir with space\\out.txt\"");
		assertEquals("spaced",
		             Files.readString(spaced.resolve("out.txt")).trim(),
		             "and a quoted path with a space in it is one path, not two arguments");

		assertSays(output(instance, "dir /b \"dir with space\""),
		           "out.txt",
		           "the quoted path reaches the program inside the line with its space intact");
	}

	@Test
	void aCommandRunsWhereTheInstanceRunsAndSeesItsVariables() throws Exception {
		DshInstance instance = makeInstance(Map.of("HDSL_COMMAND_VARIABLE", "from the instance"));

		assertSays(output(instance, "cd"),
		           workspace.toString(),
		           "the command's working directory is the instance's workspace");

		assertSays(output(instance, "echo %HDSL_COMMAND_VARIABLE%"),
		           "from the instance",
		           "a variable the instance runs with is expanded by the shell that runs the command");

		assertSays(output(instance, "echo %DSH_INSTANCE% %DSH_VERSION%"),
		           INSTANCE_ID + " 1.2.3",
		           "and the command is told which instance and which version it is about");

		assertSays(output(instance, "echo [%DSH_HOME%]"),
		           instance.homeDirectory().toString(),
		           "with the same home the instance itself runs on");
	}

	@Test
	void aCommandThatExitsNonZeroStopsTheLaunch() throws Exception {
		DshInstance instance = makeInstance(Map.of());

		DshException refused = assertThrows(DshException.class,
		                                    () -> DshCustomCommands.run(instance, "exit /b 7", "pre-launch", null));
		assertTrue(refused.getMessage().contains("7"), refused.getMessage());
		assertTrue(refused.getMessage().contains("pre-launch"), refused.getMessage());

		// A line that is not a command at all fails the same way, and says which code it failed with.
		DshException unknown = assertThrows(DshException.class,
		                                    () -> DshCustomCommands.run(instance,
		                                                                "no-such-command-hdsl",
		                                                                "pre-launch",
		                                                                null));
		assertTrue(unknown.getMessage().contains("1"), unknown.getMessage());

		assertDoesNotThrow(() -> DshCustomCommands.runQuietly(instance, "exit /b 7", "post-exit", null),
		                   "after an instance has ended a failing command is something to report, not to raise");
		assertDoesNotThrow(() -> DshCustomCommands.run(instance, "   ", "pre-launch", null),
		                   "and no command at all is a no-op rather than an empty line to the shell");
	}

	@Test
	void aVariableNamedDshHomeInTheEnvironmentCannotMoveTheHomeACommandRunsOn() throws Exception {
		Path decoy = folder.resolve("decoy-home");
		var settings = SettingsManager.settings();
		Map<String, String> before = Map.copyOf(settings.globalEnvironment());
		try {
			settings.globalEnvironmentProperty()
			        .set(DshEnvironment.parse("DSH_HOME=" + decoy + "\nHDSL_COMMAND_SIDE=kept"));

			DshInstance instance = makeInstance(Map.of());

			assertSays(output(instance, "echo [%DSH_HOME%]"),
			           "[" + instance.homeDirectory() + "]",
			           "the command works on the instance's own home, whatever a box says");
			assertSays(output(instance, "echo [%HDSL_COMMAND_SIDE%]"), "[kept]", "only the reserved name is dropped");
		} finally {
			settings.globalEnvironmentProperty().set(before);
		}
	}

	@Test
	void anInstancesOwnCommandWinsOverTheLaunchers() throws Exception {
		DshInstance instance = makeInstance(Map.of());
		var settings = SettingsManager.settings();
		String launcherPre = settings.preLaunchCommandProperty().get();
		String launcherPost = settings.postExitCommandProperty().get();
		try {
			settings.preLaunchCommandProperty().set("echo launcher-wide");
			settings.postExitCommandProperty().set("echo launcher-wide-exit");

			assertEquals("echo launcher-wide",
			             settings.preLaunchCommandFor(INSTANCE_ID),
			             "with nothing of its own, the launcher's command applies");
			assertEquals("echo launcher-wide-exit", settings.postExitCommandFor(INSTANCE_ID));

			DshInstanceSettings.setPreLaunchCommand(instance, "echo instance own");
			DshInstanceSettings.setPostExitCommand(instance, "echo instance exit");

			assertEquals("echo instance own",
			             settings.preLaunchCommandFor(INSTANCE_ID),
			             "an instance's own command is the one the launch is given");
			assertEquals("echo instance exit", settings.postExitCommandFor(INSTANCE_ID));

			assertSays(output(instance, settings.preLaunchCommandFor(INSTANCE_ID)),
			           "instance own",
			           "and it is a line the shell really runs");

			DshInstanceSettings.setPreLaunchCommand(instance, null);
			assertEquals("echo launcher-wide",
			             settings.preLaunchCommandFor(INSTANCE_ID),
			             "removing it hands the instance back to the launcher's command");
		} finally {
			settings.preLaunchCommandProperty().set(launcherPre);
			settings.postExitCommandProperty().set(launcherPost);
		}
	}
}
