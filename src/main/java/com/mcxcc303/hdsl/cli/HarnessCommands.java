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
import com.mcxcc303.hdsl.harness.DshRelease;
import com.mcxcc303.hdsl.harness.HarnessInstaller;
import com.mcxcc303.hdsl.harness.ReleaseQueries;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.InstanceListing;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.PrintStream;
import java.util.List;

@NotNullByDefault
public final class HarnessCommands {

	private HarnessCommands() {}

	/**
	 * The {@code harness list} command.
	 *
	 * @param invocation the parsed invocation
	 * @param out        the stream for normal output
	 * @param err        the stream for error output
	 * @return the process exit code
	 */
	static int list(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		List<DshInstance> instances = InstanceListing.list();
		if (instances.isEmpty()) out.println("(no instances)");

		DshTable table = new DshTable("INSTANCE", "DSH VERSION", "DSH DIRECTORY", "STATE");
		for (DshInstance instance : instances) {
			table.row(instance.id(),
			          instance.version(),
			          instance.dshDirectory().toString(),
			          HarnessInstaller.isInstalled(instance) ? "ok" : "INCOMPLETE");
		}
		table.print(out);
		return 0;
	}

	/**
	 * The {@code harness versions} command.
	 *
	 * @param invocation the parsed invocation
	 * @param out        the stream for normal output
	 * @param err        the stream for error output
	 * @return the process exit code
	 */
	static int versions(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		DshTable table = new DshTable("VERSION", "TYPE", "PUBLISHED", "TAG");
		for (DshRelease release : ReleaseQueries.fetchReleases()) {
			String tag = release.primaryTag();
			table.row(release.version(),
			          release.type().id(),
			          release.publishedAt() == null ? "-" : release.publishedAt(),
			          tag == null ? "-" : tag);
		}
		table.print(out);
		return 0;
	}
}
