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
package com.mcxcc303.hdsl.runtime.node;

import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.Semver;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * A Node.js installation together with its npm.
 * <p>
 * DeepSeek Harness needs Node {@code ^22.19.0 || >=24.0.0}.
 *
 * @param node        the node executable
 * @param nodeVersion the node version, without a leading {@code v}
 * @param npm         the npm executable, or {@code null} when absent
 * @param npmVersion  the npm version, or {@code null} when absent
 */
@NotNullByDefault
public record DshNodeRuntime(Path node, String nodeVersion, @Nullable Path npm, @Nullable String npmVersion) {
	public static final String SYSTEM = "system";
	public static final String GLOBAL = "global";
	// Oldest pnpm that can install DeepSeek Harness.
	public static final String MINIMUM_PNPM = "10.9";
	private static final int NODE_22_MAJOR = 22;
	private static final int MINIMUM_NODE_22_MINOR = 19;
	private static final int MINIMUM_NODE_MODERN_MAJOR = 24;

	/**
	 * Returns a launcher-managed runtime in the same shape as a probed one.
	 *
	 * @param runtime the installed runtime
	 * @return the descriptor
	 */
	public static DshNodeRuntime fromManaged(NodeRuntime runtime) {
		return new DshNodeRuntime(runtime.node(),
		                          runtime.version(),
		                          runtime.npm(),
		                          runtime.npm() == null ? null : versionOf(runtime.npm()));
	}

	/**
	 * Probes the toolchain on the current {@code PATH}.
	 *
	 * @return the detected runtime, or empty when no usable {@code node} was found
	 */
	public static Optional<DshNodeRuntime> detect() {
		Path node = which("node").orElse(null);
		if (node == null) {
			return Optional.empty();
		}
		String nodeVersion = versionOf(node);
		if (nodeVersion == null) {
			return Optional.empty();
		}
		Path npm = which("npm").orElse(null);
		return Optional.of(new DshNodeRuntime(node, nodeVersion, npm, npm == null ? null : versionOf(npm)));
	}

	/**
	 * Tests a version string against DeepSeek Harness's Node range.
	 *
	 * @param version the version string, optionally prefixed with {@code v}
	 * @return whether the version is supported
	 */
	public static boolean isSupportedNodeVersion(String version) {
		Semver parsed = Semver.parse(version);
		if (parsed == null) return false;
		if (parsed.major() >= MINIMUM_NODE_MODERN_MAJOR) return true;

		return parsed.major() == NODE_22_MAJOR && parsed.minor() >= MINIMUM_NODE_22_MINOR;
	}

	/**
	 * Returns the toolchain requirement shown to the user.
	 *
	 * @return a human-readable requirement string
	 */
	public static String requirement() {
		return "Node.js ^22.19.0 || >=24.0.0, plus pnpm " + MINIMUM_PNPM +
		       " or newer for installing DeepSeek Harness; pnpm is also needed for plugin management";
	}

	/**
	 * Probes a program for its version.
	 *
	 * @param executable the program to run with {@code --version}
	 * @return the first output line, trimmed, or {@code null} when no probe answered
	 */
	public static @Nullable String versionOf(Path executable) {
		String failure = null;
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				DshCommand.Result result = DshCommand.run(List.of(executable.toString(), "--version"));
				if (result.isSuccess() && !result.output().isEmpty()) {
					String first = result.output().get(0).trim();
					return first.startsWith("v") || first.startsWith("V") ? first.substring(1) : first;
				}
				failure = "`" + executable + " --version` exited " + result.exitCode();
			} catch (IOException e) {
				failure = "`" + executable + " --version` could not be started: " + e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		LOG.warning("Could not read the version of " + executable + ": " + failure);
		return null;
	}

	/**
	 * Resolves an executable on {@code PATH}.
	 *
	 * @param name the executable name
	 * @return the resolved path, or empty when it is not on {@code PATH}
	 */
	public static Optional<Path> which(String name) {
		String path = System.getenv("PATH");
		if (path == null || path.isBlank()) return Optional.empty();

		List<String> candidates = executableNames(name);
		for (String entry : path.split(File.pathSeparator)) {
			if (entry.isBlank()) continue;

			Path directory = Path.of(entry);
			for (String candidate : candidates) {
				Path file = directory.resolve(candidate);
				if (Files.isRegularFile(file) && isExecutable(file)) {
					return Optional.of(file);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Returns the file names one program name can be found under.
	 *
	 * @param name the program name as typed
	 * @return the names to look for, the plain one first
	 */
	private static List<String> executableNames(String name) {
		if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) {
			return List.of(name);
		}
		for (String suffix : new String[]{".exe", ".cmd", ".bat", ".com"}) {
			if (name.toLowerCase(Locale.ROOT).endsWith(suffix)) {
				return List.of(name);
			}
		}
		LinkedHashSet<String> names = new LinkedHashSet<>();
		names.add(name + ".exe");
		names.add(name + ".cmd");
		names.add(name + ".bat");
		String pathExt = System.getenv("PATHEXT");
		if (pathExt != null) {
			for (String extension : pathExt.split(";")) {
				String trimmed = extension.trim();
				if (!trimmed.isEmpty()) {
					names.add(name + trimmed.toLowerCase(Locale.ROOT));
				}
			}
		}
		return List.copyOf(names);
	}

	/**
	 * Returns whether a file can be run as a program.
	 *
	 * @param file the file
	 * @return whether it is executable, which every regular file is on Windows
	 */
	private static boolean isExecutable(Path file) {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS || Files.isExecutable(file);
	}

	/**
	 * Returns whether this Node release satisfies DeepSeek Harness's {@code engines} range.
	 *
	 * @return whether the version is {@code ^22.19.0} or {@code >=24.0.0}
	 */
	public boolean isNodeSupported() {
		return isSupportedNodeVersion(nodeVersion);
	}

	/**
	 * Returns whether this runtime can install DeepSeek Harness versions.
	 *
	 * @return whether {@code npm} is available
	 */
	public boolean canInstall() {
		return npm != null && npmVersion != null;
	}

	/**
	 * Returns the environment entries that put this runtime's tools first. The runtime's
	 * directory comes first, so a child process finds these tools before the system's.
	 *
	 * @return the environment additions
	 */
	public Map<String, String> pathEnvironment() {
		String existing = System.getenv("PATH");
		String bin = binDirectory().toString();
		return Map.of("PATH", existing == null || existing.isBlank() ? bin : bin + File.pathSeparator + existing);
	}

	/**
	 * Returns the directory holding this runtime's executables. {@code bin} on Linux, and the
	 * distribution root on Windows, where the node executable already sits.
	 *
	 * @return the directory of the {@code node} executable
	 */
	public Path binDirectory() {
		Path parent = node.getParent();
		return parent == null ? node : parent;
	}
}
