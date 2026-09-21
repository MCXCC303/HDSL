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
package org.jackhuang.hmcl.ui.dsh.install;

import javafx.scene.Node;
import org.jackhuang.hmcl.dsh.DshInstallProgress;
import org.jackhuang.hmcl.dsh.DshCommand;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.DshPreset;
import org.jackhuang.hmcl.dsh.DshPresetCatalog;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardProvider;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Drives the create-an-instance wizard.
///
/// Two steps, matching HMCL's install flow: pick the DeepSeek Harness version,
/// then fill in the quick-install page. Finishing returns a [Task] that the
/// wizard displayer runs with a progress dialog — creating the instance first
/// and then installing the selected plugins, so a partly-installed instance is
/// still visible and usable rather than silently lost.
@NotNullByDefault
public final class DshInstallWizardProvider implements WizardProvider {
    /// The settings key holding the chosen DeepSeek Harness version.
    /// The chosen application boot library version.
    ///
    /// Absent means the launcher's own version, which is the pairing that is
    /// known to work; the two are published together and a mismatch is found at
    /// start-up rather than degraded around.
    public static final SettingsMap.Key<String> APP_BOOT = new SettingsMap.Key<>("dsh.appBoot");

    public static final SettingsMap.Key<String> VERSION = new SettingsMap.Key<>("dsh.version");

    /// The settings key holding the instance name.
    public static final SettingsMap.Key<String> NAME = new SettingsMap.Key<>("dsh.name");

    /// The settings key holding the workspace path.
    /// The settings key holding the home policy.
    /// The settings key holding the Node runtime selection.
    /// The settings key holding the per-preset choice.
    ///
    /// Maps a preset id to the version to pin: an empty string means the
    /// registry's current version, and an absent entry means "do not install".
    public static final SettingsMap.Key<Map<String, String>> PRESET_CHOICES =
            new SettingsMap.Key<>("dsh.presetChoices");

    /// Returns the mutable per-preset choice map, creating it when absent.
    ///
    /// @param settings the wizard settings
    /// @return the choice map
    public static Map<String, String> presetChoices(SettingsMap settings) {
        Map<String, String> choices = settings.get(PRESET_CHOICES);
        if (choices == null) {
            choices = new java.util.LinkedHashMap<>();
            settings.put(PRESET_CHOICES, choices);
        }
        return choices;
    }

    /// Builds the package specs the user chose to install.
    ///
    /// @param choices the preset-id to version map
    /// @return the specs, in catalogue order
    public static List<String> specsOf(Map<String, String> choices) {
        List<String> specs = new ArrayList<>();
        for (DshPreset preset : DshPresetCatalog.builtin()) {
            String version = choices.get(preset.id());
            if (version == null) {
                continue;
            }
            specs.add(version.isEmpty() ? preset.spec() : preset.spec() + "@" + version);
        }
        return specs;
    }

    /// The version chosen before the wizard opened, or `null` to ask.
    private final @Nullable String preselectedVersion;

    /// Creates a provider that starts by asking which version to pin.
    public DshInstallWizardProvider() {
        this(null);
    }

    /// Creates a provider that starts from an already-chosen version.
    ///
    /// @param preselectedVersion the version to pin, or `null` to ask
    public DshInstallWizardProvider(@Nullable String preselectedVersion) {
        this.preselectedVersion = preselectedVersion;
    }

    @Override
    public void start(SettingsMap settings) {
        if (preselectedVersion != null) {
            settings.put(VERSION, preselectedVersion);
        }
        settings.put(PRESET_CHOICES, new java.util.LinkedHashMap<String, String>());
    }

    @Override
    public @Nullable Node createPage(WizardController controller, int step, SettingsMap settings) {
        // The version comes first and the create page follows, which is the
        // original's order: the version is the thing being installed, and the
        // page after it states that choice rather than asking for it again.
        // A preselected version skips the first step.
        boolean askVersion = preselectedVersion == null;
        int createStep = askVersion ? 1 : 0;
        if (step == createStep) {
            return new QuickInstallPage(controller);
        }
        return step == 0 ? new VersionSelectPage(controller) : null;
    }

    @Override
    public Object finish(SettingsMap settings) {
        InstallTask task = new InstallTask(settings);

        // The line the package managers report goes in the dialog's heading. A
        // task's own row would be the natural place and is not usable: the label
        // it would go in is laid out once, when the row appears, and its
        // preferred width is zero from then on, so nothing written to it is
        // drawn. The heading is bound, and it renders.
        settings.put("title", task.progress.messageProperty());

        return task;
    }

    /// Installs the runtime, makes the instance, and adds the chosen plugins.
    ///
    /// A task of its own rather than a lambda, because the package managers'
    /// output is read as it arrives and turned into the line and the bar the
    /// dialog shows, and a task is what the dialog watches.
    private final class InstallTask extends Task<Void> {
        /// The wizard's answers.
        private final SettingsMap settings;

        /// What the package managers say they are doing.
        private final DshInstallProgress progress = new DshInstallProgress();

        /// The version being installed.
        private @Nullable String version;

        /// Whether that version was already on disk when this began.
        private boolean versionWasPresent;

        /// Creates the task.
        ///
        /// @param settings the wizard's answers
        private InstallTask(SettingsMap settings) {
            this.settings = settings;
            setName(i18n("dsh.install.working"));
            // The dialog shows a line beneath a task's name only for a task that
            // belongs to no stage: a staged task becomes a stage row instead,
            // which carries a counter and nothing else. The phases are named in
            // the line rather than listed as stages, so that the counts the
            // package managers report have somewhere to appear.

        }

        @Override
        public void execute() throws Exception {
            String version = settings.get(VERSION);
            String name = settings.get(NAME);
            List<String> specs = specsOf(settings.getOrDefault(PRESET_CHOICES, Map.of()));
            if (version == null || name == null) {
                throw new DshException("The install wizard finished without a complete configuration");
            }

            Consumer<String> report = line -> {
                LOG.info("[install] " + line);
                progress.accept(line);
                double fraction = progress.fractionProperty().get();
                if (fraction >= 0) {
                    updateProgress(fraction);
                }
            };

            String nodeRuntime = org.jackhuang.hmcl.setting.SettingsManager.settings()
                    .defaultNodeRuntimeProperty().get();
            DshHomeMode homeMode = org.jackhuang.hmcl.setting.SettingsManager.settings()
                    .defaultHomeModeProperty().get();
            Path workspace = Path.of(System.getProperty("user.home"));

            // The instance comes first, because the runtime it runs goes inside
            // it: there is nowhere to install DeepSeek Harness until the instance
            // that will hold it exists. What the instance is named and where it
            // lives are settled here, and the rest fills it in.
            DshInstance instance = DshInstanceManager.create(
                    name.trim(), version, DshInstance.DEFAULT_PROFILE,
                    workspace, nodeRuntime, homeMode, null, List.of(), Map.of());
            LOG.info("Wizard created instance " + instance.id() + " (dsh " + version + ")");

            try {
                report.accept(i18n("dsh.install.progress.fetching", version));
                DshVersionManager.install(instance, report);
                updateProgress(1.0);

                String appBoot = settings.get(APP_BOOT);
                if (appBoot != null && !appBoot.isBlank() && !appBoot.equals(version)) {
                    LOG.info("Instance " + instance.id() + " was asked for boot library " + appBoot
                            + " instead of the launcher's own " + version);
                    DshVersionManager.overrideAppBoot(instance, appBoot);
                }

                if (!specs.isEmpty()) {
                    DshPluginInstaller.installSpecs(instance, specs, report);
                }
                updateProgress(1.0);

                // The instance is in the list by now and selected, but the user
                // is still looking at the dialog that made it: naming what was
                // created is what connects the two.
                FXUtils.runInFX(() -> Controllers.showToast(i18n("dsh.instance.created", instance.id())));
            } catch (RuntimeException | DshException stopped) {
                // Whether this was a cancellation is asked of the thread rather
                // than of the exception: the commands wrap an interrupt in a
                // DshException of their own, so the type does not say.
                if (Thread.currentThread().isInterrupted()) {
                    LOG.info("Install of " + instance.id() + " was cancelled");
                    DshCommand.stopRunning();
                }
                // An instance whose runtime never arrived is not an instance; it
                // would appear in the list and fail to start.
                DshVersionManager.discardPartial(instance);
                try {
                    DshInstanceManager.delete(instance.id());
                } catch (RuntimeException | DshException cleanupFailure) {
                    LOG.warning("Could not remove the half-made instance " + instance.id(), cleanupFailure);
                }
                throw stopped;
            }
        }
    }

    @Override
    public boolean cancel() {
        // Stopping is all this does now. What was written is taken away where
        // the failure is handled, because a cancellation arrives as an
        // interruption of the task rather than as a call to this method.
        DshCommand.stopRunning();
        return true;
    }
}
