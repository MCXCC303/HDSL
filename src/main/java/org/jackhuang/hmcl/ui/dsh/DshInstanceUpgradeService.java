package org.jackhuang.hmcl.ui.dsh;

import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.HarnessInstaller;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
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

/// Moves an instance to another DeepSeek Harness version.
///
/// Three things happen, and the order matters. The new runtime is installed into
/// the instance first, because the plugins that follow are resolved against it
/// and there is no point resolving them against the one being replaced. The
/// instance's record is changed only once the runtime is in place, so an upgrade
/// that fails leaves the instance running what it was running. Then the plugins
/// are installed again from the profile's own list: they were resolved against
/// the old runtime's libraries, and a plugin built against a library that has
/// moved fails at import rather than degrading.
@NotNullByDefault
public final class DshInstanceUpgradeService {
	private DshInstanceUpgradeService() {
	}

	/**
	 * Installs a version and then the instance's plugins, showing the progress in a dialog.
	 *
	 * @param instance     the instance to install into
	 * @param version      the version to install
	 * @param title        the task's name
	 * @param failureTitle the title of the dialog shown when the install fails
	 * @param onDone       run when the install finishes, or `null`
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

			HarnessInstaller.install(instance, version, progress::accept);
			DshInstanceManager.update(withVersion(instance, version));

			if (!specs.isEmpty()) {
				DshPluginInstaller.installSpecs(withVersion(instance, version), specs, progress::accept);
			}

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

	public static void reinstall(DshInstance instance, @Nullable Runnable onDone) {
		install(instance,
		        instance.version(),
		        i18n("dsh.install.reinstall"),
		        i18n("dsh.instance.reinstall.failed"),
		        onDone);
	}


	public static void upgrade(DshInstance instance, String version, @Nullable Runnable onDone) {
		install(instance,
		        version,
		        i18n("dsh.instance.upgrade", version),
		        i18n("dsh.instance.upgrade.failed"),
		        onDone);
	}

	/// Returns a copy of an instance recording a different version.
	///
	/// The copy is built from the instance itself, so the folder it lives in
	/// comes with it: an instance in a folder the user added is upgraded where
	/// it is, and rebuilt field by field it would have moved to the folder the
	/// launcher owns.
	///
	/// @param instance the instance
	/// @param version  the version to record
	/// @return the copy
	private static DshInstance withVersion(DshInstance instance, String version) {
		return instance.withVersion(version);
	}

	/// Lists the plugins the instance's profile holds, as installable specs.
	///
	/// @param instance the instance
	/// @return the specs, empty when nothing is installed
	/// @throws DshException when the home cannot be resolved
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
