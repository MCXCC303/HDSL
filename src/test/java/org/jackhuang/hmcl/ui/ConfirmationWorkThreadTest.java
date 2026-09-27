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
package org.jackhuang.hmcl.ui;

import javafx.application.Platform;
import javafx.event.EventHandler;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins what thread a question's answer is applied on, and that asking for it does not wait.
///
/// The reported defect: the removal confirmation put its spinner up, the removal finished — the log
/// ends with `Removed instance 0.0.1-rc.5` — and the dialog stayed on the screen with that spinner and
/// no buttons for as long as the launcher was left running. Nothing was logged beside it. The work had
/// been done and the launcher had said so; only the dialog never heard about it.
///
/// The cause is the thread the answer is applied on. The work finishes on a worker thread and the
/// continuation that closes the dialog [Schedulers#defaultScheduler] used to run on is the fork-join
/// pool, not the interface thread, while closing a dialog goes through [DialogUtils#close], which
/// refuses to run anywhere but the interface thread. That refusal is an exception thrown into a stage
/// nobody is watching: no close, no report, no log line. What a person sees is a question waiting
/// forever for an answer that has already arrived.
///
/// The second thing pinned here is the reason the continuation is asynchronous at all: the removal
/// stops a running instance and then removes tens of thousands of files, so a question that waited for
/// its answer on the interface thread would freeze the window it is drawn in. Both halves are measured
/// on the real toolkit, because both are facts about JavaFX rather than about this code.
class ConfirmationWorkThreadTest {

    /// Starts the toolkit for this class, or skips the class where it cannot run.
    @BeforeAll
    static void startToolkit() {
        FxToolkit.requireRunning();
    }

    /// Runs work on the interface thread and waits for it.
    ///
    /// A task that is queued behind work the interface thread is blocked on never runs, so this
    /// returning at all is itself the measurement that the interface thread is free.
    ///
    /// @param work what to run
    private static void onFxThread(Runnable work) throws Exception {
        java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(work, null);
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    /// A question whose closing is watched.
    ///
    /// @param pane     the question
    /// @param closed   whether it has closed
    /// @param closedOnFx whether it closed on the interface thread
    /// @param closedBy the thread it closed on
    private record Answered(MessageDialogPane pane, CountDownLatch closed, AtomicBoolean closedOnFx,
                            AtomicReference<Thread> closedBy) {
    }

    /// Makes a question that records the thread it is closed on.
    ///
    /// @return the question
    private static Answered question() {
        MessageDialogPane pane = new MessageDialogPane.Builder("Remove instance \"x\"?", "Remove instance",
                MessageDialogPane.MessageType.QUESTION)
                .askYesOrNo(dialog -> {
                }, null)
                .build();
        CountDownLatch closed = new CountDownLatch(1);
        AtomicBoolean closedOnFx = new AtomicBoolean();
        AtomicReference<Thread> closedBy = new AtomicReference<>();
        pane.addEventHandler(DialogCloseEvent.CLOSE, (EventHandler<DialogCloseEvent>) event -> {
            closedOnFx.set(Platform.isFxApplicationThread());
            closedBy.set(Thread.currentThread());
            closed.countDown();
        });
        return new Answered(pane, closed, closedOnFx, closedBy);
    }

    @Test
    void aQuestionClosesOnTheInterfaceThreadWhenItsWorkFinishedOnAnotherThread() throws Exception {
        Answered q = question();

        // The press, on the interface thread, exactly as the button makes it — and the answer arriving
        // on a worker thread, exactly as a removal that takes forty seconds does.
        Platform.runLater(() -> Controllers.answerQuestion(q.pane(), "Removing instance x…", () -> {
            CompletableFuture<Void> work = new CompletableFuture<>();
            ForkJoinPool.commonPool().execute(() -> work.complete(null));
            return work;
        }, null));

        assertTrue(q.closed().await(30, TimeUnit.SECONDS),
                "the question never closed, though the work it was waiting for finished");
        assertTrue(q.closedOnFx().get(),
                () -> "the question was closed on " + q.closedBy().get()
                        + " instead of the interface thread, which is a close that does not happen");
    }

    @Test
    void aQuestionWhoseWorkIsStillRunningDoesNotBlockTheInterfaceThread() throws Exception {
        Answered q = question();
        CompletableFuture<Void> work = new CompletableFuture<>();

        Platform.runLater(() -> Controllers.answerQuestion(q.pane(), "Removing instance x…", () -> work, null));

        // Queued behind the press, so this runs only once the press has returned. A question that
        // waited for its answer here would keep this task — and every repaint behind it — waiting.
        onFxThread(() -> {
        });

        assertTrue(q.closed().getCount() == 1, "the question is still open while its work runs");
        assertTrue(q.pane().isWorking(), "and says what it is waiting for while it waits");

        work.complete(null);
        assertTrue(q.closed().await(30, TimeUnit.SECONDS), "and closes once the work has finished");
    }

    @Test
    void closingADialogFromAWorkerThreadIsRefused() throws Exception {
        // What the continuation met, measured rather than read off the source: the first thing closing a
        // dialog does is check which thread it is on, and it throws instead of closing. In the removal
        // that exception went into the stage the finished work returns, whose result nobody reads —
        // which is why the log of that removal ends at `Removed instance 0.0.1-rc.5` with no line about
        // the dialog either way.
        MessageDialogPane pane = question().pane();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                DialogUtils.close(pane);
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "not-the-interface-thread");
        worker.start();
        worker.join(30_000);

        assertInstanceOf(IllegalStateException.class, thrown.get(),
                "closing a dialog off the interface thread is refused, not carried out elsewhere");
        assertEquals("Not on FX application thread; currentThread = not-the-interface-thread",
                thrown.get().getMessage());
    }
}
