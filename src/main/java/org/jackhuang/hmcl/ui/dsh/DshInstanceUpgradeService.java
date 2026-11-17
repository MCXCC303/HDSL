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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Moves an instance to another DeepSeek Harness version.
 */
@NotNullByDefault
public final class DshInstanceUpgradeService {
	private DshInstanceUpgradeService() {
	}

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

		Task<Void> task = new DshProgressTask() {
			@Override
			protected void perform() throws Exception {
				LOG.info("Installing DeepSeek Harness " + version + " for " + instance.id());

				InstanceLocks.withLock(instance, "upgraded", () -> {
					HarnessInstaller.install(instance, version, progress()::accept);
					DshInstanceManager.update(withVersion(instance, version));

					if (!specs.isEmpty()) {
						DshPluginInstaller.installSpecs(withVersion(instance, version), specs, progress()::accept);
					}
				});

				LOG.info("Instance " + instance.id() + " now runs DeepSeek Harness " + version);
			}
		};
		task.setName(title);

		TaskExecutorDialogPane pane = new TaskExecutorDialogPane(it -> {
			DshCommand.stopRunning();
			it.fireEvent(new DialogCloseEvent());
		});
		pane.setTitle(title);

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
		pane.startExecutor();
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
