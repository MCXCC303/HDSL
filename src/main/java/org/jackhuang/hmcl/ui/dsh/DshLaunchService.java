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
package org.jackhuang.hmcl.ui.dsh;

import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshPorts;
import org.jackhuang.hmcl.dsh.DshProcess;
import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.jackhuang.hmcl.dsh.DshProcessManager.LaunchState;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.TaskExecutorDialogPane;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.LogWindow;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Starts instances from the user interface and reports the outcome.
///
/// Kept apart from [DshProcessManager] so that the domain layer stays free of
/// dialogs, toasts and browser launching, and so both the main page and the
/// instance page share one launch path.
@NotNullByDefault
public final class DshLaunchService {
    private DshLaunchService() {
    }

    /// How long to wait for the browser surface's readiness line before giving up.
    ///
    /// The first boot of a profile installs its plugin bundles, which is the
    /// slow case; later boots settle in a second or two.
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(120);

    /// How often the readiness state is polled.
    private static final long POLL_MILLIS = 200;

    /// Instances whose launch is in flight, so repeat activations are ignored.
    private static final java.util.Set<String> LAUNCHING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /// Instances whose launch was stopped before it became ready.
    ///
    /// Stopping a launch in progress is a request that was answered, not a
    /// failure: without this the task would still end unsuccessfully and the
    /// user would be told the launch failed at the moment they cancelled it.
    private static final java.util.Set<String> CANCELLED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /// Reports whether an instance is currently being launched.
    ///
    /// @param instanceId the instance id
    /// @return whether a launch is in flight
    public static boolean isLaunching(String instanceId) {
        return LAUNCHING.contains(instanceId);
    }

    /// Reports what an instance is doing.
    ///
    /// The one answer every control that starts or stops an instance is drawn
    /// from: the request is in flight from the moment it is made until the
    /// surface reports ready, and the process answers for itself after that.
    ///
    /// @param instanceId the instance id
    /// @return the state, never `null`
    public static LaunchState state(String instanceId) {
        LaunchState process = DshProcessManager.stateOf(instanceId);
        if (process == LaunchState.STOPPING) {
            return LaunchState.STOPPING;
        }
        if (LAUNCHING.contains(instanceId)) {
            return LaunchState.STARTING;
        }
        return process;
    }

    /// Starts an instance, or stops the one that is up or on its way up.
    ///
    /// The one action every control that offers to start or stop an instance
    /// performs, so that no two of them can disagree: with an instance up, all
    /// of them stop it; while it is coming up, all of them cancel it; while it
    /// is going down, none of them do anything, because there is nothing left to
    /// ask for.
    ///
    /// @param instance the instance
    /// @param onDone   run on the JavaFX thread once the action settles, or `null`
    public static void toggle(DshInstance instance, @Nullable Runnable onDone) {
        toggle(instance, onDone, false);
    }

    /// Starts or stops an instance, optionally showing a launch's output.
    ///
    /// @param instance   the instance
    /// @param onDone     run once the action settles, or `null`
    /// @param showOutput whether a launch should open the process's log window
    public static void toggle(DshInstance instance, @Nullable Runnable onDone, boolean showOutput) {
        switch (state(instance.id())) {
            case STOPPED -> launch(instance, ignored -> {
                if (onDone != null) {
                    onDone.run();
                }
            }, showOutput);
            case STARTING, RUNNING -> stop(instance.id(), onDone);
            case STOPPING -> LOG.info("Instance " + instance.id() + " is already stopping");
        }
    }

    /// Returns the label of a control that starts or stops an instance.
    ///
    /// @param state the instance's state
    /// @return the label
    public static String actionLabel(LaunchState state) {
        return switch (state) {
            case STARTING -> i18n("dsh.launch.launching");
            case RUNNING -> i18n("dsh.stop");
            case STOPPING -> i18n("dsh.stopping");
            case STOPPED -> i18n("dsh.launch");
        };
    }

    /// Returns the hint of a control that starts or stops an instance.
    ///
    /// While an instance is coming up the control cancels the launch, and it
    /// says so: an instance that says Launching and then stops when pressed
    /// would be a control nobody could predict.
    ///
    /// @param state the instance's state
    /// @return the hint
    public static String actionHint(LaunchState state) {
        return switch (state) {
            case STARTING -> i18n("button.cancel");
            case STOPPING -> i18n("dsh.stopping");
            default -> actionLabel(state);
        };
    }

    /// Returns the tag an instance's row wears while it is not simply idle.
    ///
    /// @param state the instance's state
    /// @return the tag, or `null` for a stopped instance
    public static @Nullable String stateTag(LaunchState state) {
        return switch (state) {
            case STARTING -> i18n("dsh.launch.launching");
            case RUNNING -> i18n("dsh.instance.running");
            case STOPPING -> i18n("dsh.stopping");
            case STOPPED -> null;
        };
    }

    /// Launches an instance in the background.
    ///
    /// When the instance becomes ready and the user has asked for it, the
    /// browser is opened at the reported URL. Failures are surfaced through
    /// [Controllers] rather than thrown.
    ///
    /// @param instance the instance to launch
    /// @param onDone   invoked on the JavaFX thread once the launch settles, or `null`
    public static void launch(DshInstance instance, @Nullable Consumer<DshProcess> onDone) {
        launch(instance, onDone, false);
    }

    /// Launches an instance, optionally showing its output.
    ///
    /// @param instance   the instance to launch
    /// @param onDone     run after the launch settles, or `null`
    /// @param showOutput whether to open the process's log window
    public static void launch(DshInstance instance, @Nullable Consumer<DshProcess> onDone, boolean showOutput) {
        // An instance that is still going down cannot come back up yet: it holds
        // its port and its home until it has exited. Saying so is better than
        // starting a second server that would have to take a different port.
        if (DshProcessManager.isStopping(instance.id())) {
            Controllers.dialog(i18n("dsh.launch.stopping", instance.id()),
                    i18n("dsh.stop"), MessageType.WARNING);
            return;
        }
        if (!LAUNCHING.add(instance.id())) {
            return;
        }

        // The progress dialog is HMCL's, and it is meant to be given the work it
        // reports on. The pane is a fixed five hundred by three hundred, most of
        // it a task list: with no executor that area is empty and its progress
        // label reads a meaningless "0 B/s". The launch becomes a task so the
        // pane has something to show, and the pane closes itself when the task
        // stops.
        // Held rather than looked up again when the launch ends: an instance that
        // dies before it is ready is no longer a process the manager reports, so
        // asking it for one is how a failed launch came to say nothing at all —
        // no log window, no dialog, nothing to look at.
        DshProcess[] started = new DshProcess[1];
        Task<DshProcess> launch = Task.supplyAsync(() -> {
            try {
                DshProcess process = DshProcessManager.launch(instance);
                started[0] = process;
                awaitReady(process);
                return process;
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }).setName(i18n("dsh.launch.launching", instance.id()))
                // The pane's list renders stage hints, not tasks, so a task with
                // none leaves the dialog an empty box. One stage is what this
                // launch has: start the child and wait for it to say it is ready.
                .withStagesHints("dsh.launch.stage.starting");

        TaskExecutor executor = launch.executor();
        executor.addTaskListener(new TaskListener() {
            @Override
            public void onStop(boolean success, TaskExecutor stopped) {
                runInFX(() -> settle(instance, success, stopped.getException(), showOutput, onDone, started[0]));
            }
        });

        TaskExecutorDialogPane progress = new TaskExecutorDialogPane(new TaskCancellationAction(it -> {
            // Stopping waits for the child to drain, which is seconds the
            // interface must not spend frozen on the launch dialog's behalf.
            stop(instance.id(), null);
            it.fireEvent(new DialogCloseEvent());
        }));
        progress.titleProperty().set(i18n("dsh.launch.launching", instance.id()));
        progress.setExecutor(executor, true);
        Controllers.dialog(progress);

        executor.start();
    }

    /// Reports how a launch ended and hands the process back.
    ///
    /// The dialog closes itself; what is left is to say why when it failed, and
    /// to open the browser or the log window when it did not.
    ///
    /// @param instance   the instance that was launched
    /// @param success    whether the task completed
    /// @param failure    the task's exception, or `null`
    /// @param showOutput whether to open the process's log window
    /// @param onDone     run with the process, or `null`
    /// @param process    the process the launch started, or `null` when it never
    ///                   got that far
    private static void settle(DshInstance instance, boolean success, @Nullable Exception failure,
                               boolean showOutput, @Nullable Consumer<DshProcess> onDone,
                               @Nullable DshProcess process) {
        LAUNCHING.remove(instance.id());
        boolean cancelled = CANCELLED.remove(instance.id());

        if (!success) {
            if (cancelled) {
                LOG.info("Launch of " + instance.id() + " was stopped before it was ready");
            } else {
                LOG.warning("Failed to launch instance " + instance.id(), failure);
                boolean portTaken = portOf(failure) > 0;
                Controllers.dialog(failureMessage(instance, failure),
                        i18n("dsh.launch.failed"),
                        portTaken ? MessageType.WARNING : MessageType.ERROR);
            }
        } else if (process != null) {
            if (showOutput) {
                openLogWindow(process);
            }
            Optional<java.net.URI> url = process.webUrl();
            if (url.isPresent()) {
                // Said out loud as well as opened: the browser may be another
                // window, another workspace, or turned off in the settings, and
                // the launcher is the only thing that knows the instance is up.
                Controllers.showToast(i18n("dsh.launch.ready", instance.id()));
                applyLauncherVisibility();
                if (settings().openBrowserOnLaunchProperty().get()) {
                    FXUtils.openLink(url.get().toString());
                }
            } else if (!process.isRunning() && !process.isStopRequested()) {
                // Stopped by the user while it was still coming up: that is what
                // was asked for, and not a failure to report.
                Controllers.dialog(
                        i18n("dsh.launch.exited", process.exitCode().orElse(-1)),
                        i18n("dsh.launch.failed"), MessageType.ERROR);
            }
        }

        if (onDone != null && process != null) {
            onDone.accept(process);
        }
    }

    /// Stops a running instance.
    ///
    /// @param instanceId the instance id
    /// @param onDone     invoked on the JavaFX thread once the stop returns, or `null`
    public static void stop(String instanceId, @Nullable Runnable onDone) {
        if (LAUNCHING.contains(instanceId)) {
            CANCELLED.add(instanceId);
        }
        CompletableFuture.runAsync(() -> DshProcessManager.stop(instanceId), Schedulers.io())
                .whenComplete((ignored, throwable) -> runInFX(() -> {
                    if (throwable != null) {
                        LOG.warning("Failed to stop instance " + instanceId, throwable);
                    }
                    // The instance has ended, so whatever the launcher did with itself
                    // when it started is undone: it was only out of the way while there was
                    // something else to look at.
                    restoreLauncher();
                    if (onDone != null) {
                        onDone.run();
                    }
                }));
    }

    /// Moves the launcher out of the way, if it was asked to.
    private static void applyLauncherVisibility() {
        org.jackhuang.hmcl.dsh.DshLauncherVisibility choice =
                settings().launcherVisibilityProperty().get();
        switch (choice == null ? org.jackhuang.hmcl.dsh.DshLauncherVisibility.KEEP : choice) {
            case HIDE -> Controllers.getStage().hide();
            case MINIMIZE -> Controllers.getStage().setIconified(true);
            case KEEP -> {
            }
        }
    }

    /// Brings the launcher back.
    private static void restoreLauncher() {
        javafx.stage.Stage stage = Controllers.getStage();
        if (stage == null) {
            return;
        }
        if (!stage.isShowing()) {
            stage.show();
        }
        if (stage.isIconified()) {
            stage.setIconified(false);
        }
        stage.toFront();
    }

    /// Describes a launch failure in the user's terms.
    ///
    /// A port that is already taken is the one failure the user can do something
    /// about, and the only one whose message is written here rather than by the
    /// domain layer: the port and the instance belong in the sentence, and the
    /// sentence belongs in the language the interface is running in.
    ///
    /// @param instance the instance that could not start
    /// @param failure  the failure the launch ended with, or `null`
    /// @return the message to show
    private static String failureMessage(DshInstance instance, @Nullable Exception failure) {
        int port = portOf(failure);
        if (port > 0) {
            return i18n("dsh.launch.port_taken", port, instance.id());
        }
        return failure == null ? i18n("dsh.launch.failed") : failure.getMessage();
    }

    /// Finds the port a failure is about, if it is about one.
    ///
    /// The exception is unwrapped because a launch fails inside a task, which
    /// wraps what went wrong in one or two layers of its own.
    ///
    /// @param failure the failure, or `null`
    /// @return the port, or `0` when the failure is about something else
    private static int portOf(@Nullable Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DshPorts.PortUnavailableException unavailable) {
                return unavailable.port();
            }
        }
        return 0;
    }

    /// Opens HMCL's log window on a process.
    ///
    /// The window is the original's, reused unchanged: it asks the process
    /// whether it is running and tells it to stop, and both are things the
    /// launcher's process already answers. Its log list is the one the process
    /// appends to, so lines appear as the child writes them.
    ///
    /// @param process the running process
    private static void openLogWindow(DshProcess process) {
        LogWindow window = new LogWindow(process.managedProcess(), process.windowLogs());
        window.show();
    }

    /// Blocks until a process is ready, failed, or the timeout elapses.
    ///
    /// @param process the process to wait for
    private static void awaitReady(DshProcess process) {
        long deadline = System.currentTimeMillis() + READINESS_TIMEOUT.toMillis();
        while (process.state() == DshProcess.State.STARTING
                && process.isRunning()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        // Remember the port an automatic instance settled on, so its next launch
        // binds the same one. The browser interface keys session state by
        // origin, so a moving port would let two writers reach one history.
        if (process.state() == DshProcess.State.READY && process.plan().port() > 0) {
            try {
                DshPorts.remember(process.plan().instance(), process.plan().port());
            } catch (DshException e) {
                LOG.warning("Failed to record the port of " + process.plan().instance().id(), e);
            }
        }
    }
}
