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

import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.instance.DshIsolationPolicy;
import com.mcxcc303.hdsl.instance.InstanceFolders;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.runtime.node.DshNodeRuntime;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.FxToolkit;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.wizard.Navigation;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardDisplayer;
import org.jackhuang.hmcl.util.SettingsMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.junit.jupiter.api.Assertions.*;

class DshInstallWizardIsolationTest {
    private static final String VERSION = "0.1.5";

    @TempDir
    Path folder;

    private Supplier<List<Path>> previousFolders;
    private LauncherSettings launcherSettings;
    private DshIsolationPolicy previousPolicy;
    private DshHomeMode previousHomeMode;
    private String previousNodeRuntime;

    @BeforeEach
    void useTemporaryInstanceFolderAndFreshCreationDefaults() {
        FxToolkit.requireRunning();
        launcherSettings = SettingsManager.settings();
        previousPolicy = launcherSettings.isolationPolicyProperty().get();
        previousHomeMode = launcherSettings.defaultHomeModeProperty().get();
        previousNodeRuntime = launcherSettings.defaultNodeRuntimeProperty().get();
        LauncherSettings fresh = new LauncherSettings();
        launcherSettings.isolationPolicyProperty().set(fresh.isolationPolicyProperty().get());
        launcherSettings.defaultHomeModeProperty().set(fresh.defaultHomeModeProperty().get());
        launcherSettings.defaultNodeRuntimeProperty().set(DshNodeRuntime.SYSTEM);
        previousFolders = InstanceFolders.setFolders(() -> List.of(folder));
    }

    @AfterEach
    void restoreLauncherSettingsAndFolders() {
        if (previousFolders != null) {
            InstanceFolders.setFolders(previousFolders);
        }
        if (launcherSettings != null) {
            launcherSettings.isolationPolicyProperty().set(previousPolicy);
            launcherSettings.defaultHomeModeProperty().set(previousHomeMode);
            launcherSettings.defaultNodeRuntimeProperty().set(previousNodeRuntime);
        }
    }

    @Test
    void twoBareQuickInstallsPersistSeparatePrivateHomes() throws Exception {
        DshInstance a = quickInstall("instance-a", Map.of());
        DshInstance b = quickInstall("instance-b", Map.of());

        assertEquals(DshHomeMode.ISOLATED, a.homeMode());
        assertEquals(DshHomeMode.ISOLATED, b.homeMode());
        assertEquals(folder.resolve("instance-a/home"), a.homeDirectory());
        assertEquals(folder.resolve("instance-b/home"), b.homeDirectory());
        assertTrue(Files.isDirectory(a.homeDirectory()));
        assertTrue(Files.isDirectory(b.homeDirectory()));
        assertEquals(DshHomeMode.ISOLATED, InstanceStore.find(a.id()).homeMode());
        assertEquals(DshHomeMode.ISOLATED, InstanceStore.find(b.id()).homeMode());

        // HOME-resident state stays with A. This verifies storage separation,
        // without installing a real plugin or executing the DSH runtime.
        for (String relative : List.of("profiles/test/package.json", "profiles/test/profile.json",
                "sessions/test/abc/session.v3.jsonl.zstd", "state/test.json")) {
            Path state = a.homeDirectory().resolve(relative);
            Files.createDirectories(state.getParent());
            Files.writeString(state, "instance A's state");
            assertFalse(Files.exists(b.homeDirectory().resolve(relative)), relative);
        }
    }

    @Test
    void quickInstallWithPluginChoicesAlsoUsesPrivateHome() throws Exception {
        assertEquals(DshHomeMode.ISOLATED,
                quickInstall("with-plugins", Map.of("dshmarket", "")).homeMode());
    }

    @Test
    void runtimeHomeDefaultDoesNotOverrideNewInstanceIsolation() throws Exception {
        launcherSettings.defaultHomeModeProperty().set(DshHomeMode.VERSION_SHARED);
        assertEquals(DshHomeMode.ISOLATED, quickInstall("isolated-with-shared-default", Map.of()).homeMode());
        assertEquals(DshHomeMode.VERSION_SHARED, launcherSettings.defaultHomeModeProperty().get());
    }

    @Test
    void explicitlySelectedSharingPoliciesStillWork() throws Exception {
        launcherSettings.isolationPolicyProperty().set(DshIsolationPolicy.NEVER);
        DshInstance shared = quickInstall("explicitly-shared", Map.of());
        DshInstance alsoShared = quickInstall("explicitly-shared-with-plugins", Map.of("dshmarket", ""));
        assertEquals(DshHomeMode.VERSION_SHARED, shared.homeMode());
        assertEquals(shared.homeDirectory(), alsoShared.homeDirectory());

        launcherSettings.isolationPolicyProperty().set(DshIsolationPolicy.WITH_PLUGINS);
        assertEquals(DshHomeMode.VERSION_SHARED, quickInstall("conditional-bare", Map.of()).homeMode());
        assertEquals(DshHomeMode.ISOLATED,
                quickInstall("conditional-with-plugins", Map.of("dshmarket", "")).homeMode());
    }

    @Test
    void creatingAnInstanceLeavesEveryExistingManifestAndResolvedHomeUnchanged() throws Exception {
        List<DshInstance> existing = new ArrayList<>();
        List<byte[]> manifests = new ArrayList<>();
        List<Path> homes = new ArrayList<>();
        for (DshHomeMode mode : DshHomeMode.values()) {
            DshInstance instance = DshInstanceManager.create("existing-" + mode.name().toLowerCase(), VERSION,
                    DshInstance.DEFAULT_PROFILE, folder, DshNodeRuntime.SYSTEM, mode,
                    mode == DshHomeMode.CUSTOM ? folder.resolve("custom-home") : null,
                    List.of(), Map.of(), folder);
            existing.add(instance);
            manifests.add(Files.readAllBytes(instance.instanceDirectory().resolve(InstanceStore.MANIFEST_NAME)));
            homes.add(instance.homeDirectory());
        }

        // Only the creation policy changes; the default followed by GLOBAL instances stays put.
        launcherSettings.isolationPolicyProperty().set(new LauncherSettings().isolationPolicy());
        quickInstall("new-instance", Map.of());

        for (int index = 0; index < existing.size(); index++) {
            DshInstance before = existing.get(index);
            assertArrayEquals(manifests.get(index),
                    Files.readAllBytes(before.instanceDirectory().resolve(InstanceStore.MANIFEST_NAME)), before.id());
            DshInstance after = InstanceStore.find(before.id());
            assertNotNull(after);
            assertEquals(before.homeMode(), after.homeMode(), before.id());
            assertEquals(homes.get(index), after.homeDirectory(), before.id());
        }
    }

    private DshInstance quickInstall(String name, Map<String, String> presetChoices) throws Exception {
        FutureTask<DshInstallWizardProvider.InstallTask> clickInstall = new FutureTask<>(() -> {
            CapturingDisplayer displayer = new CapturingDisplayer();
            WizardController controller = new WizardController(displayer);
            controller.setProvider(new DshInstallWizardProvider(VERSION));
            controller.onStart();
            QuickInstallPage page = assertInstanceOf(QuickInstallPage.class, displayer.page);

            VBox content = (VBox) ((ScrollPane) page.getCenter()).getContent();
            ComponentList identity = (ComponentList) content.getChildren().getFirst();
            LineTextPane nameRow = (LineTextPane) identity.getContent().getFirst();
            TextField nameField = (TextField) ((HBox) nameRow.getTitleTrailing()).getChildren().getFirst();
            nameField.setText(name);
            DshInstallWizardProvider.presetChoices(controller.getSettings()).putAll(presetChoices);

            Button install = ((HBox) page.getBottom()).getChildren().stream()
                    .filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getText().equals(i18n("dsh.install.start")))
                    .findFirst().orElseThrow();
            install.fire();
            assertEquals(name, controller.getSettings().get(DshInstallWizardProvider.NAME));
            assertEquals(VERSION, controller.getSettings().get(DshInstallWizardProvider.VERSION));
            return assertInstanceOf(DshInstallWizardProvider.InstallTask.class, displayer.task);
        });
        Platform.runLater(clickInstall);
        // Execute the exact creation phase used by the returned install task;
        // the subsequent runtime download and plugin install are outside PR1-A.
        return clickInstall.get(30, TimeUnit.SECONDS).createInstance();
    }

    private static final class CapturingDisplayer implements WizardDisplayer {
        private Node page;
        private Task<?> task;

        @Override
        public void navigateTo(Node page, Navigation.NavigationDirection direction) {
            this.page = page;
        }

        @Override
        public void handleTask(SettingsMap settings, Task<?> task) {
            this.task = task;
        }
    }
}
