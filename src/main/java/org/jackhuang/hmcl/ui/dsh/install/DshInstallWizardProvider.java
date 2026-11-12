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
package org.jackhuang.hmcl.ui.dsh.install;

import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.AppBootPin;
import com.mcxcc303.hdsl.harness.DshPreset;
import com.mcxcc303.hdsl.harness.DshPresetCatalog;
import com.mcxcc303.hdsl.harness.HarnessInstaller;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import javafx.scene.Node;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.dsh.DshInstallProgress;
import org.jackhuang.hmcl.ui.dsh.DshToolchainPrompt;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardProvider;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

@NotNullByDefault
public final class DshInstallWizardProvider implements WizardProvider {
	// Absent if boot matches with harness
	public static final SettingsMap.Key<String> APP_BOOT = new SettingsMap.Key<>("dsh.appBoot");
	public static final SettingsMap.Key<String> VERSION = new SettingsMap.Key<>("dsh.version");
	public static final SettingsMap.Key<String> NAME = new SettingsMap.Key<>("dsh.name");
	// absent if "do not install".
	public static final SettingsMap.Key<Map<String, String>> PRESET_CHOICES = new SettingsMap.Key<>("dsh.presetChoices");
	private final @Nullable String preselectedVersion;

	public DshInstallWizardProvider(@Nullable String preselectedVersion) {
		this.preselectedVersion = preselectedVersion;
	}

	public static Map<String, String> presetChoices(SettingsMap settings) {
		Map<String, String> choices = settings.get(PRESET_CHOICES);
		if (choices == null) {
			choices = new LinkedHashMap<>();
			settings.put(PRESET_CHOICES, choices);
		}
		return choices;
	}

	public static List<String> specsOf(Map<String, String> choices) {
		List<String> specs = new ArrayList<>();
		for (DshPreset preset : DshPresetCatalog.builtin()) {
			String version = choices.get(preset.id());
			if (version == null) continue;
			specs.add(version.isEmpty() ? preset.spec() : preset.spec() + "@" + version);
		}
		return specs;
	}

	@Override
	public void start(SettingsMap settings) {
		if (preselectedVersion != null) settings.put(VERSION, preselectedVersion);

		settings.put(PRESET_CHOICES, new LinkedHashMap<String, String>());
		settings.put(WizardProvider.FailureCallback.KEY, (map, exception, next) -> {
			if (!DshToolchainPrompt.offer(exception)) {
				Controllers.dialog(exception.getMessage(), i18n("wizard.failed"), MessageType.ERROR, next);
				return;
			}
			next.run();
		});
	}

	@Override
	public @Nullable Node createPage(WizardController controller, int step, SettingsMap settings) {
		if (step != 0) return null;
		if (preselectedVersion == null) {
			throw new IllegalStateException("The install wizard needs the version it was opened for");
		}
		return new QuickInstallPage(controller);
	}

	@Override
	public Object finish(SettingsMap settings) {
		InstallTask task = new InstallTask(settings);
		settings.put("title", task.progress.messageProperty());
		return task;
	}

	@Override
	public boolean cancel() {
		// Only stops the command
		DshCommand.stopRunning();
		return true;
	}

	static final class InstallTask extends Task<Void> {
		private final SettingsMap settings;
		private final DshInstallProgress progress = new DshInstallProgress();
		private @Nullable String version;
		private boolean versionWasPresent;

		private InstallTask(SettingsMap settings) {
			this.settings = settings;
			setName(i18n("dsh.install.working"));
		}

		DshInstance createInstance() throws DshException {
			String version = settings.get(VERSION);
			String name = settings.get(NAME);
			if (version == null || name == null) {
				throw new DshException("The install wizard finished without a complete configuration");
			}

			String nodeRuntime = SettingsManager.settings().defaultNodeRuntimeProperty().get();
			// A private home by default. A shared home only when the policy allows sharing.
			LauncherSettings launcherSettings = SettingsManager.settings();
			DshHomeMode shared = launcherSettings.defaultHomeModeProperty().get() == DshHomeMode.ISOLATED
			                     ? DshHomeMode.VERSION_SHARED
			                     : launcherSettings.defaultHomeModeProperty().get();
			DshHomeMode homeMode = launcherSettings.isolationPolicy()
			                                       .homeMode(!presetChoices(settings).isEmpty(), shared);
			Path workspace = Path.of(System.getProperty("user.home"));

			DshInstance instance = DshInstanceManager.create(name.trim(),
			                                                 version,
			                                                 DshInstance.DEFAULT_PROFILE,
			                                                 workspace,
			                                                 nodeRuntime,
			                                                 homeMode,
			                                                 null,
			                                                 List.of(),
			                                                 Map.of());
			LOG.info("Wizard created instance " + instance.id() + " (dsh " + version + ")");
			return instance;
		}

		@Override
		public void execute() throws Exception {
			List<String> specs = specsOf(settings.getOrDefault(PRESET_CHOICES, Map.of()));
			Consumer<String> report = line -> {
				LOG.info("[install] " + line);
				progress.accept(line);
				double fraction = progress.fractionProperty().get();
				if (fraction >= 0) updateProgress(fraction);

			};
			DshInstance instance = createInstance();
			String version = instance.version();

			try {
				report.accept(i18n("dsh.install.progress.fetching", version));
				HarnessInstaller.install(instance, report);
				updateProgress(1.0);

				String appBoot = settings.get(APP_BOOT);
				if (appBoot != null && !appBoot.isBlank() && !appBoot.equals(version)) {
					LOG.info("Instance " + instance.id() + " was asked for boot library " + appBoot +
					         " instead of the launcher's own " + version);
					AppBootPin.overrideAppBoot(instance, appBoot);
				}

				if (!specs.isEmpty()) {
					DshPluginInstaller.installSpecs(instance, specs, report);
				}
				updateProgress(1.0);

				FXUtils.runInFX(() -> Controllers.showToast(i18n("dsh.instance.created", instance.id())));
			} catch (RuntimeException | DshException stopped) {
				// A cancellation is read from the thread.
				if (Thread.currentThread().isInterrupted()) {
					LOG.info("Install of " + instance.id() + " was cancelled");
					DshCommand.stopRunning();
				}
				// An instance with no runtime would appear in the list and fail to start.
				HarnessInstaller.discardPartial(instance);
				try {
					DshInstanceManager.delete(instance.id());
				} catch (RuntimeException | DshException cleanupFailure) {
					LOG.warning("Could not remove the half-made instance " + instance.id(), cleanupFailure);
				}
				throw stopped;
			}
		}
	}
}
