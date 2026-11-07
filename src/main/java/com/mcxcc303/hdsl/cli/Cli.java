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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line interface parser and dispatcher.
 */
@NotNullByDefault
public final class Cli {

	private static final Set<String> HELP = Set.of("--help", "-h");

	private Cli() {
	}

	/**
	 * Parses command-line arguments into an invocation.
	 *
	 * @param args the raw command-line arguments
	 * @return the parsed invocation, or {@code null} if launching the graphical interface
	 */
	public static @Nullable Invocation parse(List<String> args) {
		// Support legacy flag-style commands.
		List<String> deprecated = FlagStyleCommands.commandLine(args);
		if (deprecated != null) {
			return read(deprecated, deprecated);
		}

		if (args.isEmpty()) {
			return null;
		}

		String first = args.get(0);
		if (Commands.isGroup(first) || Commands.find(first) != null) {
			return read(args, null);
		}
		String gone = FlagStyleCommands.removed(args);
		if (gone != null) {
			return new Invocation("", Map.of(), false, null, gone);
		}
		if (asksHelp(args)) {
			return new Invocation("", Map.of(), true, null, null);
		}
		return null;
	}

	private static Invocation read(List<String> args, @Nullable List<String> deprecated) {
		String first = args.get(0);
		String spelling = first;
		int from = 1;
		if (Commands.find(first) == null && args.size() > 1 && !HELP.contains(args.get(1)) &&
		    !args.get(1).startsWith("-")) {
			spelling = first + " " + args.get(1);
			from = 2;
		}

		Commands.Command command = Commands.find(spelling);
		if (command == null) {
			String message = Commands.isGroup(spelling)
			                 ? spelling + " needs one of its commands"
			                 : "there is no command called '" + spelling + "'";
			return new Invocation(spelling, Map.of(), false, deprecated, message);
		}

		Map<String, List<String>> options = new LinkedHashMap<>();
		boolean help = false;

		for (int i = from; i < args.size(); i++) {
			String token = args.get(i);
			if (HELP.contains(token)) {
				help = true;
				continue;
			}
			if (!token.startsWith("--")) {
				return error(spelling, "takes options only, and '" + token + "' is not one", deprecated);
			}

			String given = token.substring(2);
			String value = null;
			int equals = given.indexOf('=');
			if (equals >= 0) {
				value = given.substring(equals + 1);
				given = given.substring(0, equals);
			}

			Commands.Parameter parameter = command.parameter(given);
			if (parameter == null) {
				return error(spelling, "does not take --" + given, deprecated);
			}
			if (parameter.kind() == Commands.Kind.FLAG) {
				if (value != null) {
					return error(spelling, "--" + given + " is a flag and takes no value", deprecated);
				}
				options.computeIfAbsent(given, name -> new ArrayList<>()).add("true");
				continue;
			}
			if (value == null) {
				if (i + 1 >= args.size()) {
					return error(spelling, "--" + given + " needs a value", deprecated);
				}
				value = args.get(++i);
			}

			List<String> values = options.computeIfAbsent(given, name -> new ArrayList<>());
			boolean many = parameter.kind() == Commands.Kind.REQUIRED_MANY ||
			               parameter.kind() == Commands.Kind.OPTIONAL_MANY;
			if (!values.isEmpty() && !many) {
				return error(spelling, "--" + given + " was given more than once", deprecated);
			}
			values.add(value);
		}

		if (help) {
			return new Invocation(spelling, Map.copyOf(options), true, deprecated, null);
		}
		List<String> missing = command.parameters()
		                              .stream()
		                              .filter(parameter -> parameter.kind() == Commands.Kind.REQUIRED ||
		                                                   parameter.kind() == Commands.Kind.REQUIRED_MANY)
		                              .filter(parameter -> !options.containsKey(parameter.name()))
		                              .map(parameter -> "--" + parameter.name())
		                              .toList();
		if (!missing.isEmpty()) {
			return error(spelling, "needs " + String.join(", ", missing), deprecated);
		}
		return new Invocation(spelling, Map.copyOf(options), false, deprecated, null);
	}

	private static Invocation error(String spelling, String message, @Nullable List<String> deprecated) {
		return new Invocation(spelling, Map.of(), false, deprecated, spelling + " " + message);
	}

	private static boolean asksHelp(List<String> args) {
		return args.stream().anyMatch(HELP::contains);
	}

	/**
	 * Executes a parsed command invocation.
	 *
	 * @param invocation the parsed command invocation
	 * @param out        the stream for normal output
	 * @param err        the stream for error output
	 * @return the process exit code
	 */
	public static int run(Invocation invocation, PrintStream out, PrintStream err) {
		int code = runCommand(invocation, out, err);
		if (invocation.deprecated() != null) {
			FlagStyleCommands.warn(out, invocation.deprecated());
		}
		return code;
	}

	private static int runCommand(Invocation invocation, PrintStream out, PrintStream err) {
		if (invocation.showHelp()) {
			help(invocation.command(), out);
			return 0;
		}

		Commands.Command command = Commands.find(invocation.command());
		if (command == null || invocation.error() != null) {
			if (invocation.error() != null) {
				err.println("error: " + invocation.error());
			}
			help(invocation.command(), err);
			return 2;
		}

		try {
			return command.action().run(invocation, out, err);
		} catch (DshException e) {
			err.println("error: " + e.getMessage());
			return 1;
		}
	}

	private static void help(String spelling, PrintStream out) {
		Commands.Command command = Commands.find(spelling);
		if (command != null) {
			printCommand(command, out);
			return;
		}

		String group = Commands.isGroup(spelling) ? spelling : "";
		out.println("HDSL command line");
		out.println();
		out.println("usage: hdsl <group> <command> [options]");
		if (!group.isEmpty()) {
			printGroup(Commands.in(group), out);
			out.println();
			out.println("`hdsl " + group + " <command> --help` says what one command takes.");
			return;
		}
		for (String name : Commands.groups()) {
			printGroup(Commands.in(name), out);
		}
		printStandalone(out);
		out.println();
		out.println("`hdsl <group> <command> --help` says what one command takes.");
	}

	private static void printGroup(List<Commands.Command> commands, PrintStream out) {
		if (commands.isEmpty()) {
			return;
		}
		out.println();
		out.println(commands.get(0).group() + ":");
		int width = commands.stream().mapToInt(command -> command.name().length()).max().orElse(0);
		for (Commands.Command command : commands) {
			out.println("  " + pad(command.name(), width) + "  " + command.description());
		}
	}

	private static void printStandalone(PrintStream out) {
		List<Commands.Command> standalone = Commands.standalone();
		if (standalone.isEmpty()) {
			return;
		}
		out.println();
		int width = standalone.stream().mapToInt(command -> command.name().length()).max().orElse(0);
		for (Commands.Command command : standalone) {
			out.println(pad(command.name(), width) + "  " + command.description());
		}
	}

	private static void printCommand(Commands.Command command, PrintStream out) {
		out.println("usage: hdsl " + usage(command));
		out.println();
		out.println(command.description());
		if (command.parameters().isEmpty()) {
			return;
		}
		out.println();
		out.println("parameters:");
		int width = command.parameters()
		                   .stream()
		                   .mapToInt(parameter -> written(parameter).length())
		                   .max()
		                   .orElse(0);
		for (Commands.Parameter parameter : command.parameters()) {
			boolean needed = parameter.kind() == Commands.Kind.REQUIRED ||
			                 parameter.kind() == Commands.Kind.REQUIRED_MANY;
			out.println("  " + pad(written(parameter), width) + "  " + parameter.description() +
			            (needed ? " (required)" : ""));
		}
	}

	private static String usage(Commands.Command command) {
		StringBuilder line = new StringBuilder(command.spelling());
		for (Commands.Parameter parameter : command.parameters()) {
			boolean needed = parameter.kind() == Commands.Kind.REQUIRED ||
			                 parameter.kind() == Commands.Kind.REQUIRED_MANY;
			String written = written(parameter);
			line.append(' ').append(needed ? written : "[" + written + "]");
			if (parameter.kind() == Commands.Kind.REQUIRED_MANY ||
			    parameter.kind() == Commands.Kind.OPTIONAL_MANY) {
				line.append("...");
			}
		}
		return line.toString();
	}

	private static String written(Commands.Parameter parameter) {
		return parameter.kind() == Commands.Kind.FLAG
		       ? "--" + parameter.name()
		       : "--" + parameter.name() + " <" + parameter.placeholder() + ">";
	}

	private static String pad(String text, int width) {
		return text.length() >= width ? text : text + " ".repeat(width - text.length());
	}

	/**
	 * A parsed command invocation with options and arguments.
	 *
	 * @param command    the command name or empty string for help
	 * @param options    parsed option values keyed by name
	 * @param showHelp   whether help was requested
	 * @param deprecated legacy flag-style argument list, if applicable
	 * @param error      parsing error message, if any
	 */
	public record Invocation(String command, Map<String, List<String>> options, boolean showHelp,
	                         @Nullable List<String> deprecated, @Nullable String error) {

		/**
		 * Returns the first value given for an option.
		 *
		 * @param name the option name without leading dashes
		 * @return the option value, or {@code null} if absent
		 */
		public @Nullable String option(String name) {
			List<String> values = options.get(name);
			return values == null || values.isEmpty() ? null : values.get(0);
		}

		/**
		 * Returns all values given for an option.
		 *
		 * @param name the option name without leading dashes
		 * @return the list of values, or an empty list if absent
		 */
		public List<String> values(String name) {
			List<String> values = options.get(name);
			return values == null ? List.of() : List.copyOf(values);
		}

		/**
		 * Reports whether a flag option was specified.
		 *
		 * @param name the flag name without leading dashes
		 * @return true if the flag was present
		 */
		public boolean flag(String name) {
			return options.containsKey(name);
		}
	}
}
