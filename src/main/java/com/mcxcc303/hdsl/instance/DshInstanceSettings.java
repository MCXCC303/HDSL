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
package com.mcxcc303.hdsl.instance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mcxcc303.hdsl.core.DshException;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Per-instance settings overrides stored in {@code settings.json} within the instance directory.
 */
@NotNullByDefault
public final class DshInstanceSettings {
	private static final String FILE = "settings.json";

	private DshInstanceSettings() {
	}

	/**
	 * Reads the build script policy this instance chose, if configured.
	 *
	 * @param instance the instance
	 * @return the stored value, or {@code null} when it follows the launcher
	 */
	public static @Nullable String buildScriptPolicy(DshInstance instance) {
		JsonObject root = read(instance);
		if (root == null || !root.has("buildScriptPolicy") || !root.get("buildScriptPolicy").isJsonPrimitive()) {
			return null;
		}
		try {
			return root.get("buildScriptPolicy").getAsString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Records the build script policy this instance chose.
	 *
	 * @param instance the instance
	 * @param policy   the stored value, or {@code null} to follow the launcher
	 * @throws DshException when the file cannot be written
	 */
	public static void setBuildScriptPolicy(DshInstance instance, @Nullable String policy) throws DshException {
		write(instance, "buildScriptPolicy", policy == null ? null : new JsonPrimitive(policy));
	}

	/**
	 * Reads the command that runs before this instance starts.
	 *
	 * @param instance the instance
	 * @return its own command, or {@code null} to follow the launcher
	 */
	public static @Nullable String preLaunchCommand(DshInstance instance) {
		return stringOf(instance, "preLaunchCommand");
	}

	/**
	 * Records the command that runs before this instance starts.
	 *
	 * @param instance the instance
	 * @param command  the command, or {@code null} to follow the launcher
	 * @throws DshException when the file cannot be written
	 */
	public static void setPreLaunchCommand(DshInstance instance, @Nullable String command) throws DshException {
		write(instance, "preLaunchCommand", command == null ? null : new JsonPrimitive(command));
	}

	/**
	 * Reads the command that runs after this instance has ended.
	 *
	 * @param instance the instance
	 * @return its own command, or {@code null} to follow the launcher
	 */
	public static @Nullable String postExitCommand(DshInstance instance) {
		return stringOf(instance, "postExitCommand");
	}

	/**
	 * Records the command that runs after this instance has ended.
	 *
	 * @param instance the instance
	 * @param command  the command, or {@code null} to follow the launcher
	 * @throws DshException when the file cannot be written
	 */
	public static void setPostExitCommand(DshInstance instance, @Nullable String command) throws DshException {
		write(instance, "postExitCommand", command == null ? null : new JsonPrimitive(command));
	}

	/**
	 * Reads the automatic port assigned by the launcher for this instance.
	 *
	 * @param instance the instance
	 * @return the port, or {@code 0} when none was recorded
	 */
	public static int autoPort(DshInstance instance) {
		Integer port = intOf(instance, "autoPort");
		return port == null || port <= 0 ? 0 : port;
	}

	/**
	 * Records the automatic port assigned by the launcher for this instance.
	 *
	 * @param instance the instance
	 * @param port     the port, or {@code 0} to clear it
	 * @throws DshException when the file cannot be written
	 */
	public static void setAutoPort(DshInstance instance, int port) throws DshException {
		write(instance, "autoPort", port <= 0 ? null : new JsonPrimitive(port));
	}

	private static @Nullable Integer intOf(DshInstance instance, String name) {
		JsonObject root = read(instance);
		if (root == null || !root.has(name) || !root.get(name).isJsonPrimitive()) {
			return null;
		}
		try {
			return root.get(name).getAsInt();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static @Nullable String stringOf(DshInstance instance, String name) {
		JsonObject root = read(instance);
		if (root == null || !root.has(name) || !root.get(name).isJsonPrimitive()) {
			return null;
		}
		try {
			return root.get(name).getAsString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Reads whether this instance's log window opens on launch.
	 *
	 * @param instance the instance
	 * @return the choice made for this instance, or {@code null} to follow the launcher
	 */
	public static @Nullable Boolean showLogs(DshInstance instance) {
		return booleanOf(instance, "showLogs");
	}

	/**
	 * Records whether this instance's log window opens on launch.
	 *
	 * @param instance the instance
	 * @param value    the choice, or {@code null} to follow the launcher
	 * @throws DshException when the file cannot be written
	 */
	public static void setShowLogs(DshInstance instance, @Nullable Boolean value) throws DshException {
		write(instance, "showLogs", value == null ? null : new JsonPrimitive(value));
	}

	/**
	 * Reads whether this instance writes launcher debug lines.
	 *
	 * @param instance the instance
	 * @return the choice made for this instance, or {@code null} to follow the launcher
	 */
	public static @Nullable Boolean debugLog(DshInstance instance) {
		return booleanOf(instance, "debugLog");
	}

	/**
	 * Records whether this instance writes launcher debug lines.
	 *
	 * @param instance the instance
	 * @param value    the choice, or {@code null} to follow the launcher
	 * @throws DshException when the file cannot be written
	 */
	public static void setDebugLog(DshInstance instance, @Nullable Boolean value) throws DshException {
		write(instance, "debugLog", value == null ? null : new JsonPrimitive(value));
	}

	/**
	 * Reads which account this instance launches with.
	 *
	 * @param instance the instance
	 * @return the account's key, or {@code null} to launch with no account
	 */
	public static @Nullable String accountKey(DshInstance instance) {
		return stringOf(instance, "accountKey");
	}

	/**
	 * Records which account this instance launches with.
	 *
	 * @param instance the instance
	 * @param key      the account's key, or {@code null} for none
	 * @throws DshException when the file cannot be written
	 */
	public static void setAccountKey(DshInstance instance, @Nullable String key) throws DshException {
		write(instance, "accountKey", key == null ? null : new JsonPrimitive(key));
	}

	/**
	 * Reads whether this instance defines an environment of its own.
	 *
	 * @param instance the instance
	 * @return whether it defines its own environment
	 */
	public static boolean environmentIsOwn(DshInstance instance) {
		JsonObject root = read(instance);
		return root != null && root.has("environmentIsOwn") && root.get("environmentIsOwn").isJsonPrimitive() &&
		       root.get("environmentIsOwn").getAsBoolean();
	}

	/**
	 * Records whether this instance defines an environment of its own.
	 *
	 * @param instance the instance
	 * @param value    whether it defines its own environment
	 * @throws DshException when the file cannot be written
	 */
	public static void setEnvironmentIsOwn(DshInstance instance, boolean value) throws DshException {
		write(instance, "environmentIsOwn", new JsonPrimitive(value));
	}

	/**
	 * Reads what this instance does with the launcher window while it runs.
	 *
	 * @param instance the instance
	 * @return the choice made for this instance, or {@code null} to follow the launcher
	 */
	public static @Nullable String launcherVisibility(DshInstance instance) {
		return stringOf(instance, "launcherVisibility");
	}

	/**
	 * Records what this instance does with the launcher window while it runs.
	 *
	 * @param instance the instance
	 * @param value    the choice, or {@code null} to follow the launcher
	 * @throws DshException when the file cannot be written
	 */
	public static void setLauncherVisibility(DshInstance instance, @Nullable String value) throws DshException {
		write(instance, "launcherVisibility", value == null ? null : new JsonPrimitive(value));
	}

	private static @Nullable Boolean booleanOf(DshInstance instance, String name) {
		JsonObject root = read(instance);
		if (root == null || !root.has(name) || !root.get(name).isJsonPrimitive()) {
			return null;
		}
		try {
			return root.get(name).getAsBoolean();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Reads whether install scripts may run without being asked about.
	 *
	 * @param instance the instance
	 * @return the choice made for this instance, or {@code null} to follow the launcher
	 */
	public static @Nullable Boolean approveBuildScripts(DshInstance instance) {
		JsonObject root = read(instance);
		if (root == null || !root.has("approveBuildScripts") || !root.get("approveBuildScripts").isJsonPrimitive()) {
			return null;
		}
		try {
			return root.get("approveBuildScripts").getAsBoolean();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Records whether install scripts may run without being asked about.
	 *
	 * @param instance the instance
	 * @param value    the choice, or {@code null} to follow the launcher again
	 * @throws DshException when the file cannot be written
	 */
	public static void setApproveBuildScripts(DshInstance instance, @Nullable Boolean value) throws DshException {
		write(instance, "approveBuildScripts", value == null ? null : new JsonPrimitive(value));
	}

	private static void write(DshInstance instance, String name, @Nullable JsonElement value) throws DshException {
		Path file = fileOf(instance);
		JsonObject root = read(instance);
		if (root == null) {
			root = new JsonObject();
		}
		if (value == null) {
			root.remove(name);
		} else {
			root.add(name, value);
		}

		Path staging = file.resolveSibling(FILE + ".hdsl");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(staging, JsonUtils.GSON.toJson(root));
			Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new DshException("Failed to write " + file, e);
		}
	}

	private static @Nullable JsonObject read(DshInstance instance) {
		Path file = fileOf(instance);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			JsonObject root = JsonUtils.fromJsonFile(file, JsonObject.class);
			return root;
		} catch (Exception e) {
			LOG.warning("Failed to read " + file, e);
			return null;
		}
	}

	private static Path fileOf(DshInstance instance) {
		try {
			return instance.instanceDirectory().resolve(FILE);
		} catch (DshException e) {
			// An instance whose directory cannot be resolved has nowhere to keep
			// settings; a path that will fail to be written is the honest answer.
			return Path.of(FILE);
		}
	}
}
