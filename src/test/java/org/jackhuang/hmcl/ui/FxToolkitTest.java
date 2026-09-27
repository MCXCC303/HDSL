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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The two things [FxToolkit] decides, pinned where they can be measured.
///
/// The wait itself is the part that has to be right: on a machine whose toolkit never comes up it is
/// what stands between a build that reports in a minute and one that holds a runner for an hour, and
/// a wait that quietly answers "started" is a wait that does nothing. So it is measured with latches
/// rather than trusted, and the toolkit this machine does have is asked for as the passing case.
class FxToolkitTest {

    @Test
    void theToolkitOfThisMachineIsUsable() {
        FxToolkit.requireRunning();
        // Asked twice: the second answer must be the cached one, because six classes share it and a
        // machine that cannot run the toolkit must not pay the startup bound six times over.
        FxToolkit.requireRunning();
    }

    @Test
    void aLatchThatNeverCountsDownGivesUpAtTheBound() {
        CountDownLatch never = new CountDownLatch(1);
        long before = System.nanoTime();

        assertFalse(FxToolkit.await(never, Duration.ofMillis(300)),
                "a latch nobody counts down must not be reported as counted down");

        long waited = Duration.ofNanos(System.nanoTime() - before).toMillis();
        assertTrue(waited >= 300 && waited < 10_000, "it should give up at the bound, and waited " + waited + "ms");
    }

    @Test
    void aLatchThatCountsDownIsNotWaitedOnForLonger() {
        CountDownLatch counted = new CountDownLatch(1);
        counted.countDown();
        long before = System.nanoTime();

        assertTrue(FxToolkit.await(counted, Duration.ofSeconds(30)), "a counted latch is ready");

        assertTrue(Duration.ofNanos(System.nanoTime() - before).toMillis() < 1_000,
                "a ready latch must come back at once rather than hold the build for its bound");
    }

    @Test
    void theToolkitThatIsRunningRunsWorkOnItsOwnThread() throws Exception {
        FxToolkit.requireRunning();
        CountDownLatch ran = new CountDownLatch(1);
        Thread[] on = new Thread[1];
        Platform.runLater(() -> {
            on[0] = Thread.currentThread();
            ran.countDown();
        });
        assertTrue(ran.await(30, java.util.concurrent.TimeUnit.SECONDS), "the toolkit ran no work");
        assertEquals("JavaFX Application Thread", on[0].getName(),
                "the toolkit's own thread is where a control has to be built");
    }
}
