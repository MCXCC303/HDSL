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

import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.launch.DshProcessManager;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.PrintStream;
import java.util.Optional;

/**
 * Checks whether an instance process is active before executing commands that modify instance state.
 */
@NotNullByDefault
final class RunningInstances {

	private RunningInstances() {
	}

	/**
	 * Checks whether the instance is running, printing an error message if it is.
	 *
	 * @param instance the instance to check
	 * @param err      the stream for error output
	 * @return true if the instance is running
	 */
	static boolean isUp(DshInstance instance, PrintStream err) {
		Optional<DshProcessManager.Running> running = DshProcessManager.findRunning(instance);
		if (running.isEmpty()) {
			return false;
		}
		err.println("error: instance " + instance.id() + " is running (pid " + running.get().pid() +
		            "); stop it first: hdsl instance stop --id " + instance.id());
		return true;
	}
}
