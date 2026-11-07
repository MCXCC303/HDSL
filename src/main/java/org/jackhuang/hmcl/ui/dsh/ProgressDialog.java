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
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Executes long-running tasks within a task progress dialog.
 */
@NotNullByDefault
public final class ProgressDialog {
	private ProgressDialog() {
	}

	/**
	 * Runs a task with a progress dialog.
	 *
	 * @param title  title of the progress dialog
	 * @param work   the task to execute
	 * @param onDone callback run on the JavaFX thread when the task ends, or {@code null}
	 */
	public static void run(String title, Work work, @Nullable Runnable onDone) {
		run(title, work, onDone, null);
	}

	/**
	 * Runs a task with a progress dialog, with custom failure handling.
	 *
	 * @param title     title of the progress dialog
	 * @param work      the task to execute
	 * @param onDone    callback run on the JavaFX thread when the task ends, or {@code null}
	 * @param onFailure predicate to handle failures, or {@code null} to show default error dialog
	 */
	public static void run(String title,
	                       Work work,
	                       @Nullable Runnable onDone,
	                       @Nullable Predicate<Exception> onFailure) {
		DshInstallProgress progress = new DshInstallProgress();

		Task<Void> task = Task.runAsync(title, () -> work.run(progress::accept));
		TaskExecutorDialogPane pane = new TaskExecutorDialogPane(new TaskCancellationAction(it -> it.fireEvent(new DialogCloseEvent())));
		pane.titleProperty().bind(progress.messageProperty());

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
		executor.start();
	}

	/**
	 * Task execution callback.
	 */
	@FunctionalInterface
	public interface Work {

		/**
		 * Executes the task.
		 *
		 * @param report consumer receiving progress text
		 * @throws DshException when execution fails
		 */
		void run(Consumer<String> report) throws DshException;
	}
}
