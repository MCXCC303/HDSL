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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.PrintStream;
import java.util.*;

/*
 * The commands as they were spelled with a flag, and the command line each one names now.
 *
 * A flag-style command still runs, and runs the same thing: its arguments are read into the command
 * they name, so every command has one implementation rather than one per spelling. What it also
 * does is say what it is called — once, after the command has said what it had to say — because a
 * spelling that works but is written down nowhere is a spelling nobody ever moves away from.
 *
 * One entry is one command: the flag it used to be written as, the command it names, and the
 * arguments that follow it. Renaming a command therefore renames it here too, which is what keeps
 * the warning from pointing at a spelling that no longer exists.
 *
 * The flag-style spelling is deprecated in v0.2.1-rc.3 and goes away in v0.2.2. What is left of it
 * then is this file.
 */
@Deprecated
@NotNullByDefault
final class FlagStyleCommands {

	// The release that deprecated the flag-style spelling.
	private static final String DEPRECATED_IN = "v0.2.1-rc.3";

	// The release that removes it.
	private static final String REMOVED_IN = "v0.2.2";

	// The flags that are flags under either spelling, and are carried over as they are.
	private static final Set<String> FLAGS = Set.of("--with-sessions", "--allow", "--deny");

	// The flags that ask for help rather than name a value, and are never read as one.
	private static final Set<String> HELP = Set.of("--help", "-h");

	// The options that are spelled differently now, and what they are spelled as.
	private static final Map<String, String> RENAMED = Map.of("--node-source", "--source");

	// Deprecated flags whose commands were removed, mapped to replacement instructions.
	private static final Map<String, String> REMOVED = Map.of(
			"--install", "DeepSeek Harness is installed into the instance that runs it: " +
			             "`hdsl instance create --id <id> --dsh-version <version>`",
			"--uninstall", "a harness version is removed with the instance that runs it: " +
			               "`hdsl instance delete --id <id>`");

	private FlagStyleCommands() {
	}

	// What follows the arguments a flag-style command names.
	private enum Trailing {
		// Nothing: the arguments in the entry are the command's arguments.
		NONE,
		// One specification after another, until the next option, as `--remove-plugin` reads them.
		SPECS,
		// Package names, here and there among the options, as `--build-scripts` reads them.
		PACKAGES,
		// A pack, which an instance id may follow, as `--install-modpack` reads them.
		OPTIONAL_ID
	}

	private record Spelling(String group, String command, List<String> parameters, List<String> flags,
	                        Trailing trailing) {
	}

	// Every flag-style command there was.
	private static final Map<String, Spelling> SPELLINGS = spellings();

	/**
	 * Returns the command line these flag-style arguments name, or null when they name no command.
	 * <p>
	 * The line is what will be run, and it is what the warning prints: the two are the same string,
	 * so what the warning tells a reader to write is what the launcher just did.
	 *
	 * @param args the raw command-line arguments
	 * @return the command line, or null when no flag-style command is among them
	 */
	static @Nullable List<String> commandLine(List<String> args) {
		List<String> command = null;
		Spelling current = null;
		List<String> modern = new ArrayList<>();

		for (int i = 0; i < args.size(); i++) {
			String token = args.get(i);
			Spelling spelling = SPELLINGS.get(token);
			if (spelling != null) {
				// A line naming two commands is read as the last of them, the way the flag-style
				// parser read it. Whatever the first one's arguments were, they are carried along and
				// refused by the command that ends up with them.
				command = spelling.group().isEmpty() ? List.of(spelling.command())
				                                     : List.of(spelling.group(), spelling.command());
				current = spelling;
				for (String parameter : spelling.parameters()) {
					if (i + 1 >= args.size() || HELP.contains(args.get(i + 1))) {
						// Short of an argument, or asked for help instead of one. The command that
						// runs is the one that says so.
						break;
					}
					modern.add(parameter);
					modern.add(args.get(++i));
				}
				modern.addAll(spelling.flags());
				if (spelling.trailing() == Trailing.SPECS) {
					while (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
						modern.add("--spec");
						modern.add(args.get(++i));
					}
				} else if (spelling.trailing() == Trailing.OPTIONAL_ID) {
					if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
						modern.add("--id");
						modern.add(args.get(++i));
					}
				}
			} else if (FLAGS.contains(token) || HELP.contains(token)) {
				modern.add(token);
			} else if (token.startsWith("--")) {
				modern.add(RENAMED.getOrDefault(token, token));
				if (i + 1 < args.size() && !args.get(i + 1).startsWith("--") && !HELP.contains(args.get(i + 1))) {
					modern.add(args.get(++i));
				}
			} else if (current != null && current.trailing() == Trailing.PACKAGES) {
				modern.add("--package");
				modern.add(token);
			} else {
				// A word this spelling knows nothing about. It travels on rather than being dropped:
				// the command it lands on refuses it, and a refusal can be read.
				modern.add(token);
			}
		}

		if (command == null) {
			return null;
		}
		List<String> line = new ArrayList<>(command);
		line.addAll(modern);
		return List.copyOf(line);
	}

	/**
	 * Returns what to write instead, when the arguments name a flag whose command is gone.
	 *
	 * @param args the raw command-line arguments
	 * @return the flags and what to write instead, or {@code null} when none of them is there
	 */
	static @Nullable String removed(List<String> args) {
		for (String flag : REMOVED.keySet()) {
			if (args.contains(flag)) {
				return flag + " is gone - " + REMOVED.get(flag);
			}
		}
		return null;
	}

	/**
	 * Prints what the flag-style command is called now, after the command itself has spoken.
	 *
	 * @param out    the stream for normal output
	 * @param modern the command line the flag-style arguments named
	 */
	static void warn(PrintStream out, List<String> modern) {
		out.println("Warning: Flag-style command is marked as deprecated in hdsl " + DEPRECATED_IN
		            + " and removal in hdsl " + REMOVED_IN + ", try new command using:");
		out.println("  hdsl " + quote(modern));
	}

	/**
	 * Returns a command line as something that can be pasted into a shell.
	 * <p>
	 * An argument with a space in it is one argument, and is written as one; the rest are left bare
	 * so that the line reads the way it would be typed.
	 */
	private static String quote(List<String> modern) {
		StringBuilder line = new StringBuilder();
		for (String word : modern) {
			if (!line.isEmpty()) {
				line.append(' ');
			}
			if (word.isEmpty() || word.indexOf(' ') >= 0 || word.indexOf('\'') >= 0) {
				line.append('\'').append(word.replace("'", "'\\''")).append('\'');
			} else {
				line.append(word);
			}
		}
		return line.toString();
	}

	// Builds the table, one command to a line.
	private static Map<String, Spelling> spellings() {
		Map<String, Spelling> spellings = new LinkedHashMap<>();

		// The launcher itself.
		spellings.put("--doctor", new Spelling("", "doctor", List.of(), List.of(), Trailing.NONE));

		// The harness, and the versions of it there are.
		spellings.put("--list-installed", new Spelling("harness", "list", List.of(), List.of(), Trailing.NONE));
		spellings.put("--list-versions", new Spelling("harness", "versions", List.of(), List.of(), Trailing.NONE));

		// Instances.
		spellings.put("--list-instances", new Spelling("instance", "list", List.of(), List.of(), Trailing.NONE));
		spellings.put("--list-running",
		              new Spelling("instance", "list", List.of(), List.of("--running"), Trailing.NONE));
		spellings.put("--create-instance",
		              new Spelling("instance", "create", List.of("--id"), List.of(), Trailing.NONE));
		spellings.put("--delete-instance",
		              new Spelling("instance", "delete", List.of("--id"), List.of(), Trailing.NONE));
		spellings.put("--launch", new Spelling("instance", "launch", List.of("--id"), List.of(), Trailing.NONE));
		spellings.put("--get-url",
		              new Spelling("instance", "launch", List.of("--id"), List.of("--url"), Trailing.NONE));
		spellings.put("--get-key",
		              new Spelling("instance", "launch", List.of("--id"), List.of("--key"), Trailing.NONE));
		spellings.put("--stop", new Spelling("instance", "stop", List.of("--id"), List.of(), Trailing.NONE));
		spellings.put("--upgrade-instance", new Spelling("instance", "upgrade", List.of("--id", "--dsh-version"),
		                                                 List.of(), Trailing.NONE));
		spellings.put("--print-launch-args",
		              new Spelling("instance", "print-launch-args", List.of("--id"), List.of(), Trailing.NONE));
		spellings.put("--test-launch",
		              new Spelling("instance", "test-launch", List.of("--id"), List.of(), Trailing.NONE));

		// Plugins.
		spellings.put("--install-plugin",
		              new Spelling("plugin", "install", List.of("--id", "--spec"), List.of(), Trailing.NONE));
		spellings.put("--remove-plugin",
		              new Spelling("plugin", "remove", List.of("--id"), List.of(), Trailing.SPECS));
		spellings.put("--install-plugin-file",
		              new Spelling("plugin", "install-file", List.of("--id", "--file"), List.of(), Trailing.NONE));
		spellings.put("--build-scripts",
		              new Spelling("plugin", "build-scripts", List.of("--id"), List.of(), Trailing.PACKAGES));

		// Packs.
		spellings.put("--export-modpack",
		              new Spelling("pack", "export", List.of("--id", "--file"), List.of(), Trailing.NONE));
		spellings.put("--export-packforge",
		              new Spelling("pack", "export-packforge", List.of("--id", "--file"), List.of(), Trailing.NONE));
		spellings.put("--install-modpack",
		              new Spelling("pack", "install", List.of("--file"), List.of(), Trailing.OPTIONAL_ID));
		spellings.put("--restore-profile",
		              new Spelling("pack", "restore-profile", List.of("--id", "--file"), List.of(), Trailing.NONE));
		spellings.put("--import-pack",
		              new Spelling("pack", "import", List.of("--id", "--file"), List.of(), Trailing.NONE));

		// Sessions.
		spellings.put("--list-sessions", new Spelling("session", "list", List.of("--id"), List.of(), Trailing.NONE));
		spellings.put("--export-sessions",
		              new Spelling("session", "export", List.of("--id", "--file"), List.of(), Trailing.NONE));
		spellings.put("--import-sessions",
		              new Spelling("session", "import", List.of("--id"), List.of(), Trailing.NONE));
		spellings.put("--migrate-session", new Spelling("session", "migrate", List.of("--id", "--session", "--to"),
		                                                List.of(), Trailing.NONE));
		spellings.put("--acp-prompt",
		              new Spelling("session", "acp-prompt", List.of("--id", "--text"), List.of(), Trailing.NONE));

		// The folders instances are looked for in.
		spellings.put("--list-directories",
		              new Spelling("directory", "list", List.of(), List.of(), Trailing.NONE));
		spellings.put("--add-directory",
		              new Spelling("directory", "add", List.of("--path"), List.of(), Trailing.NONE));

		// The places the plugin catalogue and the skill catalogues are read from.
		spellings.put("--list-plugin-sources",
		              new Spelling("source", "list", List.of(), List.of(), Trailing.NONE));
		spellings.put("--list-skill-sources",
		              new Spelling("source", "list", List.of(), List.of(), Trailing.NONE));

		// Node runtimes.
		spellings.put("--list-runtimes", new Spelling("node", "list", List.of(), List.of(), Trailing.NONE));
		spellings.put("--list-node-versions",
		              new Spelling("node", "versions", List.of(), List.of(), Trailing.NONE));
		spellings.put("--install-node",
		              new Spelling("node", "install", List.of("--version"), List.of(), Trailing.NONE));
		spellings.put("--uninstall-node",
		              new Spelling("node", "uninstall", List.of("--version"), List.of(), Trailing.NONE));

		return Map.copyOf(spellings);
	}
}
