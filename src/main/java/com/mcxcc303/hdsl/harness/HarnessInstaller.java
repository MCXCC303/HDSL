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
import com.mcxcc303.hdsl.runtime.DshPnpm;
import com.mcxcc303.hdsl.runtime.DshToolchain;
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
 * Installs a version of the harness into an instance.
 */
@NotNullByDefault
public final class HarnessInstaller {
	public static final String PACKAGE_NAME = "@deepseek-ai/dsh";
	// The characters YAML reserves at the start of a plain scalar.
	private static final String RESERVED_FIRST = "-?:,[]{}#&*!|>'\"%@`";
	private static final Semver MINIMUM_PNPM_VERSION = Objects.requireNonNull(Semver.parse(DshNodeRuntime.MINIMUM_PNPM));

	private HarnessInstaller() {}

	public static boolean isInstalled(DshInstance instance) {
		try {
			return !isInstalling(instance) && Files.isRegularFile(instance.dshEntryPoint());
		} catch (DshException e) {
			return false;
		}
	}

	public static boolean isInstalling(DshInstance instance) {
		try {
			return Files.exists(pendingMarker(instance), LinkOption.NOFOLLOW_LINKS);
		} catch (DshException e) {
			return false;
		}
	}

	/**
	 * Writes the {@code package.json} and the {@code pnpm-workspace.yaml} of an installation.
	 *
	 * @param prefix  the directory to install the version into
	 * @param version the DeepSeek Harness version
	 * @param appBoot the application boot library version to hold the harness to
	 * @throws DshException when the manifest cannot be written
	 */
	static void writeManifest(Path prefix,
	                          String version,
	                          String appBoot,
	                          @Nullable DshNodeRuntime runtime,
	                          DshDependencyPolicy policy) throws DshException {
		JsonObject dependencies = new JsonObject();
		// The harness version and the boot library version are written exactly, not as ranges.
		dependencies.addProperty(PACKAGE_NAME, version);

		// Both files are written from one map.
		Map<String, String> overrides = mergeOverrides(appBoot, heldDependencies(runtime, version, appBoot, policy));

		JsonObject manifest = new JsonObject();
		manifest.addProperty("private", true);
		manifest.add("dependencies", dependencies);

		// pnpm reads `overrides` from `pnpm-workspace.yaml`, not from `package.json`.
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
	 * Combines the boot library's pin with the policy's pins. The boot library's pin takes
	 * precedence.
	 *
	 * @param appBoot the boot library version the caller asked for
	 * @param held    the versions held by the policy
	 * @return the overrides, boot library first
	 */
	static Map<String, String> mergeOverrides(String appBoot, Map<String, String> held) {
		Map<String, String> overrides = new LinkedHashMap<>();
		overrides.put(AppBootPin.APP_BOOT_PACKAGE, appBoot);
		held.forEach(overrides::putIfAbsent);
		return overrides;
	}

	/**
	 * Returns a value YAML reads back as a string.
	 * <p>
	 * A value needing quotes is single-quoted, and a quote in the value is doubled.
	 *
	 * @param value the value
	 * @return it as a YAML scalar
	 */
	static String yamlScalar(String value) {
		boolean plain = !value.isEmpty() && RESERVED_FIRST.indexOf(value.charAt(0)) < 0 && value.equals(value.trim()) &&
		                !value.contains(": ") && !value.contains(" #") && value.indexOf('\n') < 0;
		return plain ? value : "'" + value.replace("'", "''") + "'";
	}

	/**
	 * Returns the dependencies to hold to the versions declared by the harness.
	 * <p>
	 * The floors are read from the published manifests through npm.
	 * <p>
	 * A failure here does not fail the installation. The dependency is left to resolve freely.
	 *
	 * @param runtime the Node.js installation npm reads the registry with
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
					// The harness's own declaration takes precedence over the boot library's.
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
	 * Reads the {@code dependencies} object out of npm's output.
	 * <p>
	 * A query for a version specifier produces an array, one entry per match, even when the
	 * specifier names a single version. A package with no dependencies produces a primitive.
	 *
	 * @param json npm's output
	 * @return the dependencies, or {@code null} when there are none to read
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
	 * Reads the floor out of a declared version range.
	 * <p>
	 * Only {@code 4.0.2}, {@code ^4.0.2}, {@code ~4.0.2} and {@code >=4.0.2} have a floor.
	 * Everything else returns {@code null}.
	 *
	 * @param range the declared range
	 * @return the version, or {@code null}
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
	 * The marker of an outstanding installation into the instance.
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
			// A marker that cannot be removed costs a reinstall.
			LOG.warning("Could not remove the install marker of " + instance.id(), e);
		}
	}

	/**
	 * Removes the partial harness of an interrupted installation.
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
	 * The reason an instance cannot run, or {@code null} when the instance can run.
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

		// A link inside a finished tree can name a missing directory.
		// A junction returns false from Files.isSymbolicLink.
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
	 * Requires the instance to have a DeepSeek Harness to run.
	 *
	 * @throws DshException when the instance has none, with {@link #incompleteReason} as the message
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
	 * A null or unreadable version is not old. Only a readable version can be below the
	 * minimum.
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
	 * @param pnpm the pnpm to install with
	 * @throws DshException when the pnpm is too old
	 */
	static void requireSupportedPnpm(DshPnpm pnpm) throws DshException {
		if (isPnpmTooOld(pnpm.version())) {
			throw new DshException(
					"pnpm " + pnpm.version() + " is too old; installing DeepSeek Harness requires pnpm " +
					DshNodeRuntime.MINIMUM_PNPM + " or newer");
		}
	}

	/**
	 * The pnpm install command for a project
	 *
	 * @param pnpm    the pnpm running the installation
	 * @param project the project directory, with the manifest already written
	 * @return the command
	 */
	static List<String> buildInstallCommand(DshPnpm pnpm, Path project) {
		// The launcher's progress parser reads the "Progress: resolved ..." tally line. Only the
		// append-only reporter prints the tally line.
		// pnpm does not run dependency build scripts by default. A package with build scripts
		// can fail on first use.
		List<String> arguments = new ArrayList<>(List.of("install",
		                                                 "--dir",
		                                                 project.toString(),
		                                                 "--reporter=append-only",
		                                                 "--config.dangerously-allow-all-builds=true"));
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			// Windows uses the hoisted linker and lays the packages out directly.
			arguments.add("--config.node-linker=hoisted");
		}
		return pnpm.commandFor(arguments);
	}

	/**
	 * Installs the version recorded by the instance
	 * <p>
	 * Equivalent to {@code install(instance, instance.version(), onLine)}.
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
	 * Where a failed install keeps the replaced tree.
	 *
	 * @throws DshException when the instance directory cannot be resolved
	 */
	static Path setAsideDirectory(DshInstance instance) throws DshException {
		return instance.instanceDirectory().resolve("dsh.previous");
	}

	/**
	 * The directory for staging an instance's copy during the installation. It sits inside the
	 * instance.
	 *
	 * @throws DshException when the identifier is not usable as a path segment
	 */
	static Path stagingDirectory(DshInstance instance) throws DshException {
		return instance.instanceDirectory().resolve("dsh.installing");
	}

	/**
	 * Installs a version into the instance without changing the version recorded by the instance.
	 * <p>
	 * The installation holds the instance lock. If pnpm fails, an already installed tree is kept.
	 * On Windows, an already installed tree stays in the instance's {@code dsh.previous}
	 * directory for manual recovery.
	 *
	 * @param instance the instance to install into
	 * @param version  the DeepSeek Harness version
	 * @param onLine   receives pnpm's output lines, or {@code null}
	 * @throws DshException when Node.js or pnpm is missing, pnpm is older than
	 *                      {@link DshNodeRuntime#MINIMUM_PNPM}, the instance directory cannot be
	 *                      resolved, another operation holds the instance, or the installation fails
	 */
	public static void install(DshInstance instance, String version, @Nullable Consumer<String> onLine)
	throws DshException {
		install(instance, version, onLine, null, null);
	}

	/**
	 * Installs with the runtime supplied, or detects a runtime when {@code detected} is
	 * {@code null}.
	 *
	 * <p>Tests pass a runtime through this overload.
	 *
	 * @param instance the instance to install into
	 * @param version  the DeepSeek Harness version
	 * @param onLine   receives pnpm's output lines, or {@code null}
	 * @param detected the runtime to install with, or {@code null} to detect a runtime
	 * @throws DshException when no runtime is found, or the pnpm of the runtime is missing or
	 *                      older than {@link DshNodeRuntime#MINIMUM_PNPM}, the instance directory
	 *                      cannot be resolved, another operation holds the instance, or the installation
	 *                      fails
	 */
	static void install(DshInstance instance,
	                    String version,
	                    @Nullable Consumer<String> onLine,
	                    @Nullable DshNodeRuntime detected,
	                    @Nullable DshPnpm detectedPnpm) throws DshException {
		Path target = instance.dshDirectory();
		// Take the lock before the runtime is looked at.
		InstanceLocks.withLock(instance, "installed into", () -> {
			if (!Files.isDirectory(instance.instanceDirectory(), LinkOption.NOFOLLOW_LINKS)) {
				throw new DshException("Instance " + instance.id() + " no longer exists");
			}
			// The runtime of the instance builds the tree, and the pnpm runs on the runtime.
			DshNodeRuntime runtime =
					detected != null ? detected : DshToolchain.resolve(instance.nodeRuntimeOrDefault());
			DshPnpm pnpm = detectedPnpm != null ? detectedPnpm : DshToolchain.pnpm(runtime.node());
			requireSupportedPnpm(pnpm);

			if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
				installInPlace(instance, version, runtime, pnpm, target, onLine);
			} else {
				installStaged(instance, version, runtime, pnpm, onLine);
			}
		});
	}

	private static void installStaged(DshInstance instance,
	                                  String version,
	                                  DshNodeRuntime runtime,
	                                  DshPnpm pnpm,
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

		List<String> command = buildInstallCommand(pnpm, staging);
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
			DshFiles.deleteTreeQuietly(staging);
			throw new DshException(
					"pnpm exited with code " + exitCode + " while installing " + version + " for " + instance.id() +
					":\n" + ReleaseQueries.tail(commandResult.output()));
		}

		Path bin = staging.resolve(DshVersion.PACKAGE_PATH).resolve("lib/bin.js");
		if (!Files.isRegularFile(bin)) {
			DshFiles.deleteTreeQuietly(staging);
			throw new DshException("pnpm reported success but " + bin + " is missing");
		}

		try {
			if (Files.exists(target)) DshFiles.deleteTree(target);
			Files.createDirectories(target.getParent());
			Files.move(staging, target);
		} catch (IOException e) {
			DshFiles.deleteTreeQuietly(staging);
			throw new DshException("Failed to move the staged install into " + target, e);
		}

		// A marker can arrive with an instance copied from Windows.
		clearInstalling(instance);
		LOG.info("Installed DSH " + version + " for " + instance.id() + " into " + target);
	}

	private static void installInPlace(DshInstance instance,
	                                   String version,
	                                   DshNodeRuntime runtime,
	                                   DshPnpm pnpm,
	                                   Path target,
	                                   @Nullable Consumer<String> onLine) throws DshException {
		Path aside = setAsideDirectory(instance);

		// An existing marker means the target is unfinished but may still be the only copy.
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
					// Moved rather than deleted.
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

			List<String> command = buildInstallCommand(pnpm, target);
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
					failure = new DshException(
							failure.getMessage() + "\nThe unfinished harness was kept at " + String.join(", ", kept) +
							"; it is not reported as installed.", failure);
				}
				throw failure;
			}

			discardPartial(instance);
			if (hadPrevious && !restore(aside, target)) {
				failure = new DshException(
						failure.getMessage() + "\nThe DeepSeek Harness that was there has been " + "kept in " + aside +
						" and has to be moved back by hand", failure);
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
	 * The Node runtime used by the launcher.
	 *
	 * @throws DshException when the selected runtime is not there
	 */
	public static DshNodeRuntime requireRuntime() throws DshException {
		return DshToolchain.node();
	}
}