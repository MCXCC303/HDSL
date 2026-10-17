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
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.plugin.DshDependencyPolicy;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	private HarnessInstaller() {
	}

	/// Reports whether an instance's own DeepSeek Harness is in place.
	///
	/// @param instance the instance
	/// @return whether its entry script is there
	public static boolean isInstalled(DshInstance instance) {
		try {
			return Files.isRegularFile(instance.dshEntryPoint());
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

	/// The directory an instance's copy is staged in while it is installed.
	///
	/// Inside the instance, so that moving it into place is a rename within one
	/// filesystem rather than a copy across two.
	///
	/// @param instance the instance
	/// @return the staging directory
	/// @throws DshException when the identifier is not usable as a path segment
	static Path stagingDirectory(DshInstance instance) throws DshException {
		return instance.instanceDirectory().resolve("dsh.installing");
	}

	/// Removes a copy that was being installed and did not finish.
	///
	/// @param instance the instance whose partial install to remove
	public static void discardPartial(DshInstance instance) {
		try {
			Path staging = stagingDirectory(instance);
			if (Files.exists(staging)) {
				DshFiles.deleteTree(staging);
				LOG.info("Removed the partial install for " + instance.id());
			}
		} catch (IOException | DshException e) {
			LOG.warning("Could not remove the partial install for " + instance.id(), e);
		}
	}

	/// Builds the command that installs a project's dependencies with pnpm.
	///
	/// Three decisions are in here.
	///
	/// pnpm rather than npm, because pnpm links packages into a project from a
	/// store it keeps: a second project using the same packages costs a fraction
	/// of the first. Measured here, a second copy of the same version adds 51 MB
	/// against the 402 MB it appears to occupy.
	///
	/// `append-only` because the progress tally the launcher reads is one line of
	/// the event-by-event output.
	///
	/// And the build scripts are allowed. pnpm runs none of them by default and
	/// fails the install when it finds any, and several of DeepSeek Harness's
	/// dependencies build native modules — the spawn helper among them — so an
	/// install without them looks complete and fails the moment it is used. This
	/// is the same trust npm extends by default, which is what the upstream
	/// package is installed with.
	///
	/// On Windows nothing is linked, and that is the point of it. pnpm builds
	/// `node_modules` out of links, and where Windows refuses it a symbolic link —
	/// no developer mode, no elevation, security software — it falls back to
	/// **absolute junctions**, which name the path they were made at. A junction
	/// therefore breaks the moment anything renames a directory above it: the
	/// staging rename this method used to be paired with, renaming an instance,
	/// and whatever gets added next. `hoisted` copies the packages instead of
	/// linking them, so there is nothing to break. It costs nothing for it: the
	/// tree comes out the same size (about 474 MB against 478 MB for 0.2.0-rc.2),
	/// because the store is only ever a source to copy from either way.
	///
	/// Everywhere else is left alone. pnpm makes relative symbolic links there,
	/// which survive a rename, and hoisted would only cost the shared layout the
	/// store already gives for free.
	///
	/// @param runtime the runtime to take pnpm from
	/// @param project the project directory
	/// @return the command
	static List<String> buildInstallCommand(DshNodeRuntime runtime, Path project) {
		List<String> command = new ArrayList<>(List.of(runtime.pnpm().toString(),
		                                               "install",
		                                               "--dir",
		                                               project.toString(),
		                                               "--reporter=append-only",
		                                               "--config.dangerously-allow-all-builds=true"));
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			command.add("--config.node-linker=hoisted");
		}
		return List.copyOf(command);
	}

	/// Installs the DeepSeek Harness an instance runs, into that instance.
	///
	/// The install is staged inside the instance and moved into place on success,
	/// so an interrupted install can never look like a usable runtime.
	///
	/// @param instance the instance to install for
	/// @param onLine   a consumer notified of pnpm output lines, or `null`
	/// @throws DshException when the runtime is missing or pnpm fails
	public static void install(DshInstance instance, @Nullable Consumer<String> onLine) throws DshException {
		install(instance, instance.version(), onLine);
	}

	/// Installs a version into an instance, which need not be the version it
	/// currently records.
	///
	/// The two differ while an instance is being moved to another version: the
	/// new runtime has to be in place before the record can name it.
	///
	/// @param instance the instance to install into
	/// @param version  the version to install
	/// @param onLine   a consumer notified of pnpm output lines, or `null`
	/// @throws DshException when the runtime is missing or pnpm fails
	public static void install(DshInstance instance, String version, @Nullable Consumer<String> onLine)
	throws DshException {
		Path target = instance.dshDirectory();
		Path staging = stagingDirectory(instance);

		DshNodeRuntime runtime = requireRuntime();
		if (!runtime.canManagePlugins()) {
			throw new DshException("pnpm was not found on PATH; installing DeepSeek Harness requires it");
		}

		try {
			if (Files.exists(staging)) {
				DshFiles.deleteTree(staging);
			}
			Files.createDirectories(staging);
		} catch (IOException e) {
			throw new DshException("Failed to prepare " + staging, e);
		}

		// The manifest is written before pnpm runs, because pnpm would otherwise
		// choose the versions itself. Installing by name records a caret range,
		// and a caret cannot express what these packages actually promise: the
		// whole family is published in lockstep, one version for all of it. So
		// `^0.1.6-alpha.1` resolves to alpha.2's libraries, and a release whose
		// code and libraries disagree fails at import — which is exactly what
		// happened between 0.1.6-alpha.1 and alpha.2, where a library dropped an
		// export the launcher still imported.
		//
		// Pinning the launcher to its exact version and holding the application
		// boot library to the same one keeps a tree consistent. The override is
		// the value the create page offers, and it defaults to the matching one.
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
			// pnpm's own last lines say what went wrong — a version that is not
			// published, a dependency tree that cannot be resolved, a registry that
			// cannot be reached — and an exit code says none of that.
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

	/// Requires a usable Node runtime.
	///
	/// @return the detected runtime
	/// @throws DshException when no `node` was found at all
	public static DshNodeRuntime requireRuntime() throws DshException {
		return DshNodeRuntime.detect()
		                     .orElseThrow(() -> new DshException(
				                     "Node.js was not found on PATH; " + DshNodeRuntime.requirement()));
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
}
