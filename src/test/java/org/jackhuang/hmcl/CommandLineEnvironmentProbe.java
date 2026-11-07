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
package org.jackhuang.hmcl;

import com.mcxcc303.hdsl.cli.Cli;
import com.mcxcc303.hdsl.core.DshNetworkSettings;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prints the network environment variables a command would pass to its children.
 * <p>
 * Run in a separate JVM so that the settings class is not initialized by the test
 * suite before the probe starts.
 */
public final class CommandLineEnvironmentProbe {
	private CommandLineEnvironmentProbe() {
	}

	public static void main(String[] args) {
		if (args.length > 0 && "run".equals(args[0])) {
			Cli.Invocation help = Cli.parse(List.of("--help"));
			int code = Main.runCommandLine(help);
			if (code != 0) {
				System.exit(code);
			}
		}
		Map<String, String> environment = new TreeMap<>(DshNetworkSettings.environment());
		for (Map.Entry<String, String> entry : environment.entrySet()) {
			System.out.println(entry.getKey() + "=" + entry.getValue());
		}
	}
}
