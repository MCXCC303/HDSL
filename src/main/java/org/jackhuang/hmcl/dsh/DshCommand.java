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
import java.util.Map;
import java.util.function.Consumer;

/// Runs a short-lived external command and captures its output.
///
/// Used for the npm, node and pnpm invocations that back version management.
/// Long-running processes — booting a profile — use [DshProcess] instead.
@NotNullByDefault
public final class DshCommand {
    /// The programs this launcher has started and is waiting on.
    private static final java.util.Set<Process> RUNNING = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        return run(command, null, Map.of(), null);
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
        return run(command, directory, Map.of(), onLine);
    }

    /// Runs a command with extra environment variables, waiting for it to finish.
    ///
    /// The variables are added to the inherited environment. Passing them here
    /// rather than relying on the caller's own `ProcessBuilder` matters for
    /// anything that must be scoped to a directory of ours: `dsh` reads
    /// `DSH_HOME` from its environment, and without it the command would quietly
    /// operate on the user's real `~/.dsh`.
    ///
    /// @param command     the program and its arguments
    /// @param directory   the working directory, or `null` for the current one
    /// @param environment extra environment variables
    /// @param onLine      a consumer notified of every output line, or `null`
    /// @return the command result
    /// @throws IOException          when the process cannot be started
    /// @throws InterruptedException when the calling thread is interrupted while waiting
    public static Result run(List<String> command, @Nullable Path directory,
                             Map<String, String> environment, @Nullable Consumer<String> onLine)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);

        ManagedProcess process = new ManagedProcess(builder);
        // Registered while it runs, so an install the user cancels can stop the
        // program it started. Interrupting the thread waiting on a process does
        // not stop the process: npm keeps writing to a directory nothing is
        // watching any more.
        Process running = process.getProcess();
        RUNNING.add(running);
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

        try {
            int exitCode = running.waitFor();
            process.destroyRelatedThreads();
            return new Result(exitCode, List.copyOf(output));
        } finally {
            RUNNING.remove(running);
        }
    }

    /// Stops every command this launcher is running, and waits for them to stop.
    ///
    /// Called when an install is cancelled. It waits, because the caller deletes
    /// what the command was writing: a program that has been asked to stop is
    /// still writing until it has.
    public static void stopRunning() {
        for (Process process : RUNNING) {
            process.destroy();
        }
        for (Process process : RUNNING) {
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        RUNNING.clear();
    }
}
