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
package com.mcxcc303.hdsl.core;

import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a short-lived external command and captures its output.
 */
@NotNullByDefault
public final class DshCommand {
	private static final Set<Process> RUNNING = ConcurrentHashMap.newKeySet();
	private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(2);

	private DshCommand() {
	}

	/**
	 * Runs a command, waiting for it to finish.
	 *
	 * @param command the program and its arguments
	 * @return the command result
	 * @throws IOException          when the process cannot be started
	 * @throws InterruptedException when the calling thread is interrupted while waiting
	 */
	public static Result run(List<String> command) throws IOException, InterruptedException {
		return run(command, null, Map.of(), null);
	}

	/**
	 * Runs a command in a directory, waiting for it to finish.
	 *
	 * @param command   the program and its arguments
	 * @param directory the working directory, or {@code null} for the current one
	 * @param onLine    a consumer notified of every output line, or {@code null}
	 * @return the command result
	 * @throws IOException          when the process cannot be started
	 * @throws InterruptedException when the calling thread is interrupted while waiting
	 */
	public static Result run(List<String> command, @Nullable Path directory, @Nullable Consumer<String> onLine)
	throws IOException, InterruptedException {
		return run(command, directory, Map.of(), onLine);
	}

	/**
	 * Runs a command with extra environment variables, waiting for it to finish.
	 *
	 * @param command     the program and its arguments
	 * @param directory   the working directory, or {@code null} for the current one
	 * @param environment extra environment variables
	 * @param onLine      a consumer notified of every output line, or {@code null}
	 * @return the command result
	 * @throws IOException          when the process cannot be started
	 * @throws InterruptedException when the calling thread is interrupted while waiting
	 */
	public static Result run(List<String> command,
	                         @Nullable Path directory,
	                         Map<String, String> environment,
	                         @Nullable Consumer<String> onLine) throws IOException, InterruptedException {
		return run(command, null, directory, environment, onLine);
	}

	/**
	 * Runs a command with an optional timeout, waiting for it to finish.
	 *
	 * @param command     the program and its arguments
	 * @param timeout     how long the command is given, or {@code null} to wait indefinitely
	 * @param directory   the working directory, or {@code null} for the current one
	 * @param environment extra environment variables
	 * @param onLine      a consumer notified of every output line, or {@code null}
	 * @return the command result
	 * @throws IOException          when the process cannot be started
	 * @throws InterruptedException when the calling thread is interrupted while waiting
	 */
	public static Result run(List<String> command,
	                         @Nullable Duration timeout,
	                         @Nullable Path directory,
	                         Map<String, String> environment,
	                         @Nullable Consumer<String> onLine) throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(command);
		// Pass launcher proxy configuration to child processes.
		builder.environment().putAll(DshNetworkSettings.environment());
		if (directory != null) {
			builder.directory(directory.toFile());
		}
		builder.environment().putAll(environment);
		builder.redirectErrorStream(true);

		ManagedProcess process = new ManagedProcess(builder);
		// Track running process so it can be stopped if cancelled.
		Process running = process.getProcess();
		RUNNING.add(running);
		List<String> output = Collections.synchronizedList(new ArrayList<>());

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
			int exitCode;
			if (timeout == null) {
				exitCode = running.waitFor();
			} else if (running.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				exitCode = running.exitValue();
			} else {
				killTree(running);
				process.destroyRelatedThreads();
				return new Result(0, List.of(), true);
			}
			// Drain remaining stream buffer before tearing down reader threads.
			process.awaitRelatedThreads(DRAIN_TIMEOUT);
			process.destroyRelatedThreads();
			return new Result(exitCode, List.copyOf(output));
		} catch (InterruptedException e) {
			// Interrupt received: terminate process tree.
			stopTree(running);
			Thread.currentThread().interrupt();
			throw e;
		} finally {
			RUNNING.remove(running);
		}
	}

	private static void killTree(Process process) {
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
		try {
			process.waitFor(1, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void stopTree(Process process) {
		process.descendants().forEach(ProcessHandle::destroy);
		process.destroy();
		try {
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Stops every running command and waits for processes to exit.
	 */
	public static void stopRunning() {
		for (Process process : RUNNING) {
			stopTree(process);
		}
		RUNNING.clear();
	}

	/**
	 * The result of a finished command.
	 *
	 * @param exitCode the process exit code
	 * @param output   every stdout and stderr line, in arrival order
	 * @param timedOut whether the command was stopped for running out of time
	 */
	public record Result(int exitCode, @Unmodifiable List<String> output, boolean timedOut) {
		public Result(int exitCode, @Unmodifiable List<String> output) {
			this(exitCode, output, false);
		}

		/**
		 * Returns whether the command exited successfully with code 0.
		 */
		public boolean isSuccess() {
			return exitCode == 0 && !timedOut;
		}

		/**
		 * Returns the captured output joined by newlines.
		 */
		public String text() {
			return String.join("\n", output).trim();
		}
	}
}
