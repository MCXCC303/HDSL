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

/**
 * A tool the launcher needs and the machine does not have.
 */
@NotNullByDefault
public final class DshMissingToolException extends DshException {
	/// What is missing.
	public enum Tool {
		/// The Node.js the launcher works with.
		NODE,
		/// The pnpm the launcher works with.
		PNPM
	}

	private final Tool tool;

	/**
	 * Creates the failure.
	 *
	 * @param tool    what is missing
	 * @param message what to say about it
	 */
	public DshMissingToolException(Tool tool, String message) {
		super(message);
		this.tool = tool;
	}

	/**
	 * Returns what is missing.
	 *
	 * @return the tool
	 */
	public Tool tool() {
		return tool;
	}
}
