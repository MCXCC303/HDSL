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
package org.jackhuang.hmcl.dsh;

import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/// Runs a short-lived external command and captures its output.
///
/// Used for the npm, node and pnpm invocations that back version management.
/// Long-running processes — booting a profile — use [DshProcess] instead.
@NotNullByDefault
public final class DshCommand {
    private DshCommand() {
    }

    /// The result of a finished command.
    ///
    /// @param exitCode the process exit code
    /// @param output   every stdout and stderr line, in arrival order
    public record Result(int exitCode, @org.jetbrains.annotations.Unmodifiable List<String> output) {
        /// Returns whether the command exited successfully.
        ///
        /// @return whether the exit code is zero
        public boolean isSuccess() {
            return exitCode == 0;
        }

        /// Returns the captured output joined by newlines.
        ///
        /// @return the trimmed output text
        public String text() {
            return String.join("\n", output).trim();
        }
    }

    /// Runs a command, waiting for it to finish.
    ///
    /// @param command the program and its arguments
    /// @return the command result
    /// @throws IOException          when the process cannot be started
    /// @throws InterruptedException when the calling thread is interrupted while waiting
    public static Result run(List<String> command) throws IOException, InterruptedException {
        return run(command, null, null);
    }

    /// Runs a command in a directory, waiting for it to finish.
    ///
    /// @param command   the program and its arguments
    /// @param directory the working directory, or `null` for the current one
    /// @param onLine    a consumer notified of every output line, or `null`
    /// @return the command result
    /// @throws IOException          when the process cannot be started
    /// @throws InterruptedException when the calling thread is interrupted while waiting
    public static Result run(List<String> command, @Nullable Path directory, @Nullable Consumer<String> onLine)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        builder.redirectErrorStream(true);

        ManagedProcess process = new ManagedProcess(builder);
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        Charset charset = OperatingSystem.NATIVE_CHARSET;

        process.pumpInputStream(line -> {
            output.add(line);
            if (onLine != null) {
                onLine.accept(line);
            }
        });
        process.pumpErrorStream(line -> {
            output.add(line);
            if (onLine != null) {
                onLine.accept(line);
            }
        });

        int exitCode = process.getProcess().waitFor();
        process.destroyRelatedThreads();

        return new Result(exitCode, List.copyOf(output));
    }
}
