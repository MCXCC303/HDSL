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
        // Two stages, as the install has two: the runtime is fetched and the
        // instance made from it, then the chosen plugins are installed into it.
        // The dialog lists them, which is the shape the original's install has —
        // a list of the steps it is taking rather than one line saying it is
        // busy.
        String runtimeStage = i18n("dsh.install.stage.runtime");
        String pluginStage = i18n("dsh.install.stage.plugins");

        return Task.runAsync(runtimeStage, Schedulers.io(), () -> {
            String version = settings.get(VERSION);
            installingVersion = version;
            versionWasPresent = version != null && DshVersionManager.findInstalled(version) != null;
            try {
            String name = settings.get(NAME);
            Map<String, String> choices = settings.getOrDefault(PRESET_CHOICES, Map.of());
            List<String> specs = specsOf(choices);

            if (version == null || name == null) {
                throw new DshException("The install wizard finished without a complete configuration");
            }

            // The environment comes from the launcher's settings rather than
            // from the page: a runtime and a home policy are the launcher's
            // business, and an instance that wants its own says so afterwards.
            // The workspace is the user's home, which is what a session scoped
            // to nothing in particular should be.
            String nodeRuntime = org.jackhuang.hmcl.setting.SettingsManager.settings()
                    .defaultNodeRuntimeProperty().get();
            DshHomeMode homeMode = org.jackhuang.hmcl.setting.SettingsManager.settings()
                    .defaultHomeModeProperty().get();
            Path workspace = Path.of(System.getProperty("user.home"));

            // A version chosen from the published list is not on disk yet, so it
            // is downloaded before anything is installed into a profile — there
            // is no profile to install into until the version exists.
            if (DshVersionManager.findInstalled(version) == null) {
                LOG.info("Wizard is downloading DeepSeek Harness " + version + " first");
                DshVersionManager.install(version, line -> LOG.info("[dsh] " + line));
            }

            String appBoot = settings.get(APP_BOOT);
            if (appBoot != null && !appBoot.isBlank() && !appBoot.equals(version)) {
                LOG.info("Instance " + name + " was asked for boot library " + appBoot
                        + " instead of the launcher's own " + version);
                DshVersionManager.overrideAppBoot(version, appBoot);
            }

            DshInstance instance = DshInstanceManager.create(
                    name.trim(), version, DshInstance.DEFAULT_PROFILE,
                    workspace, nodeRuntime, homeMode, null, List.of(), Map.of());

            LOG.info("Wizard created instance " + instance.id() + " (dsh " + version + ")");
            } catch (RuntimeException | DshException stopped) {
                // Whether this was a cancellation is asked of the thread rather
                // than of the exception: the commands wrap an interrupt in a
                // DshException of their own, so the type does not say.
                if (Thread.currentThread().isInterrupted()) {
                // Cancelled from the dialog. The thread waiting on npm is
                // interrupted, which stops the wait and not the program: npm
                // carries on writing into a directory nothing is watching. Stop
                // it, then take the half-written version away, because a version
                // that is half-written cannot run.
                    LOG.info("Install of " + version + " was cancelled");
                    DshCommand.stopRunning();
                    if (version != null && !versionWasPresent) {
                        // The half-written copy, not the installed version:
                        // uninstall looks up an installed version and there is
                        // none, so it would find nothing to remove.
                        DshVersionManager.discardPartial(version);
                    }
                }
                throw stopped;
            }
        })
                .withStage(runtimeStage)
                .withRunAsync(pluginStage, Schedulers.io(), () -> {
                    String version2 = settings.get(VERSION);
                    String name2 = settings.get(NAME);
                    DshInstance instance = DshInstanceManager.find(name2);
                    List<String> specs2 = specsOf(settings.getOrDefault(PRESET_CHOICES, Map.of()));
                    if (instance != null && !specs2.isEmpty()) {
                        DshPluginInstaller.installSpecs(instance, specs2, line -> LOG.info("[install] " + line));
                    }
                })
                .withStage(pluginStage)
                .withStagesHints(runtimeStage, pluginStage)
                .setName(i18n("dsh.install.working"));
    }

    /// The version this wizard is installing, while it is running.
    private volatile @Nullable String installingVersion;

    /// Whether that version was already on disk when the install began.
    private volatile boolean versionWasPresent;

    @Override
    public boolean cancel() {
        // Stop what was started, then take away what it wrote. A cancelled
        // install used to leave npm running and a half-written version behind:
        // the dialog closed and the download carried on.
        DshCommand.stopRunning();

        String version = installingVersion;
        if (version != null && !versionWasPresent) {
            DshVersionManager.discardPartial(version);
        }
        return true;
    }
}
