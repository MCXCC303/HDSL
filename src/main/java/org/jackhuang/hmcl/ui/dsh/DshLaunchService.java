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
import com.mcxcc303.hdsl.instance.PortOccupant;
import com.mcxcc303.hdsl.launch.DshLauncher;
import com.mcxcc303.hdsl.launch.DshLauncherVisibility;
import com.mcxcc303.hdsl.launch.DshProcess;
import com.mcxcc303.hdsl.launch.DshProcessManager;
import com.mcxcc303.hdsl.launch.DshProcessManager.LaunchState;
import javafx.stage.Stage;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.LogWindow;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TaskExecutorDialogPane;
import org.jackhuang.hmcl.util.CircularArrayList;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.time.Duration;
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

/// Starts instances from the user interface and reports the outcome.
@NotNullByDefault
public final class DshLaunchService {
	/// How long to wait for the browser surface's readiness line before giving up.
	private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(120);
	/// How often the readiness state is polled.
	private static final long POLL_MILLIS = 200;
	/// Instances whose launch is in flight, so repeat activations are ignored.
	private static final Set<String> LAUNCHING = ConcurrentHashMap.newKeySet();
	/// Instances whose launch was stopped before it became ready.
	private static final Set<String> CANCELLED = ConcurrentHashMap.newKeySet();

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

	public static boolean isLaunching(String instanceId) {
		return LAUNCHING.contains(instanceId);
	}

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

	public static void toggle(DshInstance instance, @Nullable Runnable onDone) {
		toggle(instance, onDone, false);
	}

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

	public static String actionLabel(LaunchState state) {
		return switch (state) {
			case STARTING -> i18n("dsh.launch.launching");
			case RUNNING -> i18n("dsh.stop");
			case STOPPING -> i18n("dsh.stopping");
			case STOPPED -> i18n("dsh.launch");
		};
	}

	public static String actionHint(LaunchState state) {
		return switch (state) {
			case STARTING -> i18n("button.cancel");
			case STOPPING -> i18n("dsh.stopping");
			default -> actionLabel(state);
		};
	}

	public static @Nullable String stateTag(LaunchState state) {
		return switch (state) {
			case STARTING -> i18n("dsh.launch.launching");
			case RUNNING -> i18n("dsh.instance.running");
			case STOPPING -> i18n("dsh.stopping");
			case STOPPED -> null;
		};
	}

	public static void launch(DshInstance instance, @Nullable Consumer<DshProcess> onDone) {
		launch(instance, onDone, false);
	}

	public static void launch(DshInstance instance, @Nullable Consumer<DshProcess> onDone, boolean showOutput) {
		if (DshProcessManager.isStopping(instance.id())) {
			Controllers.dialog(i18n("dsh.launch.stopping", instance.id()), i18n("dsh.stop"), MessageType.WARNING);
			return;
		}

		if (SettingsManager.settings().getAccounts().isEmpty()) {
			Controllers.dialog(i18n("dsh.launch.needs_account"), i18n("dsh.account.list"), MessageType.WARNING);
			Controllers.navigate(new AccountListPage());
			return;
		}
		if (!LAUNCHING.add(instance.id())) {
			return;
		}

		DshProcess[] started = new DshProcess[1];
		DshAccount[] chosen = new DshAccount[1];
		DshAccountRoute.Prepared[] route = new DshAccountRoute.Prepared[1];

		Task<Void> account = new DshStageTask("dsh.launch.stage.account", null, null, progress -> {
			chosen[0] = DshAccount.forInstance(instance);
			checkAccount(instance, chosen[0]);
			route[0] = DshAccountRoute.prepare(chosen[0]).orElse(null);
		});
		Task<Void> models = new DshStageTask("dsh.launch.stage.models", null, account, progress -> {
			if (route[0] != null) {
				route[0].resolveModels(chosen[0]);
			}
		});
		Task<Void> starting = new DshStageTask("dsh.launch.stage.starting", null, models, progress -> {
			DshLauncher.LaunchPlan plan = DshLauncher.plan(instance, chosen[0], route[0]);
			started[0] = DshProcessManager.launch(instance, chosen[0], plan);
		});
		Task<Void> ready = new DshStageTask("dsh.launch.stage.ready", null, starting, progress -> awaitReady(started[0]));

		Task<Void> launch = ready.withStagesHints("dsh.launch.stage.account",
		                                          "dsh.launch.stage.models",
		                                          "dsh.launch.stage.starting",
		                                          "dsh.launch.stage.ready");

		TaskExecutor executor = launch.executor();
		executor.addTaskListener(new TaskListener() {
			@Override
			public void onStop(boolean success, TaskExecutor stopped) {
				boolean openLog = showOutput || SettingsManager.settings().showLogsFor(instance.id());
				runInFX(() -> settle(instance, success, stopped.getException(), openLog, onDone, started[0]));
			}
		});

		TaskExecutorDialogPane progress = new TaskExecutorDialogPane(it -> {
			stop(instance.id(), null);
			it.fireEvent(new DialogCloseEvent());
		});
		progress.titleProperty().set(i18n("dsh.launch.launching", instance.id()));
		progress.setExecutor(executor, true);
		Controllers.dialog(progress);

		progress.startExecutor();
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

	/**
	 * Describes a port failure, naming what holds the port when that is known. The occupant is
	 * shown as it was found; whether it may be stopped is not decided here.
	 *
	 * @param instance the instance that could not start
	 * @param failure  the failure the launch ended with, or `null`
	 * @param occupant the process holding the port, or `null` when it could not be named
	 * @return the message to show
	 */
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

	/// Must be called on the FX thread, as constructing and showing the window is.
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
}

