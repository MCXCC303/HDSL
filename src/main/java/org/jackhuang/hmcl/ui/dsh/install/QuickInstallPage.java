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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.jetbrains.annotations.Nullable;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.DshPreset;
import org.jackhuang.hmcl.dsh.DshPresetCatalog;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.dsh.NodeRuntime;
import org.jackhuang.hmcl.dsh.NodeRuntimeManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineFileChooserButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The second step of the install wizard: name the instance and choose what to
/// install into it.
///
/// This is HMCL's "quick install" page translated to DeepSeek Harness. Where
/// HMCL offers Forge, Fabric and NeoForge, HMCL-DSH offers the plugins from
/// [DshPresetCatalog] — the marketplace first, then the add-ons a new
/// installation most often wants.
@NotNullByDefault
public final class QuickInstallPage extends ScrollPane implements WizardPage {
    /// The wizard controller used to finish.
    private final WizardController controller;

    /// The instance name field.
    private final JFXTextField nameField = new JFXTextField();

    /// The workspace chooser.
    private final LineFileChooserButton workspaceChooser = new LineFileChooserButton();

    /// The home policy selector.
    private final LineSelectButton<DshHomeMode> homeModeSelector = new LineSelectButton<>();

    /// The Node runtime selector.
    private final LineSelectButton<String> nodeSelector = new LineSelectButton<>();

    /// The preset cards, in catalogue order.
    ///
    /// The selection is read straight from the controls rather than mirrored
    /// into a map: a parallel copy can drift from what the user sees.
    private final List<PluginCard> presetCards = new ArrayList<>();

    /// The state shown on the boot library card.
    ///
    /// A property rather than a label the card writes to, because the card is
    /// built before the version it reports on is known to it.
    private final StringProperty appBootStatus = new SimpleStringProperty();

    /// Creates the quick-install page.
    ///
    /// @param controller the wizard controller
    public QuickInstallPage(WizardController controller) {
        this.controller = controller;

        setFitToWidth(true);

        VBox root = new VBox(10);
        root.setPadding(new Insets(20));
        setContent(root);
        FXUtils.smoothScrolling(this);

        Label title = new Label(i18n("dsh.install.step.quick"));
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        // Seed the recommendations before the cards are built, so they render
        // the same state the wizard will install.
        seedDefaultChoices();

        root.getChildren().addAll(title, buildInstanceList(), buildPresetList(), buildFooter());
        VBox.setVgrow(root, Priority.ALWAYS);

        applyDefaults();
    }

    /// Seeds the choice map so recommended plugins start selected.
    ///
    /// The map is the single source of truth the wizard finishes from; the
    /// cards only render it.
    private void seedDefaultChoices() {
        Map<String, String> choices = choices();
        for (DshPreset preset : DshPresetCatalog.builtin()) {
            if (preset.recommended() && !choices.containsKey(preset.id())) {
                choices.put(preset.id(), "");
            }
        }
    }

    /// Builds the instance identity section.
    ///
    /// @return the assembled component list
    private ComponentList buildInstanceList() {
        nameField.setPromptText(i18n("dsh.instance.name"));

        LineTextPane nameRow = new LineTextPane();
        nameRow.setTitle(i18n("dsh.instance.name"));
        nameRow.setTitleTrailing(nameField);

        workspaceChooser.setTitle(i18n("dsh.install.workspace"));
        workspaceChooser.setType(LineFileChooserButton.Type.OPEN_DIRECTORY);
        workspaceChooser.setLocation(System.getProperty("user.home"));

        homeModeSelector.setTitle(i18n("dsh.install.home"));
        homeModeSelector.setItems(List.of(DshHomeMode.values()));
        homeModeSelector.setNullSafeConverter(mode ->
                i18n("dsh.instance.home." + mode.name().toLowerCase(Locale.ROOT)));
        homeModeSelector.setValue(DshHomeMode.ISOLATED);

        ComponentList list = new ComponentList();
        list.getContent().add(nameRow);
        return list;
    }

    /// Returns the version chosen on the page before this one.
    ///
    /// @return the version, or `null` when none was chosen
    private String currentVersion() {
        String version = controller.getSettings().get(DshInstallWizardProvider.VERSION);
        return version == null || version.isBlank() ? null : version;
    }

    /// Builds the card choosing the application boot library.
    ///
    /// It starts on the version that matches the launcher, which is the only
    /// combination that is known to work — the two are published together and a
    /// release whose libraries disagree with it fails at import rather than
    /// degrading. Choosing another is allowed because a user may know something
    /// this launcher does not, but it is a deliberate step with a warning rather
    /// than a default.
    ///
    /// @return the card
    private Node buildAppBootCard() {
        appBootStatus.set(i18n("dsh.install.app_boot.matched", currentAppBoot()));
        InstallerCard card = new InstallerCard(SVG.EXTENSION, i18n("dsh.install.app_boot"),
                appBootStatus.get(), () -> chooseAppBoot());
        card.statusProperty().bind(appBootStatus);
        FXUtils.installFastTooltip(card, i18n("dsh.install.app_boot.hint"));
        return card;
    }

    /// Returns the boot library version that pairs with the chosen launcher.
    ///
    /// @return the version
    private String currentAppBoot() {
        String chosen = controller.getSettings().get(DshInstallWizardProvider.APP_BOOT);
        return chosen == null || chosen.isBlank() ? currentVersion() : chosen;
    }

    /// Offers the published boot library versions.
    ///
    /// Choosing one that is not the launcher's own version is what the warning
    /// is for: the pairing is not a preference, and a mismatch is discovered at
    /// start-up. The warning also states the scope, because the choice belongs
    /// to the installed version rather than to this instance — every instance
    /// running that version shares it.
    ///
    /// @param card the card to update after a choice
    private void chooseAppBoot() {
        List<String> versions = availableAppBootVersions();
        if (versions.isEmpty()) {
            Controllers.dialog(i18n("dsh.install.app_boot.unavailable"),
                    i18n("dsh.install.app_boot"), MessageType.WARNING);
            return;
        }

        LineSelectButton<String> chooser = new LineSelectButton<>();
        chooser.setTitle(i18n("dsh.install.app_boot"));
        chooser.setSubtitle(i18n("dsh.install.app_boot.hint"));
        chooser.setItems(versions);
        chooser.setValue(currentAppBoot());
        // Choosing the launcher's own version is the safe answer, so it is the
        // one already selected; anything else is a departure the warning covers.

        ComponentList list = new ComponentList();
        list.getContent().add(chooser);
        Controllers.dialog(list);

        chooser.valueProperty().addListener((observable, was, chosen) -> {
            if (chosen == null || chosen.isBlank() || chosen.equals(currentVersion())) {
                controller.getSettings().remove(DshInstallWizardProvider.APP_BOOT);
                appBootStatus.set(i18n("dsh.install.app_boot.matched", currentAppBoot()));
                return;
            }
            // The warning says what the choice costs: the pairing is not a
            // preference, and it belongs to the installed version rather than to
            // this instance, so every instance running that version is affected.
            Controllers.dialog(i18n("dsh.install.app_boot.warning", chosen, currentVersion()),
                    i18n("dsh.install.app_boot"), MessageType.WARNING, () -> {
                        controller.getSettings().put(DshInstallWizardProvider.APP_BOOT, chosen);
                        appBootStatus.set(i18n("dsh.install.app_boot.chosen", chosen));
                    });
        });
    }

    /// Lists the published boot library versions.
    ///
    /// The launcher's own version leads, so the safe choice is the first one
    /// offered rather than something to go looking for.
    ///
    /// @return the versions, newest first
    private List<String> availableAppBootVersions() {
        try {
            List<String> versions = new ArrayList<>();
            String launcherVersion = currentVersion();
            if (launcherVersion != null) {
                versions.add(launcherVersion);
            }
            for (String version : DshVersionManager.fetchPackageVersions(DshVersionManager.APP_BOOT_PACKAGE)) {
                if (!versions.contains(version)) {
                    versions.add(version);
                }
            }
            return versions;
        } catch (DshException e) {
            LOG.warning("Failed to list application boot versions", e);
            return List.of();
        }
    }

    /// Builds the card stating which version is being installed.
    ///
    /// @return the card
    private Node buildVersionCard() {
        return new InstallerCard(SVG.DOWNLOAD, i18n("dsh.install.version.card"),
                currentVersion() == null ? i18n("dsh.install.version.none") : currentVersion(),
                null);
    }

    /// Builds one card of the install grid.
    ///
    /// The shape is the original's component card: an icon, the component's
    /// name, and the state of the choice beneath it. A card that opens is given
    /// the arrow the original puts on those, so what can be opened is visible
    /// before the pointer reaches it.
    ///
    /// @param icon    the card's icon
    /// @param title   the component's name
    /// @param status  the state of the choice
    /// @param onOpen  what clicking does, or `null` when the card is a statement
    /// @return the card
    private Node buildCard(SVG icon, String title, String status, @Nullable Runnable onOpen) {
        Label name = new Label(title);
        name.getStyleClass().add("installer-item-name");

        Label value = new Label(status);
        value.getStyleClass().add("installer-item-status");

        VBox card = new VBox(4, icon.createIcon(32), name, value);
        card.getStyleClass().addAll("installer-item-wrapper", "installer-item-card");
        card.setAlignment(Pos.CENTER);

        if (onOpen == null) {
            // A statement rather than a choice: the version was settled on the
            // page before this one, which is the original's arrangement too.
            card.setDisable(true);
            return card;
        }

        Label arrow = new Label("\u2192");
        arrow.getStyleClass().add("installer-item-arrow");
        card.getChildren().add(arrow);
        FXUtils.onClicked(card, onOpen);
        return card;
    }

    /// Builds the quick-install preset section as a grid of cards.
    ///
    /// The grid mirrors HMCL's installer list: one card per thing that can be
    /// installed, showing at a glance whether it will be. A card is toggled by
    /// clicking it, because the description is the only extra information a
    /// plugin has and it does not need a page of its own.
    ///
    /// @return the assembled section
    private VBox buildPresetList() {
        Label header = new Label(i18n("dsh.install.presets"));
        header.setStyle("-fx-font-weight: bold;");
        header.setPadding(new Insets(8, 0, 0, 4));

        FlowPane grid = new FlowPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(4));

        Map<String, String> choices = choices();
        // The version leads the grid, as the game does in the original. It is
        // not a control: HMCL's vanilla card is not one either, because the
        // version was settled on the page before this one.
        grid.getChildren().add(buildVersionCard());

        // The boot library sits beside the version it belongs with. It is not a
        // plugin and is not installed as one: it is a dependency of DeepSeek
        // Harness, and the card states the pairing the manifest will record.
        grid.getChildren().add(buildAppBootCard());

        for (DshPreset preset : DshPresetCatalog.builtin()) {
            PluginCard card = new PluginCard(preset, preset.recommended());
            card.setChosenVersion(choices.get(preset.id()));
            card.setOnConfigure(() -> controller.onNext(new PresetChoicePage(controller, preset)));
            FXUtils.installFastTooltip(card, preset.description());
            presetCards.add(card);
            grid.getChildren().add(card);
        }

        return new VBox(4, header, grid);
    }

    /// Builds the page footer.
    ///
    /// @return the footer
    private HBox buildFooter() {
        JFXButton back = new JFXButton(i18n("button.previous"));
        back.setOnAction(event -> controller.onPrev(false));

        JFXButton finish = new JFXButton(i18n("dsh.install.start"));
        finish.getStyleClass().add("dialog-accept");
        finish.setOnAction(event -> {
            SettingsMap settings = controller.getSettings();
            settings.put(DshInstallWizardProvider.NAME, nameField.getText());
            settings.put(DshInstallWizardProvider.WORKSPACE, workspaceChooser.getLocation());
            settings.put(DshInstallWizardProvider.HOME_MODE, homeModeSelector.getValue());
            settings.put(DshInstallWizardProvider.NODE_RUNTIME, nodeSelector.getValue());
            if (!validate(settings)) {
                return;
            }
            controller.onFinish();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(8, back, spacer, finish);
        footer.setAlignment(Pos.CENTER_RIGHT);
        return footer;
    }

    /// Applies sensible starting values.
    private void applyDefaults() {
        List<String> runtimeOptions = new ArrayList<>();
        runtimeOptions.add(DshNodeRuntime.SYSTEM);
        for (NodeRuntime runtime : NodeRuntimeManager.listInstalled()) {
            runtimeOptions.add(runtime.version());
        }
        nodeSelector.setItems(runtimeOptions);

        nameField.setText(suggestName());
        seedDefaultChoices();
    }

    /// Suggests an unused instance name.
    ///
    /// @return the suggested name
    private String suggestName() {
        for (int i = 1; i < 1000; i++) {
            String candidate = "instance-" + i;
            if (!org.jackhuang.hmcl.dsh.DshInstanceManager.exists(candidate)) {
                return candidate;
            }
        }
        return "instance";
    }

    /// Returns the presets the user ticked.
    ///
    /// @return the selected presets
    /// Checks the collected values before the wizard commits.
    ///
    /// @param settings the collected settings
    /// @return whether the page may finish
    private boolean validate(SettingsMap settings) {
        String name = settings.get(DshInstallWizardProvider.NAME);
        if (name == null || name.isBlank()) {
            org.jackhuang.hmcl.ui.Controllers.dialog(i18n("dsh.instance.name.empty"),
                    i18n("dsh.install.step.quick"),
                    org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING);
            return false;
        }
        if (org.jackhuang.hmcl.dsh.DshInstanceManager.exists(name.trim())) {
            org.jackhuang.hmcl.ui.Controllers.dialog(i18n("dsh.install.name.taken", name.trim()),
                    i18n("dsh.install.step.quick"),
                    org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING);
            return false;
        }
        String workspace = settings.get(DshInstallWizardProvider.WORKSPACE);
        if (workspace == null || workspace.isBlank()) {
            org.jackhuang.hmcl.ui.Controllers.dialog(i18n("dsh.install.workspace.empty"),
                    i18n("dsh.install.step.quick"),
                    org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING);
            return false;
        }
        return true;
    }

    @Override
    public void onNavigate(SettingsMap settings) {
        // Returning from a plugin's choice page must show the new selection.
        Map<String, String> choices = choices();
        for (PluginCard card : presetCards) {
            card.setChosenVersion(choices.get(card.preset().id()));
        }
    }

    /// Returns the wizard's per-preset choice map.
    ///
    /// @return the mutable choice map
    private Map<String, String> choices() {
        return DshInstallWizardProvider.presetChoices(controller.getSettings());
    }

    @Override
    public String getTitle() {
        return i18n("dsh.install.step.quick");
    }
}
