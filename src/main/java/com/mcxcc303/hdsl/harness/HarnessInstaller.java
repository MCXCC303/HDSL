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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshFiles;
import com.mcxcc303.hdsl.core.Semver;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.InstanceLocks;
import com.mcxcc303.hdsl.plugin.DshDependencyPolicy;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Installs versions of the DeepSeek Harness into an instance.
 */
@NotNullByDefault
public final class HarnessInstaller {
	/**
	 * The npm package name of the DeepSeek Harness CLI.
	 */
	public static final String PACKAGE_NAME = "@deepseek-ai/dsh";

	private static final String RESERVED_FIRST = "-?:,[]{}#&*!|>'\"%@`";

	/**
	 * The oldest pnpm that can install DeepSeek Harness
	 */
	private static final Semver MINIMUM_PNPM_VERSION = Objects.requireNonNull(Semver.parse(DshNodeRuntime.MINIMUM_PNPM));

	private HarnessInstaller() {
	}

	/**
	 * Whether the instance's own DeepSeek Harness is in place: its entry script is there and no install
	 * is outstanding.
	 */
	public static boolean isInstalled(DshInstance instance) {
		try {
			return !isInstalling(instance) && Files.isRegularFile(instance.dshEntryPoint());
		} catch (DshException e) {
			return false;
		}
	}

	/**
	 * Whether an install into the instance is outstanding.
	 */
	public static boolean isInstalling(DshInstance instance) {
		try {
			return Files.exists(pendingMarker(instance), LinkOption.NOFOLLOW_LINKS);
		} catch (DshException e) {
			return false;
		}
	}

	/**
	 * Writes the manifest npm installs from.
	 *
	 * @param prefix  the directory the version is installed into
	 * @param version the DeepSeek Harness version
	 * @param appBoot the application boot library version to hold it to
	 * @param runtime the detected Node runtime, or {@code null}
	 * @param policy  the dependency holding policy
	 * @throws DshException when the manifest cannot be written
	 */
	static void writeManifest(Path prefix,
	                          String version,
	                          String appBoot,
	                          @Nullable DshNodeRuntime runtime,
	                          DshDependencyPolicy policy) throws DshException {
		JsonObject dependencies = new JsonObject();
		// Pin the launcher and the boot library to the same version.
		dependencies.addProperty(PACKAGE_NAME, version);

		// Combine overrides once to prevent duplicate keys in pnpm workspace.
		Map<String, String> overrides = mergeOverrides(appBoot, heldDependencies(runtime, version, appBoot, policy));

		JsonObject manifest = new JsonObject();
		manifest.addProperty("private", true);
		manifest.add("dependencies", dependencies);

		// pnpm 10 reads overrides from pnpm-workspace.yaml rather than package.json.
		StringBuilder workspace = new StringBuilder("overrides:\n");
		overrides.forEach((name, held) -> workspace.append("  ")
		                                           .append(yamlScalar(name))
		                                           .append(": ")
		                                           .append(yamlScalar(held))
		                                           .append('\n'));

		try {
			Files.createDirectories(prefix);
			var gson = new GsonBuilder().setPrettyPrinting().create();
			Files.writeString(prefix.resolve("package.json"), gson.toJson(manifest));
			Files.writeString(prefix.resolve("pnpm-workspace.yaml"), workspace.toString());
		} catch (IOException e) {
			throw new DshException("Failed to write the manifest for " + version, e);
		}
	}

	/**
	 * Combines the boot library's pin with the ones the policy holds.
	 *
	 * @param appBoot the boot library version the caller asked for
	 * @param held    the versions the policy holds
	 * @return the overrides, boot library first
	 */
	static Map<String, String> mergeOverrides(String appBoot, Map<String, String> held) {
		Map<String, String> overrides = new LinkedHashMap<>();
		overrides.put(AppBootPin.APP_BOOT_PACKAGE, appBoot);
		held.forEach(overrides::putIfAbsent);
		return overrides;
	}

	/**
	 * Quotes a scalar value for YAML output if required.
	 *
	 * @param value the raw scalar string
	 * @return the formatted YAML scalar
	 */
	static String yamlScalar(String value) {
		boolean plain = !value.isEmpty() && RESERVED_FIRST.indexOf(value.charAt(0)) < 0 && value.equals(value.trim()) &&
		                !value.contains(": ") && !value.contains(" #") && value.indexOf('\n') < 0;
		return plain ? value : "'" + value.replace("'", "''") + "'";
	}

	/**
	 * Returns the dependencies to hold to the versions declared by the harness and boot library.
	 *
	 * @param runtime the runtime whose npm reads the registry
	 * @param version the harness version
	 * @param appBoot the boot library version
	 * @param policy  how much of the tree to hold
	 * @return the name-to-version overrides
	 */
	static Map<String, String> heldDependencies(@Nullable DshNodeRuntime runtime,
	                                            String version,
	                                            String appBoot,
	                                            DshDependencyPolicy policy) {
		Map<String, String> held = new LinkedHashMap<>();
		// No runtime means no npm to query registry declarations.
		if (policy == DshDependencyPolicy.LATEST || runtime == null) {
			return held;
		}
		for (String asked : List.of(PACKAGE_NAME + "@" + version, AppBootPin.APP_BOOT_PACKAGE + "@" + appBoot)) {
			try {
				JsonObject declared = declaredDependencies(ReleaseQueries.runNpmView(runtime.npm(),
				                                                                     asked,
				                                                                     "dependencies").text());
				if (declared == null) {
					continue;
				}
				for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
					String name = entry.getKey();
					// First declaration wins; boot library is queried only for what the harness omitted.
					if (!policy.pins(name) || held.containsKey(name) || !entry.getValue().isJsonPrimitive()) {
						continue;
					}
					String floor = floorOf(entry.getValue().getAsString());
					if (floor != null) {
						held.put(name, floor);
					}
				}
			} catch (DshException | RuntimeException e) {
				LOG.warning(
						"Could not read what " + asked + " declares; leaving its dependencies " + "to resolve freely",
						e);
			}
		}
		return held;
	}

	/**
	 * Extracts the dependencies object from npm's output JSON.
	 *
	 * @param json npm's answer text
	 * @return the dependencies object, or {@code null} if none could be read
	 */
	static @Nullable JsonObject declaredDependencies(@Nullable String json) {
		JsonElement parsed = ReleaseQueries.parseJson(json);
		if (parsed == null || parsed.isJsonNull()) {
			return null;
		}
		if (parsed.isJsonObject()) {
			return parsed.getAsJsonObject();
		}
		if (parsed.isJsonArray()) {
			for (JsonElement element : parsed.getAsJsonArray()) {
				if (element.isJsonObject()) {
					return element.getAsJsonObject();
				}
			}
		}
		return null;
	}

	/**
	 * Reads the floor version out of a declared semver range.
	 *
	 * @param range the declared range
	 * @return the floor version, or {@code null}
	 */
	static @Nullable String floorOf(@Nullable String range) {
		String text = range == null ? "" : range.trim();
		for (String prefix : List.of("^", "~", ">=")) {
			if (text.startsWith(prefix)) {
				text = text.substring(prefix.length()).trim();
				break;
			}
		}
		return text.matches("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?") ? text : null;
	}

	/**
	 * The marker whose presence means an install into the instance is outstanding. It sits beside the
	 * harness, so an install that deletes the tree does not delete it.
	 *
	 * @throws DshException when the instance directory cannot be resolved
	 */
	static Path pendingMarker(DshInstance instance) throws DshException {
		return instance.instanceDirectory().resolve(".dsh-installing");
	}

	private static void markInstalling(DshInstance instance) throws DshException {
		Path marker = pendingMarker(instance);
		try {
			Files.createDirectories(marker.getParent());
			Files.writeString(marker, "");
		} catch (IOException e) {
			throw new DshException("Failed to prepare " + marker, e);
		}
		LOG.info("Installing into " + instance.id() + " (marker " + marker + ")");
	}

	private static void clearInstalling(DshInstance instance) {
		try {
			Files.deleteIfExists(pendingMarker(instance));
		} catch (IOException | DshException e) {
			// A marker that cannot be removed costs a reinstall; it does not endanger the tree.
			LOG.warning("Could not remove the install marker of " + instance.id(), e);
		}
	}

	/**
	 * Removes the partial harness left by an interrupted install.
	 */
	public static void discardPartial(DshInstance instance) {
		// Only call this after a failed install, or before deleting the instance.
		try {
			InstanceLocks.withLock(instance, "cleared", () -> {
				try {
					boolean installing = isInstalling(instance);
					Path target = instance.dshDirectory();

					// Delete the tree before clearing the marker
					if (installing && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
						DshFiles.deleteTree(target);
						LOG.info("Removed the partial install for " + instance.id());
					}
					clearInstalling(instance);
				} catch (IOException | DshException | RuntimeException e) {
					LOG.warning("Could not remove the partial install for " + instance.id(), e);
				}
			});
		} catch (DshException e) {
			LOG.warning("Could not lock the instance to remove its partial install: " + instance.id(), e);
		}
	}

	/**
	 * The reason an instance cannot run, or {@code null} when it can.
	 *
	 * @throws DshException when the instance directory cannot be resolved
	 */
	public static @Nullable String incompleteReason(DshInstance instance) throws DshException {
		Path script = instance.dshEntryPoint();
		if (!isInstalling(instance) && Files.isRegularFile(script)) {
			return null;
		}

		Path harness = instance.dshDirectory();
		if (!Files.isDirectory(harness, LinkOption.NOFOLLOW_LINKS)) {
			return "Instance " + instance.id() + " has no DeepSeek Harness of its own; nothing is installed in " +
			       harness;
		}
		if (isInstalling(instance)) {
			return "Instance " + instance.id() + " has an unfinished DeepSeek Harness installation; " +
			       pendingMarker(instance) + " is still there. Reinstall it to finish the job.";
		}

		// A link inside a finished tree can name a directory that is gone. A junction answers
		// false to Files.isSymbolicLink, so use DshFiles' reparse-point check.
		Path packageLink = harness.resolve(DshVersion.PACKAGE_PATH);
		if (DshFiles.isReparsePoint(packageLink) && Files.exists(packageLink, LinkOption.NOFOLLOW_LINKS) &&
		    !Files.exists(packageLink)) {
			return "The DeepSeek Harness of instance " + instance.id() + " is broken: " + packageLink +
			       " is a link to a directory that no longer exists (this is what a package manager " +
			       "link looks like after the folder it names was renamed). Reinstall it.";
		}
		return "Instance " + instance.id() + " has no DeepSeek Harness of its own; " + script + " is missing";
	}

	/**
	 * Requires that the instance has a DeepSeek Harness to run.
	 *
	 * @throws DshException when it has none, with {@link #incompleteReason} as the message
	 */
	public static void requireInstalled(DshInstance instance) throws DshException {
		String reason = incompleteReason(instance);
		if (reason != null) {
			throw new DshException(reason);
		}
	}

	/**
	 * Reports whether a pnpm version is known to be below {@link DshNodeRuntime#MINIMUM_PNPM}
	 * <p>
	 * A null or unreadable version is not old; only a version that can be read is refused.
	 *
	 * @param version the reported pnpm version, or null
	 * @return whether the version is known to be too old
	 */
	static boolean isPnpmTooOld(@Nullable String version) {
		if (version == null) {
			return false;
		}
		Semver parsed = Semver.parse(version);
		return parsed != null && parsed.compareTo(MINIMUM_PNPM_VERSION) < 0;
	}

	/**
	 * Refuses a pnpm older than {@link DshNodeRuntime#MINIMUM_PNPM}
	 *
	 * @param runtime the runtime to install with
	 * @throws DshException when its pnpm is too old
	 */
	static void requireSupportedPnpm(DshNodeRuntime runtime) throws DshException {
		if (isPnpmTooOld(runtime.pnpmVersion())) {
			throw new DshException("pnpm " + runtime.pnpmVersion() +
			                       " is too old; installing DeepSeek Harness requires pnpm " +
			                       DshNodeRuntime.MINIMUM_PNPM + " or newer");
		}
	}

	/**
	 * The pnpm install command for a project
	 *
	 * @param runtime the runtime whose pnpm runs the install; its pnpm must not be null
	 * @param project the project directory, whose manifest must already be written
	 * @return the command
	 */
	static List<String> buildInstallCommand(DshNodeRuntime runtime, Path project) {
		// Use append-only reporter for progress parsing and allow build scripts.
		List<String> command = new ArrayList<>(List.of(runtime.pnpm().toString(),
		                                               "install",
		                                               "--dir",
		                                               project.toString(),
		                                               "--reporter=append-only",
		                                               "--config.dangerously-allow-all-builds=true"));
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			// Windows uses hoisted linker to avoid fragile directory junctions.
			command.add("--config.node-linker=hoisted");
		}
		return List.copyOf(command);
	}

	/**
	 * Installs the version the instance records
	 *
	 * <p>Equivalent to {@code install(instance, instance.version(), onLine)}.
	 *
	 * @param instance the instance to install into
	 * @param onLine   receives pnpm's output lines, or {@code null}
	 * @throws DshException when the install cannot run or fails, as documented on
	 *                      {@link #install(DshInstance, String, Consumer)}
	 */
	public static void install(DshInstance instance, @Nullable Consumer<String> onLine) throws DshException {
		install(instance, instance.version(), onLine);
	}

	/**
	 * Where a failed install keeps the tree it replaced.
	 *
	 * @throws DshException when the instance directory cannot be resolved
	 */
	static Path setAsideDirectory(DshInstance instance) throws DshException {
		return instance.instanceDirectory().resolve("dsh.previous");
	}

	/**
	 * The directory an instance's copy is staged in while it is installed. It sits inside the instance,
	 * so moving it into place is a rename within one filesystem.
	 *
	 * @throws DshException when the identifier is not usable as a path segment
	 */
	static Path stagingDirectory(DshInstance instance) throws DshException {
		return instance.instanceDirectory().resolve("dsh.installing");
	}

	/**
	 * Installs a version into the instance without changing the version it records.
	 *
	 * <p>The install holds the instance lock. If pnpm fails, a tree that was already installed is
	 * kept; on Windows, one that cannot be moved back is left in the instance's
	 * {@code dsh.previous} directory for manual recovery.
	 *
	 * @param instance the instance to install into
	 * @param version  the DeepSeek Harness version
	 * @param onLine   receives pnpm's output lines, or {@code null}
	 * @throws DshException when Node.js or pnpm is missing, pnpm is older than
	 *                      {@link DshNodeRuntime#MINIMUM_PNPM}, the instance directory cannot be
	 *                      resolved, another operation holds the instance, or the install fails
	 */
	public static void install(DshInstance instance, String version, @Nullable Consumer<String> onLine)
	throws DshException {
		install(instance, version, onLine, null);
	}

	/**
	 * Installs with the runtime supplied, or detects one when {@code detected} is {@code null}.
	 *
	 * <p>Tests pass a runtime to drive the failure path without a registry or a real pnpm.
	 *
	 * @param instance the instance to install into
	 * @param version  the DeepSeek Harness version
	 * @param onLine   receives pnpm's output lines, or {@code null}
	 * @param detected the runtime to install with, or {@code null} to detect one
	 * @throws DshException when no runtime is found, its pnpm is missing or older than
	 *                      {@link DshNodeRuntime#MINIMUM_PNPM}, the instance directory cannot be
	 *                      resolved, another operation holds the instance, or the install fails
	 */
	static void install(DshInstance instance, String version, @Nullable Consumer<String> onLine,
	                    @Nullable DshNodeRuntime detected) throws DshException {
		Path target = instance.dshDirectory();
		// Acquire lock before checking runtime so a busy instance reports busy first.
		InstanceLocks.withLock(instance, "installed into", () -> {
			if (!Files.isDirectory(instance.instanceDirectory(), LinkOption.NOFOLLOW_LINKS)) {
				throw new DshException("Instance " + instance.id() + " no longer exists");
			}
			DshNodeRuntime runtime = detected != null ? detected : requireRuntime();
			if (!runtime.canManagePlugins()) {
				throw new DshException("pnpm was not found on PATH; installing DeepSeek Harness requires it");
			}
			requireSupportedPnpm(runtime);

			if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
				// Install directly in place on Windows to avoid breaking junction targets during rename.
				installInPlace(instance, version, runtime, target, onLine);
			} else {
				installStaged(instance, version, runtime, onLine);
			}
		});
	}

	private static void installStaged(DshInstance instance,
	                                  String version,
	                                  DshNodeRuntime runtime,
	                                  @Nullable Consumer<String> onLine) throws DshException {
		Path target = instance.dshDirectory();
		Path staging = stagingDirectory(instance);

		try {
			if (Files.exists(staging)) {
				DshFiles.deleteTree(staging);
			}
			Files.createDirectories(staging);
		} catch (IOException e) {
			throw new DshException("Failed to prepare " + staging, e);
		}

		writeManifest(staging, version, version, runtime, SettingsManager.settings().dependencyPolicy());

		List<String> command = buildInstallCommand(runtime, staging);

		LOG.info("Installing DSH " + version + " for " + instance.id() + ": " + String.join(" ", command));

		int exitCode;
		DshCommand.Result commandResult;
		try {
			commandResult = DshCommand.run(command, null, onLine);
			exitCode = commandResult.exitCode();
		} catch (IOException e) {
			throw new DshException("Failed to run pnpm", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DshException("Install was interrupted", e);
		}

		if (exitCode != 0) {
			deleteQuietly(staging);
			throw new DshException(
					"pnpm exited with code " + exitCode + " while installing " + version + " for " + instance.id() +
					":\n" + ReleaseQueries.tail(commandResult.output()));
		}

		Path bin = staging.resolve(DshVersion.PACKAGE_PATH).resolve("lib/bin.js");
		if (!Files.isRegularFile(bin)) {
			deleteQuietly(staging);
			throw new DshException("pnpm reported success but " + bin + " is missing");
		}

		try {
			if (Files.exists(target)) {
				DshFiles.deleteTree(target);
			}
			Files.createDirectories(target.getParent());
			Files.move(staging, target);
		} catch (IOException e) {
			deleteQuietly(staging);
			throw new DshException("Failed to move the staged install into " + target, e);
		}

		// A marker can arrive with an instance copied from Windows; clear it after a successful staged installation.
		clearInstalling(instance);
		LOG.info("Installed DSH " + version + " for " + instance.id() + " into " + target);
	}

	private static void installInPlace(DshInstance instance,
	                                   String version,
	                                   DshNodeRuntime runtime,
	                                   Path target,
	                                   @Nullable Consumer<String> onLine) throws DshException {
		Path aside = setAsideDirectory(instance);

		// An existing marker means target is unfinished but may still be the only copy; keep it for recovery.
		boolean wasInstalling = isInstalling(instance);
		markInstalling(instance);

		List<Path> recoveries = new ArrayList<>();
		boolean hadPrevious = false;

		try {
			if (wasInstalling) {
				if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
					Path recovered = instance.instanceDirectory().resolve("dsh.previous." + System.nanoTime());
					Files.move(target, recovered);
					recoveries.add(recovered);
				}
			} else {
				DshFiles.deleteTreeQuietly(aside);
				if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
					// Move rather than delete to keep a rollback copy.
					Files.move(target, aside);
					hadPrevious = true;
				}
			}
		} catch (IOException | RuntimeException e) { // not finished, keep the pre-existing marker
			if (!wasInstalling) clearInstalling(instance);
			throw new DshException("Failed to prepare " + target + " for install", e);
		}

		try {
			Files.createDirectories(target);
			writeManifest(target, version, version, runtime, SettingsManager.settings().dependencyPolicy());
			List<String> command = buildInstallCommand(runtime, target);

			LOG.info("Installing DSH " + version + " for " + instance.id() + ": " + String.join(" ", command));

			int exitCode;
			DshCommand.Result commandResult;

			try {
				commandResult = DshCommand.run(command, null, onLine);
				exitCode = commandResult.exitCode();
			} catch (IOException e) {
				throw new DshException("Failed to run pnpm", e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new DshException("Install was interrupted", e);
			}

			if (exitCode != 0) {
				throw new DshException(
						"pnpm exited with code " + exitCode + " while installing " + version + " for " + instance.id() +
						":\n" + ReleaseQueries.tail(commandResult.output()));
			}

			Path bin = target.resolve(DshVersion.PACKAGE_PATH).resolve("lib/bin.js");
			if (!Files.isRegularFile(bin)) {
				throw new DshException("pnpm reported success but " + bin + " is missing");
			}
		} catch (DshException | IOException | RuntimeException e) {
			DshException failure = e instanceof DshException dsh
			                       ? dsh
			                       : new DshException(
					                       "Failed to install " + version + " for " + instance.id() + ": " + e, e);
			if (wasInstalling) {
				try {
					DshFiles.deleteTreeQuietly(target);
				} catch (RuntimeException cleanupFailure) {
					LOG.warning("Could not remove the failed install in " + target, cleanupFailure);
				}

				List<String> kept = new ArrayList<>();
				if (Files.exists(aside, LinkOption.NOFOLLOW_LINKS)) {
					kept.add(aside.toString());
				}
				for (Path recovery : recoveries) {
					kept.add(recovery.toString());
				}
				if (!kept.isEmpty()) {
					failure = new DshException(failure.getMessage() + "\nThe unfinished harness was kept at " +
					                           String.join(", ", kept) + "; it is not reported as installed.",
					                           failure);
				}
				throw failure;
			}

			discardPartial(instance);
			if (hadPrevious && !restore(aside, target)) {
				failure = new DshException(failure.getMessage() + "\nThe DeepSeek Harness that was there has been " +
				                           "kept in " + aside + " and has to be moved back by hand", failure);
			}
			throw failure;
		}

		try {
			DshFiles.deleteTreeQuietly(aside);
			for (Path recovery : recoveries) {
				DshFiles.deleteTreeQuietly(recovery);
			}
		} catch (RuntimeException cleanupFailure) {
			LOG.warning("Could not remove the old harness copies of " + instance.id(), cleanupFailure);
		}
		clearInstalling(instance);
		LOG.info("Installed DSH " + version + " for " + instance.id() + " into " + target);
	}

	private static boolean restore(Path aside, Path target) {
		try {
			DshFiles.deleteTree(target);
			Files.move(aside, target);
			LOG.info("Put the DeepSeek Harness that was there back into " + target);
			return true;
		} catch (IOException | RuntimeException e) {
			LOG.error("Could not put the DeepSeek Harness in " + aside + " back into " + target, e);
			return false;
		}
	}

	/**
	 * Deletes a directory tree quietly, ignoring failures.
	 *
	 * @param directory the directory to remove
	 */
	static void deleteQuietly(Path directory) {
		DshFiles.deleteTreeQuietly(directory);
	}

	/**
	 * The Node runtime found on PATH.
	 *
	 * @throws DshException when no {@code node} was found
	 */
	public static DshNodeRuntime requireRuntime() throws DshException {
		return DshNodeRuntime.detect()
		                     .orElseThrow(() -> new DshException(
				                     "Node.js was not found on PATH; " + DshNodeRuntime.requirement()));
	}
}