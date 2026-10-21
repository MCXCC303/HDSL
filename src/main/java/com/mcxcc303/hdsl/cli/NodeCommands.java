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
import com.mcxcc303.hdsl.runtime.NodeRelease;
import com.mcxcc303.hdsl.runtime.NodeRuntime;
import com.mcxcc303.hdsl.runtime.NodeRuntimeManager;
import com.mcxcc303.hdsl.runtime.NodeSource;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.PrintStream;
import java.util.List;

@NotNullByDefault
public final class NodeCommands {

	private NodeCommands() {}

	static int listRuntimes(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		List<NodeRuntime> runtimes = NodeRuntimeManager.listInstalled();
		if (runtimes.isEmpty()) out.println("(no managed Node runtimes)");

		DshTable table = new DshTable("VERSION", "DIRECTORY");
		for (NodeRuntime runtime : runtimes) {
			table.row(runtime.version(), runtime.directory().toString());
		}
		table.print(out);
		return 0;
	}

	static int listNodeVersions(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		DshTable table = new DshTable("VERSION", "LABLE", "STATUS");
		for (NodeRelease release : NodeRuntimeManager.fetchReleases(NodeSource.of(invocation.option("source")))) {
//			out.println(release.version() + (release.isLts() ? "\t" + release.label() : "") +
//			            (release.isSupported() ? "" : "\tunsupported"));
			table.row(release.version(),
			          release.isLts() ? release.label() : "-",
			          release.isSupported() ? "supported" : "unsupported");
		}
		table.print(out);
		return 0;
	}

	static int installNode(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		String version = invocation.option("version");
		out.println("Installing Node.js " + version + " ...");

		NodeRuntime runtime = NodeRuntimeManager.install(version,
		                                                 NodeSource.of(invocation.option("source")),
		                                                 out::println);
		out.println("Installed Node.js " + runtime.version() + " into " + runtime.directory());
		return 0;
	}

	static int uninstallNode(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		String version = invocation.option("version");
		NodeRuntimeManager.uninstall(version);
		out.println("Removed Node.js " + version);
		return 0;
	}
}
