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
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.dsh.DshStageTask;
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
		Installation installation = new Installation(settings);
		settings.put("title", installation.title());
		return installation.steps();
	}

	@Override
	public boolean cancel() {
		// Only stops the command
		DshCommand.stopRunning();
		return true;
	}

	static final class Installation {
		private final SettingsMap settings;
		private @Nullable DshInstance instance;

		private Installation(SettingsMap settings) {
			this.settings = settings;
		}

		String title() {
			String name = instanceName();
			String subject = name.isEmpty() ? version() : name;
			return subject.isEmpty() ? i18n("dsh.install.start") : i18n("dsh.install.installation", subject);
		}

		String instanceName() {
			String name = settings.get(NAME);
			return name == null ? "" : name.trim();
		}

		String version() {
			String version = settings.get(VERSION);
			return version == null ? "" : version;
		}

		Task<Void> steps() {
			DshStageTask creating = new DshStageTask("dsh.install.stage.instance",
			                                          i18n("dsh.install.task.instance", instanceName()),
			                                          null,
			                                          progress -> instance = createInstance());
			DshStageTask runtime = new DshStageTask("dsh.install.stage.runtime",
			                                        i18n("dsh.install.task.runtime", version()),
			                                        creating,
			                                        progress -> {
				                                                DshInstance created = requireInstance();
				                                                HarnessInstaller.install(created, progress::accept);
				                                                applyAppBoot(created);
			                                        });

			List<Task.StagesHint> hints = new ArrayList<>();
			hints.add(new Task.StagesHint("dsh.install.stage.instance"));
			hints.add(new Task.StagesHint("dsh.install.stage.runtime"));

			List<String> specs = specsOf(settings.getOrDefault(PRESET_CHOICES, Map.of()));
			Task<Void> last = runtime;
			for (String spec : specs) {
				last = new DshStageTask("dsh.install.stage.plugins",
				                        i18n("dsh.install.task.plugins", spec),
				                        last,
				                        progress -> {
				                                                DshInstance created = requireInstance();
				                                                DshPluginInstaller.installSpecs(created, List.of(spec), line -> {
				                                                    LOG.info("[install] " + line);
				                                                    progress.accept(line);
				                                                });
				                                        });
			}
			if (!specs.isEmpty()) {
				hints.add(new Task.StagesHint("dsh.install.stage.plugins"));
			}

			Task<Void> chain = last.whenComplete(Schedulers.io(), exception -> {
				if (exception != null) {
					discard();
					return;
				}
				DshInstance created = instance;
				if (created != null) {
					FXUtils.runInFX(() -> Controllers.showToast(i18n("dsh.instance.created", created.id())));
				}
			});
			return chain.withStagesHints(hints);
		}

		private void applyAppBoot(DshInstance created) throws DshException {
			String appBoot = settings.get(APP_BOOT);
			if (appBoot != null && !appBoot.isBlank() && !appBoot.equals(created.version())) {
				LOG.info("Instance " + created.id() + " was asked for boot library " + appBoot +
				         " instead of the launcher's own " + created.version());
				AppBootPin.overrideAppBoot(created, appBoot);
			}
		}

		private void discard() {
			DshInstance created = instance;
			if (created == null) {
				return;
			}
			// A cancellation is an interrupt on this thread.
			if (Thread.currentThread().isInterrupted()) {
				LOG.info("Install of " + created.id() + " was cancelled");
				DshCommand.stopRunning();
			}
			HarnessInstaller.discardPartial(created);
			try {
				DshInstanceManager.delete(created.id());
			} catch (RuntimeException | DshException cleanupFailure) {
				LOG.warning("Could not remove the half-made instance " + created.id(), cleanupFailure);
			}
		}

		private DshInstance requireInstance() throws DshException {
			DshInstance created = instance;
			if (created == null) {
				throw new DshException("The install wizard finished without an instance");
			}
			return created;
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
	}
}
