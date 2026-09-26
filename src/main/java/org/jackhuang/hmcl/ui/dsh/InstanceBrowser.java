/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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

import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshPorts;
import org.jackhuang.hmcl.dsh.DshProcess;
import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Opens an instance's browser interface.
///
/// The address is the instance's own, not something to be looked up: its port is settled when it is
/// created and never changes, so "open the interface" means the same address whether the instance is
/// running or not. A running one is asked for the address it actually bound, which carries the trust
/// token — but only while that is the instance's own port: an instance the harness moved elsewhere,
/// which a patch layer restating the `webserver` row can do, is still opened at the port it is
/// recorded at. A stopped one is opened at the plain address, where the browser says the site cannot
/// be reached, which is the truthful answer to asking for a page nobody is serving yet.
///
/// **Offered wherever an instance is, running or not**, from the instance list's menu and from the
/// instance page's: an address that is not answering is a different answer from a menu entry that is
/// not there, and the second one leaves the person wondering whether they misremembered.
@NotNullByDefault
public final class InstanceBrowser {
    private InstanceBrowser() {
    }

    /// Opens an instance's interface in the browser.
    ///
    /// @param instance the instance
    public static void open(DshInstance instance) {
        if (instance.portOrDefault() <= 0) {
            // A surface that serves nothing has no address to open, and inventing one would open a
            // page that is somebody else's.
            Controllers.dialog(i18n("dsh.instance.port.auto.none"), i18n("message.error"),
                    MessageDialogPane.MessageType.ERROR);
            return;
        }
        java.util.Optional<DshProcess> running = DshProcessManager.find(instance.id());
        FXUtils.openLink(DshPorts.openAddress(instance,
                running.flatMap(DshProcess::webUrl).orElse(null)).toString());
    }
}
