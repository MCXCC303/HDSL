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
package com.mcxcc303.hdsl.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.HarnessInstaller;
import com.mcxcc303.hdsl.harness.DshPreset;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.launch.DshEnvironment;
import com.mcxcc303.hdsl.launch.DshLauncher;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Adds and removes profile plugins by driving {@code dsh plugin}.
 */
@NotNullByDefault
public final class DshPluginInstaller {

	private static final Set<String> ANSWERED_ABOUT = ConcurrentHashMap.newKeySet();

	private DshPluginInstaller() {
	}

	/**
	 * Resets tracked build script answers for a new installation session.
	 */
	public static void beginInstallation() {
		ANSWERED_ABOUT.clear();
	}

	/**
	 * Records the package keys for which build script answers have been submitted.
	 *
	 * @param keys the {@code allowBuilds} keys answered for
	 */
	public static void answeredAbout(List<String> keys) {
		ANSWERED_ABOUT.addAll(keys);
	}

	/**
	 * Installs every given preset into an instance's profile.
	 *
	 * @param instance the instance whose profile is modified
	 * @param presets  the presets to install
	 * @param onLine   receives every output line, or {@code null}
	 * @throws DshException when installation fails
	 */
	public static void install(DshInstance instance, List<DshPreset> presets, @Nullable Consumer<String> onLine)
	throws DshException {
		installSpecs(instance, presets.stream().map(DshPreset::spec).toList(), onLine);
	}

	/**
	 * Installs explicit package specs into an instance's profile.
	 *
	 * @param instance the instance whose profile is modified
	 * @param specs    the package specs to install
	 * @param onLine   receives every output line, or {@code null}
	 * @throws DshException when installation fails
	 */
	public static void installSpecs(DshInstance instance, List<String> specs, @Nullable Consumer<String> onLine)
	throws DshException {
		if (specs.isEmpty()) {
			return;
		}

		DshNodeRuntime runtime = DshLauncher.resolveRuntime(instance);
		if (!runtime.canManagePlugins()) {
			throw new DshException("pnpm was not found on PATH; installing plugins requires it");
		}

		Path home = instance.homeDirectory();
		for (String spec : specs) {
			report(onLine, "Installing " + spec + " ...");
			runPluginCommand(instance, runtime, home, List.of("add", spec), List.of(spec), onLine);
			report(onLine, "Installed " + spec);
		}
	}

	/**
	 * Resolves dependencies for an instance's profile using {@code dsh plugin install}.
	 *
	 * @param instance the instance whose profile is resolved
	 * @param onLine   receives every output line, or {@code null}
	 * @throws DshException when pnpm is missing or the resolve fails
	 */
	public static void resolve(DshInstance instance, @Nullable Consumer<String> onLine) throws DshException {
		DshNodeRuntime runtime = DshLauncher.resolveRuntime(instance);
		if (!runtime.canManagePlugins()) {
			throw new DshException("pnpm was not found on PATH; installing plugins requires it");
		}
		runPluginCommand(instance, runtime, instance.homeDirectory(), List.of("install"), List.of(), onLine);
	}

	/**
	 * Removes a package from an instance's profile.
	 *
	 * @param instance the instance whose profile is modified
	 * @param spec     the package spec to remove
	 * @param onLine   receives every output line, or {@code null}
	 * @throws DshException when the removal fails
	 */
	public static void remove(DshInstance instance, String spec, @Nullable Consumer<String> onLine)
	throws DshException {
		removeSpecs(instance, List.of(spec), onLine);
	}

	/**
	 * Removes several packages from an instance's profile in one run.
	 *
	 * @param instance the instance whose profile is modified
	 * @param specs    the package specs to remove
	 * @param onLine   receives every output line, or {@code null}
	 * @throws DshException when the removal fails
	 */
	public static void removeSpecs(DshInstance instance, List<String> specs, @Nullable Consumer<String> onLine)
	throws DshException {
		if (specs.isEmpty()) {
			return;
		}

		DshNodeRuntime runtime = DshLauncher.resolveRuntime(instance);
		if (!runtime.canManagePlugins()) {
			throw new DshException("pnpm was not found on PATH; removing plugins requires it");
		}

		report(onLine, "Removing " + String.join(", ", specs) + " ...");
		List<String> command = new ArrayList<>();
		command.add("remove");
		command.addAll(specs);
		runPluginCommand(instance, runtime, instance.homeDirectory(), command, List.of(), onLine);
		report(onLine, "Removed " + specs.size() + " package(s)");
	}

	/**
	 * Reads the packages a profile declares as dependencies.
	 *
	 * @param home    the {@code DSH_HOME} holding the profile
	 * @param profile the profile name
	 * @return the dependency name to version-range map, in manifest order
	 */
	public static Map<String, String> readDependencies(Path home, String profile) {
		Path manifest = home.resolve("profiles").resolve(profile).resolve("package.json");
		if (!Files.isRegularFile(manifest)) {
			return Map.of();
		}
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(manifest));
			if (!parsed.isJsonObject()) {
				return Map.of();
			}
			JsonObject dependencies = parsed.getAsJsonObject().getAsJsonObject("dependencies");
			if (dependencies == null) {
				return Map.of();
			}
			Map<String, String> result = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> entry : dependencies.entrySet()) {
				result.put(entry.getKey(), entry.getValue().getAsString());
			}
			return result;
		} catch (IOException | RuntimeException e) {
			LOG.warning("Failed to read the profile manifest " + manifest, e);
			return Map.of();
		}
	}

	/**
	 * Reads the bundle list a profile declares.
	 *
	 * @param home    the {@code DSH_HOME} holding the profile
	 * @param profile the profile name
	 * @return the declared bundle package names, in load order
	 */
	public static List<String> readBundles(Path home, String profile) {
		Path manifest = home.resolve("profiles").resolve(profile).resolve("package.json");
		if (!Files.isRegularFile(manifest)) {
			return List.of();
		}
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(manifest));
			if (!parsed.isJsonObject()) {
				return List.of();
			}
			JsonObject dsh = parsed.getAsJsonObject().getAsJsonObject("dsh");
			if (dsh == null) {
				return List.of();
			}
			JsonObject profileObject = dsh.getAsJsonObject("profile");
			if (profileObject == null) {
				return List.of();
			}
			JsonArray bundles = profileObject.getAsJsonArray("bundles");
			if (bundles == null) {
				return List.of();
			}
			List<String> names = new ArrayList<>(bundles.size());
			for (JsonElement element : bundles) {
				if (element.isJsonPrimitive()) {
					names.add(element.getAsString());
				}
			}
			return names;
		} catch (IOException | RuntimeException e) {
			LOG.warning("Failed to read the profile manifest " + manifest, e);
			return List.of();
		}
	}

	private static void runPluginCommand(DshInstance instance,
	                                     DshNodeRuntime runtime,
	                                     Path home,
	                                     List<String> args,
	                                     List<String> packages,
	                                     @Nullable Consumer<String> onLine) throws DshException {
		HarnessInstaller.requireInstalled(instance);
		Path script = instance.dshEntryPoint();

		List<String> command = new ArrayList<>();
		command.add(runtime.node().toString());
		command.add(script.toString());
		command.add("plugin");
		command.add("--profile");
		command.add(instance.profile());
		command.addAll(args);

		LOG.info("Installing into instance " + instance.id() + " (home " + home + ", profile " + instance.profile() +
		         "): " + String.join(" ", command));

		// Pass DSH_HOME in child environment
		Map<String, String> environment = new LinkedHashMap<>();
		environment.put("DSH_HOME", home.toString());
		environment.putAll(runtime.pathEnvironment());
		environment.putAll(DshEnvironment.of(instance));

		boolean retried = false;
		while (true) {
			try {
				DshCommand.Result result = DshCommand.run(command,
				                                          instance.workspacePath(),
				                                          environment,
				                                          line -> report(onLine, line));
				int exitCode = result.exitCode();
				if (exitCode != 0 && !retried) {
					// Check if build scripts require approval
					List<String> named = gitBuildKeys(result.output());
					boolean placeholder = explainIgnoredBuilds(result.output(), home, instance.profile()) != null;
					if (!named.isEmpty() && DshBuildScriptPolicy.of(instance) == DshBuildScriptPolicy.NEVER) {
						throw new DshException(explainGitBuild(named, home, instance.profile()));
					}
					if (!named.isEmpty()) {
						DshBuildScripts.propose(instance, named);
					}
					if (!named.isEmpty() || placeholder) {
						List<String> waiting = DshBuildScripts.unanswered(instance);
						DshBuildScriptPolicy policy = DshBuildScriptPolicy.of(instance);
						if (!waiting.isEmpty() && policy == DshBuildScriptPolicy.AUTO) {
							report(onLine, "Allowing install scripts for " + String.join(", ", waiting));
							DshBuildScripts.answer(instance, waiting, true);
							retried = true;
							continue;
						}
						if (!waiting.isEmpty() && policy == DshBuildScriptPolicy.NEVER) {
							report(onLine, "Not running install scripts for " + String.join(", ", waiting));
							DshBuildScripts.answer(instance, waiting, false);
							retried = true;
							continue;
						}
						List<String> unasked = new ArrayList<>();
						for (String key : waiting) {
							if (!ANSWERED_ABOUT.contains(key)) {
								unasked.add(key);
							}
						}
						if (!unasked.isEmpty()) {
							throw new DshBuildScriptApprovalRequired(unasked);
						}
					}
				}
				if (exitCode != 0) {
					String explanation = explainIgnoredBuilds(result.output(), home, instance.profile());
					if (explanation == null) {
						explanation = explainGitBuild(gitBuildKeys(result.output()), home, instance.profile());
					}
					if (explanation != null) {
						throw new DshException(explanation);
					}
					throw new DshException(
							"`dsh plugin " + String.join(" ", args) + "` exited with code " + exitCode + ":\n" +
							tail(result.output()));
				}
				// Check profile for any waiting builds even if exitCode was 0
				if (!retried && answerWaitingBuilds(instance, packages, onLine)) {
					retried = true;
					continue;
				}
				List<String> refused = refusedAmong(instance, packages);
				if (!refused.isEmpty()) {
					report(onLine, "Installed without running install scripts: " + String.join(", ", refused));
					LOG.info("Installed " + String.join(", ", refused) + " into instance " + instance.id() +
					         " without running their install scripts");
				}
			} catch (IOException e) {
				throw new DshException("Failed to run `dsh plugin " + String.join(" ", args) + "`", e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new DshException("`dsh plugin " + String.join(" ", args) + "` was interrupted", e);
			}
			return;
		}
	}

	private static boolean answerWaitingBuilds(DshInstance instance,
	                                           List<String> packages,
	                                           @Nullable Consumer<String> onLine) throws DshException {
		List<String> waiting = DshBuildScripts.unanswered(instance);
		if (waiting.isEmpty()) {
			List<String> refused = refusedAmong(instance, packages).stream()
			                                                       .filter(key -> !ANSWERED_ABOUT.contains(key))
			                                                       .toList();
			if (refused.isEmpty() || DshBuildScriptPolicy.of(instance) != DshBuildScriptPolicy.MANUAL) {
				return false;
			}
			LOG.info("Asking again about " + String.join(", ", refused) + ": instance " + instance.id() +
			         " refused them before, and is installing them again");
			throw new DshBuildScriptApprovalRequired(refused);
		}
		switch (DshBuildScriptPolicy.of(instance)) {
			case AUTO -> {
				report(onLine, "Allowing install scripts for " + String.join(", ", waiting));
				DshBuildScripts.answer(instance, waiting, true);
				return true;
			}
			case NEVER -> {
				report(onLine, "Not running install scripts for " + String.join(", ", waiting));
				DshBuildScripts.answer(instance, waiting, false);
				return true;
			}
			default -> {
				throw new DshBuildScriptApprovalRequired(waiting);
			}
		}
	}

	private static List<String> refusedAmong(DshInstance instance, List<String> packages) throws DshException {
		if (packages.isEmpty()) {
			return List.of();
		}
		Path profileDirectory = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
		List<String> names = packages.stream()
		                             .map(spec -> nameOf(spec, profileDirectory))
		                             .filter(Objects::nonNull)
		                             .toList();
		return DshBuildScripts.refused(instance).stream().filter(key -> names.contains(packageNameOf(key))).toList();
	}

	private static @Nullable String nameOf(String spec, Path profileDirectory) {
		Path file = DshPluginBundle.fileOf(spec, profileDirectory);
		if (file != null && Files.isRegularFile(file)) {
			try {
				return DshLocalPlugins.inspect(file).name();
			} catch (DshException e) {
				LOG.warning("Could not read the package at " + file +
				            ", so whether its install scripts were refused cannot be told", e);
				return null;
			}
		}
		return packageNameOf(spec.trim());
	}

	/**
	 * Extracts the package name from a package spec or {@code allowBuilds} key.
	 *
	 * @param spec the specification or key
	 * @return the package name
	 */
	static String packageNameOf(String spec) {
		int at = spec.indexOf('@', 1);
		return at < 0 ? spec : spec.substring(0, at);
	}

	private static @Nullable String explainIgnoredBuilds(List<String> output, Path home, String profile) {
		String text = String.join("\n", output);
		if (!text.contains("ERR_PNPM_IGNORED_BUILDS") && !text.contains("approve-builds")) {
			return null;
		}

		String packages = "one or more dependencies";
		for (String line : output) {
			int marker = line.indexOf("Ignored build scripts:");
			if (marker >= 0) {
				packages = line.substring(marker + "Ignored build scripts:".length()).trim();
				break;
			}
		}

		Path profileDirectory = home.resolve("profiles").resolve(profile);
		return "The plugin was added, but its build script was not run.\n\n" + "pnpm ignored the build script for " +
		       packages + " because the profile still carries the\n" +
		       "template placeholder in its pnpm-workspace.yaml. Some features may not work until this\n" +
		       "is resolved.\n\n" + "To resolve it, run in " + profileDirectory + ":\n" + "    pnpm approve-builds\n" +
		       "or set allowBuilds for that package to true in\n" + "    " +
		       profileDirectory.resolve("pnpm-workspace.yaml");
	}

	/**
	 * Extracts {@code allowBuilds} keys from git dependency build error output.
	 *
	 * @param output the command output lines
	 * @return the list of required build keys
	 */
	static List<String> gitBuildKeys(List<String> output) {
		if (!String.join("\n", output).contains("ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED")) {
			return List.of();
		}

		List<String> keys = new ArrayList<>();
		boolean inBlock = false;
		for (String line : output) {
			if (!inBlock) {
				inBlock = line.trim().equals("allowBuilds:");
				continue;
			}
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				// A blank line ends the block; one before the first entry is just spacing.
				if (!keys.isEmpty()) {
					break;
				}
				continue;
			}
			int colon = trimmed.lastIndexOf(": ");
			if (colon <= 0) {
				break;
			}
			String value = trimmed.substring(colon + 2).trim();
			if (!value.equals("true") && !value.equals("false")) {
				break;
			}
			keys.add(trimmed.substring(0, colon).trim());
		}
		return List.copyOf(keys);
	}

	private static @Nullable String explainGitBuild(List<String> keys, Path home, String profile) {
		if (keys.isEmpty()) {
			return null;
		}
		Path workspace = home.resolve("profiles").resolve(profile).resolve("pnpm-workspace.yaml");
		StringBuilder entries = new StringBuilder("    allowBuilds:\n");
		for (String key : keys) {
			entries.append("      ").append(key).append(": true\n");
		}
		return "This plugin comes from a repository, and a repository-hosted plugin is built when it\n" +
		       "is installed — there is no published build to fall back on, so the build has to be\n" +
		       "allowed for the installation to finish.\n\n" + "pnpm asked for these entries in " + workspace + ":\n" +
		       entries + "\nAllow this instance's build scripts (Settings → 构建脚本) and install again.";
	}

	private static String tail(List<String> lines) {
		int from = Math.max(0, lines.size() - 12);
		return String.join("\n", lines.subList(from, lines.size()));
	}

	private static void report(@Nullable Consumer<String> onStage, String message) {
		if (onStage != null) {
			onStage.accept(message);
		}
	}

	/**
	 * Thrown when an installation is waiting for somebody to allow its scripts.
	 */
	public static final class DshBuildScriptApprovalRequired extends DshException {
		private final transient List<String> packages;

		DshBuildScriptApprovalRequired(List<String> packages) {
			super("The plugin needs permission to run its install scripts: " + String.join(", ", packages));
			this.packages = List.copyOf(packages);
		}

		/**
		 * Returns the packages whose install scripts are waiting.
		 *
		 * @return the package names
		 */
		public List<String> packages() {
			return packages;
		}
	}
}

