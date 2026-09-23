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

import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.ui.LogLine;
import org.jackhuang.hmcl.util.CircularArrayList;
import org.jackhuang.hmcl.util.Log4jLevel;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// A running DeepSeek Harness child process.
///
/// The launcher keeps the process in the foreground of its own bookkeeping
/// rather than detaching it: the browser surface is a long-lived server that
/// the user starts and stops from the launcher, and the launcher must never
/// leave an orphan behind when it exits.
///
/// Readiness is taken from the line upstream prints once the configuration tree
/// has settled and the required entries have been audited:
/// `dsh web: http://127.0.0.1:<port>/?token=<token>`. That line is the only
/// stdout contract the launcher relies on.
///
/// The token arrived with the browser-trust fence; the earliest releases print the same line
/// without it (`dsh web: http://127.0.0.1:<port>`). Both are the same announcement, so both are
/// accepted — a launcher that insisted on the token showed those versions as starting for ever,
/// because the line it was waiting for is one they never learned to print.
@NotNullByDefault
public final class DshProcess {
    /// How long the child is given to drain before it is killed.
    ///
    /// Matches upstream's own bounded shutdown, so a graceful stop and a
    /// signal-initiated stop take the same time.
    public static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    /// Additional grace after a forced kill before the handle gives up waiting.
    private static final Duration FORCE_GRACE = Duration.ofSeconds(2);

    /// The readiness line for the browser surface, including its optional LAN suffix.
    ///
    /// Both the token query and the trailing slash are optional. Releases before the browser-trust
    /// fence print the address on its own and without a slash — `dsh web: http://127.0.0.1:4061` —
    /// and that address is just as good a sign that the server is up as the later spelling is.
    private static final Pattern WEB_READY = Pattern.compile(
            "^dsh web:\\s+(http://127\\.0\\.0\\.1:\\d+)/?(?:\\?token=[A-Za-z0-9_-]+)?\\s*(?:\\(LAN:.*\\))?$");

    /// How many log lines are retained for the log window.
    private static final int MAX_LOG_LINES = 2000;

    /// The lifecycle of a launched process.
    public enum State {
        /// Started, but the readiness line has not arrived yet.
        STARTING,
        /// Reported ready. Only the browser surface reaches this state.
        READY,
        /// Exited on its own.
        STOPPED,
        /// Could not be started, or exited before reporting ready.
        FAILED
    }

    private final DshLauncher.LaunchPlan plan;
    private final ManagedProcess process;
    private final Instant startedAt = Instant.now();

    private final Deque<String> logLines = new ArrayDeque<>();

    /// The same output in the form HMCL's log window consumes.
    ///
    /// Kept alongside the string deque rather than converted on demand: the
    /// window is opened while the process is running and appends as lines
    /// arrive, so it needs the list to grow under it.
    private final CircularArrayList<LogLine> windowLogs = new CircularArrayList<>();
    private final Object logLock = new Object();

    private volatile State state = State.STARTING;
    private volatile @Nullable URI webUrl;
    private volatile int exitCode = Integer.MIN_VALUE;
    private volatile @Nullable Consumer<String> logSink;
    private volatile @Nullable Consumer<State> stateListener;

    /// Whether the launcher asked this process to stop.
    ///
    /// An instance that is stopped while it is still coming up exits before it
    /// has reported ready, which is indistinguishable from a failure by the exit
    /// alone. This is what tells the two apart, so stopping a launch in progress
    /// is not reported as one that failed.
    private volatile boolean stopRequested;

    /// Starts a child process for a plan.
    ///
    /// @param plan the launch plan
    /// @throws DshException when the process cannot be started
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
        this.process.pumpInputStream(line -> onOutput(line, false));
        this.process.pumpErrorStream(line -> onOutput(line, true));

        Lang.thread(this::awaitExit, "DSH process waiter", true);

        if (!plan.surface().isWeb()) {
            // Non-server surfaces are ready as soon as they are running.
            transitionTo(State.READY);
        }
    }

    /// Starts a child process for an instance.
    ///
    /// @param instance the instance to launch
    /// @return the running handle
    /// @throws DshException when the plan cannot be built or the process cannot start
    public static DshProcess start(DshInstance instance) throws DshException {
        return start(instance, null);
    }

    /// Starts an instance, handing the harness an account if one was chosen.
    ///
    /// @param instance the instance
    /// @param account  the account, or `null` for none
    /// @return the started process
    /// @throws DshException when it cannot be started
    public static DshProcess start(DshInstance instance, @Nullable DshAccount account)
            throws DshException {
        return startPrepared(instance, DshLauncher.plan(instance, account));
    }

    /// Starts an instance from a plan that has already been built.
    ///
    /// @param instance the instance
    /// @param plan     the plan
    /// @return the started process
    /// @throws DshException when it cannot be started
    public static DshProcess startPrepared(DshInstance instance, DshLauncher.LaunchPlan plan)
            throws DshException {
        // An instance may answer for itself about debug lines, so the switch is applied here
        // rather than once at startup: what is written while this instance runs is what its
        // own answer says.
        org.jackhuang.hmcl.util.logging.Logger.setDebugEnabled(
                org.jackhuang.hmcl.setting.SettingsManager.settings().debugLogFor(instance.id()));
        // Whatever was asked to happen before this instance starts happens first, and a
        // failure stops the launch: it was asked for, and starting anyway would ignore it.
        DshCustomCommands.run(instance,
                org.jackhuang.hmcl.setting.SettingsManager.settings().preLaunchCommandFor(instance.id()),
                "pre-launch", null);
        LOG.info("Launching instance " + instance.id() + ": " + plan.commandLine());
        try {
            return new DshProcess(plan);
        } catch (DshException | RuntimeException e) {
            // The plan wrote an account overlay, and the only thing that removes one is the state
            // listener of a process that got as far as being registered. A launch that fails here —
            // the process cannot be started, the workspace is gone — would leave the file behind for
            // good: a few hundred bytes in the launcher's directory that nothing will ever look at
            // again. Removing it is the same cleanup the listener would have done.
            DshAccountOverlay.remove(plan.accountOverlay());
            throw e;
        }
    }

    /// Returns the instance this process runs.
    ///
    /// @return the instance
    public DshInstance instance() {
        return plan.instance();
    }

    /// Returns the plan this process was started from.
    ///
    /// @return the launch plan
    public DshLauncher.LaunchPlan plan() {
        return plan;
    }

    /// Returns the current lifecycle state.
    ///
    /// @return the state
    public State state() {
        return state;
    }

    /// Returns the readiness URL, once the browser surface has reported one.
    ///
    /// @return the URL, or empty while starting or for non-server surfaces
    public Optional<URI> webUrl() {
        return Optional.ofNullable(webUrl);
    }

    /// Returns the process exit code.
    ///
    /// @return the exit code, or empty while the process is still running
    public Optional<Integer> exitCode() {
        return exitCode == Integer.MIN_VALUE ? Optional.empty() : Optional.of(exitCode);
    }

    /// Returns whether the child is still running.
    ///
    /// @return whether the process is alive
    public boolean isRunning() {
        return process.isRunning();
    }

    /// Returns how long the process has been running.
    ///
    /// @return the elapsed time since start
    public Duration uptime() {
        return Duration.between(startedAt, Instant.now());
    }

    /// Returns a snapshot of the retained log lines.
    ///
    /// @return the log lines, oldest first
    /// Returns the process's output in HMCL's log-window form.
    ///
    /// @return the lines, live: the window appends to what it is given
    public CircularArrayList<LogLine> windowLogs() {
        return windowLogs;
    }

    /// Returns the underlying managed process.
    ///
    /// Exposed so HMCL's log window can be reused as-is: it needs only a process
    /// it can ask whether it is running and tell to stop.
    ///
    /// @return the managed process
    public ManagedProcess managedProcess() {
        return process;
    }

    public List<String> logLines() {
        synchronized (logLock) {
            return List.copyOf(logLines);
        }
    }

    /// Installs a sink that receives every subsequent log line.
    ///
    /// The sink is called from the stream-pump threads, so a user interface
    /// must re-post onto its own thread. Only one sink is supported; the log
    /// window owns it.
    ///
    /// @param sink the sink, or `null` to detach
    public void setLogSink(@Nullable Consumer<String> sink) {
        this.logSink = sink;
    }

    /// Installs a listener notified on every state transition.
    ///
    /// @param listener the listener, or `null` to detach
    public void setStateListener(@Nullable Consumer<State> listener) {
        this.stateListener = listener;
    }

    /// Stops the process, allowing upstream's bounded drain before killing it.
    ///
    /// Safe to call from any thread and repeatedly.
    public void stop() {
        // The command that follows an instance runs once it has gone, whether it was
        // stopped or had already ended: what it is for is knowing that a session is over.
        DshCustomCommands.runQuietly(plan.instance(),
                org.jackhuang.hmcl.setting.SettingsManager.settings().postExitCommandFor(plan.instance().id()),
                "post-exit", null);
        stopRequested = true;
        if (!isRunning()) {
            return;
        }
        Process raw = process.getProcess();
        // DeepSeek Harness spawns helper processes of its own; collect them
        // before the parent goes away, then take the whole tree down.
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
            if (descendant.isAlive()) {
                descendant.destroy();
            }
        }
    }

    /// Reports whether the launcher asked this process to stop.
    ///
    /// @return whether a stop was requested
    public boolean isStopRequested() {
        return stopRequested;
    }

    /// Records a log line, notifying the sink.
    ///
    /// @param line  the text
    /// @param error whether it arrived on stderr
    private void onOutput(String line, boolean error) {
        appendLog(line);
        synchronized (logLock) {
            windowLogs.add(new LogLine(line, error ? Log4jLevel.ERROR : Log4jLevel.INFO));
        }
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

    /// Parses the upstream readiness line for the browser surface.
    ///
    /// @param line one line of child output
    private void detectReadiness(String line) {
        Matcher matcher = WEB_READY.matcher(line.trim());
        if (!matcher.matches()) {
            return;
        }
        try {
            webUrl = new URI(matcher.group(1));
            transitionTo(State.READY);
        } catch (URISyntaxException e) {
            LOG.warning("DeepSeek Harness printed an unparsable URL: " + matcher.group(1), e);
        }
    }

    /// Appends a line to the bounded log buffer.
    ///
    /// @param line the text to record
    private void appendLog(String line) {
        synchronized (logLock) {
            logLines.addLast(line);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
        }
    }

    /// Waits for the child to exit and records the outcome.
    private void awaitExit() {
        try {
            int code = process.getProcess().waitFor();
            exitCode = code;
            process.destroyRelatedThreads();
            appendLog("Process exited with code " + code);
            transitionTo(state == State.READY ? State.STOPPED : (code == 0 ? State.STOPPED : State.FAILED));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Publishes a state transition.
    ///
    /// @param next the new state
    private void transitionTo(State next) {
        state = next;
        Consumer<State> listener = stateListener;
        if (listener != null) {
            try {
                listener.accept(next);
            } catch (RuntimeException e) {
                LOG.warning("State listener failed", e);
            }
        }
    }

    /// Returns the working directory sessions are scoped to.
    ///
    /// @return the working directory
    public Path workingDirectory() {
        return plan.workingDirectory();
    }
}
