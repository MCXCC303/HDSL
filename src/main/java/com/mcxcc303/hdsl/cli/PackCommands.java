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
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.launch.DshSession;
import com.mcxcc303.hdsl.launch.DshSessions;
import com.mcxcc303.hdsl.pack.*;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Set;

/**
 * CLI commands for importing, exporting, installing, and restoring modpacks and profile packs.
 */
@NotNullByDefault
public final class PackCommands {

	private PackCommands() {
	}

	/**
	 * Runs the {@code pack export-packforge} command.
	 *
	 * @param invocation the parsed invocation
	 * @param out        the stream for normal output
	 * @param err        the stream for error output
	 * @return the process exit code
	 * @throws DshException if the pack cannot be exported
	 */
	static int exportPackforge(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		String id = invocation.option("id");
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			err.println("error: instance " + id + " does not exist");
			return 1;
		}
		Path target = Path.of(invocation.option("file"));
		DshPackForge.Options options = new DshPackForge.Options(DshPackForge.Options.kebab(instance.id()),
		                                                        "1.0.0",
		                                                        instance.id(),
		                                                        "",
		                                                        "");
		DshPackForge.Result exported = DshPackForge.export(instance, target, options, out::println);
		out.println("Wrote " + exported.files() + " file(s), left out " + exported.excluded() + ", sha256 " +
		            exported.sha256());
		return 0;
	}

	/**
	 * Runs the {@code pack export} command.
	 *
	 * @param invocation the parsed invocation
	 * @param out        the stream for normal output
	 * @param err        the stream for error output
	 * @return the process exit code
	 * @throws DshException if the pack cannot be exported
	 */
	static int exportModpack(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		String id = invocation.option("id");
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			err.println("error: instance " + id + " does not exist");
			return 1;
		}
		Path target = Path.of(invocation.option("file"));
		boolean withSessions = invocation.flag("with-sessions");
		// Export with plugin and skill settings by default; conversations are opt-in.
		PackManifest.Options defaults = PackManifest.Options.of(instance);
		PackManifest.Options options = new PackManifest.Options(instance.id(),
		                                                        "1.0",
		                                                        "",
		                                                        "",
		                                                        "",
		                                                        "",
		                                                        withSessions,
		                                                        Set.of(),
		                                                        defaults.settings(),
		                                                        defaults.skills());
		PackExporter.ExportResult exported = PackExporter.export(instance, target, options, out::println);
		out.println("Wrote " + exported.plugins() + " plugin(s), " + exported.bytes() + " byte(s)");
		return 0;
	}

	/**
	 * Runs the {@code pack install} command.
	 *
	 * @param invocation the parsed invocation
	 * @param out        the stream for normal output
	 * @param err        the stream for error output
	 * @return the process exit code
	 * @throws DshException if installation fails
	 */
	static int installModpack(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		Path pack = Path.of(invocation.option("file"));
		String id = invocation.option("id") != null
		            ? invocation.option("id")
		            : PackManifest.readManifest(pack).instanceId();
		// Verify the target instance is not currently running.
		DshInstance existing = InstanceStore.find(id);
		if (existing != null && RunningInstances.isUp(existing, err)) {
			return 1;
		}
		ModpackInstaller.InstallResult result = ModpackInstaller.install(pack,
		                                                                 id,
		                                                                 Path.of(System.getProperty("user.home")),
		                                                                 out::println);
		out.println("Instance " + result.instance().id() + " is ready: " +
		            (result.installed() ? "harness installed, " : "harness already present, ") + result.plugins() +
		            " plugin(s), " + result.bundles() + " bundle(s)");
		return 0;
	}

	/**
	 * Runs the {@code pack restore-profile} command.
	 *
	 * @param invocation the parsed invocation
	 * @param out        the stream for normal output
	 * @param err        the stream for error output
	 * @return the process exit code
	 * @throws DshException if profile restoration fails
	 */
	static int restoreProfile(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		String id = invocation.option("id");
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			err.println("error: instance " + id + " does not exist");
			return 1;
		}
		if (RunningInstances.isUp(instance, err)) {
			return 1;
		}
		Path pack = Path.of(invocation.option("file"));
		PackManifest.Manifest manifest = PackManifest.readManifest(pack);
		int plugins = ModpackInstaller.restoreProfile(instance, manifest, pack, out::println);
		out.println("Restored " + plugins + " plugin(s) and " + manifest.bundles().size() + " bundle(s) into " +
		            instance.id());
		for (String bundle : DshPluginInstaller.readBundles(instance.homeDirectory(), instance.profile())) {
			out.println("  bundle: " + bundle);
		}
		return 0;
	}

	/**
	 * Runs the {@code pack import} command.
	 *
	 * @param invocation the parsed invocation
	 * @param out        the stream for normal output
	 * @param err        the stream for error output
	 * @return the process exit code
	 * @throws DshException if import fails
	 */
	static int importPack(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		String id = invocation.option("id");
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			err.println("error: instance " + id + " does not exist");
			return 1;
		}
		if (RunningInstances.isUp(instance, err)) {
			return 1;
		}
		Path pack = Path.of(invocation.option("file"));
		DshSessionPacks.ImportResult imported = DshSessionPacks.importFrom(instance.homeDirectory(),
		                                                                   pack,
		                                                                   out::println);
		out.println("Imported " + imported.imported() + ", skipped " + imported.skipped() + ", attachments " +
		            imported.attachments());
		for (DshSession session : DshSessions.list(instance.homeDirectory())) {
			out.println("  " + session.id() + "  " + session.workspaceSlug() + "  " +
			            (session.title() == null ? "(no title)" : session.title()));
		}
		return 0;
	}
}
