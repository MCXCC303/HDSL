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

/**
 * Tests for {@link Logger} file output and debug logging toggle behavior.
 */
class LoggerTest {
    @TempDir
    Path folder;

    private boolean debugWasEnabled;

    @BeforeEach
    void rememberTheDebugFlag() {
        debugWasEnabled = Logger.isDebugEnabled();
    }

    @AfterEach
    void restoreTheDebugFlag() {
        Logger.setDebugEnabled(debugWasEnabled);
    }

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

        // When debug is disabled, info lines are logged while debug lines are omitted.
        Logger.setDebugEnabled(false);
        logger.debug(quiet + "-debug");
        logger.info(quiet + "-info");
        Path file = logFile();
        String text = await(file, quiet + "-info");
        assertTrue(text.contains(quiet + "-info"),
                "an ordinary line never reached the log file, so nothing is being written:\n" + text);
        assertFalse(text.lines().anyMatch(line -> line.contains(quiet + "-debug")),
                "a debug line was written with the switch off:\n" + text);

        // When debug is enabled, debug lines are logged.
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
