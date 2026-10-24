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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

	/** The oldest pnpm that can install DeepSeek Harness */
	private static final Semver MINIMUM_PNPM_VERSION = Objects.requireNonNull(Semver.parse(DshNodeRuntime.MINIMUM_PNPM));

	private HarnessInstaller() {
	}

	/// Reports whether an instance's own DeepSeek Harness is in place.
	///
	/// The marker outranks the entry script, because the two disagree for exactly the case that
	/// matters: pnpm puts `@deepseek-ai/dsh` into `node_modules` while it **links**, well before
	/// the build scripts finish, so an install that was killed halfway leaves its entry script
	/// sitting there. An instance is only in place once the install that put it there said it was.
	///
	/// @param instance the instance
	/// @return whether its entry script is there and no install is outstanding
	public static boolean isInstalled(DshInstance instance) {
		try {
			return !isInstalling(instance) && Files.isRegularFile(instance.dshEntryPoint());
		} catch (DshException e) {
			return false;
		}
	}

	/// Reports whether an installation into an instance is outstanding.
	///
	/// A launch that was killed mid-install leaves the marker behind, and the instance is then a
	/// tree pnpm was still writing to. It is not in place, however complete its entry script looks.
	///
	/// @param instance the instance
	/// @return whether the marker is there
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

	/// The marker that says an installation into an instance is in progress.
	///
	/// **Beside** the harness rather than inside it, for two reasons. A package manager's project
	/// directory is its own — a foreign file in it is something pnpm could one day trip over or
	/// prune. And the marker outliving `dsh/` is the point: the harness is deleted at the start of
	/// every install, so a marker inside it would be deleted along with the evidence that the last
	/// install never finished.
	///
	/// A **file**, and its existence is asked with [LinkOption#NOFOLLOW_LINKS] so that the
	/// directory an older build staged into — `dsh.installing`, same stem, different name — cannot
	/// be mistaken for one and leave an instance that looks installed forever.
	///
	/// @param instance the instance
	/// @return the marker
	/// @throws DshException when the identifier is not usable as a path segment
	static Path pendingMarker(DshInstance instance) throws DshException {
		return instance.instanceDirectory().resolve(".dsh-installing");
	}

	/// Says that an installation into an instance is in progress.
	///
	/// @param instance the instance
	/// @throws DshException when the marker cannot be written
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

	/// Says that no installation into an instance is in progress any more.
	///
	/// @param instance the instance
	private static void clearInstalling(DshInstance instance) {
		try {
			Files.deleteIfExists(pendingMarker(instance));
		} catch (IOException | DshException e) {
			// The work it announces is done. A marker left behind only makes the next read of the
			// instance answer "not installed", which costs a reinstall and no more.
			LOG.warning("Could not remove the install marker of " + instance.id(), e);
		}
	}

	/// Removes a copy that was being installed and did not finish.
	///
	/// The whole harness goes, not a staging directory: there is no second tree to clean up,
	/// because the install that was interrupted was writing into the one the instance runs from.
	/// That is what the marker is for — it is how a tree pnpm was still writing to is told apart
	/// from a finished one.
	///
	/// The marker is the only thing standing between this and someone's working install, so what
	/// makes it safe is not this method but where it is called from. Every call site is either
	/// cleaning up an install this launcher just started, or is about to delete the whole instance
	/// directory anyway — the pack paths, which install into an instance that existed before and
	/// then remove it when the install fails. Nothing here deletes the tree of an instance that is
	/// kept afterwards. **A call site that keeps the instance must not call this on the strength of
	/// the instance having just been installed**: if the install had got far enough to leave a
	/// marker, that marker is gone by the time any caller can react to the failure, so the guard
	/// below has already made the decision.
	///
	/// @param instance the instance whose partial install to remove
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

	/// Says why an instance has no DeepSeek Harness to run.
	///
	/// One sentence used to cover three states a person cannot tell apart, and the one they meet
	/// most often — an install whose links point at a path that is not there any more — was
	/// indistinguishable from never having installed at all. What is asked here is what is on
	/// disk, so the answer names what is wrong with it.
	///
	/// The dangling-link case is not exotic: pnpm builds the links in `node_modules` as absolute
	/// **junctions** wherever Windows refuses it a symbolic link (no developer mode, no elevation,
	/// security software), and a junction naming a path the launcher has since renamed leaves the
	/// entry script unreachable while the directory around it looks complete. Recreating the link
	/// is not an option either — the same refusal that produced it would refuse the repair — so
	/// reinstalling is the answer, and the message says so.
	///
	/// @param instance the instance
	/// @return the message, or `null` when the instance is complete after all
	/// @throws DshException when the instance's own path cannot be resolved
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

		// The tree is there and the install that wrote it finished, so a path inside it went
		// somewhere else. On Windows that is a package manager link naming a directory that has
		// since been moved or renamed.
		// `Files.isSymbolicLink` would answer `false` for a junction, and a junction is exactly
		// what pnpm makes where Windows refuses it a symbolic link — so the test that recognises
		// this is the one `DshFiles` uses to tell a reparse point from a directory.
		Path packageLink = harness.resolve(DshVersion.PACKAGE_PATH);
		if (DshFiles.isReparsePoint(packageLink) && Files.exists(packageLink, LinkOption.NOFOLLOW_LINKS) &&
		    !Files.exists(packageLink)) {
			return "The DeepSeek Harness of instance " + instance.id() + " is broken: " + packageLink +
			       " is a link to a directory that no longer exists (this is what a package manager " +
			   "link looks like after the folder it names was renamed). Reinstall it.";
		}
		return "Instance " + instance.id() + " has no DeepSeek Harness of its own; " + script + " is missing";
	}

	/// Requires that an instance has a DeepSeek Harness to run, saying why it has not.
	///
	/// @param instance the instance
	/// @throws DshException when the instance has none, naming the reason
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
	/// The install writes into the directory the instance runs from, and a marker
	/// file beside it says how far it got, so an interrupted install cannot look
	/// like a usable runtime.
	///
	/// @param instance the instance to install for
	/// @param onLine   a consumer notified of pnpm output lines, or `null`
	/// @throws DshException when the runtime is missing or pnpm fails
	public static void install(DshInstance instance, @Nullable Consumer<String> onLine) throws DshException {
		install(instance, instance.version(), onLine);
	}

	/// The directory a previous install is moved aside into, so that it can be put back.
	///
	/// **Beside** the harness rather than inside the instance's own state, and named so that it is
	/// obviously not one of the launcher's own things. It is left alone by [#discardPartial] on
	/// purpose: it is the copy that holds a working runtime when an install could not replace one.
	static Path setAsideDirectory(DshInstance instance) throws DshException {
		return instance.instanceDirectory().resolve("dsh.previous");
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

	/// Installs a version into an instance, which need not be the version it
	/// currently records.
	///
	/// The two differ while an instance is being moved to another version: the
	/// new runtime has to be in place before the record can name it, which is
	/// what lets a failed install leave the previous version both standing and
	/// recorded.
	///
	/// **pnpm installs straight into the directory the instance runs from**, under a marker that
	/// says the install is outstanding. It used to install into a staging directory and rename
	/// that into place, which is a rename of a tree a package manager had just built links into,
	/// and those links do not always survive it: pnpm writes `node_modules` as **absolute
	/// junctions** wherever Windows refuses it a symbolic link — no developer mode, no elevation,
	/// security software — and every one of them names the staging path. Renaming left them
	/// pointing at nothing, so the entry script became unreachable and the instance reported that
	/// it had no harness of its own, which is what it had. Installing where the tree ends up
	/// removes the question.
	///
	/// What the rename was buying is bought by two things instead. [#isInstalling] answers true
	/// until the install says it finished, so a half-built tree is never taken for a runtime — and
	/// the marker goes down **before anything is destroyed**, so that a launcher killed halfway
	/// through still says so rather than saying there is nothing there. And the tree that was
	/// there is **moved aside** rather than deleted, so that a failure puts it back: the absolute
	/// junctions it may hold name the final path, which moving it away and back does not change, so
	/// they resolve again.
	///
	/// @param instance the instance to install into
	/// @param version  the version to install
	/// @param onLine   a consumer notified of pnpm output lines, or `null`
	/// @throws DshException when the runtime is missing, another install is already running, or
	///                       pnpm fails
	public static void install(DshInstance instance, String version, @Nullable Consumer<String> onLine)
	throws DshException {
		install(instance, version, onLine, null);
	}

	/// Installs with a runtime the caller supplies instead of probing for one.
	///
	/// Only tests pass one. It is what lets the failure half of an install be driven at all: a
	/// runtime whose `pnpm` names something that is not there fails the same way a pnpm that
	/// cannot start does, without a registry, a network, or a real pnpm being involved.
	///
	/// @param instance the instance to install into
	/// @param version  the version to install
	/// @param onLine   a consumer notified of pnpm output lines, or `null`
	/// @param detected the runtime to install with, or `null` to probe for one
	/// @throws DshException when the runtime is missing or too old, another install is already
	///                       running, or pnpm fails
	static void install(DshInstance instance, String version, @Nullable Consumer<String> onLine,
	                    @Nullable DshNodeRuntime detected) throws DshException {
		Path target = instance.dshDirectory();
		// The lock is taken before the machine is looked at, so that being turned away is the
		// answer whatever else is wrong. Asked while an install is running, this says it is busy,
		// not that this caller's own runtime happens to be missing a tool.
		InstanceLocks.withLock(instance, "installed into", () -> {
			DshNodeRuntime runtime = detected != null ? detected : requireRuntime();
			if (!runtime.canManagePlugins()) {
				throw new DshException("pnpm was not found on PATH; installing DeepSeek Harness requires it");
			}
			requireSupportedPnpm(runtime);

			if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
				// A Windows that refuses symbolic links makes pnpm fall back to absolute
				// junctions. Installing where the tree ends up removes the rename that would
				// break them.
				installInPlace(instance, version, runtime, target, onLine);
			} else {
				// Relative links survive a rename, so the other platforms keep the staged
				// install they have always had.
				installStaged(instance, version, runtime, onLine);
			}
		});
	}

	/// Installs into a staging directory and moves it into place.
	///
	/// This is the path the other platforms keep: pnpm makes relative symbolic links there, which
	/// survive the rename. Windows uses [#installInPlace] instead, because a junction does not.
	///
	/// @param instance the instance to install into
	/// @param version  the version to install
	/// @param runtime  the runtime to take pnpm from
	/// @param onLine   a consumer notified of pnpm output lines, or null
	/// @throws DshException when pnpm fails or the staged install cannot be moved into place
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

	/// The install itself, with the instance's directory held.
	///
	/// @param instance the instance to install into
	/// @param version  the version to install
	/// @param runtime  the runtime to take pnpm from
	/// @param target   the directory the instance runs from
	/// @param onLine   a consumer notified of pnpm output lines, or `null`
	/// @throws DshException when pnpm fails or the install cannot be put into place
	private static void installInPlace(DshInstance instance,
	                                       String version,
	                                       DshNodeRuntime runtime,
	                                       Path target,
	                                       @Nullable Consumer<String> onLine) throws DshException {
		Path aside = setAsideDirectory(instance);

		// The marker goes down first, before anything is deleted or moved. A launcher killed while
		// it is clearing the old tree then still answers "an install is outstanding", which is the
		// truth, rather than "nothing is installed", which is what the absence of a marker would
		// leave it saying about a tree it was in the middle of replacing.
		markInstalling(instance);

		boolean hadPrevious = false;
		try {
			DshFiles.deleteTreeQuietly(aside);
			if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
				// Moved, not deleted. It costs a rename, and it is what makes a failed install a
				// non-event for the person: putting it back restores the instance exactly, because
				// any absolute link inside it names this same path and resolves again.
				Files.move(target, aside);
				hadPrevious = true;
			}
		} catch (IOException e) {
			clearInstalling(instance);
			throw new DshException("Failed to move " + target + " aside to " + aside, e);
		}

		try {
			Files.createDirectories(target);

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
				// pnpm's own last lines say what went wrong — a version that is not
				// published, a dependency tree that cannot be resolved, a registry that
				// cannot be reached — and an exit code says none of that.
				throw new DshException(
						"pnpm exited with code " + exitCode + " while installing " + version + " for " + instance.id() +
						":\n" + ReleaseQueries.tail(commandResult.output()));
			}

			Path bin = target.resolve(DshVersion.PACKAGE_PATH).resolve("lib/bin.js");
			if (!Files.isRegularFile(bin)) {
				throw new DshException("pnpm reported success but " + bin + " is missing");
			}
		} catch (DshException | IOException | RuntimeException e) {
			// Anything at all, not only the failures this code raises: a tree this install was
			// writing into is not a runtime whatever happened, and the marker and the tree go
			// together so that what is left behind is never mistaken for either.
			discardPartial(instance);
			DshException failure = e instanceof DshException dsh
					? dsh
					: new DshException("Failed to install " + version + " for " + instance.id() + ": " + e, e);
			if (hadPrevious && !restore(aside, target)) {
				failure.addSuppressed(new DshException(
						"The DeepSeek Harness that was there has been kept in " + aside + " and has to be moved back by hand"));
			}
			throw failure;
		}

		DshFiles.deleteTreeQuietly(aside);
		clearInstalling(instance);
		LOG.info("Installed DSH " + version + " for " + instance.id() + " into " + target);
	}

	/// Puts a tree that was moved aside back where the instance runs from.
	///
	/// Nothing is moved over: whatever is in `target` is the half-built remains of the install that
	/// just failed, and it is deleted rather than kept, because it is what the tree being restored
	/// has to take the name of.
	///
	/// @param aside  where the tree was moved to
	/// @param target where the instance runs from
	/// @return whether it is back
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

	/// Requires a usable Node runtime.
	///
	/// @return the detected runtime
	/// @throws DshException when no `node` was found at all
	public static DshNodeRuntime requireRuntime() throws DshException {
		return DshNodeRuntime.detect()
		                     .orElseThrow(() -> new DshException(
				                     "Node.js was not found on PATH; " + DshNodeRuntime.requirement()));
	}
}
