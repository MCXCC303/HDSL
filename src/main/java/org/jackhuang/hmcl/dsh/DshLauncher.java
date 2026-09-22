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
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Files;
import java.io.IOException;
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
            int port) {

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

    /// The versions whose help has been read, and whether it mentions `--no-open`.
    private static final Map<String, Boolean> NO_OPEN_SUPPORT = new java.util.concurrent.ConcurrentHashMap<>();

    /// Reports whether a version's interface accepts `--no-open`.
    ///
    /// Asked of the version itself rather than decided from its number: what a
    /// release accepts is what its own help says, and a list of version numbers that
    /// once needed the flag is a list that goes stale.
    ///
    /// @param instance the instance
    /// @return whether the flag may be passed
    private static boolean acceptsNoOpen(DshInstance instance) {
        String version = instance.version();
        Boolean cached = NO_OPEN_SUPPORT.get(version);
        if (cached != null) {
            return cached;
        }

        boolean accepted = false;
        try {
            DshNodeRuntime runtime = resolveRuntime(instance);
            Path script = instance.dshEntryPoint();
            DshCommand.Result result = DshCommand.run(
                    List.of(runtime.node().toString(), script.toString(), "--help"),
                    instance.workspacePath(), Map.of("DSH_HOME", instance.homeDirectory().toString()),
                    null);
            accepted = helpMentionsNoOpen(String.join("\n", result.output()));
        } catch (DshException | IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Not knowing is not permission: a flag a version does not accept stops
            // it from starting at all, and the cost of leaving it out is a browser
            // tab the harness opens for itself.
            LOG.warning("Could not read the help of DeepSeek Harness " + version, e);
        }

        NO_OPEN_SUPPORT.put(version, accepted);
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

        List<String> command = new ArrayList<>();
        command.add(runtime.node().toString());
        command.add(script.toString());
        command.add("--profile");
        command.add(instance.profile());
        // `--no-open` is newer than the browser surface is: an old release that does
        // not know the flag exits rather than starting, so it is only passed to a
        // version whose own help mentions it. The launcher opens the browser once the
        // readiness line arrives either way.
        List<String> surfaceArguments = new ArrayList<>(surface.arguments(port));
        if (surfaceArguments.remove("--no-open") && acceptsNoOpen(instance)) {
            surfaceArguments.add("--no-open");
        }
        command.addAll(surfaceArguments);
        command.addAll(instance.extraArguments());

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("DSH_HOME", home.toString());
        environment.putAll(runtime.pathEnvironment());
        environment.putAll(instance.environment());

        LOG.debug("Launching " + instance.id() + " with the command: " + String.join(" ", command));
        return new LaunchPlan(instance, surface, List.copyOf(command), workspace,
                Map.copyOf(environment), home, port);
    }
}
