/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Files;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Turns an [DshInstance] into the exact command and environment to run.
///
/// The launcher always invokes the version's own `lib/bin.js` with `node`
/// rather than a `dsh` shim on `PATH`. That is what pins a profile to a
/// specific installation: DeepSeek Harness resolves its own bundles from the
/// installation that booted it.
///
/// `DSH_*` variables are written into the child's environment rather than into
/// a `.env` file, because upstream refuses to read `DSH_`-prefixed names from
/// `.env` files.
@NotNullByDefault
public final class DshLauncher {
    private DshLauncher() {
    }

    /// The complete plan for one launch.
    ///
    /// @param instance         the instance being launched
    /// @param version          the pinned version
    /// @param surface          the surface derived from the instance's profile
    /// @param command          the program and its arguments, in order
    /// @param workingDirectory the directory sessions are scoped to
    /// @param environment      the child environment
    /// @param homeDirectory    the `DSH_HOME` the child is given
    public record LaunchPlan(
            DshInstance instance,
            DshSurface surface,
            @Unmodifiable List<String> command,
            Path workingDirectory,
            @Unmodifiable Map<String, String> environment,
            Path homeDirectory,
            int port,
            /// The account overlay this launch wrote, or `null` when there was none. Removed when
            /// the instance stops: it belongs to one launch, not to the instance.
            @Nullable Path accountOverlay) {

        /// Returns the port the browser surface binds.
        ///
        /// @return the port, or `0` for a non-web surface
        public int port() {
            return port;
        }

        /// Renders the plan as a single shell-ready line, for logs and bug reports.
        ///
        /// @return the command line
        public String commandLine() {
            StringBuilder builder = new StringBuilder();
            builder.append("DSH_HOME=").append(homeDirectory).append(' ');
            for (String part : command) {
                if (!builder.isEmpty()) {
                    builder.append(' ');
                }
                builder.append(part.indexOf(' ') >= 0 ? '"' + part + '"' : part);
            }
            return builder.toString();
        }
    }

    /// Resolves the Node runtime an instance is pinned to.
    ///
    /// A system runtime is rejected when it falls outside DeepSeek Harness's
    /// `engines.node` range, because the failure would otherwise surface much
    /// later as an obscure startup error inside the child process.
    ///
    /// @param instance the instance
    /// @return the resolved runtime
    /// @throws DshException when the selected runtime is missing or unsupported
    public static DshNodeRuntime resolveRuntime(DshInstance instance) throws DshException {
        String selection = instance.nodeRuntimeOrDefault();
        if (DshNodeRuntime.SYSTEM.equalsIgnoreCase(selection)) {
            DshNodeRuntime runtime = DshNodeRuntime.detect()
                    .orElseThrow(() -> new DshException("Node.js was not found on PATH; "
                            + DshNodeRuntime.requirement()));
            if (!runtime.isNodeSupported()) {
                throw new DshException("The system Node.js " + runtime.nodeVersion()
                        + " is outside the supported range (" + DshNodeRuntime.requirement()
                        + "). Install a suitable runtime on the Node page, or pick one for this instance.");
            }
            return runtime;
        }

        NodeRuntime managed = NodeRuntimeManager.findInstalled(selection);
        if (managed == null) {
            throw new DshException("Node.js " + selection
                    + " is not installed; install it on the Node page or switch this instance to the system runtime");
        }
        return DshNodeRuntime.fromManaged(managed);
    }

    /// How long a version is given to answer a help request.
    private static final java.time.Duration PROBE_TIMEOUT = java.time.Duration.ofSeconds(5);

    /// The help that has been read, keyed by version **and profile**, and whether it mentions
    /// `--no-open`.
    ///
    /// Keyed by both because the answer belongs to both: what is read is
    /// `dsh --profile <name> --help`, and two profiles of one release accept different flags. Cached
    /// by version alone, the first profile to be launched would answer for every other one.
    private static final Map<String, Boolean> NO_OPEN_SUPPORT = new java.util.concurrent.ConcurrentHashMap<>();

    /// Returns the key the help of a version and profile is remembered under.
    ///
    /// @param version the version
    /// @param profile the profile
    /// @return the key
    static String capabilityKey(String version, String profile) {
        return version + "/" + profile;
    }

    /// Reports whether a version's interface accepts `--no-open`.
    ///
    /// Asked of the version itself rather than decided from its number: what a
    /// release accepts is what its own help says, and a list of version numbers that
    /// once needed the flag is a list that goes stale.
    ///
    /// The help that has to be read is the **profile's**, not the launcher's:
    /// `dsh --help` lists the profile selection and the launcher's own flags, and says nothing about
    /// what the booted app accepts. Reading that one always answers "no", which is why every launch
    /// passed no `--no-open` and the harness opened a tab of its own on top of the launcher's —
    /// two tabs for one instance. `dsh --profile <name> --help` is the request the tool documents
    /// for this ("`dsh --profile web -h` prints the web app's help, not this one's").
    ///
    /// @param instance the instance
    /// @return whether the flag may be passed
    private static boolean acceptsNoOpen(DshInstance instance) {
        String version = instance.version();
        String key = capabilityKey(version, instance.profile());
        Boolean cached = NO_OPEN_SUPPORT.get(key);
        if (cached != null) {
            return cached;
        }

        boolean accepted = false;
        Process probe = null;
        try {
            DshNodeRuntime runtime = resolveRuntime(instance);
            Path script = instance.dshEntryPoint();
            ProcessBuilder builder = new ProcessBuilder(
                    runtime.node().toString(), script.toString(),
                    "--profile", instance.profile(), "--help");
            builder.redirectErrorStream(true);
            builder.environment().put("DSH_HOME", instance.homeDirectory().toString());
            if (instance.workspacePath() != null) {
                builder.directory(instance.workspacePath().toFile());
            }
            probe = builder.start();

            // Bounded, because what is being asked may not be what a program that only wants to
            // start would answer: a help request that is ignored leaves a process running for as
            // long as it likes, and waiting for it would be waiting for an instance to finish.
            String output;
            try (InputStream stream = probe.getInputStream()) {
                if (!probe.waitFor(PROBE_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                    // Left out rather than waited for — and remembered, like every other answer:
                    // returning here would ask the same question again on the next launch.
                    LOG.info("DeepSeek Harness " + version + " did not answer --help within "
                            + PROBE_TIMEOUT.toSeconds() + "s; leaving the flag out");
                    NO_OPEN_SUPPORT.put(key, false);
                    return false;
                }
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            accepted = helpMentionsNoOpen(output);
        } catch (DshException | IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Not knowing is not permission: a flag a version does not accept stops it from
            // starting at all, and the cost of leaving it out is a browser tab the harness opens
            // for itself.
            LOG.warning("Could not read the help of DeepSeek Harness " + version, e);
        } finally {
            if (probe != null && probe.isAlive()) {
                probe.destroyForcibly();
            }
        }

        NO_OPEN_SUPPORT.put(key, accepted);
        return accepted;
    }

    /// Reports whether a version's help mentions `--no-open`.
    ///
    /// @param help the help output
    /// @return whether the flag is listed
    static boolean helpMentionsNoOpen(String help) {
        return help != null && help.contains("--no-open");
    }

    /// Builds the launch plan for an instance.
    ///
    /// @param instance the instance to launch
    /// @return the launch plan
    /// @throws DshException when the pinned version or runtime is missing, the
    ///                       entry script is absent, or the workspace cannot be created
    public static LaunchPlan plan(DshInstance instance) throws DshException {
        return plan(instance, null);
    }

    /// Builds the launch plan for an instance, with an account if one was chosen.
    ///
    /// @param instance the instance to launch
    /// @param account  the account to hand the harness, or `null` for none
    /// @return the launch plan
    /// @throws DshException when the pinned version or runtime is missing, the
    ///                       entry script is absent, or the workspace cannot be created
    public static LaunchPlan plan(DshInstance instance, @Nullable DshAccount account)
            throws DshException {
        DshNodeRuntime runtime = resolveRuntime(instance);
        // The instance runs its own copy, so there is nothing to look up: either
        // its copy is there or the instance is not ready to run.
        Path script = instance.dshEntryPoint();
        if (!Files.isRegularFile(script)) {
            throw new DshException("Instance " + instance.id()
                    + " has no DeepSeek Harness of its own; " + script + " is missing");
        }

        Path workspace = instance.workspacePath();
        try {
            Files.createDirectories(workspace);
        } catch (java.io.IOException e) {
            throw new DshException("The working directory " + workspace + " cannot be created", e);
        }

        Path home = instance.homeDirectory();
        if (instance.homeMode() == DshHomeMode.ISOLATED) {
            try {
                Files.createDirectories(home);
            } catch (java.io.IOException e) {
                throw new DshException("The instance home " + home + " cannot be created", e);
            }
        }

        DshSurface surface = DshSurface.ofProfile(instance.profile());

        // The port is settled here rather than left to the child, and it is the
        // same one on every launch of this instance.
        int port = surface.isWeb() ? DshPorts.resolve(instance) : 0;

        // The account, as an overlay the harness applies over its composed tree. Written here and
        // removed when the instance stops, so nothing of the user's own configuration is changed.
        java.util.Optional<Path> accountOverlay = DshAccountOverlay.write(instance, account);

        // An overlay adds a route; it cannot make the harness *use* one, because layers merge with
        // the user's settings on top and the overlay is the layer that loses. So the default model
        // is written where the user's own answer lives — and, when the account names no model, not
        // written at all: the harness refuses to start on a default it cannot resolve.
        if (account != null) {
            try {
                DshDefaultModel.apply(home, account.vendorId(), account.modelOrDefault());
            } catch (DshException e) {
                // Not fatal: the route is still there and can be chosen by hand. What must not
                // happen is a launch that does not start because a convenience could not be set.
                LOG.warning("Could not set the default model for " + instance.id(), e);
            }
        }

        // What the instance runs with, as the user typed it. Its launcher flags go before the
        // profile and its app flags after; `--port` and `DSH_HOME` are refused and reported.
        DshLaunchArguments.Parsed typed = DshLaunchArguments.parseArguments(instance.extraArguments());

        List<String> command = new ArrayList<>();
        command.add(runtime.node().toString());
        command.add(script.toString());
        command.addAll(typed.launcherArguments());
        // The overlay comes after the user's own launcher flags, so a `--patch` of theirs still
        // applies first and their line keeps its meaning.
        accountOverlay.ifPresent(patch -> {
            command.add("--patch");
            command.add(patch.toString());
        });
        // The profile is stated after the user's launcher flags, so one they named wins; when they
        // named none this is the instance's own.
        command.add("--profile");
        command.add(typed.profile() != null ? typed.profile() : instance.profile());

        List<String> surfaceArguments = new ArrayList<>(surface.arguments(port));
        // Whether to leave the browser alone is the user's to decide once they have said anything
        // at all about how the instance starts: a line that mentions `--no-open` gets it and one
        // that does not gets nothing, so the harness opens the tab itself and the launcher does not
        // add a second one. With no line typed the launcher asks the version whether it knows the
        // flag, because passing one an old release rejects is what stops a launch outright.
        boolean noOpen = surfaceArguments.remove("--no-open")
                && (typed.appArguments().isEmpty()
                        ? acceptsNoOpen(instance)
                        : typed.asksNoOpen());
        if (noOpen) {
            surfaceArguments.add("--no-open");
        }
        command.addAll(surfaceArguments);
        // The app arguments as typed. A `--no-open` in them belongs to the app and stays where the
        // user put it, which is why it is not filtered out here.
        command.addAll(typed.appArguments());

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("DSH_HOME", home.toString());
        // The key travels here and nowhere else: an inherited variable is the highest-precedence
        // source the harness reads, and it is gone when the process is.
        if (account != null && account.apiKey() != null && !account.apiKey().isBlank()) {
            environment.put(DshAccountOverlay.KEY_ENVIRONMENT_VARIABLE, account.apiKey().trim());
        }
        environment.putAll(runtime.pathEnvironment());
        environment.putAll(DshEnvironment.of(instance));

        LOG.debug("Launching " + instance.id() + " with the command: " + String.join(" ", command));
        return new LaunchPlan(instance, surface, List.copyOf(command), workspace,
                Map.copyOf(environment), home, port, accountOverlay.orElse(null));
    }
}
