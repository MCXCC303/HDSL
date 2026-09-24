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

import org.jackhuang.hmcl.dsh.DshAccount;
import org.jackhuang.hmcl.dsh.DshAccountOverlay;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshLauncher;
import org.jackhuang.hmcl.dsh.DshPorts;
import org.jackhuang.hmcl.dsh.DshProcess;
import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.jackhuang.hmcl.dsh.DshProcessManager.LaunchState;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.TaskExecutorDialogPane;
import org.jackhuang.hmcl.util.function.ExceptionalRunnable;
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

    /// Asks the vendor whether the account's key still works, before the launcher relies on it.
    ///
    /// The original does the same thing at the same moment, and for the same reason: a launch that
    /// gets as far as starting the game and *then* discovers the credential is dead has spent the
    /// whole startup on a failure it could have named in a second. Its step is a login; this one is a
    /// model list, which is the smallest call that answers the same question.
    ///
    /// Two of the three outcomes let the launch continue, and that is deliberate:
    ///
    /// - **valid** — nothing to say.
    /// - **unknown** — the vendor could not be reached, or does not answer this question. A network
    ///   that cannot reach a supplier today may reach it in a minute, and refusing to start because a
    ///   *check* failed would make the launcher less able than the harness it launches. It is logged.
    /// - **rejected** — the vendor said no. This is the one worth stopping for, and it stops with a
    ///   message naming whose key, so the person knows which row to fix.
    ///
    /// An account that carries no key is not checked at all — there is nothing to check — which is
    /// exactly what offline mode is.
    ///
    /// @param instance the instance being launched
    /// @param account  the account it will launch with, or `null`
    /// @throws java.util.concurrent.CompletionException when the vendor refused the key
    private static void checkAccount(DshInstance instance,
                                     @Nullable org.jackhuang.hmcl.dsh.DshAccount account) {
        if (account == null || !account.carriesAKey()) {
            return;
        }
        org.jackhuang.hmcl.dsh.DshAccount.Check result = account.check();
        switch (result.outcome()) {
            case VALID -> LOG.info("The account " + account.displayName() + " is valid");
            case UNREACHABLE, UNKNOWN -> LOG.warning("Could not confirm the account "
                    + account.displayName() + " before launching " + instance.id() + ": "
                    + result.message());
            case REJECTED -> {
                LOG.warning("The account " + account.displayName() + " was refused: " + result.message());
                throw new java.util.concurrent.CompletionException(
                        new DshException(i18n("dsh.account.rejected.before_launch",
                                account.displayName(), result.message())));
            }
        }
    }

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

        // A launcher with no account cannot launch anything, and saying so before the work starts is
        // better than a harness that comes up unconfigured and asks the person to set a supplier up
        // by hand. This mirrors the original, which will not start a game without an account either:
        // the account is what everything else is done on behalf of, and there is no meaningful
        // "nothing" to do it for.
        //
        // Checked here rather than in each control that offers to start, because every one of them
        // leads to this method and a rule stated once cannot be forgotten by the next button.
        if (org.jackhuang.hmcl.setting.SettingsManager.settings().getAccounts().isEmpty()) {
            Controllers.dialog(i18n("dsh.launch.needs_account"),
                    i18n("dsh.account.list"), MessageType.WARNING);
            Controllers.navigate(new org.jackhuang.hmcl.ui.dsh.AccountListPage());
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
        //
        // A launch is four steps, and each is named as it happens rather than the
        // whole thing sitting under one title: the account is described, its
        // supplier is asked which models it serves, the child is started, and it
        // is waited for. The list marks a stage running when the task carrying it
        // becomes ready and done when that task finishes, so each step needs a
        // task of its own — which is also why asking the supplier was moved out of
        // writing the overlay: while it is out there on the network is exactly when
        // there is something worth saying.
        //
        // The process is held rather than looked up again when the launch ends: an
        // instance that dies before it is ready is no longer a process the manager
        // reports, so asking it for one is how a failed launch came to say nothing
        // at all — no log window, no dialog, nothing to look at.
        DshProcess[] started = new DshProcess[1];
        DshAccount[] chosen = new DshAccount[1];
        DshAccountOverlay.Prepared[] overlay = new DshAccountOverlay.Prepared[1];

        // Each step names the step that runs before it, and the last one carries the hint list — so
        // the executor is handed the end of the chain and works backwards through it.
        //
        // Backwards is what makes the list move. The executor runs a task's *dependents* before the
        // task and its *dependencies* after, and a task is reported finished only once everything
        // hung off it has finished too. Chained forwards through `getDependencies`, every step would
        // still be running when the last one started, and all four rows would turn done together at
        // the end. Chained backwards, each step is finished — and its row marked done — before the
        // next one begins, which is what a person watching expects to see.
        Task<Void> account = new StageTask("dsh.launch.stage.account", null, () -> {
            chosen[0] = DshAccount.forInstance(instance);
            checkAccount(instance, chosen[0]);
            overlay[0] = DshAccountOverlay.prepare(instance, chosen[0]).orElse(null);
        });
        Task<Void> models = new StageTask("dsh.launch.stage.models", account, () -> {
            if (overlay[0] != null) {
                overlay[0].resolveModels(chosen[0]);
            }
        });
        Task<Void> starting = new StageTask("dsh.launch.stage.starting", models, () -> {
            DshLauncher.LaunchPlan plan = DshLauncher.plan(instance, chosen[0], overlay[0]);
            started[0] = DshProcessManager.launch(instance, chosen[0], plan);
        });
        Task<Void> ready = new StageTask("dsh.launch.stage.ready", starting,
                () -> awaitReady(started[0]));

        Task<Void> launch = ready.withStagesHints(
                "dsh.launch.stage.account", "dsh.launch.stage.models",
                "dsh.launch.stage.starting", "dsh.launch.stage.ready");

        TaskExecutor executor = launch.executor();
        executor.addTaskListener(new TaskListener() {
            @Override
            public void onStop(boolean success, TaskExecutor stopped) {
                boolean openLog = showOutput
                || org.jackhuang.hmcl.setting.SettingsManager.settings().showLogsFor(instance.id());
        runInFX(() -> settle(instance, success, stopped.getException(), openLog, onDone, started[0]));
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
                if (portTaken) {
                    // A port that is already taken is not a crash: it is a setting to change, and
                    // the message already says which. The crash dialog would add a log tail about
                    // nothing.
                    Controllers.dialog(failureMessage(instance, failure),
                            i18n("dsh.launch.failed"), MessageType.WARNING);
                } else {
                    // It never got as far as a process, so there is no output to show — but the
                    // reason is the launcher's own and the dialog is still the place a person looks
                    // for what to do next.
                    DshCrashDialog.show(instance, i18n("launch.failed.cannot_create_jvm"),
                            failureMessage(instance, failure), null);
                }
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
                applyLauncherVisibility(instance.id());
                if (settings().openBrowserOnLaunchProperty().get()) {
                    FXUtils.openLink(url.get().toString());
                }
            }
            // An instance that ended before ever answering is **not** reported here. The manager's
            // own listener reports every ending — including the ones that happen long after a launch
            // is over, which this path never sees — so reporting it here as well put two identical
            // crash dialogs on the screen for one crash.
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
    ///
    /// The choice is the one in force for the instance being started: its own when it has
    /// one, and the launcher's otherwise.
    ///
    /// @param instanceId the instance being started
    private static void applyLauncherVisibility(String instanceId) {
        org.jackhuang.hmcl.dsh.DshLauncherVisibility choice =
                settings().launcherVisibilityFor(instanceId);
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

    /// One step of a launch, named in the progress dialog's stage list.
    ///
    /// A task cannot be given a stage from outside: `setStage` is not public, and the stage has to be
    /// settled before the task runs, because the list begins a stage when the task carrying it
    /// becomes ready and finishes it when that task ends. So a step that is to appear in the list has
    /// to be a task of its own — which is the whole of what this class adds.
    ///
    /// Nothing is named and nothing is of showable significance: a task that is both also draws a
    /// line of its own in the same list — and, unnamed, that line reads as this class's own name —
    /// while the stage rows already say what is happening.
    private static final class StageTask extends Task<Void> {
        private final ExceptionalRunnable<?> work;
        private final @Nullable Task<?> before;

        /// @param stage  the stage this step marks, as an i18n key
        /// @param before the step that runs before this one, or `null` for the first
        /// @param work   what the step does
        private StageTask(String stage, @Nullable Task<?> before, ExceptionalRunnable<?> work) {
            this.work = work;
            this.before = before;
            setStage(stage);
            setSignificance(Task.TaskSignificance.MINOR);
            setExecutor(Schedulers.defaultScheduler());
        }

        @Override
        public void execute() throws Exception {
            work.run();
            setResult(null);
        }

        /// The step that runs before this one. It goes in `getDependents`, which the executor runs
        /// ahead of the task — the name reads backwards, and HMCL's own `allOf` puts the tasks it
        /// runs first there too.
        @Override
        public java.util.Collection<? extends Task<?>> getDependents() {
            return before == null
                    ? java.util.Collections.emptySet()
                    : java.util.Collections.singleton(before);
        }
    }
}
