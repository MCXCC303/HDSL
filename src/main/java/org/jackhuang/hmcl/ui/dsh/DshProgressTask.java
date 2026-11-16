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

import org.jackhuang.hmcl.task.Task;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * A task that shows what an installation says while it runs.
 */
@NotNullByDefault
public abstract class DshProgressTask extends Task<Void> {

	private final DshInstallProgress progress = new DshInstallProgress();

	protected DshProgressTask() {
		getProperties().put("message", progress.messageProperty());
	}

	protected final DshInstallProgress progress() {
		return progress;
	}

	@Override
	public void execute() throws Exception {
		progress.fractionProperty().addListener((observable, old, value) -> {
			double fraction = value.doubleValue();
			if (fraction >= 0) {
				updateProgress(fraction);
			}
		});
		perform();
		updateProgress(1.0);
		setResult(null);
	}

	protected abstract void perform() throws Exception;
}
