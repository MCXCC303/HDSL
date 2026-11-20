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
import com.mcxcc303.hdsl.runtime.npm.DshNpm;
import com.mcxcc303.hdsl.runtime.npm.NpmRuntimeManager;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * A Node.js installation together with its npm.
 */
@NotNullByDefault
public record DshNodeRuntime(Path node, String nodeVersion, @Nullable DshNpm npm) {
	public static final String SYSTEM = "system";
	public static final String GLOBAL = "global";
	public static final String MINIMUM_PNPM = "10.9";
	private static final int NODE_22_MAJOR = 22;
	private static final int MINIMUM_NODE_22_MINOR = 19;
	private static final int MINIMUM_NODE_MODERN_MAJOR = 24;

	public static DshNodeRuntime from(NodeRuntime runtime) {
		return new DshNodeRuntime(runtime.node(),
		                          runtime.version(),
		                          NpmRuntimeManager.bundled(runtime.node()));
	}

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
		return Optional.of(new DshNodeRuntime(node,
		                                      nodeVersion,
		                                      npm == null ? null : NpmRuntimeManager.onPath(npm)));
	}

	public static boolean isSupportedNodeVersion(String version) {
		Semver parsed = Semver.parse(version);
		if (parsed == null) return false;
		if (parsed.major() >= MINIMUM_NODE_MODERN_MAJOR) return true;

		return parsed.major() == NODE_22_MAJOR && parsed.minor() >= MINIMUM_NODE_22_MINOR;
	}

	public static String requirement() {
		return "Node.js ^22.19.0 || >=24.0.0 and pnpm >=" + MINIMUM_PNPM;
	}

	public static @Nullable String versionOf(Path executable) {
		return DshCommand.versionOf(List.of(executable.toString(), "--version"));
	}

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

	private static boolean isExecutable(Path file) {
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS || Files.isExecutable(file);
	}

	public boolean isNodeSupported() {
		return isSupportedNodeVersion(nodeVersion);
	}

	public boolean canInstall() {
		return npm != null && npm.version() != null;
	}

	public Map<String, String> pathEnvironment() {
		String existing = System.getenv("PATH");
		String bin = binDirectory().toString();
		return Map.of("PATH", existing == null || existing.isBlank() ? bin : bin + File.pathSeparator + existing);
	}

	public Path binDirectory() {
		Path parent = node.getParent();
		return parent == null ? node : parent;
	}
}
