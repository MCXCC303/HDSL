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
package org.jackhuang.hmcl.setting;

import com.mcxcc303.hdsl.launch.DshEnvironment;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;

/**
 * Bridges launcher environment settings to the instance layer.
 */
@NotNullByDefault
public final class EnvironmentDefaults {
	private EnvironmentDefaults() {
	}

	/**
	 * Installs the launcher's environment defaults into the instance layer.
	 */
	public static void install() {
		DshEnvironment.install(
				() -> settings().defaultNodeRuntimeProperty().get(),
				() -> settings().defaultHomeModeProperty().get());
	}
}
