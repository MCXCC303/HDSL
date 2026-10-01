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
package org.jackhuang.hmcl.dsh;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins what the launcher does with a runtime that answers `--version` badly.
///
/// The reported failure: a CI run said `Node.js was not found on PATH` about a Node.js that was on
/// `PATH`, a hundred milliseconds after the same test had read that very version, and the run went
/// red on a machine whose toolchain was fine. What is measured here is the reading of the answer,
/// because the message can only ever be as good as what stands behind it.
///
/// Every "executable" below is a script — that is the only way to produce the three answers that
/// matter (fails once and then answers, answers nothing, never succeeds) on every platform.
class DshNodeRuntimeTest {

    @Test
    void aVersionIsReadAndItsLeadingVIsDropped(@TempDir Path directory) throws Exception {
        assertEquals("24.1.0", DshNodeRuntime.versionOf(executable(directory, "answering", List.of("echo v24.1.0"))));
    }

    @Test
    void aVersionWithoutTheVIsReadAsItIs(@TempDir Path directory) throws Exception {
        assertEquals("22.19.0", DshNodeRuntime.versionOf(executable(directory, "plain", List.of("echo 22.19.0"))));
    }

    @Test
    void aProbeThatFailsOnceIsAskedAgain(@TempDir Path directory) throws Exception {
        // Fails the first time it runs and answers afterwards, which is what a machine under load
        // does. Without the second ask this is `null`, and `null` is exactly what the launcher
        // reported as "Node.js was not found on PATH".
        Path seen = directory.resolve("seen");
        List<String> lines = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                ? List.of(
                        "if exist \"" + seen + "\" goto answer",
                        "echo. > \"" + seen + "\"",
                        "exit /b 1",
                        ":answer",
                        "echo v22.19.0")
                : List.of(
                        "if [ -f \"" + seen + "\" ]; then",
                        "  echo v22.19.0",
                        "  exit 0",
                        "fi",
                        ": > \"" + seen + "\"",
                        "exit 1");

        assertEquals("22.19.0", DshNodeRuntime.versionOf(executable(directory, "flaky", lines)),
                "a probe that failed once must be asked again before the runtime is called missing");
        assertTrue(Files.exists(seen), "the second ask is what made it answer");
    }

    @Test
    void aProbeThatNeverAnswersIsNotARuntime(@TempDir Path directory) throws Exception {
        assertNull(DshNodeRuntime.versionOf(executable(directory, "broken", List.of("exit 3"))),
                "a program that cannot say what it is is not a runtime that can be used");
    }

    @Test
    void aProbeThatSaysNothingIsNotARuntime(@TempDir Path directory) throws Exception {
        assertNull(DshNodeRuntime.versionOf(executable(directory, "silent", List.of("exit 0"))),
                "a program that ends cleanly without a version has not answered either");
    }

    /// Writes a runnable program for this platform.
    ///
    /// A batch file on Windows and a shell script elsewhere, because those are the two things that
    /// can be started by name from `ProcessBuilder` on each.
    ///
    /// @param directory where to write it
    /// @param name      the file name, without an extension
    /// @param lines     the body
    /// @return the program
    private static Path executable(Path directory, String name, List<String> lines) throws IOException {
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            Path file = directory.resolve(name + ".cmd");
            Files.write(file, prepend("@echo off", lines));
            return file;
        }
        Path file = directory.resolve(name);
        Files.write(file, prepend("#!/bin/sh", lines));
        file.toFile().setExecutable(true);
        return file;
    }

    private static List<String> prepend(String first, List<String> lines) {
        java.util.ArrayList<String> all = new java.util.ArrayList<>();
        all.add(first);
        all.addAll(lines);
        return all;
    }
}
