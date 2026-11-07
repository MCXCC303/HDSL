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
package org.jackhuang.hmcl.ui.dsh;

import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.account.DshAccountRoute;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshPorts;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.instance.PortOccupant;
import com.mcxcc303.hdsl.launch.DshLauncher;
import com.mcxcc303.hdsl.launch.DshLauncherVisibility;
import com.mcxcc303.hdsl.launch.DshProcess;
import com.mcxcc303.hdsl.launch.DshProcessManager;
import com.mcxcc303.hdsl.launch.DshProcessManager.LaunchState;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.LogLine;
import org.jackhuang.hmcl.ui.LogWindow;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TaskExecutorDialogPane;
import org.jackhuang.hmcl.util.CircularArrayList;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.function.ExceptionalRunnable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Starts instances from the user interface and reports the outcome.
 */
@NotNullByDefault
public final class DshLaunchService {
	private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(120);
	private static final long POLL_MILLIS = 200;
	private static final Set<String> LAUNCHING = ConcurrentHashMap.newKeySet();
	private static final Set<String> CANCELLED = ConcurrentHashMap.newKeySet();

	static {
		DshProcessManager.setExitListener(process -> {
			if (DshCrashDialog.isCrash(process)) {
				try {
					runInFX(() -> DshCrashDialog.show(process.instance(),
					                                  DshCrashDialog.bannerOf(process),
					                                  DshCrashDialog.describe(process),
					                                  process));
				} catch (IllegalStateException e) {
					LOG.warning("Could not show crash dialog", e);
				}
			}
		});
	}

	private DshLaunchService() {
	}

	private static void checkAccount(DshInstance instance, @Nullable DshAccount account) {
		if (account == null || !account.carriesAKey()) {
			return;
		}
		DshAccount.Check result = account.check();
		switch (result.outcome()) {
			case VALID -> LOG.info("The account " + account.displayName() + " is valid");
			case UNREACHABLE, UNKNOWN -> LOG.warning(
					"Could not confirm the account " + account.displayName() + " before launching " + instance.id() +
					": " + result.message());
			case REJECTED -> {
				LOG.warning("The account " + account.displayName() + " was refused: " + result.message());
				throw new CompletionException(new DshException(i18n("dsh.account.rejected.before_launch",
				                                                    account.displayName(),
				                                                    result.message())));
			}
		}
	}

	/**
	 * Reports whether an instance is currently being launched.
	 *
	 * @param instanceId the instance id
	 * @return whether a launch is in flight
	 */
	public static boolean isLaunching(String instanceId) {
		return LAUNCHING.contains(instanceId);
	}

	/**
	 * Reports what an instance is doing.
	 *
	 * @param instanceId the instance id
	 * @return the launch state
	 */
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

	/**
	 * Starts an instance, or stops the one that is running or launching.
	 *
	 * @param instance the instance
	 * @param onDone   run on the JavaFX thread once settled, or {@code null}
	 */
	public static void toggle(DshInstance instance, @Nullable Runnable onDone) {
		toggle(instance, onDone, false);
	}

	/**
	 * Starts or stops an instance, optionally showing launch output.
	 *
	 * @param instance   the instance
	 * @param onDone     run once settled, or {@code null}
	 * @param showOutput whether to open the process log window
	 */
	public static void toggle(DshInstance instance, @Nullable Runnable onDone, boolean showOutput) {
		DshInstance fresh = InstanceStore.find(instance.id());
		DshInstance target = fresh != null ? fresh : instance;
		switch (state(target.id())) {
			case STOPPED -> launch(target, ignored -> {
				if (onDone != null) {
					onDone.run();
				}
			}, showOutput);
			case STARTING, RUNNING -> stop(target.id(), onDone);
			case STOPPING -> LOG.info("Instance " + target.id() + " is already stopping");
		}
	}

	/**
	 * Returns the label of a control that starts or stops an instance.
	 */
	public static String actionLabel(LaunchState state) {
		return switch (state) {
			case STARTING -> i18n("dsh.launch.launching");
			case RUNNING -> i18n("dsh.stop");
			case STOPPING -> i18n("dsh.stopping");
			case STOPPED -> i18n("dsh.launch");
		};
	}

	/**
	 * Returns the hint of a control that starts or stops an instance.
	 */
	public static String actionHint(LaunchState state) {
		return switch (state) {
			case STARTING -> i18n("button.cancel");
			case STOPPING -> i18n("dsh.stopping");
			default -> actionLabel(state);
		};
	}

	/**
	 * Returns the status tag for an instance row.
	 */
	public static @Nullable String stateTag(LaunchState state) {
		return switch (state) {
			case STARTING -> i18n("dsh.launch.launching");
			case RUNNING -> i18n("dsh.instance.running");
			case STOPPING -> i18n("dsh.stopping");
			case STOPPED -> null;
		};
	}

	/**
	 * Launches an instance in the background.
	 *
	 * @param instance the instance to launch
	 * @param onDone   invoked on the JavaFX thread once the launch settles, or {@code null}
	 */
	public static void launch(DshInstance instance, @Nullable Consumer<DshProcess> onDone) {
		launch(instance, onDone, false);
	}

	/**
	 * Launches an instance, optionally showing its output.
	 *
	 * @param instance   the instance to launch
	 * @param onDone     run after the launch settles, or {@code null}
	 * @param showOutput whether to open the process log window
	 */
	public static void launch(DshInstance instance, @Nullable Consumer<DshProcess> onDone, boolean showOutput) {
		DshInstance fresh = InstanceStore.find(instance.id());
		DshInstance target = fresh != null ? fresh : instance;
		if (DshProcessManager.isStopping(target.id())) {
			Controllers.dialog(i18n("dsh.launch.stopping", target.id()), i18n("dsh.stop"), MessageType.WARNING);
			return;
		}

		if (SettingsManager.settings().getAccounts().isEmpty()) {
			Controllers.dialog(i18n("dsh.launch.needs_account"), i18n("dsh.account.list"), MessageType.WARNING);
			Controllers.navigate(new AccountListPage());
			return;
		}
		if (!LAUNCHING.add(target.id())) {
			return;
		}

		DshProcess[] started = new DshProcess[1];
		DshAccount[] chosen = new DshAccount[1];
		DshAccountRoute.Prepared[] route = new DshAccountRoute.Prepared[1];

		Task<Void> account = new StageTask("dsh.launch.stage.account", null, () -> {
			chosen[0] = DshAccount.forInstance(target);
			checkAccount(target, chosen[0]);
			route[0] = DshAccountRoute.prepare(chosen[0]).orElse(null);
		});
		Task<Void> models = new StageTask("dsh.launch.stage.models", account, () -> {
			if (route[0] != null) {
				route[0].resolveModels(chosen[0]);
			}
		});
		Task<Void> starting = new StageTask("dsh.launch.stage.starting", models, () -> {
			DshLauncher.LaunchPlan plan = DshLauncher.plan(target, chosen[0], route[0]);
			started[0] = DshProcessManager.launch(target, chosen[0], plan);
		});
		Task<Void> ready = new StageTask("dsh.launch.stage.ready", starting, () -> awaitReady(started[0]));

		Task<Void> launch = ready.withStagesHints("dsh.launch.stage.account",
		                                          "dsh.launch.stage.models",
		                                          "dsh.launch.stage.starting",
		                                          "dsh.launch.stage.ready");

		TaskExecutor executor = launch.executor();
		executor.addTaskListener(new TaskListener() {
			@Override
			public void onStop(boolean success, TaskExecutor stopped) {
				boolean openLog = showOutput || SettingsManager.settings().showLogsFor(target.id());
				runInFX(() -> settle(target, success, stopped.getException(), openLog, onDone, started[0]));
			}
		});

		TaskExecutorDialogPane progress = new TaskExecutorDialogPane(new TaskCancellationAction(it -> {
			stop(target.id(), null);
			it.fireEvent(new DialogCloseEvent());
		}));
		progress.titleProperty().set(i18n("dsh.launch.launching", target.id()));
		progress.setExecutor(executor, true);
		Controllers.dialog(progress);

		executor.start();
	}

	private static void settle(DshInstance instance,
	                           boolean success,
	                           @Nullable Exception failure,
	                           boolean showOutput,
	                           @Nullable Consumer<DshProcess> onDone,
	                           @Nullable DshProcess process) {
		LAUNCHING.remove(instance.id());
		boolean cancelled = CANCELLED.remove(instance.id());

		if (!success) {
			if (cancelled) {
				LOG.info("Launch of " + instance.id() + " was stopped before it was ready");
			} else {
				LOG.warning("Failed to launch instance " + instance.id(), failure);
				int taken = portOf(failure);
				if (taken > 0) {
					CompletableFuture.supplyAsync(() -> PortOccupant.find(taken), Schedulers.io())
					                 .whenComplete((occupant, throwable) -> runInFX(() -> {
						                 if (throwable != null) {
							                 LOG.info("Could not name the process holding port " + taken, throwable);
						                 }
						                 Controllers.dialog(failureMessage(instance, failure, occupant == null ? null : occupant.orElse(null)),
							                 i18n("dsh.launch.port_prompt.title"),
							                 MessageType.WARNING);
					                 }));
				} else {
					DshCrashDialog.show(instance,
					                    i18n("launch.failed.cannot_create_jvm"),
					                    failureMessage(instance, failure),
					                    null);
				}
			}
		} else if (process != null) {
			if (showOutput) {
				openLogWindow(process);
			}
			Optional<URI> url = process.webUrl();
			if (url.isPresent()) {
				Controllers.showToast(i18n("dsh.launch.ready", instance.id()));
				applyLauncherVisibility(instance.id());
				if (settings().openBrowserOnLaunchProperty().get() && process.plan().launcherOpensTheBrowser()) {
					FXUtils.openLink(DshPorts.openAddress(instance, url.get()).toString());
				}
			}
		}

		if (onDone != null && process != null) {
			onDone.accept(process);
		}
	}

	/**
	 * Stops a running instance.
	 *
	 * @param instanceId the instance id
	 * @param onDone     invoked on the JavaFX thread once the stop returns, or {@code null}
	 */
	public static void stop(String instanceId, @Nullable Runnable onDone) {
		if (LAUNCHING.contains(instanceId)) {
			CANCELLED.add(instanceId);
		}
		CompletableFuture.runAsync(() -> DshProcessManager.stop(instanceId), Schedulers.io())
		                 .whenComplete((ignored, throwable) -> runInFX(() -> {
			                 if (throwable != null) {
				                 LOG.warning("Failed to stop instance " + instanceId, throwable);
			                 }
			                 restoreLauncher();
			                 if (onDone != null) {
				                 onDone.run();
			                 }
		                 }));
	}

	private static void applyLauncherVisibility(String instanceId) {
		DshLauncherVisibility choice = settings().launcherVisibilityFor(instanceId);
		switch (choice == null ? DshLauncherVisibility.KEEP : choice) {
			case HIDE -> Controllers.getStage().hide();
			case MINIMIZE -> Controllers.getStage().setIconified(true);
			case KEEP -> {
			}
		}
	}

	private static void restoreLauncher() {
		Stage stage = Controllers.getStage();
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

	private static String failureMessage(DshInstance instance, @Nullable Exception failure) {
		return failureMessage(instance, failure, null);
	}

	private static String failureMessage(DshInstance instance,
	                                     @Nullable Exception failure,
	                                     @Nullable PortOccupant.Occupant occupant) {
		int port = portOf(failure);
		if (port <= 0) {
			return failure == null ? i18n("dsh.launch.failed") : failure.getMessage();
		}
		String description = PortOccupant.describe(occupant);
		if (occupant == null) {
			return i18n("dsh.launch.port_taken", port, instance.id());
		}
		return i18n("dsh.launch.port_taken_by", port,
		            description != null ? description : i18n("dsh.launch.port_holder.unknown"),
		            occupant.pid(), instance.id());
	}

	private static int portOf(@Nullable Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof DshPorts.PortUnavailableException unavailable) {
				return unavailable.port();
			}
		}
		return 0;
	}

	private static void reportPortDrift(DshInstance instance, DshProcess process, int observed) {
		LOG.warning("Instance " + instance.id() + " was launched on port " + process.plan().port() +
		            ", but DeepSeek Harness is serving on " + observed +
		            ". A profile patch layer is restating the webserver row, which is the row `--port`" +
		            " is read from, so the launcher's number was ignored; look for a row with id" +
		            " \"webserver\" in " + process.plan()
		                                          .homeDirectory()
		                                          .resolve("profiles")
		                                          .resolve(instance.profile())
		                                          .resolve("cordis.patch.yml"));
	}

	static void openLogWindow(DshProcess process) {
		LogWindow window = new LogWindow(process.managedProcess(),
		                                 new CircularArrayList<>(),
		                                 process.instance().id(),
		                                 () -> stop(process.instance().id(), null));
		DshProcess.LogFeed feed = process.followLogs(line -> runInFX(() -> window.logLine(line)));
		window.logLines(feed.initial());
		window.setOnHidden(event -> feed.unsubscribe().run());
		window.show();
	}

	private static void awaitReady(DshProcess process) {
		long deadline = System.currentTimeMillis() + READINESS_TIMEOUT.toMillis();
		while (process.state() == DshProcess.State.STARTING && process.isRunning() &&
		       System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}

		if (process.state() == DshProcess.State.READY) {
			int planned = process.plan().port();
			int observed = DshPorts.observedPort(process.webUrl().orElse(null), planned);
			if (observed > 0) {
				if (observed != planned) {
					reportPortDrift(process.plan().instance(), process, observed);
				}
				try {
					DshPorts.remember(process.plan().instance(), observed);
				} catch (DshException e) {
					LOG.warning("Failed to record the port of " + process.plan().instance().id(), e);
				}
			}
		}
	}

	private static final class StageTask extends Task<Void> {
		private final ExceptionalRunnable<?> work;
		private final @Nullable Task<?> before;

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

		@Override
		public Collection<? extends Task<?>> getDependents() {
			return before == null ? Collections.emptySet() : Collections.singleton(before);
		}
	}
}
