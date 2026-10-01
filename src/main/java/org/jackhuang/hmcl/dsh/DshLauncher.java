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
            /// Whether this launch is the one that opens the interface.
            ///
            /// One instance, one interface, and exactly one thing that opens it: either the launcher
            /// opens the browser once the harness reports ready, or the harness opens it itself —
            /// never both. Which one it is follows from whether the child was told not to: the flag
            /// is passed when the version's help says it knows it, and when the help did not answer
            /// there is nothing to pass and the harness will open its own tab. Opening a second one
            /// on top of that is the two windows this records the answer to.
            boolean opensTheBrowser) {

        /// Returns the port the browser surface binds.
        ///
        /// @return the port, or `0` for a non-web surface
        public int port() {
            return port;
        }

        /// Reports whether the launcher is the one that opens the interface.
        ///
        /// @return whether the launcher opens the browser
        public boolean launcherOpensTheBrowser() {
            return opensTheBrowser;
        }

        /// Renders the plan as a single shell-ready line, for logs and bug reports.
        ///
        /// The prefix is spelled the way the platform's own shell would spell
        /// it, so a line copied out of the log into a terminal means the same
        /// thing on the machine that reads it.
        ///
        /// @return the command line
        public String commandLine() {
            StringBuilder builder = new StringBuilder();
            boolean windows = org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                    == org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS;
            if (windows) {
                builder.append("set \"DSH_HOME=").append(homeDirectory).append("\" && ");
            } else {
                builder.append("DSH_HOME=").append(homeDirectory).append(' ');
            }
            for (String part : command) {
                builder.append(part.indexOf(' ') >= 0 ? '"' + part + '"' : part);
                builder.append(' ');
            }
            return builder.toString().trim();
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
                    .orElseThrow(DshLauncher::noSystemRuntime);
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

    /// Why no system runtime can be used, said as which of the two things actually happened.
    ///
    /// There are two ways to arrive here, and the message used to name one of them for both: there
    /// is nothing named `node` on `PATH`, or there is one and it does not answer `--version`.
    /// "Not found on PATH" is what a person is told in either case, and for the second it sends them
    /// to check a `PATH` that is correct — measured, on a CI runner, where the launcher said exactly
    /// that about a node it had read the version of a moment before.
    ///
    /// @return the exception to throw
    private static DshException noSystemRuntime() {
        Path node = DshNodeRuntime.which("node").orElse(null);
        if (node == null) {
            return new DshException("Node.js was not found on PATH; " + DshNodeRuntime.requirement());
        }
        return new DshException("The Node.js at " + node + " did not report its version, so whether it is "
                + "supported cannot be told (" + DshNodeRuntime.requirement() + "). "
                + "Running `" + node + " --version` says what is wrong with it.");
    }

    /// How long a version is given to answer a help request.
    ///
    /// Generous, and it has to be: what answers it is a whole application being booted by `node`, on
    /// the machine that is about to run it, and the first launch after an install is the slowest
    /// there is. Five seconds was measured failing on exactly that — a freshly installed 0.1.5-alpha.2
    /// did not answer in time, the flag was left out, and the harness opened a tab of its own on top
    /// of the launcher's. Waiting is not free either, but it happens once per version and profile,
    /// and the wrong answer costs a second window on every launch.
    private static final java.time.Duration PROBE_TIMEOUT = java.time.Duration.ofSeconds(20);

    /// The help that has been read, keyed by version **and profile**, and what it said about
    /// `--no-open`.
    ///
    /// Keyed by both because the answer belongs to both: what is read is
    /// `dsh --profile <name> --help`, and two profiles of one release accept different flags. Cached
    /// by version alone, the first profile to be launched would answer for every other one.
    private static final Map<String, NoOpen> NO_OPEN_SUPPORT = new java.util.concurrent.ConcurrentHashMap<>();

    /// What a version's own help said about `--no-open`.
    ///
    /// Three answers rather than two, because "this release does not know the flag" and "this release
    /// did not answer" call for opposite things. A release whose help *was read* and does not list the
    /// flag is one whose interface has no browser to open and no way to be told about one — every
    /// 0.0.1-rc.x and 0.1.5-rc.x answers its help this way, and the same releases serve the interface
    /// and leave the browser alone — so the launcher has to open it. A release that did not answer is
    /// one that was probably busy **starting**: a help request a server ignores is a server booting,
    /// and the tab it opens for itself is the reason the launcher must not add a second one.
    enum NoOpen {
        /// The help lists `--no-open`, so the flag may be passed and the launcher must open the
        /// interface itself.
        ACCEPTED,

        /// The help was read and does not list `--no-open`: the flag must not be passed, and the
        /// interface does not open a browser of its own either.
        ABSENT,

        /// The help could not be read, so nothing is known: the flag is left out and the interface is
        /// left to open its own tab.
        UNKNOWN
    }

    /// Returns the key the help of a version and profile is remembered under.
    ///
    /// @param version the version
    /// @param profile the profile
    /// @return the key
    static String capabilityKey(String version, String profile) {
        return version + "/" + profile;
    }

    /// Reads what a version's interface says about `--no-open`.
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
    /// @return what the help said
    private static NoOpen noOpenSupport(DshInstance instance) {
        String version = instance.version();
        String key = capabilityKey(version, instance.profile());
        NoOpen cached = NO_OPEN_SUPPORT.get(key);
        if (cached != null) {
            return cached;
        }

        NoOpen answer = NoOpen.UNKNOWN;
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
                            + PROBE_TIMEOUT.toSeconds() + "s; it is more likely to be starting than to be"
                            + " answering, so the launcher leaves the browser to it");
                    NO_OPEN_SUPPORT.put(key, NoOpen.UNKNOWN);
                    return NoOpen.UNKNOWN;
                }
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            answer = helpMentionsNoOpen(output) ? NoOpen.ACCEPTED : NoOpen.ABSENT;
        } catch (DshException | IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Not knowing is not permission: a flag a version does not accept stops it from
            // starting at all.
            LOG.warning("Could not read the help of DeepSeek Harness " + version, e);
        } finally {
            if (probe != null && probe.isAlive()) {
                probe.destroyForcibly();
            }
        }

        NO_OPEN_SUPPORT.put(key, answer);
        return answer;
    }

    /// Reports whether a version's help mentions `--no-open`.
    ///
    /// @param help the help output
    /// @return whether the flag is listed
    static boolean helpMentionsNoOpen(String help) {
        return help != null && help.contains("--no-open");
    }

    /// Decides who opens the interface, which is the whole of the two-window question.
    ///
    /// A version that was told `--no-open` stays off the browser, so somebody has to open it, and that
    /// is what the launcher is for: a person watching a launch should see the interface without having
    /// to find the address.
    ///
    /// A version whose help does **not** list `--no-open` cannot be told anything, and it does not
    /// open a tab of its own either. Its help lists no browser option at all — measured against
    /// 0.0.1-rc.5, whose `--profile web --help` lists `--host`, `--port`, `--trusted-host` and `-h`
    /// and nothing else — and the release it belongs to serves the interface and leaves the browser
    /// alone, which is what an instance on it was reported doing: started, serving, and no browser
    /// anywhere. So the launcher opens it for those too. Assuming the opposite is what left them with
    /// no browser at all.
    ///
    /// Only a version that could not be asked is left to open its own tab, because the usual reason
    /// for that is that it is busy **starting** — the five-second probe that timed out on
    /// 0.1.5-alpha.2 is the case the second window was reported from: the flag was left out, and the
    /// harness, already booting, opened a tab while the launcher opened another.
    ///
    /// @param web         whether the surface serves an address at all
    /// @param surfaceAsks whether the surface wants the launcher to open the interface
    /// @param support     what the version's own help said about `--no-open`
    /// @return whether the launcher opens the interface
    static boolean launcherOpensTheInterface(boolean web, boolean surfaceAsks, NoOpen support) {
        if (!web || !surfaceAsks) {
            // Nothing to open: an app that serves no address has no window, and a surface that did
            // not ask for one is not the launcher's to open.
            return false;
        }
        return support != NoOpen.UNKNOWN;
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
        return plan(instance, account, null);
    }

    /// Builds the launch plan for an instance, with an account whose route is already described.
    ///
    /// A caller that has to show the person what it is doing — the launch dialog, which names each
    /// step as it happens — describes the account itself, asks the supplier for its models, and hands
    /// the result here. A caller with nothing to show passes `null` and the route is written here.
    ///
    /// @param instance  the instance to launch
    /// @param account   the account to hand the harness, or `null` for none
    /// @param prepared  the described route, or `null` to write it in this call
    /// @return the launch plan
    /// @throws DshException when the pinned version or runtime is missing, the
    ///                       entry script is absent, or the workspace cannot be created
    public static LaunchPlan plan(DshInstance instance, @Nullable DshAccount account,
                                  @Nullable DshAccountRoute.Prepared prepared)
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

        // From what the profile boots, not from what it is called: a pack's profile is named after
        // the pack and still boots the browser app.
        DshSurface surface = DshSurface.of(instance);

        // The port is settled here rather than left to the child, and it is the
        // same one on every launch of this instance.
        int port = surface.isWeb() ? DshPorts.resolve(instance) : 0;

        // What the instance runs with, as the user typed it. Its launcher flags go before the
        // profile and its app flags after; `--port` and `DSH_HOME` are refused and reported. Read
        // first because which profile is booted decides which profile every file below belongs to.
        DshLaunchArguments.Parsed typed = DshLaunchArguments.parseArguments(instance.extraArguments());
        String profile = typed.profile() != null ? typed.profile() : instance.profile();

        // Anything a launch that was killed before it could tidy up left behind, put back first: the
        // note beside the harness's own settings says the last launch never got to its own cleanup,
        // so it is dealt with here — **before** this launch decides what to inject, and whatever kind
        // of launch this is: one with no account tidies up after one that had a key, which is what
        // keeps a supplier out of a launch that wants none.
        // Every name the launcher builds routes under: the accounts that carry a key, which are the
        // ones it makes a supplier for.
        java.util.List<String> accountRoutes = new java.util.ArrayList<>();
        for (DshAccount known : org.jackhuang.hmcl.setting.SettingsManager.settings().getAccounts()) {
            if (known.carriesAKey()) {
                accountRoutes.add(known.displayName());
            }
        }
        // And the route this launch itself will build, if it builds one.
        String injecting = account != null && account.carriesAKey() ? account.displayName() : null;

        try {
            DshInjectedSettings.settle(instance);
            // Then the launcher's own work from launches that are long over, taken away without
            // asking: a route left behind is a supplier the harness offers with nothing behind it,
            // and a launch that wants no supplier must not inherit one.
            DshInjectedSettings.clean(instance, profile, accountRoutes, injecting);
        } catch (DshException e) {
            LOG.warning("Could not put back what the last launch of " + instance.id()
                    + " left in its settings", e);
        }

        // And the same contract in the home's own .env, which outlives this launch: a
        // person who starts the harness by hand — or from the terminal this launcher opens
        // on it — gets the answers a launcher's own launch gives. Not fatal when it cannot
        // be written: this run already carries the values in its environment.
        try {
            DshAccountContract.publish(instance, account);
        } catch (DshException e) {
            LOG.warning("Could not write the account contract into the home of " + instance.id(), e);
        }

        // What this launch is about to disturb, noted before it does. Written here because it lives
        // exactly as long as the launch does.
        if (account != null && account.carriesAKey()) {
            DshInjectedSettings.capture(instance, account.displayName(), account.key(), profile);
            // Then the profile object, so the route is one the harness's own models page can see and
            // edit while it runs. The note written just above is what takes it away again — which is
            // why this comes second and not before.
            try {
                DshInjectedSettings.publish(instance, account.displayName(),
                        DshAccountRoute.environmentVariable(account.displayName()));
            } catch (DshException e) {
                // Not fatal: the route still works, it is only not on that page.
                LOG.warning("Could not make " + account.displayName() + " visible on the models page", e);
            }
        }

        // The account's supplier, as a route in the profile's **own** patch layer — the file the
        // harness's own configuration editor writes. Not a `--patch` overlay: an overlay is applied
        // over that file and replaces the entry's whole config, which is what made adding a supplier
        // impossible and hid the person's own suppliers. It stays there for this launch only; the
        // note captured above is what takes it back out. See DshAccountRoute.
        //
        // A caller that described the route already — so it could say so while the supplier is asked
        // — wrote its own copy, and is not asked a second time.
        DshAccountRoute.Prepared route = prepared;
        if (route == null && account != null && account.carriesAKey()) {
            route = DshAccountRoute.prepare(account).orElse(null);
            if (route != null) {
                route.resolveModels(account);
            }
        }
        if (route != null) {
            DshAccountRoute.apply(home, profile, route);
        }

        // A route says what the harness is offered; it cannot make the harness *use* one, because
        // the person's own answer outranks a route the launcher wrote. So the default model is
        // written where that answer lives — and, when the account names no model, not written at all.
        //
        // The provider written here is the **account's name**, which is the route name just created.
        // It used to be the vendor's id, which stopped being the route name when routes became the
        // person's own suppliers — and a default naming a route that does not exist is a harness that
        // will not start.
        //
        // For the launcher's own vendor there is nothing to write either way: the harness knows that
        // vendor's catalogue and picks from it, so naming a model would be naming one of a list it
        // can already read.
        if (account != null && account.carriesAKey()
                && account.kind() != DshAccount.AccountKind.OFFICIAL) {
            try {
                DshDefaultModel.apply(home, account.displayName(), account.modelOrDefault());
            } catch (DshException e) {
                // Not fatal: the route is still there and can be chosen by hand. What must not
                // happen is a launch that does not start because a convenience could not be set.
                LOG.warning("Could not set the default model for " + instance.id(), e);
            }
        }

        List<String> command = new ArrayList<>();
        command.add(runtime.node().toString());
        command.add(script.toString());
        command.addAll(typed.launcherArguments());
        // The profile is stated after the user's launcher flags, so one they named wins; when they
        // named none this is the instance's own.
        command.add("--profile");
        command.add(profile);

        List<String> surfaceArguments = new ArrayList<>(surface.arguments(port));
        // The browser is the surface's own business, and whether this launch leaves it to the harness
        // is what these three lines decide: the surface asks for the launcher to open it, and what the
        // version's own help said decides whether the harness can be told to stay off.
        //
        // The typed line does not enter into it. It used to: a line that mentioned `--no-open` had the
        // flag passed and one that did not had nothing passed, on the reasoning that the harness would
        // then open the tab itself. That reasoning is what left old versions with no browser at all —
        // see below — so the question is asked of the version, which is the only thing that knows, and
        // the user's own arguments are appended afterwards exactly as they typed them.
        boolean wantsToOpen = surfaceArguments.remove("--no-open");
        NoOpen support = noOpenSupport(instance);
        boolean noOpen = wantsToOpen && support == NoOpen.ACCEPTED;
        if (noOpen) {
            surfaceArguments.add("--no-open");
        }
        command.addAll(surfaceArguments);
        // The app arguments as typed. A `--no-open` in them belongs to the app and stays where the
        // user put it, which is why it is not filtered out here.
        command.addAll(typed.appArguments());

        boolean launcherOpens = launcherOpensTheInterface(surface.isWeb(), wantsToOpen, support);
        if (surface.isWeb()) {
            LOG.info("DeepSeek Harness " + instance.version() + " "
                    + switch (support) {
                        case ACCEPTED -> "was told --no-open, so the launcher opens the interface";
                        case ABSENT -> "has no --no-open and opens no browser of its own,"
                                + " so the launcher opens the interface";
                        case UNKNOWN -> "could not be asked about --no-open, so the launcher leaves the"
                                + " browser to it";
                    });
        }

        Map<String, String> environment = new LinkedHashMap<>();
        // The key travels here and nowhere else: an inherited variable is the highest-precedence
        // source the harness reads, and it is gone when the process is. One variable per route, so
        // that a route left in the profile by another account names a variable nothing sets rather
        // than picking this key up. See DshAccountRoute.
        if (account != null && account.carriesAKey()) {
            String key = account.apiKey().trim();
            environment.put(DshAccountRoute.environmentVariable(account.displayName()), key);
            // And the harness's own web search, which reads a name of the vendor's rather than a
            // route's — and only for a DeepSeek route, because the service behind that entry is
            // DeepSeek's and another vendor's key would be refused by it. The entry itself is not
            // touched: the launcher used to patch it, which made it unsavable in the settings page
            // for exactly the reason the supplier route was.
            if (route != null && route.deepSeek()) {
                environment.put(DshAccountRoute.WEB_SEARCH_ENVIRONMENT_VARIABLE, key);
            }
        }
        environment.putAll(runtime.pathEnvironment());
        environment.putAll(DshEnvironment.of(instance));
        // The home **after** the sets a person wrote, not before. `DshEnvironment.of` refuses a
        // `DSH_HOME` of their own, so the two orders agree about what the value is; stating it last
        // is what makes that true by construction rather than by that refusal being remembered.
        environment.put(DshEnvironment.HOMEDIRECTORY_VARIABLE, home.toString());
        // What a plugin needs to show who this instance is running as. Last, so the
        // contract states what is rather than what somebody typed: it is a description of
        // this launch, and a description that can be quietly overwritten is a lie waiting
        // to happen. See DshAccountContract for the same values in the home's .env.
        environment.putAll(DshAccountContract.values(instance, account));

        LOG.debug("Launching " + instance.id() + " with the command: " + String.join(" ", command));
        return new LaunchPlan(instance, surface, List.copyOf(command), workspace,
                Map.copyOf(environment), home, port, launcherOpens);
    }
}
