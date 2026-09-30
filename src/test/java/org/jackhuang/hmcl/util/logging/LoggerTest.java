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
package org.jackhuang.hmcl.util.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The launcher's own log is written, and the switch that asks for debug lines changes what is
/// in it.
///
/// This pins the defect behind "turning on debug logging changes nothing": the switch was stored,
/// read back, and applied — to a logger nothing had ever started. `Logger` holds its lines in a
/// queue and writes them from a worker thread that `start(Path)` creates, and the launcher never
/// called it, so `Logger.setDebugEnabled(true)` set a flag governing a queue no one drained and a
/// file nothing created. The line the debug switch is for — `DshLauncher`'s
/// "Launching <id> with the command: …" — was written into memory and stayed there for as long as
/// the process lived.
///
/// The assertion is made on the **file**, not on the flag and not on a call count: a file that
/// appears with the line in it is the whole of what the switch promises, and the only evidence
/// that does not depend on how the writing is arranged inside.
///
/// One test method rather than two, although it makes two statements: `Logger` is process-wide
/// and `start` installs a worker thread and a shutdown hook, so starting it twice in one JVM —
/// which two test methods would do — leaves the first thread writing through a writer nothing
/// names any more. The order inside the method is the order of the thing being described: with
/// the switch off a debug line is left out and an info line is not; with it on the debug line
/// arrives.
class LoggerTest {
    /// A folder of this test's own to write the log into.
    @TempDir
    Path folder;

    /// Whether debug lines were on before this test changed it.
    private boolean debugWasEnabled;

    /// Remembers the flag, so the rest of the suite sees what it saw.
    @BeforeEach
    void rememberTheDebugFlag() {
        debugWasEnabled = Logger.isDebugEnabled();
    }

    /// Puts the debug flag back, so a test does not decide for the run.
    @AfterEach
    void restoreTheDebugFlag() {
        Logger.setDebugEnabled(debugWasEnabled);
    }

    /// Waits for text to reach a file, and returns what is there either way.
    ///
    /// The writer runs on its own thread and flushes when it has nothing else to do, so the file
    /// is polled rather than read once: reading once would make this test a race, and a race that
    /// fails intermittently is worse than no test.
    ///
    /// @param file   the file
    /// @param needle the text to wait for
    /// @return the file's contents
    private static String await(Path file, String needle) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            String text = Files.isRegularFile(file) ? Files.readString(file) : "";
            if (text.contains(needle)) {
                return text;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }

    /// Returns the single log file the logger made in the folder.
    ///
    /// @return the file
    private Path logFile() throws Exception {
        try (var entries = Files.list(folder)) {
            return entries
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no log file was written in " + folder));
        }
    }

    @Test
    void theLogIsAFileAndTheDebugSwitchDecidesWhatGoesInIt() throws Exception {
        Logger logger = Logger.LOG;
        logger.setLogRetention(0);
        logger.start(folder);

        String quiet = "quiet-" + System.nanoTime();
        String loud = "loud-" + System.nanoTime();

        // First, with the switch off: an info line is written, and a debug line is not. The
        // marker is unique per run, so nothing from another line can answer for it.
        Logger.setDebugEnabled(false);
        logger.debug(quiet + "-debug");
        logger.info(quiet + "-info");
        Path file = logFile();
        String text = await(file, quiet + "-info");
        assertTrue(text.contains(quiet + "-info"),
                "an ordinary line never reached the log file, so nothing is being written:\n" + text);
        assertFalse(text.lines().anyMatch(line -> line.contains(quiet + "-debug")),
                "a debug line was written with the switch off:\n" + text);

        // Then with it on: the same line arrives. This is the half that was broken — the file
        // existed in neither case, because the logger was never started at all.
        Logger.setDebugEnabled(true);
        logger.debug(loud + "-debug");
        text = await(file, loud + "-debug");
        assertTrue(text.contains(loud + "-debug"),
                "the debug switch was turned on and no debug line reached the log file:\n" + text);
        assertTrue(text.contains(quiet + "-info"),
                "the log file is the same one, so the earlier line is still in it");

        Logger.setDebugEnabled(false);
    }
}
