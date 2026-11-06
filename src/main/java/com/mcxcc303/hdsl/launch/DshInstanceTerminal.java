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
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshPermissions;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Opens the system's terminal on an instance, with:
 * <ol>
 * <li>the instance's home</li>
 * <li>environment (holds some secrets, use {@link DshPermissions})</li>
 * <li>toolchain</li>
 * </ol>
 * <p>
 * Creates a `#!/usr/bin/env bash` file on Linux, or a batch file on Windows.
 */
@NotNullByDefault
public final class DshInstanceTerminal {
	private static final String TERM_SESSIONS_DIRECTORY = "terminals";
	private static final String SHIM_DIR = "bin";
	private static final String SCRIPT = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "open.cmd" : "open.sh";
	private static final String SHIM = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "dsh.cmd" : "dsh";
	/**
	 * What an environment variable may be called to be written into the script.
	 */
	private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	/**
	 * The terminal emulators tried, most preferred first, for a Linux desktop.
	 */
	private static final List<Emulator> LINUX_EMULATORS = List.of(new Emulator("x-terminal-emulator", List.of("-e")),
	                                                              new Emulator("kgx", List.of("--")),
	                                                              new Emulator("gnome-terminal", List.of("--")),
	                                                              new Emulator("konsole", List.of("-e")),
	                                                              new Emulator("xfce4-terminal", List.of("-e")),
	                                                              new Emulator("mate-terminal", List.of("-e")),
	                                                              new Emulator("tilix", List.of("-e")),
	                                                              new Emulator("kitty", List.of()),
	                                                              new Emulator("alacritty", List.of("-e")),
	                                                              new Emulator("wezterm", List.of("start", "--")),
	                                                              new Emulator("foot", List.of()),
	                                                              new Emulator("xterm", List.of("-e")));
	/**
	 * The terminals tried on Windows, most preferred first.
	 */
	private static final List<Emulator> WINDOWS_EMULATORS = List.of(new Emulator("wt.exe", List.of("cmd.exe", "/k")),
	                                                                new Emulator("cmd.exe",
	                                                                             List.of("/c",
	                                                                                     "start",
	                                                                                     "HDSL",
	                                                                                     "cmd.exe",
	                                                                                     "/k")));

	private DshInstanceTerminal() {}

	/**
	 * Opens a terminal on an instance.
	 *
	 * @param instance the instance
	 * @param account  the account it launches with, or null
	 * @throws DshException when there is no terminal to open, or it cannot be started
	 */
	public static void open(DshInstance instance, @Nullable DshAccount account) throws DshException {
		Path script = write(instance, account);
		Emulator emulator = findPreferedEmulator();
		if (emulator == null) {
			throw new DshException("No terminal emulator was found. Install one of: " +
			                       String.join(", ", getEmulators().stream().map(Emulator::command).toList()));
		}

		List<String> command = new ArrayList<>();
		command.add(emulator.command());
		command.addAll(emulator.arguments());
		command.add(script.toString());
		try {
			new ProcessBuilder(command).start();
			LOG.info("Opened " + emulator.command() + " on " + instance.id() + " with " + script);
		} catch (IOException e) {
			throw new DshException("Failed to start " + emulator.command(), e);
		}
	}

	/**
	 * Writes a session's script and its dsh shim, and returns the script.
	 * <p>
	 * The files are not removed when the terminal closes, because the launcher cannot tell when that happens.<br>
	 * On Windows the batch file is written in the machine's charset.
	 *
	 * @param account the account it launches with
	 * @return the script to hand the terminal
	 * @throws DshException when it cannot be written
	 */
	public static Path write(DshInstance instance, @Nullable DshAccount account) throws DshException {
		return write(instance, account, DshLauncher.resolveRuntime(instance));
	}

	/**
	 * Returns the first terminal emulator this system has.
	 *
	 * @return the emulator, or null when can't get any
	 */
	private static @Nullable Emulator findPreferedEmulator() {
		for (Emulator emulator : getEmulators()) {
			if (DshNodeRuntime.which(emulator.command()).isPresent()) return emulator;
		}
		return null;
	}

	private static List<Emulator> getEmulators() {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? WINDOWS_EMULATORS : LINUX_EMULATORS;
	}

	/**
	 * Writes a session's script and its dsh shim, with given runtime.
	 *
	 * @param account the account it launches with
	 * @param runtime the node runtime the instance runs on
	 * @return the script to hand the terminal
	 * @throws DshException when it cannot be written
	 */
	static Path write(DshInstance instance, @Nullable DshAccount account, DshNodeRuntime runtime) throws DshException {
		Path sessions = getTermSessionsDir();
		Path session = sessions.resolve(instance.id());
		Path bin = session.resolve(SHIM_DIR);
		Path script = session.resolve(SCRIPT);
		Path shim = bin.resolve(SHIM);
		Charset charset = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		                  ? OperatingSystem.NATIVE_CHARSET
		                  : StandardCharsets.UTF_8;
		try {
			DshPermissions.createRestrictedDirectory(bin, DshPermissions.SECRET_DIRECTORY);
			DshPermissions.restrict(sessions, DshPermissions.SECRET_DIRECTORY);
			DshPermissions.restrict(session, DshPermissions.SECRET_DIRECTORY);
			DshPermissions.writeRestricted(script,
			                               scriptText(instance, account, runtime),
			                               charset,
			                               DshPermissions.SECRET_EXECUTABLE);
			DshPermissions.writeRestricted(shim,
			                               shimText(instance, runtime),
			                               charset,
			                               DshPermissions.SECRET_EXECUTABLE);
		} catch (IOException e) {
			throw new DshException("Failed to write the terminal session for " + instance.id(), e);
		}
		setExecutable(script);
		setExecutable(shim);
		return script;
	}

	static Path getTermSessionsDir() {
		return Metadata.HMCL_USER_HOME.resolve(TERM_SESSIONS_DIRECTORY);
	}

	/**
	 * Builds the script, with the runtime given.
	 * <p>
	 * The environment is the launcher's own, in the launcher's own order: the home it runs against,
	 * the launcher-wide and per-instance environment, the account's key, and PATH last.
	 *
	 * @param instance the instance
	 * @param account  the account to launch with, or null for none
	 * @param runtime  the node runtime the instance runs on
	 * @return the script
	 * @throws DshException when the instance's paths cannot be resolved
	 */
	static String scriptText(DshInstance instance, @Nullable DshAccount account, DshNodeRuntime runtime)
	throws DshException {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		       ? windowsScriptText(instance, account, runtime)
		       : linuxScriptText(instance, account, runtime);
	}

	/**
	 * Builds the dsh shim a session puts in front of the system's.
	 *
	 * @param instance the instance
	 * @param runtime  the node runtime the instance runs on
	 * @return the shim's text
	 * @throws DshException when the instance's entry script cannot be resolved
	 */
	static String shimText(DshInstance instance, DshNodeRuntime runtime) throws DshException {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			return "@echo off\r\n" +
			       "rem The instance's own dsh: what dsh means in the terminal the launcher opened on\r\n" +
			       "rem it. The instance's entry script, run by the instance's own node.\r\n" + "\"" + runtime.node() +
			       "\" \"" + instance.dshEntryPoint() + "\" %*\r\n";
		}
		return "#!/usr/bin/env bash\n" +
		       "# The instance's own dsh: what dsh means in the terminal the launcher opened on\n" +
		       "# it. The instance's entry script, run by the instance's own node.\n" + "exec " +
		       quote(runtime.node().toString()) + " " + quote(instance.dshEntryPoint().toString()) + " \"$@\"\n";
	}

	private static void setExecutable(Path file) {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			return;
		}
		if (!file.toFile().setExecutable(true, true)) {
			LOG.warning("Could not make " + file + " executable");
		}
	}

	/**
	 * Builds the batch script to set a Windows shell up for an instance.
	 *
	 * @param account the account to launch with
	 * @param runtime the node runtime the instance runs on
	 * @return the script
	 * @throws DshException when the instance's paths cannot be resolved
	 */
	private static String windowsScriptText(DshInstance instance, @Nullable DshAccount account, DshNodeRuntime runtime)
	throws DshException {
		// instances from an added folder currently does not have the dir.
		Path instanceDirectory = instance.instanceDirectory();
		Path shim_dir = getTermSessionsDir().resolve(instance.id()).resolve(SHIM_DIR);
		Map<String, String> environment = environment(instance, account, runtime);

		StringBuilder commands = new StringBuilder();
		commands.append("@echo off\r\n");
		commands.append("rem Written by Hello DeepSeek! Launcher, again on every open.\r\n");
		commands.append("rem\r\n");
		commands.append("rem This shell is set up the way the launcher sets the instance up when it starts\r\n");
		commands.append("rem it: the same home, the same environment, the same toolchain. dsh here is this\r\n");
		commands.append("rem instance's own harness and npm is the npm it installs with, so running dsh web\r\n");
		commands.append("rem in this shell starts this instance's harness and nothing else's.\r\n");
		commands.append("rem\r\n");
		commands.append("rem With arguments it runs them and exits; with none it leaves a shell.\r\n");
		commands.append("\r\n");
		commands.append("cd /d \"").append(instanceDirectory).append("\" || exit /b 1\r\n");
		for (Map.Entry<String, String> env : environment.entrySet()) {
			if (!NAME.matcher(env.getKey()).matches()) {
				// A name the interpreter cannot hold is dropped; the editor accepts any text.
				LOG.warning("Not setting " + env.getKey() + ": not a usable variable name");
				continue;
			}
			// The instance's own tools first, and then dsh shim
			String value = "PATH".equals(env.getKey())
			               ? shim_dir + File.pathSeparator + env.getValue()
			               : env.getValue();
			commands.append("set \"").append(env.getKey()).append('=').append(value).append("\"\r\n");
		}
		commands.append("\r\n");
		commands.append("echo This shell is set up for the instance ").append(instance.id()).append(".\r\n");
		commands.append("echo dsh is its own harness; npm is the one it uses. Try: dsh web\r\n");
		commands.append("\r\n");
		commands.append("if \"%~1\"==\"\" goto shell\r\n");
		commands.append("call %*\r\n");
		commands.append("exit /b %ERRORLEVEL%\r\n");
		commands.append(":shell\r\n");
		return commands.toString();
	}

	/**
	 * Builds the shell script to set a Linux shell up for an instance.
	 *
	 * @param account the account to launch with
	 * @param runtime the node runtime the instance runs on
	 * @return the script
	 * @throws DshException when the instance's paths cannot be resolved
	 */
	private static String linuxScriptText(DshInstance instance, @Nullable DshAccount account, DshNodeRuntime runtime)
	throws DshException {
		// The instance's own directory, not its workspace, and the directory recorded on it, not the
		// launcher's path of the same name; see [DshInstanceTerminal#windowsScriptText].
		Path instanceDirectory = instance.instanceDirectory();
		Path shim_dir = getTermSessionsDir().resolve(instance.id()).resolve(SHIM_DIR);
		Map<String, String> environment = environment(instance, account, runtime);

		StringBuilder commands = new StringBuilder();
		commands.append("#!/usr/bin/env bash\n");
		commands.append("# Written by Hello DeepSeek! Launcher, again on every open.\n");
		commands.append("#\n");
		commands.append("# This shell is set up the way the launcher sets the instance up when it starts\n");
		commands.append("# it: the same home, the same environment, the same toolchain. dsh here is this\n");
		commands.append("# instance's own harness and npm is the npm it installs with, so running dsh web\n");
		commands.append("# in this shell starts this instance's harness and nothing else's.\n");
		commands.append("#\n");
		commands.append("# With arguments it runs them and exits; with none it leaves a shell.\n");
		commands.append('\n');
		commands.append("cd ").append(quote(instanceDirectory.toString())).append(" || exit 1\n");
		for (Map.Entry<String, String> env : environment.entrySet()) {
			if (!NAME.matcher(env.getKey()).matches()) {
				// A name a shell cannot hold is dropped; the environment editor accepts any text.
				LOG.warning("Not exporting " + env.getKey() + ": not a usable variable name");
				continue;
			}
			// The instance's own tools first, and its dsh shim before even those.
			String value = "PATH".equals(env.getKey())
			               ? shim_dir + File.pathSeparator + env.getValue()
			               : env.getValue();
			commands.append("export ").append(env.getKey()).append('=').append(quote(value)).append('\n');
		}
		commands.append('\n');
		commands.append("echo ")
		        .append(quote("This shell is set up for the instance " + instance.id() + "."))
		        .append('\n');
		commands.append("echo ")
		        .append(quote("dsh is its own harness; npm is the one it uses. " + "Try: dsh web"))
		        .append('\n');
		commands.append('\n');
		commands.append("if [ \"$#\" -gt 0 ]; then\n");
		commands.append("    exec \"$@\"\n");
		commands.append("fi\n");
		commands.append("exec ").append(quote(getSystemShell())).append(" -i\n");
		return commands.toString();
	}

	static String quote(String value) {
		return "'" + value.replace("'", "'\\''") + "'";
	}

	/**
	 * Builds the environment in the launcher's order.
	 *
	 * @param account the account to launch with, or null for none
	 * @param runtime the node runtime the instance runs on
	 * @return the environment
	 * @throws DshException when the instance's paths cannot be resolved
	 */
	private static Map<String, String> environment(DshInstance instance,
	                                               @Nullable DshAccount account,
	                                               DshNodeRuntime runtime) throws DshException {
		Path home = instance.homeDirectory();
		Map<String, String> environment = new LinkedHashMap<>();
		environment.put("DSH_HOME", home.toString());
		environment.putAll(DshEnvironment.of(instance));
		if (account != null && account.carriesAKey()) {
			environment.put(DshAccountRoute.environmentVariable(account.displayName()), account.apiKey().trim());
		}
		String inherited = environment.getOrDefault("PATH", Objects.toString(System.getenv("PATH")));
		environment.put("PATH",
		                runtime.binDirectory() +
		                (inherited.isBlank() ? "" : File.pathSeparator + inherited));
		return environment;
	}

	static String getSystemShell() {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			String comSpec = System.getenv("ComSpec");
			return comSpec == null || comSpec.isBlank() ? "cmd.exe" : comSpec;
		}
		String shell = System.getenv("SHELL");
		return shell == null || shell.isBlank() ? "/bin/bash" : shell;
	}

	/**
	 * A terminal emulator and how to make a script run in it.
	 *
	 * @param command   the executable's name
	 * @param arguments flag or command between emulator and the command to run.
	 *                  e.g. alacritty -e SCRIPT.sh, alacritty->command and [-e]->arguments
	 */
	private record Emulator(String command, List<String> arguments) {}
}
