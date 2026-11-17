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
	private static final Pattern NPM_FETCH = Pattern.compile("npm http fetch ");
	private static final Pattern NPM_CACHE = Pattern.compile("npm http cache ");
	private static final Pattern DOWNLOAD_PERCENT = Pattern.compile("Downloading (\\d+)%");

	private final SimpleStringProperty message = new SimpleStringProperty("");
	// -1 until the work can say how far along it is.
	private final SimpleDoubleProperty fraction = new SimpleDoubleProperty(-1);

	private int resolved;
	private int reused;
	private int downloaded;
	private int added;
	private int npmSeen;
	private int npmCached;
	private Phase phase = Phase.RESOLVING;

	public ReadOnlyStringProperty messageProperty() {
		return message;
	}

	public ReadOnlyDoubleProperty fractionProperty() {
		return fraction;
	}

	/**
	 * Reads one line of a package manager's output, from the thread running it.
	 */
	public void accept(String line) {
		Matcher pnpm = PNPM.matcher(line);
		if (pnpm.find()) {
			int newResolved = Integer.parseInt(pnpm.group(1));
			int newReused = Integer.parseInt(pnpm.group(2));
			int newDownloaded = Integer.parseInt(pnpm.group(3));
			int newAdded = Integer.parseInt(pnpm.group(4));
			if (newAdded > added) {
				phase = Phase.IMPORTING;
			} else if (newDownloaded > downloaded && phase == Phase.RESOLVING) {
				phase = Phase.FETCHING;
			}

			resolved = newResolved;
			reused = newReused;
			downloaded = newDownloaded;
			added = newAdded;

			switch (phase) {
				case IMPORTING -> publish(i18n("dsh.install.progress.pnpm", added, resolved, downloaded, reused),
				                           resolved > 0 ? (double) added / resolved : -1);
				case FETCHING -> publish(i18n("dsh.install.progress.fetching", downloaded + reused, resolved),
				                          resolved > 0 ? (double) (downloaded + reused) / resolved : -1);
				case RESOLVING -> publish(i18n("dsh.install.progress.resolving", resolved), -1);
			}
			return;
		}

		if (NPM_FETCH.matcher(line).find()) {
			npmSeen++;
		} else if (NPM_CACHE.matcher(line).find()) {
			npmSeen++;
			npmCached++;
		} else {
			Matcher percent = DOWNLOAD_PERCENT.matcher(line);
			if (percent.find()) {
				int percentDownloaded = Integer.parseInt(percent.group(1));
				publish(i18n("dsh.node.download.progress", percentDownloaded), percentDownloaded / 100.0);
			}
			return;
		}

		// npm never states a total, so there is no fraction to give.
		publish(i18n("dsh.install.progress.npm", npmSeen, npmSeen - npmCached, npmCached), -1);
	}

	private enum Phase {
		RESOLVING,
		FETCHING,
		IMPORTING
	}

	private void publish(String line, double value) {
		Platform.runLater(() -> {
			message.set(line);
			if (value >= 0) {
				fraction.set(value);
			}
		});
	}
}
