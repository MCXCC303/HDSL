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

import com.mcxcc303.hdsl.launch.DshNetworkSettings;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Runs a short-lived external command and captures its output.
 */
@NotNullByDefault
public final class DshCommand {
	private static final Set<Process> RUNNING = ConcurrentHashMap.newKeySet();

	private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(2);

	private DshCommand() {
	}

	public static Result run(List<String> command) throws IOException, InterruptedException {
		return run(command, null, Map.of(), null);
	}

	public static Result run(List<String> command, @Nullable Path directory, @Nullable Consumer<String> onLine)
	throws IOException, InterruptedException {
		return run(command, directory, Map.of(), onLine);
	}

	public static Result run(List<String> command,
	                         @Nullable Path directory,
	                         Map<String, String> environment,
	                         @Nullable Consumer<String> onLine) throws IOException, InterruptedException {
		return run(command, null, directory, environment, onLine);
	}

	public static Result run(List<String> command,
	                         @Nullable Duration timeout,
	                         @Nullable Path directory,
	                         Map<String, String> environment,
	                         @Nullable Consumer<String> onLine) throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.environment().putAll(DshNetworkSettings.environment());
		if (directory != null) {
			builder.directory(directory.toFile());
		}
		builder.environment().putAll(environment);
		builder.redirectErrorStream(true);

		ManagedProcess process = new ManagedProcess(builder);
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
			process.awaitRelatedThreads(DRAIN_TIMEOUT);
			process.destroyRelatedThreads();
			return new Result(exitCode, List.copyOf(output));
		} catch (InterruptedException e) {
			stopTree(running);
			Thread.currentThread().interrupt();
			throw e;
		} finally {
			RUNNING.remove(running);
		}
	}

	public static @Nullable String versionOf(List<String> command) {
		String failure = null;
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				Result result = run(command);
				if (result.isSuccess() && !result.output().isEmpty()) {
					String first = result.output().get(0).trim();
					return first.startsWith("v") || first.startsWith("V") ? first.substring(1) : first;
				}
				failure = "`" + String.join(" ", command) + "` exited " + result.exitCode();
			} catch (IOException e) {
				failure = "`" + String.join(" ", command) + "` could not be started: " + e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		LOG.warning("Could not read the version of " + String.join(" ", command) + ": " + failure);
		return null;
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

	public static void stopRunning() {
		for (Process process : RUNNING) {
			stopTree(process);
		}
		RUNNING.clear();
	}

	public record Result(int exitCode, @Unmodifiable List<String> output, boolean timedOut) {
		public Result(int exitCode, @Unmodifiable List<String> output) {
			this(exitCode, output, false);
		}

		public boolean isSuccess() {
			return exitCode == 0 && !timedOut;
		}

		public String text() {
			return String.join("\n", output).trim();
		}
	}
}
