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
import org.jackhuang.hmcl.task.Schedulers;
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

    /// Reports whether an instance is currently being launched.
    ///
    /// @param instanceId the instance id
    /// @return whether a launch is in flight
    public static boolean isLaunching(String instanceId) {
        return LAUNCHING.contains(instanceId);
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
        if (!LAUNCHING.add(instance.id())) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                DshProcess process = DshProcessManager.launch(instance);
                awaitReady(process);
                return process;
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((process, throwable) -> runInFX(() -> {
            LAUNCHING.remove(instance.id());
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause()
                        : throwable;
                LOG.warning("Failed to launch instance " + instance.id(), cause);
                Controllers.dialog(cause.getMessage(), i18n("dsh.launch.failed"), MessageType.ERROR);
            } else {
                if (showOutput) {
                    openLogWindow(process);
                }
                Optional<java.net.URI> url = process.webUrl();
                if (url.isPresent()) {
                    if (settings().openBrowserOnLaunchProperty().get()) {
                        FXUtils.openLink(url.get().toString());
                    }
                    Controllers.showToast(i18n("dsh.launch.ready", instance.id()));
                } else if (process.isRunning()) {
                    Controllers.showToast(i18n("dsh.launch.started", instance.id()));
                } else {
                    Controllers.dialog(
                            i18n("dsh.launch.exited", process.exitCode().orElse(-1)),
                            i18n("dsh.launch.failed"), MessageType.ERROR);
                }
            }
            if (onDone != null) {
                onDone.accept(process);
            }
        }));
    }

    /// Stops a running instance.
    ///
    /// @param instanceId the instance id
    /// @param onDone     invoked on the JavaFX thread once the stop returns, or `null`
    public static void stop(String instanceId, @Nullable Runnable onDone) {
        CompletableFuture.runAsync(() -> DshProcessManager.stop(instanceId), Schedulers.io())
                .whenComplete((ignored, throwable) -> runInFX(() -> {
                    if (throwable != null) {
                        LOG.warning("Failed to stop instance " + instanceId, throwable);
                    }
                    if (onDone != null) {
                        onDone.run();
                    }
                }));
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
