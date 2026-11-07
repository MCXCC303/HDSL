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

/**
 * Utility for initializing and verifying the JavaFX runtime once per test suite.
 */
public final class FxToolkit {

    private static final Duration STARTUP_BOUND = Duration.ofSeconds(Long.getLong("hdsl.test.fx.startup.seconds", 60L));
    private static final Duration WORK_BOUND = Duration.ofSeconds(Long.getLong("hdsl.test.fx.work.seconds", 15L));

    private static boolean attempted;
    private static boolean usable;
    private static String refusal;

    private FxToolkit() {
    }

    /**
     * Ensures the JavaFX toolkit is running, or aborts the calling test if unavailable.
     */
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
                started.countDown();
            } catch (Throwable failure) {
                thrown[0] = failure;
                started.countDown();
            }
        }, "javafx-toolkit-starter");
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
        CountDownLatch ran = new CountDownLatch(1);
        Platform.runLater(ran::countDown);
        if (!await(ran, WORK_BOUND)) {
            refuse("the JavaFX toolkit started but ran no work within " + WORK_BOUND.toSeconds() + "s");
            return;
        }
        usable = true;
    }

    private static void refuse(String why) {
        System.err.print("[" + FxToolkit.class.getSimpleName() + "] " + why + " on " + describe()
                + "; the tests that need the toolkit are skipped here." + System.lineSeparator() + stacks());
        refusal = why + " on " + describe() + " (the thread dump above names the frame it is stuck in)";
    }

    private static String describe() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch") + ", java " + System.getProperty("java.version")
                + ", java.awt.headless=" + System.getProperty("java.awt.headless");
    }

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

    static boolean await(CountDownLatch latch, Duration bound) {
        try {
            return latch.await(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
