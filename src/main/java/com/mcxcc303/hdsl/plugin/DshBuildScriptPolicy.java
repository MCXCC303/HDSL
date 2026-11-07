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

import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Policy for handling build/install scripts during plugin installation.
 */
@NotNullByDefault
public enum DshBuildScriptPolicy {
	/** Run build scripts automatically without prompting. */
	AUTO("auto"),

	/** Prompt the user for approval. */
	MANUAL("manual"),

	/** Never run build scripts. */
	NEVER("never");

	private final String id;

	DshBuildScriptPolicy(String id) {
		this.id = id;
	}

	/**
	 * Parses a stored policy identifier.
	 *
	 * @param value the stored value, or {@code null}
	 * @return the policy, or {@code null} if unknown
	 */
	public static @Nullable DshBuildScriptPolicy of(@Nullable String value) {
		if (value == null) {
			return null;
		}
		for (DshBuildScriptPolicy policy : values()) {
			if (policy.id.equalsIgnoreCase(value.trim())) {
				return policy;
			}
		}
		// Backward compatibility for boolean strings
		if (value.equalsIgnoreCase("true")) {
			return AUTO;
		}
		if (value.equalsIgnoreCase("false")) {
			return NEVER;
		}
		return null;
	}

	/**
	 * Returns the build script policy for the given instance.
	 *
	 * @param instance the instance
	 * @return the instance policy, or the launcher default if unset
	 */
	public static DshBuildScriptPolicy of(DshInstance instance) {
		Boolean legacy = DshInstanceSettings.approveBuildScripts(instance);
		if (legacy != null) {
			return legacy ? AUTO : NEVER;
		}
		String own = DshInstanceSettings.buildScriptPolicy(instance);
		DshBuildScriptPolicy policy = of(own);
		return policy != null ? policy : SettingsManager.settings().buildScriptPolicy();
	}

	/**
	 * Returns the stored identifier.
	 *
	 * @return the id
	 */
	public String id() {
		return id;
	}
}

