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

import com.mcxcc303.hdsl.core.DshException;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TaskExecutorDialogPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Runs a task that takes time behind the original's progress dialog.
 */
@NotNullByDefault
public final class ProgressDialog {
	private ProgressDialog() {
	}

	/// Runs one installation with a progress dialog, and says so when it fails.
	///
	/// @param onDone run on the interface thread when the work ends, whether it
	///               succeeded or not, or `null`
	public static void run(String title, Work work, @Nullable Runnable onDone) {
		run(title, work, onDone, null);
	}

	/// Runs one installation, letting the caller deal with a particular failure.
	///
	/// @param onDone    run on the interface thread when the work ends, whether it
	///                  succeeded or not, or `null`
	/// @param onFailure asked about the failure; returning true means it was dealt
	///                  with and no error is shown, or `null` to always show it
	public static void run(String title,
	                       Work work,
	                       @Nullable Runnable onDone,
	                       @Nullable Predicate<Exception> onFailure) {
		Task<Void> task = new DshProgressTask() {
			@Override
			protected void perform() throws Exception {
				work.run(progress()::accept);
			}
		};
		task.setName(title);

		TaskExecutorDialogPane pane = new TaskExecutorDialogPane(it -> it.fireEvent(new DialogCloseEvent()));
		pane.setTitle(title);

		TaskExecutor executor = task.executor();
		executor.addTaskListener(new TaskListener() {
			@Override
			public void onStop(boolean success, TaskExecutor stopped) {
				runInFX(() -> {
					if (!success) {
						Exception failure = stopped.getException();
						LOG.warning("Failed: " + title, failure);
						if (onFailure == null || failure == null || !onFailure.test(failure)) {
							Controllers.dialog(failure == null ? i18n("message.error") : failure.getMessage(),
							                   title,
							                   MessageType.ERROR);
						}
					}
					if (onDone != null) {
						onDone.run();
					}
				});
			}
		});

		pane.setExecutor(executor, true);
		Controllers.dialog(pane);
		pane.startExecutor();
	}

	/// Work that takes time and can fail.
	@FunctionalInterface
	public interface Work {
		void run(Consumer<String> report) throws DshException;
	}
}
