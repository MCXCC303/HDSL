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
import org.jackhuang.hmcl.setting.LauncherLock;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Arrays;
import java.util.List;

/**
 * Application entry point for HDSL.
 */
@NotNullByDefault
public final class Main {
	private Main() {
	}

	/**
	 * Application entry point.
	 *
	 * @param args command-line arguments
	 */
	public static void main(String[] args) {
		List<String> arguments = Arrays.asList(args);

		// Install environment defaults before resolving CLI or instances.
		org.jackhuang.hmcl.setting.EnvironmentDefaults.install();

		Cli.Invocation invocation = Cli.parse(arguments);
		if (invocation != null) {
			System.exit(Cli.run(invocation, System.out, System.err));
		}

		if (arguments.contains("--version")) {
			System.out.println(Metadata.FULL_TITLE);
			return;
		}

		// Enforce single-instance lock to prevent concurrent settings writes.
		if (!LauncherLock.shared().acquire()) {
			return;
		}

		Launcher.main(args);
	}
}
