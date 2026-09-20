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

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A small headless command surface for version and instance management.
///
/// This exists so that the launcher's core can be driven, scripted and verified
/// without a display. The GUI is a second consumer of exactly the same
/// [DshVersionManager] and [DshInstanceManager] calls.
@NotNullByDefault
public final class DshCli {
    private DshCli() {
    }

    /// The commands understood by the launcher.
    public enum Command {
        /// Prints a full diagnostics report.
        DOCTOR,
        /// Prints the installed versions.
        LIST_INSTALLED,
        /// Prints the versions published to the npm registry.
        LIST_REMOTE,
        /// Installs one version.
        INSTALL,
        /// Removes one version.
        UNINSTALL,
        /// Prints the instances.
        LIST_INSTANCES,
        /// Creates an instance.
        CREATE_INSTANCE,
        /// Removes an instance.
        DELETE_INSTANCE,
        /// Launches an instance and blocks until it exits.
        LAUNCH,
        /// Prints the instances that are currently running.
        LIST_RUNNING,
        /// Stops a running instance.
        STOP,
        /// Lists the Node runtimes the launcher installed.
        LIST_RUNTIMES,
        /// Lists the Node releases available for this platform.
        LIST_NODE_VERSIONS,
        /// Installs a Node runtime.
        INSTALL_NODE,
        /// Removes an installed Node runtime.
        UNINSTALL_NODE,
        /// Installs a plugin into an instance's profile.
        INSTALL_PLUGIN
    }

    /// A parsed command line.
    ///
    /// @param command    the requested command
    /// @param arguments  positional arguments, the first being the subject
    /// @param options    the `--key value` options
    /// @param showHelp   whether help was requested
    public record Invocation(Command command, List<String> arguments,
                             Map<String, String> options, boolean showHelp) {
        /// Returns the first positional argument.
        ///
        /// @return the subject, or `null` when none was given
        public @Nullable String subject() {
            return arguments.isEmpty() ? null : arguments.get(0);
        }

        /// Returns an option value.
        ///
        /// @param name the option name without leading dashes
        /// @return the value, or `null` when the option was not given
        public @Nullable String option(String name) {
            return options.get(name);
        }
    }

    /// Parses launcher arguments into an invocation.
    ///
    /// @param args the raw command-line arguments
    /// @return the parsed invocation, or `null` when the arguments request the GUI
    public static @Nullable Invocation parse(List<String> args) {
        if (!args.contains("--doctor")
                && !args.contains("--list-installed")
                && !args.contains("--list-versions")
                && !args.contains("--list-instances")
                && !args.contains("--install")
                && !args.contains("--uninstall")
                && !args.contains("--create-instance")
                && !args.contains("--delete-instance")
                && !args.contains("--launch")
                && !args.contains("--list-running")
                && !args.contains("--stop")
                && !args.contains("--list-runtimes")
                && !args.contains("--list-node-versions")
                && !args.contains("--install-node")
                && !args.contains("--uninstall-node")
                && !args.contains("--install-plugin")) {
            return null;
        }

        Command command = Command.DOCTOR;
        List<String> positional = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        boolean help = false;

        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            switch (token) {
                case "--doctor" -> command = Command.DOCTOR;
                case "--list-installed" -> command = Command.LIST_INSTALLED;
                case "--list-versions" -> command = Command.LIST_REMOTE;
                case "--list-instances" -> command = Command.LIST_INSTANCES;
                case "--help", "-h" -> help = true;
                case "--install" -> {
                    command = Command.INSTALL;
                    if (i + 1 < args.size()) positional.add(args.get(++i));
                }
                case "--uninstall" -> {
                    command = Command.UNINSTALL;
                    if (i + 1 < args.size()) positional.add(args.get(++i));
                }
                case "--create-instance" -> {
                    command = Command.CREATE_INSTANCE;
                    if (i + 1 < args.size()) positional.add(args.get(++i));
                }
                case "--delete-instance" -> {
                    command = Command.DELETE_INSTANCE;
                    if (i + 1 < args.size()) positional.add(args.get(++i));
                }
                case "--launch" -> {
                    command = Command.LAUNCH;
                    if (i + 1 < args.size()) positional.add(args.get(++i));
                }
                case "--list-running" -> command = Command.LIST_RUNNING;
                case "--list-runtimes" -> command = Command.LIST_RUNTIMES;
                case "--list-node-versions" -> command = Command.LIST_NODE_VERSIONS;
                case "--install-node" -> {
                    command = Command.INSTALL_NODE;
                    if (i + 1 < args.size()) positional.add(args.get(++i));
                }
                case "--uninstall-node" -> {
                    command = Command.UNINSTALL_NODE;
                    if (i + 1 < args.size()) positional.add(args.get(++i));
                }
                case "--install-plugin" -> {
                    command = Command.INSTALL_PLUGIN;
                    if (i + 1 < args.size()) positional.add(args.get(++i));
                    if (i + 1 < args.size()) positional.add(args.get(++i));
                }
                case "--stop" -> {
                    command = Command.STOP;
                    if (i + 1 < args.size()) positional.add(args.get(++i));
                }
                default -> {
                    if (token.startsWith("--")) {
                        String name = token.substring(2);
                        if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
                            options.put(name, args.get(++i));
                        } else {
                            options.put(name, "true");
                        }
                    } else {
                        positional.add(token);
                    }
                }
            }
        }

        return new Invocation(command, List.copyOf(positional), Map.copyOf(options), help);
    }

    /// Runs a parsed command.
    ///
    /// @param invocation the command to run
    /// @param out        the stream for normal output
    /// @param err        the stream for error output
    /// @return the process exit code
    public static int run(Invocation invocation, PrintStream out, PrintStream err) {
        if (invocation.showHelp() || (invocation.command() != Command.DOCTOR && invocation.subject() == null
                && invocation.command() != Command.LIST_INSTALLED
                && invocation.command() != Command.LIST_REMOTE
                && invocation.command() != Command.LIST_INSTANCES
                && invocation.command() != Command.LIST_RUNNING
                && invocation.command() != Command.LIST_RUNTIMES
                && invocation.command() != Command.LIST_NODE_VERSIONS)) {
            printUsage(out);
            return invocation.showHelp() ? 0 : 1;
        }

        try {
            switch (invocation.command()) {
                case DOCTOR -> {
                    return DshDoctor.report(out);
                }
                case LIST_INSTALLED -> {
                    List<DshVersion> installed = DshVersionManager.listInstalled();
                    if (installed.isEmpty()) {
                        out.println("(no installed versions)");
                    }
                    installed.forEach(version -> out.println(version.version() + "\t" + version.directory()));
                    return 0;
                }
                case LIST_REMOTE -> {
                    for (DshRelease release : DshVersionManager.fetchReleases()) {
                        String tag = release.primaryTag();
                        out.println(release.version() + (tag == null ? "" : "\t" + tag));
                    }
                    return 0;
                }
                case INSTALL -> {
                    out.println("Installing DeepSeek Harness " + invocation.subject() + " ...");
                    DshVersion version = DshVersionManager.install(invocation.subject(), out::println);
                    out.println("Installed " + version.version() + " into " + version.directory());
                    return 0;
                }
                case UNINSTALL -> {
                    DshVersionManager.uninstall(invocation.subject());
                    out.println("Removed " + invocation.subject());
                    return 0;
                }
                case LIST_INSTANCES -> {
                    List<DshInstance> instances = DshInstanceManager.list();
                    if (instances.isEmpty()) {
                        out.println("(no instances)");
                    }
                    for (DshInstance instance : instances) {
                        out.println(instance.id()
                                + "\tdsh " + instance.version()
                                + "\tprofile " + instance.profile()
                                + "\t" + instance.homeMode()
                                + "\thome " + instance.homeDirectory()
                                + "\tworkspace " + instance.workspacePath());
                    }
                    return 0;
                }
                case CREATE_INSTANCE -> {
                    return createInstance(invocation, out, err);
                }
                case DELETE_INSTANCE -> {
                    DshInstanceManager.delete(invocation.subject());
                    out.println("Removed instance " + invocation.subject());
                    return 0;
                }
                case LAUNCH -> {
                    return launchInstance(invocation, out, err);
                }
                case LIST_RUNNING -> {
                    List<DshProcess> running = DshProcessManager.running();
                    if (running.isEmpty()) {
                        out.println("(nothing running)");
                    }
                    for (DshProcess process : running) {
                        out.println(process.instance().id()
                                + "\t" + process.state()
                                + "\t" + process.plan().surface()
                                + "\t" + process.webUrl().map(Object::toString).orElse("-")
                                + "\tup " + process.uptime().toSeconds() + "s");
                    }
                    return 0;
                }
                case LIST_RUNTIMES -> {
                    List<NodeRuntime> runtimes = NodeRuntimeManager.listInstalled();
                    if (runtimes.isEmpty()) {
                        out.println("(no managed Node runtimes)");
                    }
                    for (NodeRuntime runtime : runtimes) {
                        out.println(runtime.version() + "\t" + runtime.directory());
                    }
                    return 0;
                }
                case LIST_NODE_VERSIONS -> {
                    for (NodeRelease release : NodeRuntimeManager.fetchReleases()) {
                        out.println(release.version()
                                + (release.isLts() ? "\t" + release.label() : "")
                                + (release.isSupported() ? "" : "\tunsupported"));
                    }
                    return 0;
                }
                case INSTALL_NODE -> {
                    out.println("Installing Node.js " + invocation.subject() + " ...");
                    NodeRuntime runtime = NodeRuntimeManager.install(invocation.subject(), out::println);
                    out.println("Installed Node.js " + runtime.version() + " into " + runtime.directory());
                    return 0;
                }
                case UNINSTALL_NODE -> {
                    NodeRuntimeManager.uninstall(invocation.subject());
                    out.println("Removed Node.js " + invocation.subject());
                    return 0;
                }
                case INSTALL_PLUGIN -> {
                    if (invocation.arguments().size() < 2) {
                        err.println("error: --install-plugin needs an instance and a package spec");
                        return 1;
                    }
                    DshInstance instance = DshInstanceManager.find(invocation.arguments().get(0));
                    if (instance == null) {
                        err.println("error: instance " + invocation.arguments().get(0) + " does not exist");
                        return 1;
                    }
                    String spec = invocation.arguments().get(1);
                    out.println("Installing " + spec + " into " + instance.id() + " ...");
                    DshPluginInstaller.install(instance,
                            List.of(new DshPreset(spec, spec, spec, "", false, true)),
                            out::println);
                    out.println("Installed " + spec);
                    for (String bundle : DshPluginInstaller.readBundles(instance.homeDirectory(), instance.profile())) {
                        out.println("  bundle: " + bundle);
                    }
                    return 0;
                }
                case STOP -> {
                    out.println(DshProcessManager.stop(invocation.subject())
                            ? "Stopped " + invocation.subject()
                            : "Instance " + invocation.subject() + " is not running");
                    return 0;
                }
                default -> {
                    err.println("Unhandled command: " + invocation.command());
                    return 2;
                }
            }
        } catch (DshException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    /// Launches an instance and blocks until it exits.
    ///
    /// The launcher keeps child processes in its own process group: the JVM
    /// shutdown hook stops them, so reattaching or interrupting this command
    /// never leaves an orphaned `dsh` behind.
    ///
    /// @param invocation the parsed invocation
    /// @param out        the stream for normal output
    /// @param err        the stream for error output
    /// @return the process exit code
    private static int launchInstance(Invocation invocation, PrintStream out, PrintStream err)
            throws DshException {
        DshInstance instance = DshInstanceManager.find(invocation.subject());
        if (instance == null) {
            err.println("error: instance " + invocation.subject() + " does not exist");
            return 1;
        }

        DshProcess process = DshProcessManager.launch(instance);
        process.setLogSink(line -> System.out.println("[" + instance.id() + "] " + line));

        long deadline = System.currentTimeMillis() + java.time.Duration.ofSeconds(90).toMillis();
        while (process.state() == DshProcess.State.STARTING
                && process.isRunning()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (process.webUrl().isPresent()) {
            out.println("ready: " + process.webUrl().get());
        } else {
            out.println("state: " + process.state() + " (exit " + process.exitCode().orElse(-1) + ")");
        }
        out.println("Press Ctrl-C to stop.");

        while (process.isRunning()) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        process.stop();
        return process.exitCode().orElse(0);
    }

    /// Creates an instance from the command line.
    ///
    /// @param invocation the parsed invocation
    /// @param out        the stream for normal output
    /// @param err        the stream for error output
    /// @return the process exit code
    private static int createInstance(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
        String id = invocation.subject();
        String version = invocation.option("dsh-version");
        if (version == null) {
            List<DshVersion> installed = DshVersionManager.listInstalled();
            if (installed.isEmpty()) {
                err.println("error: no DeepSeek Harness version is installed; pass --version or install one first");
                return 1;
            }
            version = installed.get(0).version();
        }

        String profile = invocation.option("profile");
        if (profile == null) {
            profile = DshInstance.DEFAULT_PROFILE;
        }

        String workspaceOption = invocation.option("workspace");
        Path workspace = Path.of(workspaceOption != null ? workspaceOption : System.getProperty("user.home"))
                .toAbsolutePath().normalize();

        DshHomeMode mode = DshHomeMode.ISOLATED;
        String modeOption = invocation.option("home-mode");
        if (modeOption != null) {
            mode = DshHomeMode.valueOf(modeOption.toUpperCase(java.util.Locale.ROOT));
        }
        String nodeRuntime = invocation.option("runtime");
        Path customHome = invocation.option("home") == null
                ? null
                : Path.of(invocation.option("home")).toAbsolutePath().normalize();

        DshInstance instance = DshInstanceManager.create(id, version, profile, workspace,
                nodeRuntime, mode, customHome, List.of(), Map.of());
        out.println("Created instance " + instance.id() + " (dsh " + instance.version()
                + ", profile " + instance.profile() + ", home " + instance.homeDirectory() + ")");
        return 0;
    }

    /// Prints the command list.
    ///
    /// @param out the stream to print to
    private static void printUsage(PrintStream out) {
        out.println("""
                HMCL-DSH command line

                usage: hmcl-dsh <command> [options]

                versions:
                  --list-installed                 list installed DeepSeek Harness versions
                  --list-versions                  list versions published to the npm registry
                  --install <version>              install a version into its own npm prefix
                  --uninstall <version>            remove an installed version

                instances:
                  --list-instances                 list launcher instances
                  --create-instance <id>           create an instance
                      --dsh-version <version>        version to pin (defaults to the newest installed)
                      --profile <name>               profile to boot (default: web)
                      --workspace <path>             session working directory (default: $HOME)
                      --home-mode <mode>             isolated | version_shared | custom
                      --home <path>                  required for --home-mode custom
                      --runtime <version>            Node runtime to pin (default: system)
                  --delete-instance <id>           remove an instance

                plugins:
                  --install-plugin <id> <spec>     install a plugin into an instance profile

                node runtimes:
                  --list-runtimes                  list installed Node runtimes
                  --list-node-versions             list Node releases for this platform
                  --install-node <version>         install a Node runtime
                  --uninstall-node <version>       remove a Node runtime

                running:
                  --launch <id>                    start an instance and block until it exits
                  --list-running                   list running instances
                  --stop <id>                      stop a running instance

                diagnostics:
                  --doctor                         print a full diagnostics report
                  --version                        print the launcher version
                """);
    }
}
