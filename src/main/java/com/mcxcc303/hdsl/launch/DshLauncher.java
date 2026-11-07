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
import com.mcxcc303.hdsl.account.DshAccountContract;
import com.mcxcc303.hdsl.account.DshAccountRoute;
import com.mcxcc303.hdsl.account.DshDefaultModel;
import com.mcxcc303.hdsl.account.DshInjectedSettings;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.DshSurface;
import com.mcxcc303.hdsl.harness.HarnessInstaller;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshPorts;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import com.mcxcc303.hdsl.runtime.NodeRuntime;
import com.mcxcc303.hdsl.runtime.NodeRuntimeManager;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Resolves runtimes, environments, and commands to build execution plans for instances.
 */
@NotNullByDefault
public final class DshLauncher {
	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
	private static final Map<String, NoOpen> NO_OPEN_SUPPORT = new ConcurrentHashMap<>();

	private DshLauncher() {
	}

	/**
	 * Resolves the Node runtime an instance is configured to use.
	 *
	 * @param instance the instance
	 * @return the resolved runtime
	 * @throws DshException when the runtime is missing or unsupported
	 */
	public static DshNodeRuntime resolveRuntime(DshInstance instance) throws DshException {
		String selection = instance.nodeRuntimeOrDefault();
		if (DshNodeRuntime.SYSTEM.equalsIgnoreCase(selection)) {
			DshNodeRuntime runtime = DshNodeRuntime.detect().orElseThrow(DshLauncher::noSystemRuntime);
			if (!runtime.isNodeSupported()) {
				throw new DshException(
						"The system Node.js " + runtime.nodeVersion() + " is outside the supported range (" +
						DshNodeRuntime.requirement() +
						"). Install a suitable runtime on the Node page, or pick one for this instance.");
			}
			return runtime;
		}

		NodeRuntime managed = NodeRuntimeManager.findInstalled(selection);
		if (managed == null) {
			throw new DshException("Node.js " + selection +
			                       " is not installed; install it on the Node page or switch this instance to the system runtime");
		}
		return DshNodeRuntime.fromManaged(managed);
	}

	private static DshException noSystemRuntime() {
		Path node = DshNodeRuntime.which("node").orElse(null);
		if (node == null) {
			return new DshException("Node.js was not found on PATH; " + DshNodeRuntime.requirement());
		}
		return new DshException("The Node.js at " + node + " did not report its version, so whether it is " +
		                        "supported cannot be told (" + DshNodeRuntime.requirement() + "). " + "Running `" +
		                        node + " --version` says what is wrong with it.");
	}

	/**
	 * Returns the cache key for a version and profile capability lookup.
	 *
	 * @param version the version
	 * @param profile the profile
	 * @return composite lookup key
	 */
	static String capabilityKey(String version, String profile) {
		return version + "/" + profile;
	}

	private static NoOpen noOpenSupport(DshInstance instance) {
		String version = instance.version();
		String key = capabilityKey(version, instance.profile());
		NoOpen cached = NO_OPEN_SUPPORT.get(key);
		if (cached != null) {
			return cached;
		}

		NoOpen answer = NoOpen.UNKNOWN;
		Process probe = null;
		try {
			DshNodeRuntime runtime = resolveRuntime(instance);
			Path script = instance.dshEntryPoint();
			ProcessBuilder builder = new ProcessBuilder(runtime.node().toString(),
			                                            script.toString(),
			                                            "--profile",
			                                            instance.profile(),
			                                            "--help");
			builder.redirectErrorStream(true);
			builder.environment().put("DSH_HOME", instance.homeDirectory().toString());
			if (instance.workspacePath() != null) {
				builder.directory(instance.workspacePath().toFile());
			}
			probe = builder.start();

			String output;
			try (InputStream stream = probe.getInputStream()) {
				if (!probe.waitFor(PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
					LOG.info("DeepSeek Harness " + version + " did not answer --help within " +
					         PROBE_TIMEOUT.toSeconds() + "s; leaving browser open to the process");
					NO_OPEN_SUPPORT.put(key, NoOpen.UNKNOWN);
					return NoOpen.UNKNOWN;
				}
				output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			}
			answer = helpMentionsNoOpen(output) ? NoOpen.ACCEPTED : NoOpen.ABSENT;
		} catch (DshException | IOException | InterruptedException | RuntimeException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			LOG.warning("Could not read the help of DeepSeek Harness " + version, e);
		} finally {
			if (probe != null && probe.isAlive()) {
				probe.destroyForcibly();
			}
		}

		NO_OPEN_SUPPORT.put(key, answer);
		return answer;
	}

	/**
	 * Checks whether the command help output mentions {@code --no-open}.
	 *
	 * @param help the help output
	 * @return true if {@code --no-open} is present
	 */
	static boolean helpMentionsNoOpen(String help) {
		return help != null && help.contains("--no-open");
	}

	/**
	 * Determines whether the launcher should open the web browser for the instance.
	 *
	 * @param web         whether the surface serves a web address
	 * @param surfaceAsks whether the surface requested the launcher to open the interface
	 * @param support     what the version's help said about {@code --no-open}
	 * @return true if the launcher should open the browser
	 */
	static boolean launcherOpensTheInterface(boolean web, boolean surfaceAsks, NoOpen support) {
		if (!web || !surfaceAsks) {
			return false;
		}
		return support != NoOpen.UNKNOWN;
	}

	/**
	 * Builds the launch plan for an instance.
	 *
	 * @param instance the instance to launch
	 * @return the launch plan
	 * @throws DshException when dependencies or directories cannot be resolved
	 */
	public static LaunchPlan plan(DshInstance instance) throws DshException {
		return plan(instance, null);
	}

	/**
	 * Builds the launch plan for an instance with an optional account.
	 *
	 * @param instance the instance to launch
	 * @param account  the account to use, or null
	 * @return the launch plan
	 * @throws DshException when dependencies or directories cannot be resolved
	 */
	public static LaunchPlan plan(DshInstance instance, @Nullable DshAccount account) throws DshException {
		return plan(instance, account, null);
	}

	/**
	 * Builds the launch plan for an instance with an optional account and prepared route.
	 *
	 * @param instance the instance to launch
	 * @param account  the account to use, or null
	 * @param prepared pre-configured account route, or null
	 * @return the launch plan
	 * @throws DshException when dependencies or directories cannot be resolved
	 */
	public static LaunchPlan plan(DshInstance instance,
	                              @Nullable DshAccount account,
	                              @Nullable DshAccountRoute.Prepared prepared) throws DshException {
		DshNodeRuntime runtime = resolveRuntime(instance);
		HarnessInstaller.requireInstalled(instance);
		Path script = instance.dshEntryPoint();

		Path workspace = instance.workspacePath();
		try {
			Files.createDirectories(workspace);
		} catch (IOException e) {
			throw new DshException("The working directory " + workspace + " cannot be created", e);
		}

		Path home = instance.homeDirectory();
		if (instance.homeMode() == DshHomeMode.ISOLATED) {
			try {
				Files.createDirectories(home);
			} catch (IOException e) {
				throw new DshException("The instance home " + home + " cannot be created", e);
			}
		}

		DshSurface surface = DshSurface.of(instance);
		int port = surface.isWeb() ? DshPorts.resolve(instance) : 0;

		DshLaunchArguments.Parsed typed = DshLaunchArguments.parseArguments(instance.extraArguments());
		String profile = typed.profile() != null ? typed.profile() : instance.profile();

		List<String> accountRoutes = new ArrayList<>();
		for (DshAccount known : SettingsManager.settings().getAccounts()) {
			if (known.carriesAKey()) {
				accountRoutes.add(known.displayName());
			}
		}
		String injecting = account != null && account.carriesAKey() ? account.displayName() : null;

		try {
			DshInjectedSettings.settle(instance);
			DshInjectedSettings.clean(instance, profile, accountRoutes, injecting);
		} catch (DshException e) {
			LOG.warning("Could not put back what the last launch of " + instance.id() + " left in its settings", e);
		}

		try {
			DshAccountContract.publish(instance, account);
		} catch (DshException e) {
			LOG.warning("Could not write the account contract into the home of " + instance.id(), e);
		}

		if (account != null && account.carriesAKey()) {
			DshInjectedSettings.capture(instance, account.displayName(), account.key(), profile);
			try {
				DshInjectedSettings.publish(instance,
				                            account.displayName(),
				                            DshAccountRoute.environmentVariable(account.displayName()));
			} catch (DshException e) {
				LOG.warning("Could not make " + account.displayName() + " visible on the models page", e);
			}
		}

		DshAccountRoute.Prepared route = prepared;
		if (route == null && account != null && account.carriesAKey()) {
			route = DshAccountRoute.prepare(account).orElse(null);
			if (route != null) {
				route.resolveModels(account);
			}
		}
		if (route != null) {
			DshAccountRoute.apply(home, profile, route);
		}

		if (account != null && account.carriesAKey() && account.kind() != DshAccount.AccountKind.OFFICIAL) {
			try {
				DshDefaultModel.apply(home, account.displayName(), account.modelOrDefault());
			} catch (DshException e) {
				LOG.warning("Could not set the default model for " + instance.id(), e);
			}
		}

		List<String> command = new ArrayList<>();
		command.add(runtime.node().toString());
		command.add(script.toString());
		command.addAll(typed.launcherArguments());
		command.add("--profile");
		command.add(profile);

		List<String> surfaceArguments = new ArrayList<>(surface.arguments(port));
		boolean wantsToOpen = surfaceArguments.remove("--no-open");
		NoOpen support = noOpenSupport(instance);
		boolean noOpen = wantsToOpen && support == NoOpen.ACCEPTED;
		if (noOpen) {
			surfaceArguments.add("--no-open");
		}
		command.addAll(surfaceArguments);
		command.addAll(typed.appArguments());

		boolean launcherOpens = launcherOpensTheInterface(surface.isWeb(), wantsToOpen, support);
		if (surface.isWeb()) {
			LOG.info("DeepSeek Harness " + instance.version() + " " + switch (support) {
				case ACCEPTED -> "was told --no-open, so the launcher opens the interface";
				case ABSENT ->
						"has no --no-open and opens no browser of its own, so the launcher opens the interface";
				case UNKNOWN -> "could not be asked about --no-open, so the launcher leaves the browser to it";
			});
		}

		Map<String, String> environment = new LinkedHashMap<>();
		if (account != null && account.carriesAKey()) {
			String key = account.apiKey().trim();
			environment.put(DshAccountRoute.environmentVariable(account.displayName()), key);
			if (route != null && route.deepSeek()) {
				environment.put(DshAccountRoute.WEB_SEARCH_ENVIRONMENT_VARIABLE, key);
			}
		}
		environment.putAll(runtime.pathEnvironment());
		environment.putAll(DshEnvironment.of(instance));
		environment.put(DshEnvironment.HOMEDIRECTORY_VARIABLE, home.toString());
		environment.putAll(DshAccountContract.values(instance, account));

		LOG.debug("Launching " + instance.id() + " with the command: " + String.join(" ", command));
		return new LaunchPlan(instance,
		                      surface,
		                      List.copyOf(command),
		                      workspace,
		                      Map.copyOf(environment),
		                      home,
		                      port,
		                      launcherOpens);
	}

	/**
	 * Support status for the {@code --no-open} flag in an instance profile.
	 */
	enum NoOpen {
		ACCEPTED,
		ABSENT,
		UNKNOWN
	}

	/**
	 * Execution plan for launching an instance.
	 *
	 * @param instance         the instance being launched
	 * @param surface          the surface derived from the profile
	 * @param command          the executable command and arguments
	 * @param workingDirectory working directory for execution
	 * @param environment      child process environment variables
	 * @param homeDirectory    the instance DSH_HOME directory
	 * @param port             bound port, or 0 for non-web
	 * @param opensTheBrowser  whether the launcher is responsible for opening the browser
	 */
	public record LaunchPlan(DshInstance instance, DshSurface surface, @Unmodifiable List<String> command,
	                         Path workingDirectory, @Unmodifiable Map<String, String> environment, Path homeDirectory,
	                         int port, boolean opensTheBrowser) {

		public int port() {
			return port;
		}

		public boolean launcherOpensTheBrowser() {
			return opensTheBrowser;
		}

		/**
		 * Formats the launch plan as a single command line for logging and reproduction.
		 *
		 * @return shell command string
		 */
		public String commandLine() {
			StringBuilder builder = new StringBuilder();
			boolean windows = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS;
			if (windows) {
				builder.append("set \"DSH_HOME=").append(homeDirectory).append("\" && ");
			} else {
				builder.append("DSH_HOME=").append(homeDirectory).append(' ');
			}
			for (String part : command) {
				builder.append(part.indexOf(' ') >= 0 ? '"' + part + '"' : part);
				builder.append(' ');
			}
			return builder.toString().trim();
		}
	}
}
