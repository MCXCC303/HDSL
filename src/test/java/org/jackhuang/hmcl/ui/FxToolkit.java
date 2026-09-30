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
package org.jackhuang.hmcl.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/// The JavaFX toolkit, started once for the whole suite and bounded while it starts.
///
/// What asked for this is a runner rather than a test. On the image that `macos-26-intel` resolves to
/// — `macos-26`, macOS 26.6.1, build 25G76 — the toolkit's own startup took eighteen minutes in one
/// build and sixty-four in another before it called back, and the interface thread then ran no work
/// at all: the six classes that need the toolkit failed one after another, each on a thirty-second
/// wait, and the build held the runner for seventy-six minutes to report that one machine cannot
/// draw. The same tests pass on `macos-26-arm64`, on Windows and on Linux, where the same startup was
/// measured at 1.4 seconds.
///
/// The toolkit is therefore started here and not in a class initialiser. In a class initialiser a
/// platform that never comes up blocks the *class* itself, which is the one place no timeout reaches:
/// JUnit's bound covers methods, and a test method behind a blocked initialiser never begins. Started
/// on a daemon thread with a bound, a machine that cannot run the toolkit is a skip that says so —
/// with the stacks of everything alive at the time, which is what names the frame it is stuck in —
/// and the rest of the suite still runs and still reports.
///
/// Neither bound is a guess about speed. Startup was measured at 1.4 s on the arm64 Mac and well
/// under a second on Windows and Linux, and the longest test in the suite takes about twenty seconds.
///
/// Both are read from system properties — `hdsl.test.fx.startup.seconds` and `hdsl.test.fx.work.seconds`
/// — so that a machine which cannot run the toolkit can be simulated: setting the first to `0` skips
/// every class that needs it in seconds, which is how the skip path was measured. On the runner that
/// cannot run it, the same path costs the bound once and nothing for the classes after it.
public final class FxToolkit {

    /// How long the toolkit is given to start before the classes that need it are skipped.
    private static final Duration STARTUP_BOUND = Duration.ofSeconds(Long.getLong("hdsl.test.fx.startup.seconds", 60L));

    /// How long a toolkit that says it has started is given to run one task.
    private static final Duration WORK_BOUND = Duration.ofSeconds(Long.getLong("hdsl.test.fx.work.seconds", 15L));

    private static boolean attempted;
    private static boolean usable;
    private static String refusal;

    private FxToolkit() {
    }

    /// Has the toolkit running, or skips the test that asked where it cannot run.
    ///
    /// A toolkit that says it has started is not the same as one that runs work — the Intel runner's
    /// did exactly that — so both are measured before a test is allowed to wait on it.
    ///
    /// The answer is worked out once per worker: the tests that need the toolkit are six classes in
    /// one JVM, and a machine that cannot run it must not pay the bound six times over.
    public static void requireRunning() {
        synchronized (FxToolkit.class) {
            if (!attempted) {
                attempted = true;
                start();
            }
        }
        if (!usable) {
            Assumptions.abort(refusal);
        }
    }

    private static void start() {
        CountDownLatch started = new CountDownLatch(1);
        Throwable[] thrown = new Throwable[1];
        Thread starter = new Thread(() -> {
            try {
                Platform.startup(started::countDown);
            } catch (IllegalStateException alreadyRunning) {
                // Another class in this worker started it, and the toolkit is up either way.
                started.countDown();
            } catch (Throwable failure) {
                thrown[0] = failure;
                started.countDown();
            }
        }, "javafx-toolkit-starter");
        // A starter still inside the toolkit's native startup must not hold the worker open.
        starter.setDaemon(true);
        starter.start();

        if (!await(started, STARTUP_BOUND)) {
            refuse("the JavaFX toolkit did not start within " + STARTUP_BOUND.toSeconds() + "s");
            return;
        }
        if (thrown[0] != null) {
            refuse("the JavaFX toolkit could not be started: " + thrown[0]);
            return;
        }
        // Started, and now asked to do something, because the two are not the same claim.
        CountDownLatch ran = new CountDownLatch(1);
        Platform.runLater(ran::countDown);
        if (!await(ran, WORK_BOUND)) {
            refuse("the JavaFX toolkit started but ran no work within " + WORK_BOUND.toSeconds() + "s");
            return;
        }
        usable = true;
    }

    /// Records why the toolkit cannot be used, and puts the reason and the stacks into the build log.
    ///
    /// Printed as well as reported: a skipped class is one line in a build log, and the stacks are the
    /// only thing in it that says what the machine was doing instead of starting the toolkit.
    ///
    /// @param why what was being waited for
    private static void refuse(String why) {
        System.err.print("[" + FxToolkit.class.getSimpleName() + "] " + why + " on " + describe()
                + "; the tests that need the toolkit are skipped here." + System.lineSeparator() + stacks());
        refusal = why + " on " + describe() + " (the thread dump above names the frame it is stuck in)";
    }

    /// What this machine is, for the log of a refusal.
    ///
    /// @return the description
    private static String describe() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch") + ", java " + System.getProperty("java.version")
                + ", java.awt.headless=" + System.getProperty("java.awt.headless");
    }

    /// The stack of every live thread, as the log of a refusal.
    ///
    /// @return the dump
    private static String stacks() {
        StringBuilder report = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            report.append("    \"").append(entry.getKey().getName()).append("\" ").append(entry.getKey().getState())
                    .append(entry.getKey().isDaemon() ? " daemon" : "").append(System.lineSeparator());
            for (StackTraceElement frame : entry.getValue()) {
                report.append("        at ").append(frame).append(System.lineSeparator());
            }
        }
        return report.toString();
    }

    /// Waits for a latch and answers whether it counted down in time.
    ///
    /// @param latch the latch to wait for
    /// @param bound how long to wait for it
    /// @return whether it counted down
    static boolean await(CountDownLatch latch, Duration bound) {
        try {
            return latch.await(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
