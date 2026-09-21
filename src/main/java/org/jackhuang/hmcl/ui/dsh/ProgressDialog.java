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

import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstallProgress;
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

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Runs a task that takes time behind the original's progress dialog.
///
/// Installing a plugin, changing a boot library and removing a package all run
/// pnpm, which takes as long as it takes and says why when it fails; packing or
/// unpacking a set of conversations copies hundreds of megabytes. Doing that
/// quietly leaves the page that asked for it unchanged, with no way to tell
/// whether anything happened, so the original's dialog is shown with whatever the
/// work reports as it goes.
@NotNullByDefault
public final class ProgressDialog {
    private ProgressDialog() {
    }

    /// Work that takes time and can fail.
    @FunctionalInterface
    public interface Work {
        /// Runs the installation.
        ///
        /// @param report receives the package manager's output
        /// @throws DshException when the work fails
        void run(Consumer<String> report) throws DshException;
    }

    /// Runs one installation with a progress dialog, and says so when it fails.
    ///
    /// @param title  the line the dialog is titled with
    /// @param work   the work, given a sink for the package manager's output
    /// @param onDone run on the interface thread when the work ends, whether it
    ///               succeeded or not, or `null`
    public static void run(String title, Work work, @Nullable Runnable onDone) {
        DshInstallProgress progress = new DshInstallProgress();

        Task<Void> task = Task.runAsync(title, () -> work.run(progress::accept));
        TaskExecutorDialogPane pane = new TaskExecutorDialogPane(
                new TaskCancellationAction(it -> it.fireEvent(new DialogCloseEvent())));
        pane.titleProperty().bind(progress.messageProperty());

        TaskExecutor executor = task.executor();
        executor.addTaskListener(new TaskListener() {
            @Override
            public void onStop(boolean success, TaskExecutor stopped) {
                runInFX(() -> {
                    if (!success) {
                        Exception failure = stopped.getException();
                        LOG.warning("Failed: " + title, failure);
                        Controllers.dialog(failure == null ? i18n("message.error") : failure.getMessage(),
                                title, MessageType.ERROR);
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
}
