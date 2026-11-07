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
package com.mcxcc303.hdsl.plugin;

import org.jetbrains.annotations.NotNullByDefault;

/**
 * Policy controlling whether harness dependencies are pinned to their declared floor versions.
 */
@NotNullByDefault
public enum DshDependencyPolicy {
	/** Do not pin any dependencies; install matching latest versions. */
	LATEST {
		@Override
		public boolean pins(String packageName) {
			return false;
		}
	},

	/** Pin core vendor packages ({@code @deepseek-ai/*}) to their declared floor versions. */
	CORE_PINNED {
		@Override
		public boolean pins(String packageName) {
			return packageName.startsWith("@deepseek-ai/");
		}
	},

	/** Pin all declared dependencies to their declared floor versions. */
	LOCKED {
		@Override
		public boolean pins(String packageName) {
			return true;
		}
	};

	/**
	 * Reports whether this policy pins the given dependency.
	 *
	 * @param packageName the dependency package name
	 * @return whether to pin it
	 */
	public abstract boolean pins(String packageName);
}

