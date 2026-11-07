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

import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Runs pre-launch and post-exit custom shell commands configured for an instance.
 *
 * <p>Commands execute in the instance's workspace directory ({@link DshInstance#workspacePath()})
 * and see the instance's environment variables (with {@code DSH_HOME} pinned to the instance's home).
 * Pre-launch command failures halt the launch process, while post-exit command failures are logged.
 */
@NotNullByDefault
public final class DshCustomCommands {
	private DshCustomCommands() {
	}

	/**
	 * Runs a custom command for an instance.
	 *
	 * @param instance the instance
	 * @param command  the command line to run, or null/blank
	 * @param phase    the execution phase description
	 * @param onLine   consumer for command output lines, or null
	 * @throws DshException when the command cannot be run or exits non-zero
	 */
	public static void run(DshInstance instance,
	                       @Nullable String command,
	                       String phase,
	                       @Nullable Consumer<String> onLine) throws DshException {
		if (command == null || command.isBlank()) {
			return;
		}

		LOG.info("Running the " + phase + " command of " + instance.id() + ": " + command);
		Map<String, String> environment = new LinkedHashMap<>();
		environment.put("DSH_INSTANCE", instance.id());
		environment.put("DSH_VERSION", instance.version());
		environment.putAll(DshEnvironment.of(instance));
		environment.put(DshEnvironment.HOMEDIRECTORY_VARIABLE, instance.homeDirectory().toString());

		try {
			DshCommand.Result result = DshCommand.run(shellCommand(command),
			                                          instance.workspacePath(),
			                                          environment,
			                                          onLine);
			if (result.exitCode() != 0) {
				throw new DshException(
						"The " + phase + " command exited with code " + result.exitCode() + ": " + command);
			}
		} catch (IOException e) {
			throw new DshException("Failed to run the " + phase + " command: " + command, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DshException("The " + phase + " command was interrupted: " + command, e);
		}
	}

	/**
	 * Runs a custom command and logs any failure instead of throwing.
	 *
	 * @param instance the instance
	 * @param command  the command line to run, or null/blank
	 * @param phase    the execution phase description
	 * @param onLine   consumer for command output lines, or null
	 */
	public static void runQuietly(DshInstance instance,
	                              @Nullable String command,
	                              String phase,
	                              @Nullable Consumer<String> onLine) {
		try {
			run(instance, command, phase, onLine);
		} catch (DshException e) {
			LOG.warning(e.getMessage());
		}
	}

	/**
	 * Wraps a shell command string for the host platform's command interpreter.
	 *
	 * <p>On Windows, uses {@code %ComSpec% /d /s /c <command>}:
	 * <ul>
	 * <li>{@code /d} disables AutoRun execution from registry keys, preventing command hijacking.</li>
	 * <li>{@code /s} modifies quote handling so the command string following {@code /c} is preserved verbatim.</li>
	 * <li>{@code /c} executes the command string and terminates.</li>
	 * </ul>
	 * On Unix/Linux, invokes {@code /bin/sh -c <command>}.
	 *
	 * @param command the shell command line
	 * @return command arguments for ProcessBuilder
	 */
	static List<String> shellCommand(String command) {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			String interpreter = System.getenv("ComSpec");
			if (interpreter == null || interpreter.isBlank()) {
				interpreter = "cmd.exe";
			}
			return List.of(interpreter, "/d", "/s", "/c", command);
		}
		return List.of("/bin/sh", "-c", command);
	}
}
