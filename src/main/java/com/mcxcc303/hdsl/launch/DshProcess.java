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
package com.mcxcc303.hdsl.launch;

import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.account.DshInjectedSettings;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.LogLine;
import org.jackhuang.hmcl.util.CircularArrayList;
import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.util.Log4jLevel;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Manages the lifecycle, output, and readiness of a DeepSeek Harness child process.
 * <p>
 * The process remains attached to the launcher so it can be stopped and its
 * output collected. Web readiness is detected from the upstream `dsh web:` line.
 */
@NotNullByDefault
public final class DshProcess {
	// Maximum time to wait for a graceful shutdown before forcing termination.
	public static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

	// Maximum time to wait after requesting forced termination.
	private static final Duration FORCE_GRACE = Duration.ofSeconds(2);

	// How long the pump threads are given to hand over what the child left in its pipes.
	private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(2);

	// Matches the browser readiness announcement and captures its URL.
	private static final Pattern WEB_READY = Pattern.compile(
			"^dsh web:\\s+(http://127\\.0\\.0\\.1:\\d+/?(?:\\?token=[A-Za-z0-9_-]+)?)" + "\\s*(?:\\(LAN:.*\\))?$");
	private static final int MAX_LOG_LINES = 2000;
	private final DshLauncher.LaunchPlan plan;
	private final ManagedProcess process;
	private final Instant startedAt = Instant.now();
	private final Deque<String> logLines = new ArrayDeque<>();
	private final CircularArrayList<LogLine> windowLogs = new CircularArrayList<>();
	private final List<Consumer<LogLine>> logListeners = new CopyOnWriteArrayList<>();
	private final Object logLock = new Object();
	private final Object stateLock = new Object();
	private volatile State state = State.STARTING;
	private volatile @Nullable URI webUrl;
	private volatile int exitCode = Integer.MIN_VALUE;
	private volatile @Nullable Consumer<String> logSink;
	private volatile @Nullable Consumer<State> stateListener;
	private boolean terminalNotified;
	private volatile boolean stopRequested;

	/**
	 * Starts a child process for a plan.
	 *
	 * @param plan the launch plan
	 * @throws DshException when the process cannot be started
	 */
	private DshProcess(DshLauncher.LaunchPlan plan) throws DshException {
		this.plan = plan;

		ProcessBuilder builder = new ProcessBuilder(plan.command());
		builder.directory(plan.workingDirectory().toFile());
		builder.environment().putAll(plan.environment());

		try {
			this.process = new ManagedProcess(builder);
		} catch (IOException e) {
			throw new DshException("Failed to start DeepSeek Harness: " + e.getMessage(), e);
		}

		appendLog("$ " + plan.commandLine());
		this.process.pumpInputStream(line -> onOutput(line, false), StandardCharsets.UTF_8);
		this.process.pumpErrorStream(line -> onOutput(line, true), StandardCharsets.UTF_8);

		recordRunning(null);
		Lang.thread(this::awaitExit, "DSH process waiter", true);

		// Non-server surfaces are ready as soon as they are running.
		if (!plan.surface().isWeb()) transitionTo(State.READY);

	}

	/**
	 * Parses the URL from a browser readiness announcement.
	 *
	 * @param line one line of child output
	 * @return the URL, or empty if the line is not a valid announcement
	 */
	static Optional<URI> parseWebUrl(String line) {
		Matcher matcher = WEB_READY.matcher(line.trim());
		if (!matcher.matches()) return Optional.empty();

		try {
			return Optional.of(new URI(matcher.group(1)));
		} catch (URISyntaxException e) {
			LOG.warning("DeepSeek Harness printed an unparsable URL: " + matcher.group(1), e);
			return Optional.empty();
		}
	}

	/**
	 * Starts a child process for an instance.
	 *
	 * @param instance the instance to launch
	 * @return the running handle
	 * @throws DshException when the plan cannot be built or the process cannot start
	 */
	public static DshProcess start(DshInstance instance) throws DshException {
		return start(instance, null);
	}

	/**
	 * Starts an instance, with an HDSL account if chosen.
	 *
	 * @param instance the instance
	 * @param account  the account, or `null` for none
	 * @return the started process
	 * @throws DshException when it cannot be started
	 */
	public static DshProcess start(DshInstance instance, @Nullable DshAccount account) throws DshException {
		return startPrepared(instance, DshLauncher.plan(instance, account));
	}

	/**
	 * Starts an instance from a plan that has already been built.
	 *
	 * @param instance the instance
	 * @param plan     the plan
	 * @return the started process
	 * @throws DshException when it cannot be started
	 */
	public static DshProcess startPrepared(DshInstance instance, DshLauncher.LaunchPlan plan) throws DshException {
		DshCustomCommands.run(instance,
		                      SettingsManager.settings().preLaunchCommandFor(instance.id()),
		                      "pre-launch",
		                      null);
		LOG.info("Launching instance " + instance.id() + ": " + plan.commandLine());
		try {
			return new DshProcess(plan);
		} catch (DshException | RuntimeException e) {
			try {
				DshInjectedSettings.settle(plan.instance());
			} catch (DshException settleFailure) {
				LOG.info("Could not put back what the failed launch of " + plan.instance().id() +
				         " left in its settings", settleFailure);
			}
			throw e;
		}
	}

	public DshInstance instance() {
		return plan.instance();
	}

	public DshLauncher.LaunchPlan plan() {
		return plan;
	}

	public State state() {
		return state;
	}

	public long pid() {
		return process.getProcess().pid();
	}

	private void recordRunning(@Nullable URI url) {
		DshRunningRecord.write(plan.instance(), pid(), plan.port(), url, plan.surface(), startedAt.toEpochMilli());
	}

	public Optional<URI> webUrl() {
		return Optional.ofNullable(webUrl);
	}

	public Optional<Integer> exitCode() {
		return exitCode == Integer.MIN_VALUE ? Optional.empty() : Optional.of(exitCode);
	}

	public boolean isRunning() {
		return process.isRunning();
	}

	public Duration uptime() {
		return Duration.between(startedAt, Instant.now());
	}

	/**
	 * Returns the process's output for HMCL's log window, copied under the lock the pumps write with.
	 */
	public List<LogLine> windowLogs() {
		synchronized (logLock) {
			return List.copyOf(windowLogs);
		}
	}

	/**
	 * Subscribes to log output and returns the lines collected before subscription.
	 * <p>
	 * The snapshot and subscription are performed under the same lock, so output
	 * cannot be lost or delivered twice across the handover. The listener runs on
	 * a stream-pump thread and must not block; UI updates must be dispatched separately.
	 *
	 * @param listener receives subsequent log lines
	 * @return the initial snapshot and an unsubscribe action
	 */
	public LogFeed followLogs(Consumer<LogLine> listener) {
		Objects.requireNonNull(listener, "listener");
		synchronized (logLock) {
			List<LogLine> initial = List.copyOf(windowLogs);
			logListeners.add(listener);
			return new LogFeed(initial, () -> logListeners.remove(listener));
		}
	}

	public ManagedProcess managedProcess() {
		return process;
	}

	/**
	 * Installs a sink for subsequent output lines.
	 * <p>
	 * The sink runs on a stream-pump thread. Only one sink is supported.
	 */
	public void setLogSink(@Nullable Consumer<String> sink) {
		this.logSink = sink;
	}

	/**
	 * Installs a listener for lifecycle transitions.
	 * <p>
	 * If the process has already reached a terminal state, the listener is
	 * notified immediately.
	 *
	 * @param listener the listener, or `null` to detach
	 */
	public void setStateListener(@Nullable Consumer<State> listener) {
		State current;
		boolean notifyImmediate = false;
		synchronized (stateLock) {
			if (this.stateListener == listener && (state == State.STOPPED || state == State.FAILED) && terminalNotified) {
				return;
			}
			this.stateListener = listener;
			this.terminalNotified = false;
			current = this.state;
			if (listener != null && (current == State.STOPPED || current == State.FAILED)) {
				this.terminalNotified = true;
				notifyImmediate = true;
			}
		}
		if (notifyImmediate && listener != null) {
			try {
				listener.accept(current);
			} catch (RuntimeException e) {
				LOG.warning("State listener failed", e);
			}
		}
	}

	/**
	 * Stops the process gracefully, forcing termination if it does not exit in time.
	 * <p>
	 * The process's descendants are also terminated. Safe to call repeatedly.
	 */
	public void stop() {
		DshCustomCommands.runQuietly(plan.instance(),
		                             SettingsManager.settings().postExitCommandFor(plan.instance().id()),
		                             "post-exit",
		                             null);
		stopRequested = true;
		if (!isRunning()) return;

		Process raw = process.getProcess();
		List<ProcessHandle> descendants = raw.descendants().toList();
		raw.destroy();
		try {
			if (!raw.waitFor(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
				appendLog("Process did not exit within " + SHUTDOWN_GRACE.toSeconds() + "s, killing it");
				raw.destroyForcibly();
				raw.waitFor(FORCE_GRACE.toSeconds(), TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			raw.destroyForcibly();
		}
		for (ProcessHandle descendant : descendants) {
			if (descendant.isAlive()) descendant.destroy();
		}
	}

	public boolean isStopRequested() {
		return stopRequested;
	}

	private void onOutput(String line, boolean error) {
		record(line, error ? Log4jLevel.ERROR : Log4jLevel.INFO);
		if (plan.surface().isWeb() && webUrl == null) {
			detectReadiness(line);
		}
		Consumer<String> sink = logSink;
		if (sink != null) {
			try {
				sink.accept(line);
			} catch (RuntimeException e) {
				LOG.warning("Log sink failed", e);
			}
		}
	}

	private void detectReadiness(String line) {
		Optional<URI> parsed = parseWebUrl(line);
		if (parsed.isEmpty()) return;

		webUrl = parsed.get();
		recordRunning(webUrl);
		transitionTo(State.READY);
	}

	private void record(String line, Log4jLevel level) {
		LogLine entry = new LogLine(line, level);
		List<Consumer<LogLine>> listeners;
		synchronized (logLock) {
			logLines.addLast(line);
			while (logLines.size() > MAX_LOG_LINES) {
				logLines.removeFirst();
			}
			windowLogs.add(entry);
			while (windowLogs.size() > MAX_LOG_LINES) {
				windowLogs.removeFirst();
			}
			listeners = List.copyOf(logListeners);
		}
		for (Consumer<LogLine> listener : listeners) {
			try {
				listener.accept(entry);
			} catch (RuntimeException e) {
				LOG.warning("Log listener failed", e);
			}
		}
	}

	private void appendLog(String line) {
		record(line, Log4jLevel.INFO);
	}

	private void awaitExit() {
		try {
			int code = process.getProcess().waitFor();
			exitCode = code;

			try {
				process.awaitRelatedThreads(DRAIN_TIMEOUT);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			process.destroyRelatedThreads();

			DshRunningRecord.delete(plan.instance());
			appendLog("Process exited with code " + code);
			transitionTo(state == State.READY ? State.STOPPED : (code == 0 ? State.STOPPED : State.FAILED));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void transitionTo(State next) {
		Consumer<State> listener = null;
		synchronized (stateLock) {
			if (state == State.STOPPED || state == State.FAILED) {
				return;
			}
			if (state == next) {
				return;
			}
			state = next;
			if (next == State.STOPPED || next == State.FAILED) {
				if (!terminalNotified && stateListener != null) {
					terminalNotified = true;
					listener = stateListener;
				}
			} else {
				listener = stateListener;
			}
		}
		if (listener != null) {
			try {
				listener.accept(next);
			} catch (RuntimeException e) {
				LOG.warning("State listener failed", e);
			}
		}
	}

	public Path workingDirectory() {
		return plan.workingDirectory();
	}

	// The lifecycle of a launched process.
	public enum State {
		/**
		 * The process has started but has not reported readiness.
		 */
		STARTING,
		/**
		 * The process has reported readiness.
		 */
		READY,
		/**
		 * The process exited without being classified as a startup failure.
		 */
		STOPPED,
		/**
		 * The process exited before readiness with a non-zero exit code.
		 */
		FAILED
	}

	/**
	 * A follower's starting snapshot and a handle that ends the subscription.
	 *
	 * @param initial     the lines collected before the subscription
	 * @param unsubscribe stops the subscription; safe to call more than once
	 */
	public record LogFeed(List<LogLine> initial, Runnable unsubscribe) {}
}
