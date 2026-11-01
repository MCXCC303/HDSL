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

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Turns what a package manager prints into a progress line and fraction.
 */
@NotNullByDefault
public final class DshInstallProgress {
	private static final Pattern PNPM = Pattern.compile(
			"Progress: resolved (\\d+), reused (\\d+), downloaded (\\d+), added (\\d+)");
	private static final Pattern NPM_CACHE = Pattern.compile("npm http cache ");
	private static final Pattern NPM_FETCH = Pattern.compile("npm http fetch ");

	private final SimpleStringProperty message = new SimpleStringProperty(i18n("message.doing"));
	// -1 until the work can say how far along it is.
	private final SimpleDoubleProperty fraction = new SimpleDoubleProperty(-1);

	private int npmSeen;
	private int npmCached;

	public ReadOnlyStringProperty messageProperty() {
		return message;
	}

	public ReadOnlyDoubleProperty fractionProperty() {
		return fraction;
	}

	/**
	 * Reads one line of a package manager's output, from the thread running it
	 * The line reaches the dialog through the interface thread.
	 */
	public void accept(String line) {
		Matcher pnpm = PNPM.matcher(line);
		if (pnpm.find()) {
			int resolved = Integer.parseInt(pnpm.group(1));
			int reused = Integer.parseInt(pnpm.group(2));
			int downloaded = Integer.parseInt(pnpm.group(3));
			int added = Integer.parseInt(pnpm.group(4));

			show(i18n("dsh.install.progress.pnpm", added, resolved, downloaded, reused));
			if (resolved > 0) fraction.set(Math.min(1.0, (double) added / resolved));
			return;
		}

		if (NPM_FETCH.matcher(line).find()) {
			npmSeen++;
		} else if (NPM_CACHE.matcher(line).find()) {
			npmSeen++;
			npmCached++;
		} else return;

		// npm never states a total, so there is no fraction to give.
		show(i18n("dsh.install.progress.npm", npmSeen, npmSeen - npmCached, npmCached));
	}

	private void show(String line) {
		Platform.runLater(() -> message.set(line));
	}
}
