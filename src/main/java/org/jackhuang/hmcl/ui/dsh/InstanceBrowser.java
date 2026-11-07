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
package org.jackhuang.hmcl.ui.dsh;

import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshPorts;
import com.mcxcc303.hdsl.launch.DshProcess;
import com.mcxcc303.hdsl.launch.DshProcessManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Optional;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Utility to open an instance's web interface in the default browser.
 */
@NotNullByDefault
public final class InstanceBrowser {
	private InstanceBrowser() {
	}

	/**
	 * Opens the web interface for the specified instance.
	 *
	 * @param instance the instance
	 */
	public static void open(DshInstance instance) {
		if (instance.portOrDefault() <= 0) {
			Controllers.dialog(i18n("dsh.instance.port.auto.none"),
			                   i18n("message.error"),
			                   MessageDialogPane.MessageType.ERROR);
			return;
		}
		Optional<DshProcess> running = DshProcessManager.find(instance.id());
		FXUtils.openLink(DshPorts.openAddress(instance, running.flatMap(DshProcess::webUrl).orElse(null)).toString());
	}
}
