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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

@NotNullByDefault
final class RemovedCommands {

	private static final Map<String, String> REMOVED = Map.of(
			"--install", "DeepSeek Harness is installed into the instance that runs it: " +
				             "`hdsl instance create --id <id> --dsh-version <version>`",
			"--uninstall", "a harness version is removed with the instance that runs it: " +
				               "`hdsl instance delete --id <id>`");

	private RemovedCommands() {
	}

	/**
	 * Returns the message for a removed command named in the arguments.
	 *
	 * @param args the raw command-line arguments
	 * @return the message, or `null` when the arguments name no removed command
	 */
	static @Nullable String message(List<String> args) {
		for (Map.Entry<String, String> removed : REMOVED.entrySet()) {
			if (args.contains(removed.getKey())) {
				return removed.getKey() + " is gone - " + removed.getValue();
			}
		}
		return null;
	}
}
