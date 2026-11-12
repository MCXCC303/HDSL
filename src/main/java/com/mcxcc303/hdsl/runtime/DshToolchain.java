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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Provides the launcher's Node.js and pnpm.
 * <p>
 * Each instance runs the DeepSeek Harness with its own Node.js, separate from the launcher's selection.
 * Selections come from suppliers installed by {@link #install}.
 */
@NotNullByDefault
public final class DshToolchain {
	public static final String SYSTEM = "system";
	private static volatile Supplier<String> nodeSelection = () -> SYSTEM;
	private static volatile Supplier<String> pnpmSelection = () -> SYSTEM;

	private DshToolchain() {}

	/**
	 * Installs the suppliers that provide the selections.
	 *
	 * @param node supplies the Node.js selection
	 * @param pnpm supplies the pnpm selection
	 */
	public static void install(Supplier<String> node, Supplier<String> pnpm) {
		nodeSelection = node;
		pnpmSelection = pnpm;
	}

	/**
	 * Returns the Node.js selection.
	 *
	 * @return {@link #SYSTEM}, or the version of an installed runtime
	 */
	public static String nodeSelection() {
		return nodeSelection.get();
	}

	/**
	 * Returns the pnpm selection.
	 *
	 * @return {@link #SYSTEM}, or the version of an installed pnpm
	 */
	public static String pnpmSelection() {
		return pnpmSelection.get();
	}

	/**
	 * Resolves the selected Node.js runtime.
	 * <p>
	 * A system runtime is refused if it's outside the supported range.
	 *
	 * @param selection {@link #SYSTEM} or the version of an installed runtime
	 * @return the runtime
	 * @throws DshException if the selected runtime is not installed, unsupported or not on {@code PATH}
	 */
	public static DshNodeRuntime resolve(String selection) throws DshException {
		if (SYSTEM.equalsIgnoreCase(selection)) {
			DshNodeRuntime runtime = DshNodeRuntime.detect().orElseThrow(() -> new DshMissingToolException(
					DshMissingToolException.Tool.NODE,
					"Node.js was not found on PATH; " + DshNodeRuntime.requirement()));
			if (!runtime.isNodeSupported()) {
				throw new DshMissingToolException(DshMissingToolException.Tool.NODE,
				                                  "The system Node.js " + runtime.nodeVersion() +
				                                  " is outside the supported range (" + DshNodeRuntime.requirement() +
				                                  "). Install a suitable runtime on the Node page, or pick one for this instance.");
			}
			return runtime;
		}
		NodeRuntime managed = NodeRuntimeManager.findInstalled(selection);
		if (managed == null) {
			throw new DshMissingToolException(DshMissingToolException.Tool.NODE,
			                                  "Node.js " + selection +
			                                  " is not installed; install it on the Node page or pick the system one");
		}
		return DshNodeRuntime.fromManaged(managed);
	}

	/**
	 * Resolves the selected Node.js.
	 *
	 * @return the runtime
	 * @throws DshException when the selected Node.js is not there
	 */
	public static DshNodeRuntime node() throws DshException {
		return resolve(nodeSelection());
	}

	/**
	 * Resolves the npm bundled with the selected Node.js runtime.
	 * <p>
	 * A Node.js installation carries an npm, and there is no npm to choose.
	 *
	 * @return the npm executable
	 * @throws DshException when the selected Node.js carries none
	 */
	public static Path npm() throws DshException {
		DshNodeRuntime runtime = node();
		Path npm = runtime.npm();
		if (npm == null) {
			throw new DshMissingToolException(DshMissingToolException.Tool.NODE,
			                                  "the Node.js at " + runtime.node() + " carries no npm; " +
			                                  "reading the registry and installing a pnpm require one");
		}
		return npm;
	}

	/**
	 * Resolves the selected pnpm.
	 *
	 * @param node the Node.js that runs a managed pnpm; {@code null} lets pnpm resolve Node.js
	 *             through its shim
	 * @return the pnpm
	 * @throws DshException if the selected pnpm is not installed or not on {@code PATH}
	 */
	public static DshPnpm pnpm(@Nullable Path node) throws DshException {
		DshPnpm pnpm = findPnpm(node);
		if (pnpm == null) {
			throw new DshMissingToolException(DshMissingToolException.Tool.PNPM,
			                                  "pnpm " + pnpmSelection() +
			                                  " is not installed; install one on the pnpm page or pick the system one");
		}
		return pnpm;
	}

	/**
	 * Looks for the selected pnpm without failing.
	 *
	 * @param node the Node.js running a managed pnpm, or {@code null} when the pnpm resolves
	 *             Node.js through the shim
	 * @return the pnpm, or {@code null} when there is none
	 */
	public static @Nullable DshPnpm findPnpm(@Nullable Path node) {
		String selection = pnpmSelection();
		if (SYSTEM.equalsIgnoreCase(selection)) {
			return PnpmRuntimeManager.detect();
		}
		PnpmRuntime managed = PnpmRuntimeManager.findInstalled(selection);
		if (managed == null) return null;
		return new DshPnpm(managed.cli(), node, managed.version(), managed.shimDirectory());
	}

	/**
	 * Returns environment entries that put the toolchain first on {@code PATH}.
	 *
	 * @param node the Node.js executable; its parent directory is prepended to {@code PATH}, or
	 *             {@code null} to skip it
	 * @param pnpm the pnpm; its shim directory is prepended after the Node.js directory
	 * @return a map with a single {@code PATH} entry
	 */
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
