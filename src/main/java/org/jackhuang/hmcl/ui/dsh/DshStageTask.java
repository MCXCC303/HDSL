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
import org.jackhuang.hmcl.util.function.ExceptionalConsumer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;

/**
 * One step of a longer operation, shown as a stage in the progress dialog's stage list.
 */
@NotNullByDefault
public final class DshStageTask extends DshProgressTask {

	private final ExceptionalConsumer<DshInstallProgress, Exception> work;
	private final @Nullable Task<?> before;

	/**
	 * A step that appears in the stage list.
	 *
	 * @param stage  the stage this step marks, as an i18n key
	 * @param name   the name of the row this step draws, or `null` for no row
	 * @param before the step that runs before this one, or `null` for the first
	 */
	public DshStageTask(String stage,
	                    @Nullable String name,
	                    @Nullable Task<?> before,
	                    ExceptionalConsumer<DshInstallProgress, Exception> work) {
		this.work = work;
		this.before = before;
		setStage(stage);
		if (name == null) {
			setSignificance(TaskSignificance.MINOR);
		} else {
			setName(name);
			setSignificance(TaskSignificance.MAJOR);
		}
	}

	@Override
	protected void perform() throws Exception {
		work.accept(progress());
	}

	@Override
	public Collection<? extends Task<?>> getDependents() {
		return before == null ? Collections.emptySet() : Collections.singleton(before);
	}
}
