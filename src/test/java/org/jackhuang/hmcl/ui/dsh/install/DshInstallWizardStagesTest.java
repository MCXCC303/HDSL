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
package org.jackhuang.hmcl.ui.dsh.install;

import org.jackhuang.hmcl.util.SettingsMap;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@NotNullByDefault
class DshInstallWizardStagesTest {
	private static final String VERSION = "0.2.0";

	@Test
	void namesTheDialogAfterTheInstance() {
		SettingsMap settings = settingsOf("my-instance", Map.of());

		new DshInstallWizardProvider(VERSION).finish(settings);

		assertEquals(I18n.i18n("dsh.install.installation", "my-instance"), settings.get("title"));
	}

	private static SettingsMap settingsOf(String name, Map<String, String> choices) {
		SettingsMap settings = new SettingsMap();
		settings.put(DshInstallWizardProvider.NAME, name);
		settings.put(DshInstallWizardProvider.VERSION, VERSION);
		settings.put(DshInstallWizardProvider.PRESET_CHOICES, choices);
		return settings;
	}
}
