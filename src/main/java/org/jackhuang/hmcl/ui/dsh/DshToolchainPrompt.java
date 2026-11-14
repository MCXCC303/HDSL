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

import com.mcxcc303.hdsl.runtime.DshMissingToolException;
import com.mcxcc303.hdsl.runtime.DshMissingToolException.Tool;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

@NotNullByDefault
public final class DshToolchainPrompt {
	private DshToolchainPrompt() {
	}

	/**
	 * Shows the guidance for a missing tool.
	 *
	 * @param failure what went wrong
	 * @return whether the failure was a missing tool, and the guidance was shown
	 */
	public static boolean offer(Exception failure) {
		Throwable cause = failure instanceof CompletionException && failure.getCause() != null
		                   ? failure.getCause()
		                   : failure;
		if (!(cause instanceof DshMissingToolException missing)) {
			return false;
		}
		boolean node = missing.tool() == Tool.NODE;
		String title = node ? i18n("dsh.node.title") : i18n("dsh.pnpm.title");
		String message = node ? i18n("dsh.toolchain.missing.node") : i18n("dsh.toolchain.missing.pnpm");
		Controllers.dialog(new MessageDialogPane.Builder(message, title, MessageDialogPane.MessageType.INFO)
		                                                 .addAction(i18n("dsh.toolchain.missing.action"),
		                                                            () -> open(missing.tool()))
		                                                 .build());
		return true;
	}

	/**
	 * Opens the page that lists the tool.
	 *
	 * @param tool {@code node} or {@code pnpm}
	 */
	private static void open(Tool tool) {
		SettingsPage page = new SettingsPage();
		Controllers.navigate(page);
		page.openTab(tool == Tool.NODE ? "node" : "pnpm");
	}
}
