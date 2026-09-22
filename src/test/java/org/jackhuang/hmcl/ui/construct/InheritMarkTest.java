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
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that an inheritable row's mark reports which of the two is deciding.
///
/// The mark is the only thing that can. While a row follows the launcher it shows the launcher's
/// value, so taking the setting over changes nothing on screen except the mark: a row whose mark
/// stayed the same either way could not say whether pressing the globe again takes the setting over
/// or hands it back. The custom-command rows were in exactly that state.
///
/// These rows are JavaFX controls, so the toolkit has to be running to build one. It is started
/// once here and the work is done on its thread, which is what the controls expect.
class InheritMarkTest {
    static {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        }
        try {
            assertTrue(started.await(30, TimeUnit.SECONDS), "the JavaFX toolkit did not start");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while starting the JavaFX toolkit", e);
        }
    }

    @Test
    void theTextFieldMarkFollowsTheOverrideState() throws Exception {
        onFxThread(() -> {
            LineInheritableTextField row = new LineInheritableTextField("Command before an instance starts");

            row.setOverridden(false);
            Object inherited = markOf(row);
            assertNotNull(inherited, "the row has no mark beside its name");

            row.setOverridden(true);
            Object overridden = markOf(row);
            assertNotSame(inherited, overridden, "the mark does not change when the row takes the setting over");

            row.setOverridden(false);
            assertNotSame(overridden, markOf(row), "the mark does not change back when the row follows again");
        });
    }

    /// Runs a piece of work on the JavaFX thread and waits for it.
    ///
    /// @param work what to run
    /// @throws Exception when the work threw
    private static void onFxThread(Runnable work) throws Exception {
        FutureTask<Void> task = new FutureTask<>(work, null);
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    /// Reads the mark beside a row's name.
    ///
    /// @param row the row
    /// @return the graphic the mark is showing, or `null` when there is no mark
    private static Object markOf(LineComponent row) {
        return row.getTitleTrailing() instanceof com.jfoenix.controls.JFXButton button
                ? button.getGraphic() : null;
    }
}
