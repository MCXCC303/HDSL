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

import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Launcher-wide environment defaults and instance environment resolution.
 * <p>
 * Manages runtime defaults and merges global and instance environment variables,
 * preventing overriding of launcher-managed variables like {@code DSH_HOME}.
 */
@NotNullByDefault
public final class DshEnvironment {
	static final String HOMEDIRECTORY_VARIABLE = "DSH_HOME";

	private static volatile Supplier<String> nodeDefault = () -> DshNodeRuntime.SYSTEM;
	private static volatile Supplier<DshHomeMode> homeDefault = () -> DshHomeMode.ISOLATED;

	private DshEnvironment() {
	}

	/**
	 * Installs the suppliers used to resolve launcher defaults.
	 *
	 * @param node the default Node runtime supplier
	 * @param home the default DSH_HOME policy supplier
	 */
	public static void install(Supplier<String> node, Supplier<DshHomeMode> home) {
		nodeDefault = node;
		homeDefault = home;
	}

	/**
	 * Returns the default Node runtime identifier.
	 *
	 * @return the default runtime selection
	 */
	public static String nodeRuntimeDefault() {
		String value = nodeDefault.get();
		return value == null || value.isBlank() ? DshNodeRuntime.SYSTEM : value;
	}

	/**
	 * Returns the default DSH_HOME isolation mode.
	 *
	 * @return the default policy, never {@link DshHomeMode#GLOBAL}
	 */
	public static DshHomeMode homeModeDefault() {
		DshHomeMode value = homeDefault.get();
		return value == null || value == DshHomeMode.GLOBAL ? DshHomeMode.ISOLATED : value;
	}

	/**
	 * Returns the merged environment variables for an instance.
	 *
	 * <p>Global launcher variables are merged with instance-specific variables, dropping any
	 * variables the launcher manages for itself (such as {@link #HOMEDIRECTORY_VARIABLE}).
	 *
	 * <p><strong>Refusal of {@code DSH_HOME}:</strong> The launcher manages the instance's home directory.
	 * If user-specified environment variables could inject {@code DSH_HOME}, the instance process could be
	 * hijacked to read a decoy directory (compromising credentials, profiles, sessions, and origin storage).
	 * Dropping it quietly ensures free-form typing in settings does not crash the launcher while preventing hijacking.
	 *
	 * @param instance the instance
	 * @return the merged environment map
	 */
	public static Map<String, String> of(DshInstance instance) {
		Map<String, String> merged = new LinkedHashMap<>();
		try {
			Map<String, String> launcherWide = SettingsManager.settings().globalEnvironment();
			putRefusingTheLaunchersOwn(merged, launcherWide);
		} catch (RuntimeException e) {
			Logger.LOG.warning("Could not read the launcher's environment", e);
		}
		putRefusingTheLaunchersOwn(merged, instance.environment());
		return merged;
	}

	private static void putRefusingTheLaunchersOwn(Map<String, String> target, Map<String, String> source) {
		for (Map.Entry<String, String> entry : source.entrySet()) {
			if (isReserved(entry.getKey())) {
				Logger.LOG.warning("Not setting " + entry.getKey() + ": the launcher states it for the instance");
				continue;
			}
			target.put(entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Checks whether an environment variable name is reserved by the launcher.
	 *
	 * <p>The check is performed case-insensitively because Windows environment variables are case-insensitive
	 * (e.g., {@code dsh_home} vs {@code DSH_HOME}, or {@code Path} vs {@code PATH}). On Windows, a differently-cased
	 * variable would otherwise bypass the check and overwrite the launcher-provided variable in the child process.
	 *
	 * @param name the variable name
	 * @return true if the variable is reserved
	 */
	static boolean isReserved(@Nullable String name) {
		return name != null && HOMEDIRECTORY_VARIABLE.equalsIgnoreCase(name.trim());
	}

	/**
	 * Parses environment variables from {@code KEY=VALUE} formatted text lines.
	 *
	 * @param text the text to parse
	 * @return map of parsed environment variables
	 */
	public static Map<String, String> parse(String text) {
		Map<String, String> variables = new LinkedHashMap<>();
		if (text == null) {
			return variables;
		}
		for (String line : text.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			int equals = trimmed.indexOf('=');
			if (equals < 0) {
				variables.put(trimmed, "");
			} else {
				variables.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
			}
		}
		return variables;
	}

	/**
	 * Formats environment variables into {@code KEY=VALUE} lines.
	 *
	 * @param variables the variables map
	 * @return formatted text lines
	 */
	public static String format(Map<String, String> variables) {
		StringBuilder text = new StringBuilder();
		for (Map.Entry<String, String> entry : variables.entrySet()) {
			text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
		}
		return text.toString();
	}
}
