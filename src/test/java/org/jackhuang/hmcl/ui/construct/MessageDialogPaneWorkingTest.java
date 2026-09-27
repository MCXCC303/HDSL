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
package org.jackhuang.hmcl.ui.construct;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import org.jackhuang.hmcl.ui.FxToolkit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins what a question does while its answer is still being worked out.
///
/// The reported defect: the confirmation a removal is asked in closed the moment Yes was pressed, and
/// the removal itself takes seconds — an instance that is running is stopped first and its tree is
/// then removed with retries, because a handle that is closing takes a moment to close. So the row
/// was still on the list, the dialog was gone, and pressing again was the obvious thing to do: what a
/// person saw was a removal that had not happened, and the second press found a half-removed instance.
///
/// What has to hold, then, is that a question being worked out cannot be answered again and does not
/// go away; and that a question whose answer failed to arrive can be answered again, because the only
/// other thing to do with it would be to leave it there forever. Both are things the dialog decides
/// for itself, so both are pinned here rather than in the page that asks.
class MessageDialogPaneWorkingTest {

    /// Starts the toolkit for this class, or skips the class where it cannot run.
    @BeforeAll
    static void startToolkit() {
        FxToolkit.requireRunning();
    }

    /// Runs work on the interface thread and waits for it.
    ///
    /// @param work what to run
    private static void onFxThread(Runnable work) throws Exception {
        FutureTask<Void> task = new FutureTask<>(work, null);
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    /// A yes/no question, with a counter that stands in for the work it asks about.
    ///
    /// @param asks how many times the question has been closed by a button
    private record Question(MessageDialogPane pane, AtomicInteger asks) {
    }

    /// Makes a question and counts how many times it is answered.
    ///
    /// @return the question
    private static Question question() {
        MessageDialogPane pane = new MessageDialogPane.Builder("Remove instance \"x\"?", "Remove instance",
                MessageDialogPane.MessageType.QUESTION).yesOrNo(null, null).build();
        AtomicInteger asked = new AtomicInteger();
        pane.addEventHandler(DialogCloseEvent.CLOSE, (EventHandler<DialogCloseEvent>) event -> asked.incrementAndGet());
        return new Question(pane, asked);
    }

    /// Makes a question whose answer the caller works out, the way [Controllers#confirmAsync] does.
    ///
    /// @param pressed what to run when 是 is pressed
    /// @return the question
    private static Question questionAnsweredByItsCaller(java.util.function.Consumer<MessageDialogPane> pressed) {
        MessageDialogPane pane = new MessageDialogPane.Builder("Remove instance \"x\"?", "Remove instance",
                MessageDialogPane.MessageType.QUESTION)
                .askYesOrNo(pressed::accept, null)
                .build();
        AtomicInteger asked = new AtomicInteger();
        pane.addEventHandler(DialogCloseEvent.CLOSE, (EventHandler<DialogCloseEvent>) event -> asked.incrementAndGet());
        return new Question(pane, asked);
    }

    /// The first button in the action row, which is Yes.
    ///
    /// @param pane the dialog
    /// @return the button
    private static ButtonBase yes(MessageDialogPane pane) {
        return assertInstanceOf(ButtonBase.class, pane.getActions().getChildren().get(0));
    }

    @Test
    void aQuestionThatHasNotBeenPressedClosesWhenItIs() throws Exception {
        Question q = question();

        onFxThread(() -> yes(q.pane()).fire());

        assertEquals(1, q.asks().get(), "an ordinary question closes on its answer");
        assertFalse(q.pane().isWorking());
    }

    @Test
    void aQuestionBeingWorkedOutCannotBeAnsweredAgain() throws Exception {
        Question q = question();
        // The button from before the work started: a second press is not something a person can do —
        // it is not on the screen — but a press that is already on its way would still arrive, and
        // that is what this pins.
        ButtonBase pressed = yes(q.pane());

        onFxThread(() -> {
            q.pane().setWorking(true, "Removing instance x…");
            pressed.fire();
            pressed.fire();
        });

        assertEquals(0, q.asks().get(),
                "a question whose work is running must not close, whatever is pressed");
        assertTrue(q.pane().isWorking());
    }

    @Test
    void aQuestionBeingWorkedOutShowsWhatItIsWaitingForInsteadOfItsButtons() throws Exception {
        Question q = question();

        onFxThread(() -> q.pane().setWorking(true, "Removing instance x…"));

        assertEquals(2, q.pane().getActions().getChildren().size(),
                "the answer is replaced by the reason and a spinner, so there is nothing left to press twice");
        Label waiting = assertInstanceOf(Label.class, q.pane().getActions().getChildren().get(0));
        assertEquals("Removing instance x…", waiting.getText());
        assertInstanceOf(SpinnerPane.class, q.pane().getActions().getChildren().get(1));
    }

    @Test
    void aQuestionWhoseWorkFailedCanBeAnsweredAgain() throws Exception {
        Question q = question();

        onFxThread(() -> {
            q.pane().setWorking(true, "Removing instance x…");
            q.pane().setWorking(false, null);
        });
        assertEquals(2, q.pane().getActions().getChildren().size(),
                "the buttons come back together, in the order they were put in");

        onFxThread(() -> yes(q.pane()).fire());

        assertEquals(1, q.asks().get(), "a failure leaves a question that can still be answered");
    }

    @Test
    void aQuestionAnsweredByItsCallerDoesNotCloseWhenYesIsPressed() throws Exception {
        // The defect as it was reported: pressing 是 on the removal confirmation closed the dialog at
        // once. The work it had just started took twenty seconds, and the dialog was gone in one.
        AtomicInteger answered = new AtomicInteger();
        Question q = questionAnsweredByItsCaller(pane -> {
            answered.incrementAndGet();
            pane.setWorking(true, "Removing instance x…");
        });

        onFxThread(() -> yes(q.pane()).fire());

        assertEquals(1, answered.get(), "the caller is handed the press");
        assertEquals(0, q.asks().get(),
                "a question whose answer the caller works out does not close on the press");
        assertTrue(q.pane().isWorking(), "it waits instead, which is what the spinner is for");
    }

    @Test
    void aQuestionAnsweredByItsCallerStillClosesWhenTheWorkIsDone() throws Exception {
        Question q = questionAnsweredByItsCaller(pane -> pane.setWorking(true, "Removing instance x…"));

        onFxThread(() -> yes(q.pane()).fire());
        // What confirmAsync does once the work has succeeded.
        onFxThread(() -> q.pane().fireEvent(new DialogCloseEvent()));

        assertEquals(1, q.asks().get(), "the caller's own close is what ends it");
    }

    @Test
    void theClosingHandlerAButtonIsGivenRunsBeforeAnythingAddedToItAfterwards() throws Exception {
        // Why a caller cannot hold the dialog open from its own handler, which is why the button
        // says so instead. Measured rather than assumed: JavaFX runs the handlers on one node's
        // bubbling path in the order they were added, so the closing handler, added when the button
        // was, always gets its word in first.
        List<String> order = new java.util.ArrayList<>();
        MessageDialogPane pane = new MessageDialogPane.Builder("q", "q", MessageDialogPane.MessageType.QUESTION)
                .yesOrNo(null, null)
                .build();
        pane.addEventHandler(DialogCloseEvent.CLOSE,
                (EventHandler<DialogCloseEvent>) event -> order.add("closing"));
        ButtonBase button = yes(pane);
        button.addEventHandler(ActionEvent.ACTION, event -> order.add("caller's"));

        onFxThread(button::fire);

        assertEquals(List.of("closing", "caller's"), order,
                "the closing handler runs first, so a caller that sets the waiting state from its own"
                        + " handler sets it after the dialog has already closed");
    }
}
