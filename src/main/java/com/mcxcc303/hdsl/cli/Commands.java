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
package com.mcxcc303.hdsl.cli;

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.DshDoctor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@NotNullByDefault
final class Commands {

	// How many times a parameter may be given, and whether it has to be.
	enum Kind {
		// Given once, and the command does not run without it.
		REQUIRED,
		// Given at most once.
		OPTIONAL,
		// Given without a value; what the command reads is whether it is there.
		FLAG,
		// Given once or more.
		REQUIRED_MANY,
		// Given any number of times, including none.
		OPTIONAL_MANY
	}

	/**
	 * One parameter of one command.
	 *
	 * @param name        the spelling, without the leading dashes
	 * @param kind        how many times it may be given
	 * @param placeholder what the value is, for the help; empty for a flag
	 * @param description what the parameter is for, for the help
	 */
	record Parameter(String name, Kind kind, String placeholder, String description) {
	}

	/**
	 * One command.
	 *
	 * @param group       the group it is typed under, or the empty string for a command that stands alone
	 * @param name        the name it is typed as
	 * @param description what it does, for the help
	 * @param parameters  what it takes
	 * @param action      what it does
	 */
	record Command(String group, String name, String description, List<Parameter> parameters, Action action) {

		/**
		 * Returns the words this command is typed as.
		 *
		 * @return the group and the name, or the name alone for a command that stands alone
		 */
		String spelling() {
			return group.isEmpty() ? name : group + " " + name;
		}

		/**
		 * Returns the parameter of this name.
		 *
		 * @param name the spelling, without the leading dashes
		 * @return the parameter, or `null` when this command takes none of that name
		 */
		@Nullable Parameter parameter(String name) {
			for (Parameter parameter : parameters) {
				if (parameter.name().equals(name)) {
					return parameter;
				}
			}
			return null;
		}
	}

	// What a command does when it runs.
	interface Action {
		/**
		 * Runs one command.
		 *
		 * @param invocation the parsed invocation
		 * @param out        the stream for normal output
		 * @param err        the stream for error output
		 * @return the process exit code
		 */
		int run(Cli.Invocation invocation, PrintStream out, PrintStream err) throws DshException;
	}

	private Commands() {
	}

	// Every command, in the order the help lists them.
	private static final List<Command> ALL = List.of(
			// The launcher itself.
			new Command("", "doctor", "print a full diagnostics report",
			            List.of(), (invocation, out, err) -> DshDoctor.report(out)),

			// Instances.
			// TODO: `instance create` unifies
			new Command("instance", "create", "create a complete dsh instance",
			            List.of(required("id", "id", "the instance's name"),
			                    required("dsh-version", "version", "the DeepSeek Harness version to run"),
			                    optional("profile", "name", "the profile to boot (default: web)"),
			                    optional("workspace", "path", "the session working directory (default: $HOME)"),
			                    optional("home-mode", "mode", "isolated | version_shared | custom"),
			                    optional("home", "path", "the home directory, for --home-mode custom"),
			                    optional("runtime", "version", "the Node runtime to run it with (default: system)")),
			            InstanceCommands::createInstance),
			new Command("instance", "list", "list the instances, or the ones that are running",
			            List.of(flag("running", "list the instances that are running")),
			            InstanceCommands::listInstances),
			new Command("instance", "delete", "remove an instance",
			            List.of(required("id", "id", "the instance to remove")),
			            InstanceCommands::deleteInstance),
			new Command("instance", "launch", "start an instance, or print what one would be started with",
			            List.of(required("id", "id", "the instance"),
			                    flag("url", "print the address it serves, without starting it"),
			                    flag("key", "print the key it asks for, without starting it")),
			            InstanceCommands::launchInstance),
			new Command("instance", "stop", "stop a running instance",
			            List.of(required("id", "id", "the instance to stop")),
			            InstanceCommands::stop),
			new Command("instance", "upgrade", "move an instance to another harness version, keeping its plugins",
			            List.of(required("id", "id", "the instance to move"),
			                    required("dsh-version", "version", "the version to move it to")),
			            InstanceCommands::upgradeInstance),
			new Command("instance", "print-launch-args", "print what a typed argument line comes to",
			            List.of(required("id", "id", "the instance")),
			            InstanceCommands::printLaunchArguments),
			new Command("instance", "test-launch", "launch an instance once and print what it says",
			            List.of(required("id", "id", "the instance")),
			            InstanceCommands::testLaunch),

			// Plugins.
			new Command("plugin", "list", "list the plugins an instance's profile holds",
			            List.of(required("id", "id", "the instance"),
			                    flag("show-bundles", "show the loader rows each plugin's bundle inserts"),
			                    flag("show-dsh", "show dsh internal plugin version requirements")),
			            PluginCommands::listPlugins),
			new Command("plugin", "install", "install a plugin into an instance's profile",
			            List.of(required("id", "id", "the instance"),
			                    required("spec", "spec", "the plugin, by name, version or local path")),
			            PluginCommands::installPlugin),
			new Command("plugin", "remove", "remove one or more plugins from an instance's profile",
			            List.of(required("id", "id", "the instance"),
			                    many("spec", "spec", "a plugin to remove; one --spec for each")),
			            PluginCommands::removePlugin),
			new Command("plugin", "install-file", "install a plugin from a file",
			            List.of(required("id", "id", "the instance"),
			                    required("file", "path", "the packed plugin")),
			            PluginCommands::installPluginFile),
			new Command("plugin", "build-scripts", "read, or answer, what a profile may run while it installs",
			            List.of(required("id", "id", "the instance"),
			                    flag("allow", "allow the packages named, or every unanswered one"),
			                    flag("deny", "refuse the packages named, or every unanswered one"),
			                    any("package", "name", "the package to answer for (default: every one waiting)")),
			            PluginCommands::buildScripts),

			// Packs.
			new Command("pack", "export", "write an instance's configuration into a pack",
			            List.of(required("id", "id", "the instance"),
			                    required("file", "path", "the pack to write")),
			            PackCommands::exportModpack),
			new Command("pack", "export-packforge", "write an instance as a DSH-PackForge pack",
			            List.of(required("id", "id", "the instance"),
			                    required("file", "path", "the pack to write"),
			                    flag("with-sessions", "carry the instance's conversations as well")),
			            PackCommands::exportPackforge),
			new Command("pack", "install", "build an instance from a pack",
			            List.of(required("file", "path", "the pack to read"),
			                    optional("id", "id", "the instance to make (default: the pack's own name)")),
			            PackCommands::installModpack),
			new Command("pack", "restore-profile", "put a pack's profile into an instance that exists",
			            List.of(required("id", "id", "the instance"),
			                    required("file", "path", "the pack to read")),
			            PackCommands::restoreProfile),
			new Command("pack", "import", "read a pack's sessions into an instance",
			            List.of(required("id", "id", "the instance"),
			                    required("file", "path", "the pack to read")),
			            PackCommands::importPack),

			// Sessions.
			new Command("session", "list", "list an instance's sessions",
			            List.of(required("id", "id", "the instance")),
			            SessionCommands::listSessions),
			new Command("session", "export", "write an instance's sessions into a pack",
			            List.of(required("id", "id", "the instance"),
			                    required("file", "path", "the pack to write")),
			            SessionCommands::exportSessions),
			new Command("session", "import", "copy sessions in from another home",
			            List.of(required("id", "id", "the instance"),
			                    optional("from", "home", "the home to copy from (default: this one)")),
			            SessionCommands::importSessions),
			new Command("session", "migrate", "move a session to another instance",
			            List.of(required("id", "id", "the instance the session is in"),
			                    required("session", "session", "the session to move"),
			                    required("to", "id", "the instance to move it to")),
			            SessionCommands::migrateSession),
			new Command("session", "acp-prompt", "send one prompt over the Agent Client Protocol",
			            List.of(required("id", "id", "the instance"),
			                    required("text", "text", "the prompt to send")),
			            SessionCommands::acpPrompt),

			// The folders instances are looked for in.
			new Command("directory", "list", "print the folders instances are looked for in",
			            List.of(), (invocation, out, err) -> DirectoryCommands.listDirectories(out)),
			new Command("directory", "add", "add a folder, and scan it for instances",
			            List.of(required("path", "path", "the folder to add")),
			            DirectoryCommands::addDirectory),

			new Command("source",
			            "list",
			            "print the places the plugin catalogue and the skill catalogues are read from",
			            List.of(),
			            (invocation, out, err) -> PluginSourceCommands.listSources(out)),

			// Node runtimes.
			new Command("node", "list", "list the Node runtimes the launcher installed",
			            List.of(), NodeCommands::listRuntimes),
			new Command("node", "versions", "list the Node releases this platform can install",
			            List.of(optional("source", "source", "where to read the releases from"),
			                    flag("show-lifetime", "show each version's lifetime schedule")),
			            NodeCommands::listNodeVersions),
			new Command("node", "install", "install a Node runtime",
			            List.of(required("version", "version", "the Node version to install"),
			                    optional("source", "source", "where to read the release from")),
			            NodeCommands::installNode),
			new Command("node", "uninstall", "remove an installed Node runtime",
			            List.of(required("version", "version", "the Node version to remove")),
			            NodeCommands::uninstallNode),

			// The harness the instances run.
			new Command("harness", "list", "list the harness each instance runs, and whether it is complete",
			            List.of(), HarnessCommands::list),
			new Command("harness", "versions", "list the harness versions the registry publishes",
			            List.of(), HarnessCommands::versions));

	// The commands by the words they are typed as, one entry per spelling.
	private static final Map<String, Command> BY_SPELLING = bySpelling();

	// The groups, in the order the help lists them.
	private static final List<String> GROUPS = ALL.stream()
	                                              .map(Command::group)
	                                              .filter(group -> !group.isEmpty())
	                                              .distinct()
	                                              .toList();

	/**
	 * Returns every command, in the order the help lists them.
	 *
	 * @return the commands
	 */
	static List<Command> all() {
		return ALL;
	}

	/**
	 * Returns the groups, in the order the help lists them.
	 *
	 * @return the group names
	 */
	static List<String> groups() {
		return GROUPS;
	}

	/**
	 * Returns the commands of one group, in the order the help lists them.
	 *
	 * @param group the group
	 * @return its commands, empty when the group is not one of ours
	 */
	static List<Command> in(String group) {
		return ALL.stream().filter(command -> command.group().equals(group)).toList();
	}

	/**
	 * Returns the commands that stand alone, in the order the help lists them.
	 *
	 * @return the commands with no group
	 */
	static List<Command> standalone() {
		return ALL.stream().filter(command -> command.group().isEmpty()).toList();
	}

	/**
	 * Reports whether a word is one of the groups.
	 *
	 * @param word the word
	 * @return whether the word names a group
	 */
	static boolean isGroup(String word) {
		return GROUPS.contains(word);
	}

	/**
	 * Returns the command a spelling names.
	 *
	 * @param spelling the words the command is typed as, such as `instance create`
	 * @return the command, or `null` when no command is spelled that way
	 */
	static @Nullable Command find(String spelling) {
		return BY_SPELLING.get(spelling);
	}

	private static Map<String, Command> bySpelling() {
		Map<String, Command> commands = new LinkedHashMap<>();
		for (Command command : ALL) {
			commands.put(command.spelling(), command);
		}
		return Map.copyOf(commands);
	}

	private static Parameter required(String name, String placeholder, String description) {
		return new Parameter(name, Kind.REQUIRED, placeholder, description);
	}

	private static Parameter optional(String name, String placeholder, String description) {
		return new Parameter(name, Kind.OPTIONAL, placeholder, description);
	}

	private static Parameter flag(String name, String description) {
		return new Parameter(name, Kind.FLAG, "", description);
	}

	private static Parameter many(String name, String placeholder, String description) {
		return new Parameter(name, Kind.REQUIRED_MANY, placeholder, description);
	}

	private static Parameter any(String name, String placeholder, String description) {
		return new Parameter(name, Kind.OPTIONAL_MANY, placeholder, description);
	}
}
