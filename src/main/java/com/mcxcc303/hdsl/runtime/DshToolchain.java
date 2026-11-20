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
package com.mcxcc303.hdsl.runtime;

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.runtime.node.DshNodeRuntime;
import com.mcxcc303.hdsl.runtime.node.NodeRuntime;
import com.mcxcc303.hdsl.runtime.node.NodeRuntimeManager;
import com.mcxcc303.hdsl.runtime.npm.DshNpm;
import com.mcxcc303.hdsl.runtime.pnpm.DshPnpm;
import com.mcxcc303.hdsl.runtime.pnpm.PnpmRuntime;
import com.mcxcc303.hdsl.runtime.pnpm.PnpmRuntimeManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Provides the launcher's Node.js, npm and pnpm.
 */
@NotNullByDefault
public final class DshToolchain {
	public static final String SYSTEM = DshRuntimeLocation.SYSTEM;
	private static volatile Supplier<String> nodeSelection = () -> SYSTEM;
	private static volatile Supplier<String> pnpmSelection = () -> SYSTEM;

	private DshToolchain() {}

	public static void install(Supplier<String> node, Supplier<String> pnpm) {
		nodeSelection = node;
		pnpmSelection = pnpm;
	}

	public static String nodeSelection() {
		return nodeSelection.get();
	}

	public static String pnpmSelection() {
		return pnpmSelection.get();
	}

	public static List<DshRuntimeEntry> nodeEntries() {
		List<DshRuntimeEntry> entries = new ArrayList<>();
		for (NodeRuntime runtime : NodeRuntimeManager.listInstalled()) {
			entries.add(new DshRuntimeEntry(DshRuntimeOrigin.MANAGED, runtime.directory(), runtime.node(),
			                                runtime.version()));
		}
		DshNodeRuntime system = DshNodeRuntime.detect().orElse(null);
		if (system != null) {
			entries.add(new DshRuntimeEntry(DshRuntimeOrigin.SYSTEM, null, system.node(), system.nodeVersion()));
		}
		return entries;
	}

	public static List<DshRuntimeEntry> pnpmEntries() {
		List<DshRuntimeEntry> entries = new ArrayList<>();
		for (PnpmRuntime runtime : PnpmRuntimeManager.listInstalled()) {
			entries.add(new DshRuntimeEntry(DshRuntimeOrigin.MANAGED, runtime.directory(), runtime.cli(),
			                                runtime.version()));
		}
		DshPnpm system = PnpmRuntimeManager.detect();
		if (system != null) {
			entries.add(new DshRuntimeEntry(DshRuntimeOrigin.SYSTEM, null, system.command(), system.version()));
		}
		return entries;
	}

	public static boolean isLastRuntime(List<DshRuntimeEntry> entries, Path directory) {
		for (DshRuntimeEntry entry : entries) {
			if (entry.directory() == null || !entry.directory().equals(directory)) {
				return false;
			}
		}
		return true;
	}

	public static DshNodeRuntime resolve(String selection) throws DshException {
		Path directory = DshRuntimeLocation.pathOf(selection);
		if (directory == null) {
			DshNodeRuntime runtime = DshNodeRuntime.detect().orElseThrow(DshToolchain::noSystemNode);
			if (!runtime.isNodeSupported()) {
				throw new DshMissingToolException(DshMissingToolException.Tool.NODE,
				                                  "System Node.js " + runtime.nodeVersion() +
				                                  " is outside the supported range (" + DshNodeRuntime.requirement() +
				                                  "). Install a supported runtime on the Node page, or select one for this instance.");
			}
			return runtime;
		}

		NodeRuntime runtime = NodeRuntimeManager.find(directory);
		if (runtime == null) {
			throw new DshMissingToolException(DshMissingToolException.Tool.NODE,
			                                  "No usable Node.js at " + directory +
			                                  ". Install one on the Node page, or select another runtime.");
		}
		return DshNodeRuntime.from(runtime);
	}

	private static DshMissingToolException noSystemNode() {
		Path node = DshNodeRuntime.which("node").orElse(null);
		if (node == null) {
			return new DshMissingToolException(DshMissingToolException.Tool.NODE,
			                                   "Node.js was not found on PATH; Requires: " + DshNodeRuntime.requirement());
		}
		return new DshMissingToolException(DshMissingToolException.Tool.NODE,
		                                   "Unrecognized Node.js Runtime: " + node);
	}

	public static DshNodeRuntime node() throws DshException {
		return resolve(nodeSelection());
	}

	public static DshNpm npm() throws DshException {
		DshNodeRuntime runtime = node();
		DshNpm npm = runtime.npm();
		if (npm == null) {
			throw new DshMissingToolException(DshMissingToolException.Tool.NODE,
			                                  "Node.js at " + runtime.node() +
			                                  " has no npm. Installing pnpm requires npm.");
		}
		return npm;
	}

	public static @Nullable DshNpm findNpm() {
		try {
			return node().npm();
		} catch (DshException e) {
			return null;
		}
	}

	public static DshPnpm pnpm(@Nullable Path node) throws DshException {
		DshPnpm pnpm = findPnpm(node);
		if (pnpm == null) {
			throw new DshMissingToolException(DshMissingToolException.Tool.PNPM,
			                                  "pnpm " + pnpmSelection() +
			                                  " is not installed. Install it on the pnpm page, or select the system pnpm.");
		}
		return pnpm;
	}

	public static @Nullable DshPnpm findPnpm(@Nullable Path node) {
		Path directory;
		try {
			directory = DshRuntimeLocation.pnpmPathOf(pnpmSelection());
		} catch (DshException e) {
			return null;
		}
		if (directory == null) {
			return PnpmRuntimeManager.detect();
		}
		PnpmRuntime managed = PnpmRuntimeManager.find(directory);
		if (managed == null) return null;
		return new DshPnpm(managed.cli(), node, managed.version(), managed.shimDirectory());
	}

	public static Map<String, String> pathEnvironment(@Nullable Path node, DshPnpm pnpm) {
		List<String> entries = new ArrayList<>();
		if (node != null) {
			Path parent = node.getParent();
			if (parent != null) {
				entries.add(parent.toString());
			}
		}
		entries.add(pnpm.shimDirectory().toString());
		String existing = System.getenv("PATH");
		if (existing != null && !existing.isBlank()) {
			entries.add(existing);
		}
		return Map.of("PATH", String.join(File.pathSeparator, entries));
	}
}
