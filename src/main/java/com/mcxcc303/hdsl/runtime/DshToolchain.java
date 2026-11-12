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
	 *
	 * @return the runtime
	 * @throws DshException if the selected runtime is not installed or not on {@code PATH}
	 */
	public static DshNodeRuntime node() throws DshException {
		String selection = nodeSelection();
		if (SYSTEM.equalsIgnoreCase(selection)) {
			return DshNodeRuntime.detect()
			                     .orElseThrow(() -> new DshException(
					                     "Node.js was not found on PATH; " + DshNodeRuntime.requirement()));
		}
		NodeRuntime managed = NodeRuntimeManager.findInstalled(selection);
		if (managed == null) {
			throw new DshException(
					"Node.js " + selection + " is not installed; install it on the Node page or pick the system one");
		}
		return DshNodeRuntime.fromManaged(managed);
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
		String selection = pnpmSelection();
		if (SYSTEM.equalsIgnoreCase(selection)) {
			DshPnpm system = PnpmRuntimeManager.detect();
			if (system == null) {
				throw new DshException("pnpm was not found on PATH; install one on the pnpm page or pick the system one");
			}
			return system;
		}
		PnpmRuntime managed = PnpmRuntimeManager.findInstalled(selection);
		if (managed == null) {
			throw new DshException(
					"pnpm " + selection + " is not installed; install it on the pnpm page or pick the system one");
		}
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
