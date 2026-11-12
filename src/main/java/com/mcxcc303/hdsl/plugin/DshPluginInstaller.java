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
import com.mcxcc303.hdsl.harness.DshPreset;
import com.mcxcc303.hdsl.harness.HarnessInstaller;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.launch.DshEnvironment;
import com.mcxcc303.hdsl.launch.DshLauncher;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import com.mcxcc303.hdsl.runtime.DshPnpm;
import com.mcxcc303.hdsl.runtime.DshToolchain;
import org.jackhuang.hmcl.util.i18n.I18n;
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
 * <p>
 * {@code dsh plugin} runs pnpm inside the profile and reconciles the bundles afterward. The lock
 * handling and the failure modes belong to upstreams.
 * <p>
 * Every call runs against the instance's own {@code DSH_HOME} and pinned {@code dsh}.
 */
@NotNullByDefault
public final class DshPluginInstaller {
	// Records the refusals already raised in the running installation.
	private static final Set<String> ANSWERED_ABOUT = ConcurrentHashMap.newKeySet();

	private DshPluginInstaller() {}

	/**
	 * Starts a new installation and clears the refusals an earlier installation recorded.
	 * <p>
	 * Called once per installation.
	 */
	public static void beginInstallation() {
		ANSWERED_ABOUT.clear();
	}

	/**
	 * Records the {@code allowBuilds} keys last put to the interface.
	 *
	 * @param keys the keys, in the form of {@link DshBuildScriptApprovalRequired#packages}
	 */
	public static void answeredAbout(List<String> keys) {
		ANSWERED_ABOUT.addAll(keys);
	}

	/**
	 * Installs every given preset into an instance's profile.
	 *
	 * @param instance the instance holding the modified profile
	 * @param presets  the presets to install
	 * @param onLine   receives every output line, or {@code null}
	 * @throws DshException when the runtime or version is unavailable, {@code pnpm}
	 *                      is missing, or an installation fails
	 */
	public static void install(DshInstance instance, List<DshPreset> presets, @Nullable Consumer<String> onLine)
	throws DshException {
		installSpecs(instance, presets.stream().map(DshPreset::spec).toList(), onLine);
	}

	/**
	 * Installs explicit package specs into an instance's profile.
	 * <p>
	 * A spec may pin a version, as {@code name@1.2.3}.
	 *
	 * @param instance the instance holding the modified profile
	 * @param specs    the package specs to install
	 * @param onLine   receives every output line, or {@code null}
	 * @throws DshException when the runtime or version is unavailable, {@code pnpm}
	 *                      is missing, or an installation fails
	 */
	public static void installSpecs(DshInstance instance, List<String> specs, @Nullable Consumer<String> onLine)
	throws DshException {
		if (specs.isEmpty()) return;

		DshNodeRuntime runtime = DshLauncher.resolveRuntime(instance);
		DshPnpm pnpm = DshToolchain.pnpm(runtime.node());

		Path home = instance.homeDirectory();
		for (String spec : specs) {
			report(onLine, "Installing " + spec + " ...");
			runPluginCommand(instance, runtime, pnpm, home, List.of("add", spec), List.of(spec), onLine);
			report(onLine, "Installed " + spec);
		}
	}

	/**
	 * Resolves an instance's profile from the manifest in the profile directory.
	 * <p>
	 * Forwards {@code install} to pnpm inside the profile directory. The profile is created when
	 * missing, and the packages listed in the manifest are installed.
	 * <p>
	 * Reconciliation keeps the entries already in the manifest and appends only what is missing.
	 * To restore a pack's plugin list, write the list and run this once.
	 *
	 * @param instance the instance holding the resolved profile
	 * @param onLine   receives every output line, or {@code null}
	 * @throws DshException when pnpm is missing or the resolve fails
	 */
	public static void resolve(DshInstance instance, @Nullable Consumer<String> onLine) throws DshException {
		DshNodeRuntime runtime = DshLauncher.resolveRuntime(instance);
		DshPnpm pnpm = DshToolchain.pnpm(runtime.node());
		runPluginCommand(instance, runtime, pnpm, instance.homeDirectory(), List.of("install"), List.of(), onLine);
	}

	/**
	 * Removes a package from an instance's profile.
	 *
	 * @param instance the instance holding the modified profile
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
	 * @param instance the instance holding the modified profile
	 * @param specs    the package specs to remove
	 * @param onLine   receives every output line, or {@code null}
	 * @throws DshException when the removal fails
	 */
	public static void removeSpecs(DshInstance instance, List<String> specs, @Nullable Consumer<String> onLine)
	throws DshException {
		if (specs.isEmpty()) return;

		DshNodeRuntime runtime = DshLauncher.resolveRuntime(instance);
		DshPnpm pnpm = DshToolchain.pnpm(runtime.node());

		report(onLine, "Removing " + String.join(", ", specs) + " ...");
		List<String> command = new ArrayList<>();
		command.add("remove");
		command.addAll(specs);
		runPluginCommand(instance, runtime, pnpm, instance.homeDirectory(), command, List.of(), onLine);
		report(onLine, "Removed " + specs.size() + " package(s)");
	}

	/**
	 * Reads the dependencies declared by a profile.
	 *
	 * @param home    the {@code DSH_HOME} holding the profile
	 * @param profile the profile name
	 * @return the dependency name to version-range map, in manifest order
	 */
	public static Map<String, String> readDependencies(Path home, String profile) {
		Path manifest = home.resolve("profiles").resolve(profile).resolve("package.json");
		if (!Files.isRegularFile(manifest)) return Map.of();

		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(manifest));
			if (!parsed.isJsonObject()) return Map.of();

			JsonObject dependencies = parsed.getAsJsonObject().getAsJsonObject("dependencies");
			if (dependencies == null) return Map.of();

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
	 * Reads the bundle list declared by a profile.
	 *
	 * @param home    the {@code DSH_HOME} holding the profile
	 * @param profile the profile name
	 * @return the declared bundle package names, in load order
	 */
	public static List<String> readBundles(Path home, String profile) {
		Path manifest = home.resolve("profiles").resolve(profile).resolve("package.json");
		if (!Files.isRegularFile(manifest)) return List.of();

		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(manifest));
			if (!parsed.isJsonObject()) return List.of();

			JsonObject dsh = parsed.getAsJsonObject().getAsJsonObject("dsh");
			if (dsh == null) return List.of();

			JsonObject profileObject = dsh.getAsJsonObject("profile");
			if (profileObject == null) return List.of();

			JsonArray bundles = profileObject.getAsJsonArray("bundles");
			if (bundles == null) return List.of();

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

	/**
	 * Runs one {@code dsh plugin} invocation against an instance.
	 *
	 * @param instance the instance
	 * @param runtime  the resolved Node runtime
	 * @param home     the {@code DSH_HOME} to operate on
	 * @param args     the pnpm arguments to forward
	 * @param packages the package specs to be installed in this run.
	 * @param onLine   receives every output line, or {@code null}
	 * @throws DshException when the command cannot be run or exits non-zero
	 */
	private static void runPluginCommand(DshInstance instance,
	                                     DshNodeRuntime runtime,
	                                     DshPnpm pnpm,
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

		// The log names the instance, the DSH_HOME, and the command.
		LOG.info("Installing into instance " + instance.id() + " (home " + home + ", profile " + instance.profile() +
		         "): " + String.join(" ", command));

		// DSH_HOME travels in the child's environment.
		Map<String, String> environment = new LinkedHashMap<>();
		environment.put("DSH_HOME", home.toString());
		environment.putAll(DshToolchain.pathEnvironment(runtime.node(), pnpm));
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
					List<String> named = gitBuildKeys(result.output());
					boolean placeholder = explainIgnoredBuilds(result.output(), home, instance.profile()) != null;
					if (!named.isEmpty() && DshBuildScriptPolicy.of(instance) == DshBuildScriptPolicy.NEVER) {
						// A repository-hosted package has no published build to fall back on
						throw new DshException(explainGitBuild(named));
					}
					if (!named.isEmpty()) {
						DshBuildScripts.propose(instance, named);
					}
					if (!named.isEmpty() || placeholder) {
						List<String> waiting = DshBuildScripts.unanswered(instance);
						DshBuildScriptPolicy policy = DshBuildScriptPolicy.of(instance);
						if (!waiting.isEmpty() && policy == DshBuildScriptPolicy.AUTO) {
							// after allowed the scripts will run again
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
						explanation = explainGitBuild(gitBuildKeys(result.output()));
					}
					if (explanation != null) {
						throw new DshException(explanation);
					}
					throw new DshException(
							"dsh plugin " + String.join(" ", args) + " exited with code " + exitCode + ":\n" +
							tail(result.output()));
				}
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
				throw new DshException("Failed to run dsh plugin " + String.join(" ", args), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new DshException("dsh plugin " + String.join(" ", args) + " was interrupted", e);
			}
			return;
		}
	}

	/**
	 * For the pending entries, the policy decides:
	 * {@code MANUAL} throws {@link DshBuildScriptApprovalRequired} and let user decide.
	 * Others write a decision and return {@code true};
	 *
	 * @param instance the instance holding the profile
	 * @param packages the package specs this run installs; empty means nothing is installed
	 * @param onLine   receives the decision text, or {@code null}
	 * @return whether a decision was written, so the command should run again
	 * @throws DshException                   if the profile cannot be read or written
	 * @throws DshBuildScriptApprovalRequired if the interface must ask a person
	 */
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
				// The scripts are allowed, and the command runs again.
				report(onLine, "Allowing install scripts for " + String.join(", ", waiting));
				DshBuildScripts.answer(instance, waiting, true);
				return true;
			}
			case NEVER -> {
				// The packages are installed and the scripts are not run.
				report(onLine, "Not running install scripts for " + String.join(", ", waiting));
				DshBuildScripts.answer(instance, waiting, false);
				return true;
			}
			default -> {
				throw new DshBuildScriptApprovalRequired(waiting);
			}
		}
	}

	/**
	 * Returns the refused entries among the given package specs, matched by package name.
	 *
	 * @param instance the instance
	 * @param packages the package specs this run is about
	 * @return the refused keys, in profile order
	 */
	private static List<String> refusedAmong(DshInstance instance, List<String> packages) throws DshException {
		if (packages.isEmpty()) return List.of();

		Path profileDirectory = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
		List<String> names = packages.stream()
		                             .map(spec -> nameOf(spec, profileDirectory))
		                             .filter(Objects::nonNull)
		                             .toList();
		return DshBuildScripts.refused(instance).stream().filter(key -> names.contains(packageNameOf(key))).toList();
	}

	/**
	 * Returns the package name of a spec.
	 * <p>
	 * If the spec points to an existing file, the name is read from that file.
	 * Otherwise, the name is parsed from the spec string.
	 *
	 * @param spec             a name, a name and a version, or a location
	 * @param profileDirectory the profile's directory, used to resolve a relative location
	 * @return the package name, or {@code null} if the file cannot be read
	 */
	private static @Nullable String nameOf(String spec, Path profileDirectory) {
		Path file = DshPluginBundle.fileOf(spec, profileDirectory);
		// fileOf also returns a missing path, so the file system has to be checked.
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
	 * Returns the package name from a spec or an {@code allowBuilds} key.
	 * <p>
	 * For example {@code @scope/pkg@1.2.3} yields {@code @scope/pkg}.
	 *
	 * @param spec a package spec or an {@code allowBuilds} key
	 * @return the package name
	 */
	static String packageNameOf(String spec) {
		int at = spec.indexOf('@', 1);
		return at < 0 ? spec : spec.substring(0, at);
	}

	/**
	 * Returns the user-facing message for pnpm's ignored-build-script failure.
	 *
	 * @param output  the captured command output
	 * @param home    the instance's {@code DSH_HOME}
	 * @param profile the profile name
	 * @return the message, or {@code null} if the output is a different failure
	 */
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
		return I18n.i18n("dsh.settings.build_scripts.ignored", profileDirectory, packages);
	}

	/**
	 * Returns the {@code allowBuilds} entries that pnpm prints for a repository-hosted package.
	 * <p>
	 * A plugin fetched from a repository is built by its {@code prepare} script.
	 * pnpm runs the script only after the profile allows it. For this failure, pnpm
	 * prints the required {@code allowBuilds} block.
	 *
	 * @param output the captured command output
	 * @return the entries, or an empty list if the output is a different failure
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
				// A blank line ends the block. One before the first entry is just spacing.
				if (!keys.isEmpty()) break;
				continue;
			}
			int colon = trimmed.lastIndexOf(": ");
			if (colon <= 0) break;

			String value = trimmed.substring(colon + 2).trim();
			if (!value.equals("true") && !value.equals("false")) break;

			keys.add(trimmed.substring(0, colon).trim());
		}
		return List.copyOf(keys);
	}

	/**
	 * Explains why pnpm will not build a repository-hosted plugin, and how to fix it.
	 *
	 * @param keys the {@code allowBuilds} entries pnpm asked for
	 * @return the explanation, or {@code null} if {@code keys} is empty
	 */
	private static @Nullable String explainGitBuild(List<String> keys) {
		if (keys.isEmpty()) return null;
		return I18n.i18n("dsh.settings.build_scripts.repository", String.join(", ", keys));
	}

	/**
	 * Returns the last few output lines, for an error message.
	 *
	 * @param lines the captured output
	 * @return the trailing lines joined by newlines
	 */
	private static String tail(List<String> lines) {
		int from = Math.max(0, lines.size() - 12);
		return String.join("\n", lines.subList(from, lines.size()));
	}

	/**
	 * Forwards a progress message when a consumer is attached.
	 *
	 * @param onLine  the consumer, or {@code null}
	 * @param message the message
	 */
	private static void report(@Nullable Consumer<String> onLine, String message) {
		if (onLine != null) {
			onLine.accept(message);
		}
	}

	/**
	 * Thrown when the scripts of an installation need a person's permission.
	 * <p>
	 * The installer leaves the decision to a person. It reports the pending packages, and the
	 * interface starting the installation raises the question.
	 */
	public static final class DshBuildScriptApprovalRequired extends DshException {
		private final transient List<String> packages;

		DshBuildScriptApprovalRequired(List<String> packages) {
			super("The plugin needs permission to run its install scripts: " + String.join(", ", packages));
			this.packages = List.copyOf(packages);
		}

		/**
		 * Returns the packages needing permission for the install scripts.
		 *
		 * @return the package names
		 */
		public List<String> packages() {
			return packages;
		}
	}
}
