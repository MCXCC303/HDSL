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
package com.mcxcc303.hdsl.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Splits command-line arguments into launcher flags and booted application arguments.
 * <p>
 * Ensures managed flags (such as {@code --port}) and environment overrides
 * (such as {@code DSH_HOME=...}) are properly segregated or rejected.
 */
@NotNullByDefault
public final class DshLaunchArguments {
	private static final Set<String> LAUNCHER_FLAGS = Set.of("--profile", "--patch", "--from-default-profile");

	private static final Set<String> VALUE_FLAGS = Set.of("--profile",
	                                                      "--patch",
	                                                      "--from-default-profile",
	                                                      "--host",
	                                                      "--port",
	                                                      "--trusted-host");

	private static final Set<String> MANAGED_FLAGS = Set.of("--port");

	private static final String ENVIRONMENT_PREFIX = DshEnvironment.HOMEDIRECTORY_VARIABLE + "=";

	private DshLaunchArguments() {
	}

	/**
	 * Parses a command line string into launcher and app arguments.
	 *
	 * @param text the command line string, or null
	 * @return parsed arguments
	 */
	public static Parsed parse(@Nullable String text) {
		return parseArguments(tokenize(text));
	}

	/**
	 * Splits argument tokens into launcher flags and app arguments.
	 *
	 * @param arguments the argument tokens
	 * @return parsed arguments
	 */
	public static Parsed parseArguments(List<String> arguments) {
		List<String> launcher = new ArrayList<>();
		List<String> app = new ArrayList<>();
		List<String> refusals = new ArrayList<>();
		String profile = null;
		boolean pendingLauncherValue = false;
		boolean pendingProfile = false;
		boolean skipValue = false;

		for (String token : arguments) {
			if (token.startsWith(ENVIRONMENT_PREFIX)) {
				refusals.add(i18n("dsh.launch.refused.home", token));
				continue;
			}

			String name = token;
			String inlineValue = null;
			if (token.startsWith("--")) {
				int equals = token.indexOf('=');
				if (equals > 0) {
					name = token.substring(0, equals);
					inlineValue = token.substring(equals + 1);
				}
			}

			if (MANAGED_FLAGS.contains(name)) {
				refusals.add(i18n("dsh.launch.refused.port", name));
				skipValue = inlineValue == null;
				continue;
			}
			if (LAUNCHER_FLAGS.contains(name)) {
				if (inlineValue != null) {
					launcher.add(token);
					if ("--profile".equals(name)) {
						profile = inlineValue;
					}
					continue;
				}
				launcher.add(token);
				pendingLauncherValue = VALUE_FLAGS.contains(name);
				if ("--profile".equals(name)) {
					pendingProfile = true;
				}
				continue;
			}
			if (pendingProfile) {
				profile = token;
				launcher.add(token);
				pendingProfile = false;
				pendingLauncherValue = false;
				continue;
			}
			if (pendingLauncherValue) {
				launcher.add(token);
				pendingLauncherValue = false;
				continue;
			}
			if (skipValue) {
				skipValue = false;
				continue;
			}
			app.add(token);
		}

		return new Parsed(List.copyOf(launcher), List.copyOf(app), profile, List.copyOf(refusals));
	}

	/**
	 * Tokenizes a command line string into argument tokens respecting quotes and escapes.
	 *
	 * @param text command line text
	 * @return list of argument tokens
	 */
	public static List<String> tokenize(@Nullable String text) {
		List<String> tokens = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return tokens;
		}
		StringBuilder current = new StringBuilder();
		boolean started = false;
		char quote = 0;
		boolean escaped = false;

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (escaped) {
				current.append(c);
				escaped = false;
				started = true;
				continue;
			}
			if (c == '\\') {
				char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
				boolean escapes = switch (quote) {
					case '\'' -> false;
					case '"' -> next == '"' || next == '\\';
					default -> true;
				};
				if (escapes) {
					escaped = true;
					started = true;
					continue;
				}
				current.append(c);
				started = true;
				continue;
			}
			if (quote != 0) {
				if (c == quote) {
					quote = 0;
				} else {
					current.append(c);
				}
				started = true;
				continue;
			}
			if (c == '\'' || c == '"') {
				quote = c;
				started = true;
				continue;
			}
			if (Character.isWhitespace(c)) {
				if (started) {
					tokens.add(current.toString());
					current.setLength(0);
					started = false;
				}
				continue;
			}
			current.append(c);
			started = true;
		}
		if (started) {
			tokens.add(current.toString());
		}
		return tokens;
	}

	/**
	 * Result of parsing command line arguments.
	 *
	 * @param launcherArguments flags passed to the launcher
	 * @param appArguments      arguments passed to the application
	 * @param profile           explicit profile name, if specified
	 * @param refusals          list of rejected flags with explanatory messages
	 */
	public record Parsed(@Unmodifiable List<String> launcherArguments, @Unmodifiable List<String> appArguments,
	                     @Nullable String profile, @Unmodifiable List<String> refusals) {

		/**
		 * Checks whether {@code --no-open} was specified in the application arguments.
		 *
		 * @return true if browser launch was suppressed
		 */
		public boolean asksNoOpen() {
			return appArguments.contains("--no-open");
		}
	}
}
