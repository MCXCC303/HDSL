package org.jackhuang.hmcl.ui.dsh;

import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.HarnessInstaller;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.instance.InstanceLocks;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TaskExecutorDialogPane;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Service for upgrading or reinstalling the DeepSeek Harness runtime of an instance.
 */
@NotNullByDefault
public final class DshInstanceUpgradeService {
	private DshInstanceUpgradeService() {
	}

	/**
	 * Installs a version and the instance's plugins, showing progress in a dialog.
	 *
	 * @param instance     the instance to install into
	 * @param version      the version to install
	 * @param title        the task title
	 * @param failureTitle the title of the dialog shown when the install fails
	 * @param onDone       callback executed on completion, or {@code null}
	 */
	private static void install(DshInstance instance,
	                            String version,
	                            String title,
	                            String failureTitle,
	                            @Nullable Runnable onDone) {
		List<String> specs;
		try {
			specs = specsOf(instance);
		} catch (DshException e) {
			Controllers.dialog(e.getMessage(), failureTitle, MessageType.ERROR);
			return;
		}

		DshInstallProgress progress = new DshInstallProgress();

		Task<Void> task = Task.runAsync(title, () -> {
			LOG.info("Installing DeepSeek Harness " + version + " for " + instance.id());

			InstanceLocks.withLock(instance, "upgraded", () -> {
				HarnessInstaller.install(instance, version, progress::accept);
				DshInstanceManager.update(withVersion(instance, version));

				if (!specs.isEmpty()) {
					DshPluginInstaller.installSpecs(withVersion(instance, version), specs, progress::accept);
				}
			});

			LOG.info("Instance " + instance.id() + " now runs DeepSeek Harness " + version);
		});

		TaskExecutorDialogPane pane = new TaskExecutorDialogPane(new TaskCancellationAction(it -> {
			DshCommand.stopRunning();
			it.fireEvent(new DialogCloseEvent());
		}));
		pane.titleProperty().bind(progress.messageProperty());

		TaskExecutor executor = task.executor();
		executor.addTaskListener(new TaskListener() {
			@Override
			public void onStop(boolean success, TaskExecutor stopped) {
				FXUtils.runInFX(() -> {
					if (!success) {
						Exception failure = stopped.getException();
						LOG.warning("Could not install DeepSeek Harness " + version + " for " + instance.id(), failure);
						Controllers.dialog(failure == null ? failureTitle : failure.getMessage(),
						                   failureTitle,
						                   MessageType.ERROR);
					} else if (onDone != null) {
						onDone.run();
					}
				});
			}
		});

		pane.setExecutor(executor, true);
		Controllers.dialog(pane);
		executor.start();
	}

	/**
	 * Reinstalls the current runtime version and plugins of an instance.
	 *
	 * @param instance the instance to reinstall
	 * @param onDone   callback executed on completion, or {@code null}
	 */
	public static void reinstall(DshInstance instance, @Nullable Runnable onDone) {
		install(instance,
		        instance.version(),
		        i18n("dsh.install.reinstall"),
		        i18n("dsh.instance.reinstall.failed"),
		        onDone);
	}

	/**
	 * Upgrades an instance to a specified runtime version.
	 *
	 * @param instance the instance to upgrade
	 * @param version  the target version
	 * @param onDone   callback executed on completion, or {@code null}
	 */
	public static void upgrade(DshInstance instance, String version, @Nullable Runnable onDone) {
		install(instance,
		        version,
		        i18n("dsh.instance.upgrade", version),
		        i18n("dsh.instance.upgrade.failed"),
		        onDone);
	}

	private static DshInstance withVersion(DshInstance instance, String version) {
		return instance.withVersion(version);
	}

	private static List<String> specsOf(DshInstance instance) throws DshException {
		Map<String, String> dependencies = DshPluginInstaller.readDependencies(instance.homeDirectory(),
		                                                                       instance.profile());
		List<String> specs = new ArrayList<>();
		for (Map.Entry<String, String> entry : dependencies.entrySet()) {
			specs.add(entry.getValue() == null || entry.getValue().isBlank()
			          ? entry.getKey()
			          : entry.getKey() + "@" + entry.getValue());
		}
		return specs;
	}
}
