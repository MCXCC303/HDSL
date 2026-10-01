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
package org.jackhuang.hmcl.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Adds and removes profile plugins by driving `dsh plugin`.
///
/// The launcher deliberately does not talk to npm itself. Upstream already
/// ships a forwarder — `dsh plugin --profile <name> <pnpm args>` runs `pnpm`
/// inside the profile directory and then reconciles `dsh.profile.bundles` — so
/// shelling out to it keeps the lock handling, the bundle reconciliation and
/// the failure modes exactly as upstream defines them.
///
/// All calls run against the instance's own `DSH_HOME` and its pinned `dsh`
/// installation, so they cannot disturb another instance or the user's global
/// `dsh`.
@NotNullByDefault
public final class DshPluginInstaller {

    /// The refusals an installation in progress has already asked about.
    ///
    /// A package the profile refused before is asked about once per installation the person starts, and
    /// this is what makes that "once" true. An answer is written into the profile pnpm reads — and
    /// writing `false` there leaves the profile in exactly the state the question is asked in: refused,
    /// and being installed again. So without this, every re-run of the same installation asked again,
    /// and the installation would not take no for an answer; the only way past it was to press yes.
    ///
    /// The installing interface is what keeps this honest: it records what it answered, and it starts a
    /// new installation when a person starts one. The installer cannot make that distinction itself,
    /// because a question it has just been told the answer to and a new question about the same package
    /// are the same command run against the same profile.
    private static final java.util.Set<String> ANSWERED_ABOUT = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private DshPluginInstaller() {
    }

    /// Starts a new installation, so what an earlier one was told is asked about again.
    ///
    /// Called once per installation a person starts — not once per command, because installing several
    /// packages is several commands and each of them has to keep the answers the ones before it were
    /// given, and not when a question is answered, because answering is what carries the same
    /// installation on.
    public static void beginInstallation() {
        ANSWERED_ABOUT.clear();
    }

    /// Records the refusals an answer has just been written for.
    ///
    /// The keys are pnpm's own, as the profile holds them — what
    /// [DshBuildScriptApprovalRequired#packages] carries.
    ///
    /// @param keys the `allowBuilds` keys the answer was written for
    public static void answeredAbout(List<String> keys) {
        ANSWERED_ABOUT.addAll(keys);
    }

    /// Installs every given preset into an instance's profile.
    ///
    /// Presets are installed one at a time so that a failure names the package
    /// that caused it, and so partial progress is visible in the log.
    ///
    /// @param instance the instance whose profile is modified
    /// @param presets  the presets to install
    /// @param onLine   receives every output line, or `null`
    /// @throws DshException when the runtime or version is unavailable, `pnpm`
    ///                       is missing, or an install fails
    public static void install(DshInstance instance, List<DshPreset> presets,
                               @Nullable Consumer<String> onLine) throws DshException {
        installSpecs(instance, presets.stream().map(DshPreset::spec).toList(), onLine);
    }

    /// Installs explicit package specs into an instance's profile.
    ///
    /// The specs may pin a version — `name@1.2.3` — which is what the install
    /// wizard records when the user picks a specific release on a plugin's
    /// choice page.
    ///
    /// @param instance the instance whose profile is modified
    /// @param specs    the package specs to install
    /// @param onLine   receives every output line, or `null`
    /// @throws DshException when the runtime or version is unavailable, `pnpm`
    ///                       is missing, or an install fails
    public static void installSpecs(DshInstance instance, List<String> specs,
                                    @Nullable Consumer<String> onLine) throws DshException {
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

    /// Resolves a profile from the manifest it holds.
    ///
    /// `install` forwarded to pnpm inside the profile directory, which is what both
    /// creates a profile that does not exist yet — the harness writes its manifest,
    /// patch template and package-manager settings first — and installs whatever
    /// that manifest then lists. Restoring a pack's plugin list means writing the
    /// list and running this once: reconciliation keeps the entries a manifest
    /// already has and only appends what is missing, so the order survives.
    ///
    /// @param instance the instance whose profile is resolved
    /// @param onLine   receives every output line, or `null`
    /// @throws DshException when pnpm is missing or the resolve fails
    public static void resolve(DshInstance instance, @Nullable Consumer<String> onLine) throws DshException {
        DshNodeRuntime runtime = DshLauncher.resolveRuntime(instance);
        if (!runtime.canManagePlugins()) {
            throw new DshException("pnpm was not found on PATH; installing plugins requires it");
        }
        runPluginCommand(instance, runtime, instance.homeDirectory(), List.of("install"), List.of(), onLine);
    }

    /// Removes a package from an instance's profile.
    ///
    /// @param instance the instance whose profile is modified
    /// @param spec     the package spec to remove
    /// @param onLine   receives every output line, or `null`
    /// @throws DshException when the removal fails
    public static void remove(DshInstance instance, String spec, @Nullable Consumer<String> onLine)
            throws DshException {
        removeSpecs(instance, List.of(spec), onLine);
    }

    /// Removes several packages from an instance's profile in one run.
    ///
    /// One command rather than one per package, which is what the interface's
    /// multiple selection asks for: `dsh plugin` forwards its arguments to pnpm
    /// verbatim, so `remove a b c` is a single pnpm run and a single pass of
    /// bundle reconciliation. Installing deliberately does not work this way —
    /// see [DshPluginInstaller#installSpecs] — but removal has no order to lose:
    /// the packages are leaving, and pnpm removes them together or reports which
    /// one stopped it.
    ///
    /// @param instance the instance whose profile is modified
    /// @param specs    the package specs to remove
    /// @param onLine   receives every output line, or `null`
    /// @throws DshException when the removal fails
    public static void removeSpecs(DshInstance instance, List<String> specs,
                                   @Nullable Consumer<String> onLine) throws DshException {
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

    /// Reads the packages a profile declares as dependencies.
    ///
    /// This is everything the user has installed, which is a superset of the
    /// bundle list: a package only joins `dsh.profile.bundles` when it ships a
    /// bundle patch *and* the reconciling `dsh plugin` run completed. Showing
    /// both is what makes an installed-but-inactive plugin visible instead of
    /// silently doing nothing.
    ///
    /// @param home    the `DSH_HOME` holding the profile
    /// @param profile the profile name
    /// @return the dependency name to version-range map, in manifest order
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
            Map<String, String> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : dependencies.entrySet()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
            return result;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to read the profile manifest " + manifest, e);
            return Map.of();
        }
    }

    /// Reads the bundle list a profile declares.
    ///
    /// @param home    the `DSH_HOME` holding the profile
    /// @param profile the profile name
    /// @return the declared bundle package names, in load order
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

    /// Runs one `dsh plugin` invocation against an instance.
    ///
    /// @param instance the instance
    /// @param runtime  the resolved Node runtime
    /// @param home     the `DSH_HOME` to operate on
    /// @param args     the pnpm arguments to forward
    /// @param packages the package specs this run is about, which is what a remembered refusal is
    ///                 matched against; empty for a run that installs nothing
    /// @param onLine   receives every output line, or `null`
    /// @throws DshException when the command cannot be run or exits non-zero
    private static void runPluginCommand(DshInstance instance,
                                         DshNodeRuntime runtime,
                                         Path home,
                                         List<String> args,
                                         List<String> packages,
                                         @Nullable Consumer<String> onLine) throws DshException {
        Path script = instance.dshEntryPoint();
        if (!Files.isRegularFile(script)) {
            throw new DshException("Instance " + instance.id()
                    + " has no DeepSeek Harness of its own; " + script + " is missing");
        }
        if (!Files.isRegularFile(script)) {
            throw new DshException("The installed version is incomplete: " + script + " is missing");
        }

        List<String> command = new ArrayList<>();
        command.add(runtime.node().toString());
        command.add(script.toString());
        command.add("plugin");
        command.add("--profile");
        command.add(instance.profile());
        command.addAll(args);

        // Which instance this is doing it to, said out loud before it is done. An installation into the
        // wrong instance is invisible from its result — a plugin appears somewhere, or nowhere, and
        // the launcher's log said nothing about where the command went — so the target is written here
        // in the same words a launch uses: the instance, its home, and the command itself.
        LOG.info("Installing into instance " + instance.id() + " (home " + home + ", profile "
                + instance.profile() + "): " + String.join(" ", command));

        // DSH_* cannot come from a .env file — upstream rejects those names there
        // — so DSH_HOME must travel in the child's environment. Getting this
        // wrong would silently operate on the user's real ~/.dsh.
        Map<String, String> environment = new java.util.LinkedHashMap<>();
        environment.put("DSH_HOME", home.toString());
        environment.putAll(runtime.pathEnvironment());
        environment.putAll(DshEnvironment.of(instance));

        boolean retried = false;
        while (true) {
        try {
            DshCommand.Result result = DshCommand.run(command, instance.workspacePath(), environment,
                    line -> report(onLine, line));
            int exitCode = result.exitCode();
            if (exitCode != 0 && !retried) {
                // Two ways a plugin is waiting to be allowed to build. pnpm writes a placeholder
                // into the profile for the packages whose build it merely ignored; for a package
                // fetched from a repository it writes nothing and names the exact entry it needs
                // instead, because that entry has to carry the resolved address. Recording what it
                // named as a question puts both onto the same path — see DshBuildScripts#propose.
                List<String> named = gitBuildKeys(result.output());
                boolean placeholder = explainIgnoredBuilds(result.output(), home, instance.profile()) != null;
                if (!named.isEmpty() && DshBuildScriptPolicy.of(instance) == DshBuildScriptPolicy.NEVER) {
                    // A repository-hosted package is built **by** being installed — there is no
                    // published build to fall back on — so refusing the build refuses the
                    // installation. Saying so beats a retry that cannot succeed.
                    throw new DshException(explainGitBuild(named, home, instance.profile()));
                }
                if (!named.isEmpty()) {
                    DshBuildScripts.propose(instance, named);
                }
                if (!named.isEmpty() || placeholder) {
                    List<String> waiting = DshBuildScripts.unanswered(instance);
                    DshBuildScriptPolicy policy = DshBuildScriptPolicy.of(instance);
                    if (!waiting.isEmpty() && policy == DshBuildScriptPolicy.AUTO) {
                        // This instance's plugins may build themselves, so the question
                        // is already answered: say so and run the same command again.
                        report(onLine, "Allowing install scripts for " + String.join(", ", waiting));
                        DshBuildScripts.answer(instance, waiting, true);
                        retried = true;
                        continue;
                    }
                    if (!waiting.isEmpty() && policy == DshBuildScriptPolicy.NEVER) {
                        // The answer is no, so the packages are installed and their
                        // scripts are not run: installing is not the same decision as
                        // running what comes with it.
                        report(onLine, "Not running install scripts for " + String.join(", ", waiting));
                        DshBuildScripts.answer(instance, waiting, false);
                        retried = true;
                        continue;
                    }
                    // What has already been put to somebody in this installation is not put again.
                    // An answer of “no” leaves the refusal in the profile, and a refusal is the same
                    // state a question is asked about — asking on that state alone asks forever.
                    List<String> unasked = new ArrayList<>();
                    for (String key : waiting) {
                        if (!ANSWERED_ABOUT.contains(key)) {
                            unasked.add(key);
                        }
                    }
                    if (!unasked.isEmpty()) {
                        // Somebody has to decide, and only the interface can ask.
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
                throw new DshException("`dsh plugin " + String.join(" ", args)
                        + "` exited with code " + exitCode + ":\n" + tail(result.output()));
            }
            // A run that succeeded can still have left a package's build undecided, so what decides is
            // the profile rather than the exit code — and both halves of that are measured against
            // pnpm 11.24. An undecided build does fail the run by default: `strict-dep-builds` is true
            // in pnpm's own default configuration and it exits 1 with `ERR_PNPM_IGNORED_BUILDS`. But it
            // writes the placeholder into `allowBuilds` *before* raising that — the order in its code is
            // `writeIgnoredBuildsToAllowBuilds(...)`, then `if (opts.strictDepBuilds) throw` — and a
            // launcher that took the exit code as the whole answer would be wrong about the runs where
            // that flag is off, which is what `pnpm dlx` sets for itself and what `--config.strict-dep-builds=false`
            // asks for. Reading the profile is right about both.
            if (!retried && answerWaitingBuilds(instance, packages, onLine)) {
                retried = true;
                continue;
            }
            // What was just installed without its install scripts is said here rather than left to be
            // discovered later: a package whose build did not run installs perfectly well and then does
            // nothing — the plugin is listed, cannot load, and nothing anywhere says why.
            List<String> refused = refusedAmong(instance, packages);
            if (!refused.isEmpty()) {
                report(onLine, "Installed without running install scripts: " + String.join(", ", refused));
                LOG.info("Installed " + String.join(", ", refused)
                        + " into instance " + instance.id() + " without running their install scripts");
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

    /// Thrown when an installation is waiting for somebody to allow its scripts.
    ///
    /// The installer does not answer for a person: it reports what is waiting, and
    /// the interface that started the installation asks.
    public static final class DshBuildScriptApprovalRequired extends DshException {
        /// The packages whose install scripts are waiting.
        private final transient List<String> packages;

        /// Creates the failure.
        ///
        /// @param packages the packages
        DshBuildScriptApprovalRequired(List<String> packages) {
            super("The plugin needs permission to run its install scripts: "
                    + String.join(", ", packages));
            this.packages = List.copyOf(packages);
        }

        /// Returns the packages whose install scripts are waiting.
        ///
        /// @return the package names
        public List<String> packages() {
            return packages;
        }
    }

    /// Answers the build scripts a profile is waiting to be told about.
    ///
    /// What is waiting is read from the profile rather than guessed from how the package manager
    /// exited, because the two are not the same question. An undecided build does fail the run by
    /// default — pnpm 11 has `strict-dep-builds: true` and exits 1 — but it writes the placeholder
    /// before it raises that, and the flag can be off. The profile is what says whether anything is
    /// still undecided, whichever way the run ended.
    ///
    /// The three policies are the setting's own three answers, and only the middle one is not an
    /// answer: under [DshBuildScriptPolicy#MANUAL] the question goes to the interface, which is the
    /// only thing that can ask a person.
    ///
    /// A package the profile has already been told **no** about is asked about again, because pnpm
    /// remembers a refusal in the same file it remembers an approval in, and that made a refusal
    /// final for good: installing the package again wrote the same entry, ran nothing, said nothing,
    /// and left a plugin installed that cannot load. Refusing is an answer to *an* installation —
    /// what it is about is running code now — so under the manual setting the question comes back
    /// with the next one, and answering it differently is how somebody changes their mind. Under the
    /// other two settings nobody is asked anything, and those answers stay as they are.
    ///
    /// Asked **once per installation** and not once per command: what an answer was given for is
    /// recorded for as long as that installation runs ([#beginInstallation], [#answeredAbout]),
    /// because writing `false` leaves the profile refused *and* being installed, which is this
    /// question's own condition — without that, answering no would ask again for ever, and an
    /// installation would not take no for an answer.
    ///
    /// @param instance the instance whose profile is waiting
    /// @param packages the package specs this run is installing, which a remembered refusal is
    ///                 matched against by name; empty when the run installs nothing
    /// @param onLine   receives what was decided, or `null`
    /// @return whether an answer was written, which makes the command worth running again
    /// @throws DshException                     when the profile cannot be read or written
    /// @throws DshBuildScriptApprovalRequired   when only the interface can answer
    private static boolean answerWaitingBuilds(DshInstance instance, List<String> packages,
                                               @Nullable Consumer<String> onLine) throws DshException {
        List<String> waiting = DshBuildScripts.unanswered(instance);
        if (waiting.isEmpty()) {
            List<String> refused = refusedAmong(instance, packages).stream()
                    .filter(key -> !ANSWERED_ABOUT.contains(key))
                    .toList();
            if (refused.isEmpty() || DshBuildScriptPolicy.of(instance) != DshBuildScriptPolicy.MANUAL) {
                return false;
            }
            LOG.info("Asking again about " + String.join(", ", refused)
                    + ": instance " + instance.id() + " refused them before, and is installing them again");
            throw new DshBuildScriptApprovalRequired(refused);
        }
        switch (DshBuildScriptPolicy.of(instance)) {
            case AUTO -> {
                // This instance's plugins may build themselves, so the question is already answered:
                // say so, and run the same command again so that the scripts actually run.
                report(onLine, "Allowing install scripts for " + String.join(", ", waiting));
                DshBuildScripts.answer(instance, waiting, true);
                return true;
            }
            case NEVER -> {
                // The answer is no, so the packages are installed and their scripts are not run:
                // installing is not the same decision as running what comes with it.
                report(onLine, "Not running install scripts for " + String.join(", ", waiting));
                DshBuildScripts.answer(instance, waiting, false);
                return true;
            }
            default -> {
                // Somebody has to decide, and only the interface can ask.
                throw new DshBuildScriptApprovalRequired(waiting);
            }
        }
    }

    /// Returns the refused entries a run is about, matched by package name.
    ///
    /// The two sides are written differently on purpose. A package spec is what the interface has —
    /// `dsh-better-sidebar@0.21.1` — and an `allowBuilds` key is pnpm's id for the package, which is
    /// `node-pty` for one from the registry and `build-script-probe@file:../../x.tgz` for one from a
    /// file. Both carry the name before the `@` that introduces the version or the location, so the
    /// name is what they are matched by. A spec that carries no name — a path, a tarball — has its
    /// name read out of the file it points at, and one nothing can be named from matches nothing
    /// rather than matching everything.
    ///
    /// @param instance the instance
    /// @param packages the package specs this run is about
    /// @return the refused keys, in the order the profile lists them
    private static List<String> refusedAmong(DshInstance instance, List<String> packages) throws DshException {
        if (packages.isEmpty()) {
            return List.of();
        }
        Path profileDirectory = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
        List<String> names = packages.stream()
                .map(spec -> nameOf(spec, profileDirectory))
                .filter(java.util.Objects::nonNull)
                .toList();
        return DshBuildScripts.refused(instance).stream()
                .filter(key -> names.contains(packageNameOf(key)))
                .toList();
    }

    /// Returns the name of the package a spec is about.
    ///
    /// A spec that carries a version or a location names its package before the `@`; one that is only
    /// a location names nothing at all — and a location is exactly what the interface passes for a
    /// plugin the user has as a file: [DshLocalPlugins] copies the file into the instance and installs
    /// it by the path inside it, which is what the profile then records. The name is in the file, and
    /// reading it there is what makes the two sides comparable, because it is the same name pnpm read
    /// when it wrote its key.
    ///
    /// Measured, and the defect this exists for: with the path taken as the name, installing the same
    /// file into the same instance again asked nothing at all. The first installation asked, because
    /// pnpm writes the undecided package into the profile itself; every one after it was silent, so a
    /// plugin that cannot load, refused once, could never be allowed.
    ///
    /// @param spec             the specification, which may be a name, a name and a version, or a location
    /// @param profileDirectory the profile's directory, which a relative location is resolved against
    /// @return the name, or `null` when no package can be named from it
    private static @Nullable String nameOf(String spec, Path profileDirectory) {
        Path file = DshPluginBundle.fileOf(spec, profileDirectory);
        // `fileOf` also answers with a path that is not there — the name of a package from the
        // registry comes back as a relative path under the profile — so what is a file is decided by
        // the file system rather than by the shape of the specification.
        if (file != null && Files.isRegularFile(file)) {
            try {
                return DshLocalPlugins.inspect(file).name();
            } catch (DshException e) {
                LOG.warning("Could not read the package at " + file
                        + ", so whether its install scripts were refused cannot be told", e);
                return null;
            }
        }
        return packageNameOf(spec.trim());
    }

    /// Returns the package name in a spec or an `allowBuilds` key.
    ///
    /// The name ends where the version or the location begins. The `@` is looked for from the second
    /// character, so a scope's own `@` is not taken for one: `@scope/pkg@1.2.3` is `@scope/pkg`.
    ///
    /// @param spec a package spec or an `allowBuilds` key
    /// @return the package name
    static String packageNameOf(String spec) {
        int at = spec.indexOf('@', 1);
        return at < 0 ? spec : spec.substring(0, at);
    }

    /// Turns pnpm's ignored-build-script failure into something actionable.
    ///
    /// A freshly created profile carries a placeholder in `pnpm-workspace.yaml`:
    ///
    /// ```yaml
    /// allowBuilds:
    ///   node-pty: set this to true or false
    /// ```
    ///
    /// Until that is resolved, pnpm refuses to run the package's build script
    /// and exits non-zero — after it has already added the package. The raw
    /// output is a wall of pnpm progress lines, so the launcher translates the
    /// condition instead of relaying it.
    ///
    /// The launcher does not resolve the placeholder itself: `allowBuilds`
    /// decides whether arbitrary post-install scripts may run, and that is the
    /// user's decision to make, not the launcher's.
    ///
    /// @param output  the captured command output
    /// @param home    the instance's `DSH_HOME`
    /// @param profile the profile name
    /// @return the explanation, or `null` when this is a different failure
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
        return "The plugin was added, but its build script was not run.\n\n"
                + "pnpm ignored the build script for " + packages + " because the profile still carries the\n"
                + "template placeholder in its pnpm-workspace.yaml. Some features may not work until this\n"
                + "is resolved.\n\n"
                + "To resolve it, run in " + profileDirectory + ":\n"
                + "    pnpm approve-builds\n"
                + "or set allowBuilds for that package to true in\n"
                + "    " + profileDirectory.resolve("pnpm-workspace.yaml");
    }

    /// Reads the entries pnpm named for a package it will not build.
    ///
    /// A plugin fetched from a repository is built by its own `prepare` script, and pnpm refuses
    /// to run one until the profile says it may. For this it does **not** write a placeholder —
    /// the entry has to carry the address pnpm resolved the repository to — and prints the exact
    /// block it wants instead:
    ///
    /// ```yaml
    /// allowBuilds:
    ///   dshmarket@https://codeload.github.com/owner/name/tar.gz/<sha>: true
    /// ```
    ///
    /// Those entries are what this reads, so the launcher can ask about them the way it asks
    /// about every other build script.
    ///
    /// @param output the captured command output
    /// @return the entries, or an empty list when this is a different failure
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

    /// Explains a repository-hosted plugin that will not build.
    ///
    /// The raw output is the same wall of package-manager lines as the other build refusal, and
    /// the condition is worth naming: this plugin has no published build to install, so the
    /// build is not something that can be skipped.
    ///
    /// @param keys    the entries pnpm named
    /// @param home    the instance's `DSH_HOME`
    /// @param profile the profile name
    /// @return the explanation, or `null` when there is nothing to explain
    private static @Nullable String explainGitBuild(List<String> keys, Path home, String profile) {
        if (keys.isEmpty()) {
            return null;
        }
        Path workspace = home.resolve("profiles").resolve(profile).resolve("pnpm-workspace.yaml");
        StringBuilder entries = new StringBuilder("    allowBuilds:\n");
        for (String key : keys) {
            entries.append("      ").append(key).append(": true\n");
        }
        return "This plugin comes from a repository, and a repository-hosted plugin is built when it\n"
                + "is installed — there is no published build to fall back on, so the build has to be\n"
                + "allowed for the installation to finish.\n\n"
                + "pnpm asked for these entries in " + workspace + ":\n"
                + entries
                + "\nAllow this instance's build scripts (Settings → 构建脚本) and install again.";
    }

    /// Returns the last few output lines, for an error message.
    ///
    /// @param lines the captured output
    /// @return the trailing lines joined by newlines
    private static String tail(List<String> lines) {
        int from = Math.max(0, lines.size() - 12);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    /// Forwards a progress message when a consumer is attached.
    ///
    /// @param onLine  the consumer, or `null`
    /// @param message the message
    private static void report(@Nullable Consumer<String> onLine, String message) {
        if (onLine != null) {
            onLine.accept(message);
        }
    }
}
