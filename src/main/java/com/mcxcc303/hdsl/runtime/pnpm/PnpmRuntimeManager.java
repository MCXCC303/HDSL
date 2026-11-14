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
package com.mcxcc303.hdsl.runtime.pnpm;

import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshFiles;
import com.mcxcc303.hdsl.core.DshPaths;
import com.mcxcc303.hdsl.harness.VersionOrder;
import com.mcxcc303.hdsl.runtime.node.DshNodeRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Installs and enumerates the launcher-managed pnpm versions.
 */
@NotNullByDefault
public final class PnpmRuntimeManager {
	public static final String PACKAGE_NAME = "pnpm";

	private PnpmRuntimeManager() {
	}

	/**
	 * Lists the pnpm versions installed under {@link DshPaths#PNPM}.
	 *
	 * @return the installed versions, newest first
	 */
	public static List<PnpmRuntime> listInstalled() {
		List<PnpmRuntime> runtimes = new ArrayList<>();
		Path root = DshPaths.PNPM;
		if (!Files.isDirectory(root)) {
			return runtimes;
		}
		try (var entries = Files.list(root)) {
			for (Path directory : entries.filter(Files::isDirectory).toList()) {
				PnpmRuntime runtime = new PnpmRuntime(directory.getFileName().toString(), directory);
				if (runtime.isUsable()) {
					runtimes.add(runtime);
				}
			}
		} catch (IOException e) {
			LOG.warning("Failed to enumerate the installed pnpm versions in " + root, e);
		}
		runtimes.sort(Comparator.comparing(PnpmRuntime::version, VersionOrder::compareVersions).reversed());
		return runtimes;
	}

	/**
	 * Finds an installed pnpm by version.
	 *
	 * @param version the version string
	 * @return the installed pnpm, or {@code null} when no such version is installed
	 */
	public static @Nullable PnpmRuntime findInstalled(String version) {
		for (PnpmRuntime runtime : listInstalled()) {
			if (runtime.version().equals(version)) {
				return runtime;
			}
		}
		return null;
	}

	/**
	 * Finds the pnpm on {@code PATH}.
	 */
	public static @Nullable DshPnpm detect() {
		Path pnpm = DshNodeRuntime.which("pnpm").orElse(null);
		if (pnpm == null) {
			return null;
		}
		Path parent = pnpm.getParent();
		return new DshPnpm(pnpm, null, DshNodeRuntime.versionOf(pnpm),
		                   parent == null ? Path.of(".") : parent);
	}

	/**
	 * Installs a pnpm version into an npm prefix.
	 *
	 * @param version the version to install, with or without a leading {@code v}
	 * @param npm     the npm executable installing pnpm
	 * @param onStage receives progress lines, or {@code null}
	 * @return the installed pnpm
	 * @throws DshException when the install fails
	 */
	public static PnpmRuntime install(String version, Path npm, @Nullable Consumer<String> onStage)
	throws DshException {
		String normalized = version.trim();
		if (normalized.startsWith("v") || normalized.startsWith("V")) {
			normalized = normalized.substring(1);
		}
		if (findInstalled(normalized) != null) {
			throw new DshException("pnpm " + normalized + " is already installed");
		}

		Path target = DshPaths.pnpmDirectory(normalized);
		Path staging = target.resolveSibling(target.getFileName() + ".installing");
		try {
			Files.createDirectories(DshPaths.PNPM);
			DshFiles.deleteTreeQuietly(staging);
			Files.createDirectories(staging);
		} catch (IOException e) {
			throw new DshException("Failed to prepare " + staging, e);
		}

		stage(onStage, "Installing pnpm " + normalized);
		List<String> command = List.of(npm.toString(), "install", "--global", "--prefix", staging.toString(),
		                               PACKAGE_NAME + "@" + normalized, "--no-audit", "--no-fund");

		DshCommand.Result result;
		try {
			result = DshCommand.run(command, null, null, npmEnvironment(npm), onStage);
		} catch (IOException e) {
			DshFiles.deleteTreeQuietly(staging);
			throw new DshException("Failed to install pnpm " + normalized + ": " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			DshFiles.deleteTreeQuietly(staging);
			throw new DshException("Installing pnpm " + normalized + " was interrupted", e);
		}
		if (!result.isSuccess()) {
			DshFiles.deleteTreeQuietly(staging);
			throw new DshException("npm exited with code " + result.exitCode() + " while installing pnpm " +
					normalized);
		}

		try {
			DshFiles.deleteTreeQuietly(target);
			Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			DshFiles.deleteTreeQuietly(staging);
			throw new DshException("Failed to move the pnpm installation into " + target + ": " + e.getMessage(), e);
		}

		PnpmRuntime runtime = new PnpmRuntime(normalized, target);
		if (!runtime.isUsable()) {
			throw new DshException("pnpm " + normalized + " was installed without a runnable entry script");
		}
		LOG.info("Installed pnpm " + normalized + " into " + target);
		return runtime;
	}

	/**
	 * Removes an installed pnpm.
	 *
	 * @param version the version to remove
	 * @throws DshException when the version is not installed or cannot be removed
	 */
	public static void uninstall(String version) throws DshException {
		PnpmRuntime runtime = findInstalled(version);
		if (runtime == null) {
			throw new DshException("pnpm " + version + " is not installed");
		}
		try {
			DshFiles.deleteTree(runtime.directory());
		} catch (IOException e) {
			throw new DshException(
					"Failed to remove " + runtime.directory() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
					e);
		}
		LOG.info("Removed pnpm " + version);
	}

	private static Map<String, String> npmEnvironment(Path npm) {
		Path directory = npm.getParent();
		if (directory == null) return Map.of();

		String existing = System.getenv("PATH");
		String path = existing == null || existing.isBlank()
		              ? directory.toString()
		              : directory + File.pathSeparator + existing;
		return Map.of("PATH", path);
	}

	private static void stage(@Nullable Consumer<String> onStage, String message) {
		if (onStage != null) {
			onStage.accept(message);
		}
	}
}
