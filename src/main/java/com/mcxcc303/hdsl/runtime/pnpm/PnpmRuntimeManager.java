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
import com.mcxcc303.hdsl.core.Semver;
import com.mcxcc303.hdsl.runtime.node.DshNodeRuntime;
import com.mcxcc303.hdsl.runtime.npm.DshNpm;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
		runtimes.sort(Comparator.comparing(PnpmRuntime::version, Semver::compare).reversed());
		return runtimes;
	}

	public static @Nullable PnpmRuntime find(Path directory) {
		Path normalized = directory.toAbsolutePath().normalize();
		Path name = normalized.getFileName();
		if (name == null || !DshPaths.isManagedPnpm(normalized)) {
			return null;
		}
		PnpmRuntime runtime = new PnpmRuntime(name.toString(), normalized);
		return runtime.isUsable() ? runtime : null;
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

	public static PnpmRuntime install(String version, DshNpm npm, @Nullable Consumer<String> onStage)
	throws DshException {
		String normalized = version.trim();
		if (normalized.startsWith("v") || normalized.startsWith("V")) {
			normalized = normalized.substring(1);
		}
		Path target = DshPaths.pnpmDirectory(normalized);
		if (Files.exists(target)) {
			throw new DshException("pnpm " + normalized + " is already installed");
		}
		Path staging = target.resolveSibling(target.getFileName() + ".installing");
		try {
			Files.createDirectories(DshPaths.PNPM);
			DshFiles.deleteTreeQuietly(staging);
			Files.createDirectories(staging);
		} catch (IOException e) {
			throw new DshException("Failed to prepare " + staging, e);
		}

		stage(onStage, "Installing pnpm " + normalized);
		List<String> command = npm.commandFor(List.of("install", "--global", "--prefix", staging.toString(),
		                                                   PACKAGE_NAME + "@" + normalized, "--no-audit", "--no-fund"));

		DshCommand.Result result;
		try {
			result = DshCommand.run(command, null, null, npm.pathEnvironment(), onStage);
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

	public static void uninstall(Path directory) throws DshException {
		Path target = directory.toAbsolutePath().normalize();
		if (!DshPaths.isManagedPnpm(target)) {
			throw new DshException("Cannot remove pnpm " + target + ": Not managed by HDSL.");
		}
		if (!Files.isDirectory(target)) {
			throw new DshException("No pnpm at " + target);
		}

		try {
			DshFiles.deleteTree(target);
		} catch (IOException e) {
			throw new DshException("Failed to remove " + target + (e.getMessage() == null ? "" : ": " + e.getMessage()),
					e);
		}
		LOG.info("Removed the pnpm at " + target);
	}

	private static void stage(@Nullable Consumer<String> onStage, String message) {
		if (onStage != null) {
			onStage.accept(message);
		}
	}
}
