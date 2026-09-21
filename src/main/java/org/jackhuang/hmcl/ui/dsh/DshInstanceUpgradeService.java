package org.jackhuang.hmcl.ui.dsh;

import org.jackhuang.hmcl.dsh.DshCommand;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstallProgress;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.dsh.DshVersionManager;
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

    /// Starts the upgrade, showing its progress in a dialog.
    ///
    /// @param instance the instance to move
    /// @param version  the version to move it to
    /// @param onDone   run when the upgrade finishes, or `null`
    public static void upgrade(DshInstance instance, String version, @Nullable Runnable onDone) {
        // Read before anything changes: this is what the profile holds now, and
        // the manifest it is read from is rewritten by the install that follows.
        List<String> specs;
        try {
            specs = specsOf(instance);
        } catch (DshException e) {
            Controllers.dialog(e.getMessage(), i18n("dsh.instance.upgrade.failed"),
                    MessageType.ERROR);
            return;
        }

        DshInstallProgress progress = new DshInstallProgress();

        Task<Void> task = Task.runAsync(i18n("dsh.instance.upgrade", version), () -> {
            LOG.info("Moving instance " + instance.id() + " to DeepSeek Harness " + version);

            DshVersionManager.install(instance, version, progress::accept);
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
                        LOG.warning("Could not move instance " + instance.id() + " to " + version, failure);
                        Controllers.dialog(failure == null ? i18n("dsh.instance.upgrade.failed") : failure.getMessage(),
                                i18n("dsh.instance.upgrade.failed"),
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

    /// Returns a copy of an instance recording a different version.
    ///
    /// @param instance the instance
    /// @param version  the version to record
    /// @return the copy
    private static DshInstance withVersion(DshInstance instance, String version) {
        return new DshInstance(instance.id(), version, instance.profile(), instance.workspace(),
                instance.nodeRuntime(), instance.homeMode(), instance.customHome(),
                instance.extraArguments(), instance.environment(), instance.icon(), instance.iconFile(),
                instance.portMode(), instance.port(), instance.createdAt());
    }

    /// Lists the plugins the instance's profile holds, as installable specs.
    ///
    /// @param instance the instance
    /// @return the specs, empty when nothing is installed
    /// @throws DshException when the home cannot be resolved
    private static List<String> specsOf(DshInstance instance) throws DshException {
        Map<String, String> dependencies =
                DshPluginInstaller.readDependencies(instance.homeDirectory(), instance.profile());
        List<String> specs = new ArrayList<>();
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            specs.add(entry.getValue() == null || entry.getValue().isBlank()
                    ? entry.getKey()
                    : entry.getKey() + "@" + entry.getValue());
        }
        return specs;
    }
}
