/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.construct;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.DialogUtils;
import org.jackhuang.hmcl.ui.FxToolkit;
import org.jackhuang.hmcl.ui.TaskManagerPage;
import org.jackhuang.hmcl.ui.decorator.Decorator;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.TaskManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies background task lifetimes and reuse of progress dialogs.
@NotNullByDefault
final class TaskExecutorDialogPaneTest {

    /// Starts the toolkit for this class, or skips the class where it cannot run.
    @BeforeAll
    static void startToolkit() {
        FxToolkit.requireRunning();
    }

    /// Verifies hidden work continues and its completion removes only its own dialog entry.
    @Test
    void backgroundTaskContinuesUntilCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        TaskExecutor executor = Task.runAsync(() -> {
            executions.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
        }).executor();
        TaskExecutorDialogPane pane = onFxThread(() -> {
            TaskExecutorDialogPane dialog = new TaskExecutorDialogPane(TaskCancellationAction.NORMAL);
            dialog.setExecutor(executor);
            executor.addTaskListener(new TaskListener() {
                /// Signals after the pane's completion callback has been queued.
                @Override
                public void onStop(boolean success, TaskExecutor completed) {
                    stopped.countDown();
                }
            });
            StackPane container = new StackPane();
            new Scene(container);
            DialogUtils.show(container, dialog);
            executor.start();
            return dialog;
        });
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            onFxThread(() -> {
                AtomicInteger backgroundCalls = new AtomicInteger();
                pane.setOnBackground(backgroundCalls::incrementAndGet);
                Node progress = pane.getCenter();
                StackPane container = new StackPane();
                new Scene(container);
                for (int i = 0; i < 3; i++) {
                    pane.runInBackground();
                    pane.runInBackground();
                    assertEquals(TaskManager.Status.RUNNING, TaskManager.getInstance().statusProperty().get());
                    assertFalse(DialogUtils.isShowing(pane));
                    assertFalse(executor.isCancelled());
                    assertTrue(TaskManager.getInstance().getTasks().contains(pane));
                    // Reuse the same pane through the dialog stack used by Controllers.dialog.
                    DialogUtils.show(container, pane);
                    DialogUtils.show(container, pane);
                    // The dialog becomes visible once its transition finishes, which is when the
                    // background state is cleared.
                    pane.onDialogShown();
                    assertEquals(TaskManager.Status.IDLE, TaskManager.getInstance().statusProperty().get());
                    pane.restore();
                    assertSame(progress, pane.getCenter());
                    @Nullable JFXDialogPane stack = (JFXDialogPane) pane.getProperties()
                            .get(DialogUtils.PROPERTY_PARENT_PANE_REF);
                    assertNotNull(stack);
                    assertEquals(1, stack.size());
                }
                assertEquals(1, backgroundCalls.get());
                pane.runInBackground();
                Label other = new Label("Another dialog");
                DialogUtils.show(container, other);
                return true;
            });
        } finally {
            release.countDown();
        }
        assertTrue(stopped.await(10, TimeUnit.SECONDS));
        onFxThread(() -> {
            assertFalse(TaskManager.getInstance().getTasks().contains(pane));
            assertFalse(DialogUtils.isShowing(pane));
            assertEquals(TaskManager.Status.IDLE, TaskManager.getInstance().statusProperty().get());
            pane.restore();
            assertFalse(DialogUtils.isShowing(pane));
            return true;
        });
        assertEquals(1, executions.get());
        assertNull(executor.getException());
    }

    /// Verifies disabled cancellation remains disabled and an enabled action runs only once.
    @Test
    void cancellationHonorsActionAndWaitsForCompletion() throws Exception {
        TaskExecutorDialogPane pane = onFxThread(() -> {
            TaskExecutorDialogPane dialog = new TaskExecutorDialogPane(TaskCancellationAction.NO_CANCEL);
            ControlledExecutor executor = new ControlledExecutor();
            dialog.setExecutor(executor, false);
            executor.start();
            @Nullable ButtonBase cancel = (ButtonBase) dialog.lookup(".dialog-cancel");
            assertNotNull(cancel);
            assertTrue(cancel.isDisabled());
            cancel.fire();
            assertFalse(executor.isCancelled());
            dialog.setCancel(null);
            assertTrue(cancel.isDisabled());
            AtomicInteger cancellations = new AtomicInteger();
            dialog.setCancel(ignored -> cancellations.incrementAndGet());
            assertFalse(cancel.isDisabled());
            cancel.fire();
            cancel.fire();
            assertTrue(executor.isCancelled());
            assertEquals(1, cancellations.get());
            assertTrue(TaskManager.getInstance().getTasks().contains(dialog));
            executor.stop(false);
            return dialog;
        });
        onFxThread(() -> {
            assertFalse(TaskManager.getInstance().getTasks().contains(pane));
            return true;
        });
    }

    /// Verifies both successful and failed completion remove hidden tasks from the list.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completionRemovesHiddenTask(boolean success) throws Exception {
        TaskExecutorDialogPane pane = onFxThread(() -> {
            TaskExecutorDialogPane dialog = new TaskExecutorDialogPane(TaskCancellationAction.NO_CANCEL);
            ControlledExecutor executor = new ControlledExecutor();
            dialog.setExecutor(executor, false);
            executor.start();
            assertTrue(TaskManager.getInstance().getTasks().contains(dialog));
            StackPane container = new StackPane();
            new Scene(container);
            DialogUtils.show(container, dialog);
            dialog.runInBackground();
            assertEquals(TaskManager.Status.RUNNING, TaskManager.getInstance().statusProperty().get());
            assertThrows(UnsupportedOperationException.class,
                    () -> TaskManager.getInstance().getTasks().clear());
            executor.stop(success);
            return dialog;
        });
        onFxThread(() -> {
            assertFalse(TaskManager.getInstance().getTasks().contains(pane));
            assertEquals(TaskManager.Status.IDLE, TaskManager.getInstance().statusProperty().get());
            return true;
        });
    }

    /// Verifies a visible task list switches from progress to a retained, single-use action.
    @Test
    void backgroundCompletionRetainsPendingAction() throws Exception {
        AtomicInteger installations = new AtomicInteger();
        TaskManagerPage page = onFxThread(() -> {
            TaskManagerPage result = new TaskManagerPage();
            new Scene(result, 600, 400);
            return result;
        });
        TaskExecutorDialogPane pane = onFxThread(() -> {
            TaskExecutorDialogPane dialog = new TaskExecutorDialogPane(TaskCancellationAction.NORMAL);
            dialog.setTitle("Example task");
            ControlledExecutor executor = new ControlledExecutor();
            dialog.setExecutor(executor);
            StackPane container = new StackPane();
            new Scene(container);
            DialogUtils.show(container, dialog);
            executor.start();
            dialog.runInBackground();
            page.applyCss();
            page.layout();
            assertFalse(page.lookupAll(".jfx-spinner").isEmpty());
            assertFalse(dialog.runOrDeferCompletionAction("Install", "Ready to install", installations::incrementAndGet));
            executor.stop(true);
            return dialog;
        });
        onFxThread(() -> {
            try {
                assertEquals(0, installations.get());
                assertFalse(DialogUtils.isShowing(pane));
                assertTrue(TaskManager.getInstance().getTasks().contains(pane));
                assertEquals(TaskManager.Status.PENDING, TaskManager.getInstance().statusProperty().get());
                page.applyCss();
                page.layout();
                ButtonBase install = page.lookupAll(".jfx-button").stream()
                        .filter(ButtonBase.class::isInstance)
                        .map(ButtonBase.class::cast)
                        .filter(button -> "Install".equals(button.getText()))
                        .findFirst().orElseThrow();
                assertTrue(page.lookupAll(".label").stream().anyMatch(node -> node instanceof Label label
                        && "Ready to install".equals(label.getText())));
                assertTrue(page.lookupAll(".jfx-spinner").isEmpty());
                install.fire();
                pane.runPendingAction();
                assertEquals(1, installations.get());
                assertNull(pane.pendingActionProperty().get());
                assertFalse(TaskManager.getInstance().getTasks().contains(pane));
            } finally {
                pane.setExecutor(null);
            }
            return true;
        });
    }

    /// Verifies hiding or restoring a queued dialog cannot start it ahead of background work.
    @Test
    void queuedDialogCanBeHiddenAndRestored() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        List<CompletableFuture<Boolean>> completions = new ArrayList<>();
        onFxThread(() -> {
            Field field = Controllers.class.getDeclaredField("decorator");
            field.setAccessible(true);
            @Nullable Object previous = field.get(null);
            Decorator decorator = new Decorator(new StackPane());
            new Scene(decorator.getRoot());
            field.set(null, decorator);
            TaskExecutorDialogPane first = new TaskExecutorDialogPane(TaskCancellationAction.NORMAL);
            TaskExecutorDialogPane second = new TaskExecutorDialogPane(TaskCancellationAction.NORMAL);
            try {
                first.setExecutor(Task.runAsync(() -> assertTrue(release.await(10, TimeUnit.SECONDS))).executor());
                Controllers.dialog(first);
                completions.add(first.startExecutor());
                first.runInBackground();
                second.setExecutor(Task.runAsync(executions::incrementAndGet).executor());
                Controllers.dialog(second);
                completions.add(second.startExecutor());
                assertTrue(second.queuedProperty().get());
                assertEquals(TaskManager.Status.RUNNING, TaskManager.getInstance().statusProperty().get());
                second.runInBackground();
                assertEquals(TaskManager.Status.RUNNING, TaskManager.getInstance().statusProperty().get());
                assertFalse(DialogUtils.isShowing(second));
                second.restore();
                second.restore();
                assertTrue(DialogUtils.isShowing(second));
                assertTrue(second.queuedProperty().get());
                assertEquals(0, executions.get());
                second.runInBackground();
                first.setExecutor(null);
                assertEquals(TaskManager.Status.PENDING, TaskManager.getInstance().statusProperty().get());
            } finally {
                DialogUtils.close(first);
                DialogUtils.close(second);
                first.setExecutor(null);
                second.setExecutor(null);
                field.set(null, previous);
            }
            return true;
        });
        release.countDown();
        for (CompletableFuture<Boolean> completion : completions)
            assertTrue(completion.get(10, TimeUnit.SECONDS));
        assertEquals(1, executions.get());
    }

    /// Executes an operation on the JavaFX thread and waits for its result.
    private static <T> T onFxThread(Callable<T> action) throws Exception {
        FutureTask<T> future = new FutureTask<>(action);
        Platform.runLater(future);
        return future.get(30, TimeUnit.SECONDS);
    }

    /// Emits executor lifecycle events under test control without scheduling work.
    @NotNullByDefault
    private static final class ControlledExecutor extends TaskExecutor {

        /// The number of times execution has been requested.
        private int starts;

        /// Creates an executor with an unused root task.
        private ControlledExecutor() {
            super(Task.runAsync(() -> { }));
        }

        @Override
        public TaskExecutor start() {
            starts++;
            taskListeners.forEach(TaskListener::onStart);
            return this;
        }

        /// Rejects synchronous execution, which this test executor does not support.
        @Override
        public boolean test() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        /// Reports completion with the supplied result.
        private void stop(boolean success) {
            taskListeners.forEach(listener -> listener.onStop(success, this));
        }
    }
}
