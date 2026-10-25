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
package com.mcxcc303.hdsl.harness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The `appBoot` pin: which version of the boot library an instance starts with, and the
/// override that says so in the instance's own profile.
///
/// A value read back out of a profile is checked against what the manifest declares, because a
/// profile somebody edited by hand is a profile the launcher has to be able to refuse.
@NotNullByDefault
public final class AppBootPin {
	/// The npm package that provides the application boot library.
	///
	/// Held to the same version as DeepSeek Harness itself: the two are
	/// published together, and a mismatch between them is a failure at import
	/// rather than a degradation.
	public static final String APP_BOOT_PACKAGE = "@deepseek-ai/dsh-app-boot";

	/// The line that pins the boot library, as the file writes it.
	///
	/// Anchored on the key rather than looked for anywhere in a line, and that is the whole point:
	/// the workspace file also *lists* packages, in entries spelled "- '@scope/name@1.2.3'", and a
	/// scan for the name alone reads the boot library out of one of those. What it answers with then
	/// still carries the "@" that separated the two halves of that list entry — and that value
	/// travels: an exported pack records it, and installing it writes a workspace file whose pin is
	/// not a version at all, which pnpm then refuses.
	private static final Pattern APP_BOOT_PIN = Pattern.compile(
			"^[ \\t]*['\"]?" + Pattern.quote(APP_BOOT_PACKAGE) + "['\"]?[ \\t]*:[ \\t]*(\\S+)[ \\t]*$",
			Pattern.MULTILINE);

	private AppBootPin() {
	}

	/**
	 * Holds an instance's boot library to a different release.
	 *
	 * @param instance the instance to change
	 * @param appBoot  the boot library version to hold it to
	 * @throws DshException when the manifest cannot be written or pnpm fails
	 */
	public static void overrideAppBoot(DshInstance instance, String appBoot) throws DshException {
		overrideAppBoot(instance, appBoot, null);
	}

	/**
	 * Holds an instance's copy to a boot library version, reporting the HarnessInstaller.install.
	 *
	 * @param instance the instance to change
	 * @param appBoot  the boot library version to hold it to
	 * @param onLine   receives the package manager's output, or `null`
	 * @throws DshException when the manifest cannot be written or pnpm fails
	 */
	public static void overrideAppBoot(DshInstance instance, String appBoot, @Nullable Consumer<String> onLine)
	throws DshException {
		Path target = instance.dshDirectory();
		if (!Files.isDirectory(target)) {
			throw new DshException("Instance " + instance.id() + " has no DeepSeek Harness to change");
		}

		DshNodeRuntime runtime = HarnessInstaller.requireRuntime();
		if (!runtime.canManagePlugins()) {
			throw new DshException("pnpm was not found on PATH; changing the boot library requires it");
		}

		HarnessInstaller.writeManifest(target,
		                               instance.version(),
		                               appBoot,
		                               runtime,
		                               SettingsManager.settings().dependencyPolicy());

		List<String> command = HarnessInstaller.buildInstallCommand(runtime, target);

		LOG.info("Holding " + instance.id() + " to boot library " + appBoot);
		int exitCode;
		DshCommand.Result commandResult;
		try {
			commandResult = DshCommand.run(command, null, onLine);
			exitCode = commandResult.exitCode();
		} catch (IOException e) {
			throw new DshException("Failed to run pnpm", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DshException("The boot library change was interrupted", e);
		}
		if (exitCode != 0) {
			throw new DshException(
					"pnpm exited with code " + exitCode + " while changing the boot library of " + instance.id() +
					":\n" + ReleaseQueries.tail(commandResult.output()));
		}
	}

	/// Returns the boot library version the workspace file pins, or null when it pins none.
	///
	/// The seam the reader is written against, so the parse can be tested on a file's own text
	/// rather than through an instance that has to exist first.
	///
	/// @param workspace the file's text
	/// @return the pinned version, or null
	static @Nullable String appBootPin(String workspace) {
		Matcher pin = APP_BOOT_PIN.matcher(workspace);
		if (!pin.find()) {
			return null;
		}
		String version = pin.group(1).trim();
		if (version.length() > 1 && (version.charAt(0) == '\'' || version.charAt(0) == '"') &&
		    version.charAt(version.length() - 1) == version.charAt(0)) {
			version = version.substring(1, version.length() - 1);
		}
		return version.isEmpty() ? null : version;
	}

	/// Returns the boot library version an instance is held to.
	///
	/// The pin is read back from where it was written, because that is the
	/// instance's own record of the choice: asking pnpm which copy it linked
	/// would mean reproducing its store layout, and the version that matters to
	/// the page is the one the instance asks for.
	///
	/// @param instance the instance
	/// @return the version, or `null` when the instance has no pin to read
	public static @Nullable String readAppBoot(DshInstance instance) {
		Path prefix;
		try {
			prefix = instance.dshDirectory();
		} catch (DshException e) {
			return null;
		}

		Path workspace = prefix.resolve("pnpm-workspace.yaml");
		if (Files.isRegularFile(workspace)) {
			try {
				String pinned = appBootPin(Files.readString(workspace));
				if (pinned != null) {
					return pinned;
				}
			} catch (IOException e) {
				LOG.warning("Failed to read the boot library pin of " + instance.id(), e);
			}
		}

		Path manifest = prefix.resolve("package.json");
		if (!Files.isRegularFile(manifest)) {
			return null;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
			JsonElement overrides = root.get("overrides");
			if (overrides == null || !overrides.isJsonObject()) {
				return null;
			}
			JsonElement version = overrides.getAsJsonObject().get(APP_BOOT_PACKAGE);
			return version == null || !version.isJsonPrimitive() ? null : version.getAsString();
		} catch (IOException | RuntimeException e) {
			LOG.warning("Failed to read the boot library pin of " + instance.id(), e);
			return null;
		}
	}
}
