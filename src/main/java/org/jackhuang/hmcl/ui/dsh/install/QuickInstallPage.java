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


    /// The home policy selector.


    /// The Node runtime selector.


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
        // The original's install pages pad their root by sixteen.
        root.setPadding(new Insets(16));
        setContent(root);
        FXUtils.smoothScrolling(this);

        // Seed the recommendations before the cards are built, so they render
        // the same state the wizard will install.
        seedDefaultChoices();

        // No in-page heading: the window's title bar names the step.
        root.getChildren().addAll(buildInstanceList(), buildPresetList(), buildFooter());
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
    /// Opens the boot library chooser.
    ///
    /// The chooser is a page of its own, as the original's is, rather than a
    /// dropdown in a dialog: a dropdown cannot be left without choosing, has
    /// nowhere to put a list longer than the screen, and gives the versions no
    /// room to say what they are.
    private void chooseAppBoot() {
        Controllers.getDecorator().startWizard(
                new AppBootWizardProvider(currentVersion(), chosen -> {
                    if (chosen == null) {
                        controller.getSettings().remove(DshInstallWizardProvider.APP_BOOT);
                        appBootStatus.set(i18n("dsh.install.app_boot.matched", currentAppBoot()));
                    } else {
                        controller.getSettings().put(DshInstallWizardProvider.APP_BOOT, chosen);
                        appBootStatus.set(i18n("dsh.install.app_boot.chosen", chosen));
                    }
                }),
                // The wizard's category names the task, its page names the step,
                // as the original's "安装新游戏 - 选择 Fabric API 版本" does.
                i18n("dsh.instance.create"));
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
    /// Builds the quick-install preset section as a grid of cards.
    ///
    /// The grid mirrors HMCL's installer list: one card per thing that can be
    /// installed, showing at a glance whether it will be. A card is toggled by
    /// clicking it, because the description is the only extra information a
    /// plugin has and it does not need a page of its own.
    ///
    /// @return the assembled section
    private VBox buildPresetList() {
        // The original's section heading, not a label styled to look like one:
        // the class carries the size, colour and padding the interface uses for
        // a heading, and a hand-styled label matches none of them exactly.
        Node header = ComponentList.createComponentListTitle(i18n("dsh.install.presets"));

        // Cards sit twelve apart with nothing around the grid; the surrounding
        // spacing is the page's, not the grid's.
        FlowPane grid = new FlowPane();
        grid.setHgap(12);
        grid.setVgap(12);

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
            // The cross on the card is how a plugin is left out; the chooser's
            // list holds versions and nothing else.
            card.setOnClear(() -> {
                choices.remove(preset.id());
                card.setChosenVersion(null);
            });
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

        // The original's install button: raised, and sized rather than left to
        // its text.
        JFXButton finish = FXUtils.newRaisedButton(i18n("dsh.install.start"));
        finish.setPrefWidth(100);
        finish.setPrefHeight(40);
        finish.setOnAction(event -> {
            // Only what the page still asks for. The environment comes from the
            // launcher's settings, and the working directory from the user's
            // home; writing them here from controls that are no longer on the
            // page would record values nobody chose.
            SettingsMap settings = controller.getSettings();
            settings.put(DshInstallWizardProvider.NAME, nameField.getText());
            if (!validate(settings)) {
                return;
            }
            controller.onFinish();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(8, back, spacer, finish);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(0, 10, 10, 10));
        return footer;
    }

    /// Applies sensible starting values.
    private void applyDefaults() {
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
