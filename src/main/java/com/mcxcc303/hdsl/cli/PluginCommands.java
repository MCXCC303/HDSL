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
package com.mcxcc303.hdsl.cli;

import com.mcxcc303.hdsl.cli.Cli.Invocation;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.DshPreset;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.plugin.DshBuildScripts;
import com.mcxcc303.hdsl.plugin.DshLocalPlugins;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * The commands about plugins
 */
@NotNullByDefault
public final class PluginCommands {

	private PluginCommands() {
	}

	/**
	 * Runs the `plugin install` command
	 */
	static int installPlugin(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		String id = invocation.option("id");
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			err.println("error: instance " + id + " does not exist");
			return 1;
		}
		String spec = invocation.option("spec");
		out.println("Installing " + spec + " into " + instance.id() + " ...");
		DshPluginInstaller.install(instance, List.of(new DshPreset(spec, spec, spec, "", false, true)), out::println);
		out.println("Installed " + spec);
		for (String bundle : DshPluginInstaller.readBundles(instance.homeDirectory(), instance.profile())) {
			out.println("  bundle: " + bundle);
		}
		return 0;
	}

	/**
	 * Runs the `plugin build-scripts` command
	 * <p>
	 * Without `--allow` or `--deny` it prints the profile's install-script entries. <br>
	 * With one of them the packages named are answered for, or every package still waiting when none is named
	 */
	static int buildScripts(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		String id = invocation.option("id");
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			err.println("error: instance " + id + " does not exist");
			return 1;
		}

		if (!invocation.flag("allow") && !invocation.flag("deny")) {
			for (DshBuildScripts.Pending entry : DshBuildScripts.pending(instance)) {
				out.println(
						entry.name() + ": " + (entry.allowed() == null ? "waiting for an answer" : entry.allowed()));
			}
			return 0;
		}

		boolean allow = invocation.flag("allow");
		List<String> named = invocation.values("package");
		List<String> targets = named.isEmpty() ? DshBuildScripts.unanswered(instance) : named;
		int written = DshBuildScripts.answer(instance, targets, allow);
		out.println((allow ? "Allowed" : "Refused") + " install scripts for " + written + " package(s) in " +
		            instance.id());
		return 0;
	}

	/**
	 * Runs the `plugin install-file` command
	 */
	static int installPluginFile(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		String id = invocation.option("id");
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			err.println("error: instance " + id + " does not exist");
			return 1;
		}
		Path file = Path.of(invocation.option("file"));
		DshLocalPlugins.Result result = DshLocalPlugins.install(instance, file, out::println);
		out.println("Installed " + result.pkg().name() + " " + result.pkg().version() +
		            " from the instance's own copy at " + result.installed());
		out.println("  bundle patch: " + (result.pkg().isBundle() ? result.pkg().bundlePatch() : "(none)"));
		for (String bundle : DshPluginInstaller.readBundles(instance.homeDirectory(), instance.profile())) {
			out.println("  bundle: " + bundle);
		}
		return 0;
	}

	/**
	 * Runs the `plugin remove` command
	 */
	static int removePlugin(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		String id = invocation.option("id");
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			err.println("error: instance " + id + " does not exist");
			return 1;
		}
		List<String> specs = invocation.values("spec");
		out.println("Removing " + String.join(", ", specs) + " from " + instance.id() + " ...");
		DshPluginInstaller.removeSpecs(instance, specs, out::println);
		out.println("Removed " + specs.size() + " package(s)");
		for (String bundle : DshPluginInstaller.readBundles(instance.homeDirectory(), instance.profile())) {
			out.println("  bundle: " + bundle);
		}
		return 0;
	}
}
