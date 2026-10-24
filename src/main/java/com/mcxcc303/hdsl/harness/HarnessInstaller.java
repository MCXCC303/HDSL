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

/// Installing a version of the harness into an instance.
///
/// The manifest is written by hand, so every value that is not a plain YAML scalar is quoted; the
/// dependencies a package declares are held to the floors they name, which is what keeps a package
/// the launcher never chose from arriving underneath it.
@NotNullByDefault
public final class HarnessInstaller {
	/// The npm package name of the DeepSeek Harness CLI.
	public static final String PACKAGE_NAME = "@deepseek-ai/dsh";

	/// The characters YAML reserves at the start of a plain scalar.
	///
	/// A scalar beginning with one of these is an indicator to the reader rather than text, so
	/// anything starting with one has to be quoted.
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

	/// Writes the manifest npm installs from.
	///
	/// The launcher states its dependencies rather than letting npm pick them,
	/// so a version is reproducible and the libraries stay with the launcher
	/// that expects them.
	///
	/// @param prefix  the directory the version is installed into
	/// @param version the DeepSeek Harness version
	/// @param appBoot the application boot library version to hold it to
	/// @throws DshException when the manifest cannot be written
	static void writeManifest(Path prefix,
	                          String version,
	                          String appBoot,
	                          @Nullable DshNodeRuntime runtime,
	                          DshDependencyPolicy policy) throws DshException {
		JsonObject dependencies = new JsonObject();
		// A caret cannot express the lockstep: a range like ^0.1.6-alpha.1 resolves to alpha.2's
		// libraries, and a release whose code and libraries disagree fails at import. Pin the launcher
		// and the boot library to the same version.
		dependencies.addProperty(PACKAGE_NAME, version);

		// One map, and both files are written from it. Two computations — one per file — is how the
		// same key came to be written twice: the boot library is put in explicitly *and* it is one of
		// the vendor's packages, so the policy named it again and pnpm refused the file with
		// `duplicated mapping key`. Building the answer once makes that unrepresentable.
		Map<String, String> overrides = mergeOverrides(appBoot, heldDependencies(runtime, version, appBoot, policy));

		JsonObject manifest = new JsonObject();
		manifest.addProperty("private", true);
		manifest.add("dependencies", dependencies);

		// The override goes in a file of its own, not in this one. npm reads an
		// `overrides` field here and pnpm does not: since pnpm 10 the settings it
		// used to take from `package.json` live in `pnpm-workspace.yaml`, and a
		// `pnpm` field here is ignored with a warning. Writing only npm's spelling
		// left the pinning silently not applied, and a runtime came out with the
		// launcher at one version and its boot library at another — a pairing that
		// fails at import rather than degrading. The tests cover the pair.
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

	/// Combines the boot library's pin with the ones the policy holds.
	///
	/// The boot library wins where the two name the same package, and that is not a tie-break but the
	/// point: it is the one the **caller** chose — the create page offers it — while the policy only
	/// knows what the harness's ranges say. Keeping them in one map is also what stops the same key
	/// being written twice, which is a file pnpm refuses outright.
	///
	/// @param appBoot the boot library version the caller asked for
	/// @param held    the versions the policy holds
	/// @return the overrides, boot library first
	static Map<String, String> mergeOverrides(String appBoot, Map<String, String> held) {
		Map<String, String> overrides = new LinkedHashMap<>();
		overrides.put(AppBootPin.APP_BOOT_PACKAGE, appBoot);
		held.forEach(overrides::putIfAbsent);
		return overrides;
	}

	/// Returns a value written so that YAML reads it back as the string it is.
	///
	/// The file is built by hand rather than through an emitter, so a value that is not a plain
	/// scalar has to be quoted here or the file stops being YAML. Both halves of an override can be
	/// such a value: a package name starts with `@`, which YAML reserves, and a pin can be a range
	/// or a protocol as readily as a version. One this cannot write plainly is single-quoted, which
	/// is where a quote of its own is doubled — the one escape that style has.
	///
	/// @param value the value
	/// @return it as a YAML scalar
	static String yamlScalar(String value) {
		boolean plain = !value.isEmpty() && RESERVED_FIRST.indexOf(value.charAt(0)) < 0 && value.equals(value.trim()) &&
		                !value.contains(": ") && !value.contains(" #") && value.indexOf('\n') < 0;
		return plain ? value : "'" + value.replace("'", "''") + "'";
	}

	/// Returns the dependencies to hold to the versions the harness declares.
	///
	/// The declared value is a range — `^4.0.2` — and its **floor** is the version the author built
	/// against: a caret asks for that version or anything newer that claims compatibility, and
	/// "anything newer" is what has twice broken a launch. So the floor is read out and written as an
	/// override, which is not a guess about what works but a statement of what the package itself
	/// says it was written for.
	///
	/// Read from the **published manifests** rather than from disk: this runs before `pnpm install`,
	/// when the harness's own `package.json` is not on the machine yet. Both packages that take part
	/// in the pairing are asked — the harness, and the boot library it is held to — because each
	/// declares part of the tree and the boot library is where the plugin framework is named.
	///
	/// **A failure here must not fail the install.** A registry that cannot be reached, or a range
	/// this does not recognise, leaves that dependency to resolve freely — which is exactly the
	/// behaviour of [DshDependencyPolicy#LATEST] and better than refusing to install at all.
	///
	/// @param runtime the runtime whose npm reads the registry
	/// @param version the harness version
	/// @param appBoot the boot library version
	/// @param policy  how much of the tree to hold
	/// @return the name-to-version overrides
	static Map<String, String> heldDependencies(@Nullable DshNodeRuntime runtime,
	                                            String version,
	                                            String appBoot,
	                                            DshDependencyPolicy policy) {
		Map<String, String> held = new LinkedHashMap<>();
		// No runtime means no npm, which means no registry to read the declarations from. Nothing is
		// held, which is the same answer as `LATEST` and better than refusing to install.
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
					// First answer wins: the harness's own declaration is the one that matters, and
					// the boot library is asked second only for what the harness did not name.
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

	/// Reads a `dependencies` object out of what npm answered.
	///
	/// npm answers a question about a **version specifier** with an array — one entry per version it
	/// matched — even when the specifier names exactly one, so `npm view pkg@1.2.3 dependencies
    /// --json` gives `[{…}]` rather than `{…}`. Reading only the object shape therefore found
	/// nothing, silently: the policy would have been offered, selected, saved, and applied to
	/// nothing at all. Hence a method of its own with the real shape written down, and a test that
	/// feeds it the real answer.
	///
	/// A primitive is npm's answer for a package that declares no dependencies at all.
	///
	/// @param json npm's answer
	/// @return the dependencies, or `null` when there are none to read
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

	/// Reads the floor out of a declared version range.
	///
	/// Only the shapes that have a floor are answered: `4.0.2`, `^4.0.2`, `~4.0.2` and `>=4.0.2`.
	/// Anything else — `*`, `latest`, a tag, a git address, a compound range — is answered with
	/// `null` and left alone, because a range whose floor this cannot name is one it must not guess
	/// at.
	///
	/// @param range the declared range
	/// @return the version, or `null`
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
	 * Removes the harness an interrupted install left. Only for an install this launcher just failed,
	 * or for an instance about to be deleted; an instance that is kept must not lose a working harness.
	 */
	public static void discardPartial(DshInstance instance) {
		try {
			boolean installing = isInstalling(instance);
			clearInstalling(instance);
			Path target = instance.dshDirectory();
			if (installing && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				DshFiles.deleteTree(target);
				LOG.info("Removed the partial install for " + instance.id());
			}
		} catch (IOException | DshException e) {
			LOG.warning("Could not remove the partial install for " + instance.id(), e);
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
		// pnpm rather than npm: its store shares package contents between projects.
		// append-only: the launcher's progress parser reads the "Progress: resolved ..." tally line.
		// Dependency build scripts: npm runs them by default and pnpm 10 does not; a package that
		// needs one can fail on first use.
		List<String> command = new ArrayList<>(List.of(runtime.pnpm().toString(),
		                                               "install",
		                                               "--dir",
		                                               project.toString(),
		                                               "--reporter=append-only",
		                                               "--config.dangerously-allow-all-builds=true"));
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			// Windows falls back to junctions, which name absolute paths and break when the tree
			// is moved; the hoisted linker lays the packages out directly instead.
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
		// Take the lock before the runtime is looked at: a caller turned away should hear that the
		// instance is busy, not that its own runtime is missing a tool.
		InstanceLocks.withLock(instance, "installed into", () -> {
			DshNodeRuntime runtime = detected != null ? detected : requireRuntime();
			if (!runtime.canManagePlugins()) {
				throw new DshException("pnpm was not found on PATH; installing DeepSeek Harness requires it");
			}
			requireSupportedPnpm(runtime);

			if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
				// Windows falls back to absolute junctions when it refuses symlinks; installing
				// where the tree ends up removes the rename that would break them.
				installInPlace(instance, version, runtime, target, onLine);
			} else {
				// Relative links survive a rename, so the other platforms keep the staged install.
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

		LOG.info("Installed DSH " + version + " for " + instance.id() + " into " + target);
	}

	private static void installInPlace(DshInstance instance,
	                                   String version,
	                                   DshNodeRuntime runtime,
	                                   Path target,
	                                   @Nullable Consumer<String> onLine) throws DshException {
		Path aside = setAsideDirectory(instance);

		// The marker goes down before anything is deleted or moved, so a launcher killed while it
		// clears the old tree still says an install is outstanding.
		markInstalling(instance);

		boolean hadPrevious = false;
		try {
			DshFiles.deleteTreeQuietly(aside);
			if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
				// Moved rather than deleted: restoring it is a rename, and absolute links inside it
				// still name this path.
				Files.move(target, aside);
				hadPrevious = true;
			}
		} catch (IOException e) {
			clearInstalling(instance);
			throw new DshException("Failed to move " + target + " aside to " + aside, e);
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
			// Whatever failed, the tree is not a runtime; discard it and put the old one back.
			discardPartial(instance);
			DshException failure = e instanceof DshException dsh
			                       ? dsh
			                       : new DshException(
					                       "Failed to install " + version + " for " + instance.id() + ": " + e, e);
			if (hadPrevious && !restore(aside, target)) {
				failure.addSuppressed(new DshException(
						"The DeepSeek Harness that was there has been kept in " + aside +
						" and has to be moved back by hand"));
			}
			throw failure;
		}

		DshFiles.deleteTreeQuietly(aside);
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

	/// Deletes a directory, ignoring failures.
	///
	/// The trees here are a package manager's: thousands of files, some of them
	/// read-only and some of them junctions. Removed with [DshFiles], which is
	/// what makes that work on Windows; see it for what was measured.
	///
	/// @param directory the directory to remove
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
