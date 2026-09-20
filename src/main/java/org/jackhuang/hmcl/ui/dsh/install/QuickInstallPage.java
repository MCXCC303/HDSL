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
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.DshPreset;
import org.jackhuang.hmcl.dsh.DshPresetCatalog;
import org.jackhuang.hmcl.dsh.NodeRuntime;
import org.jackhuang.hmcl.dsh.NodeRuntimeManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineFileChooserButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
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

        root.getChildren().addAll(title, buildInstanceList(), buildRuntimeList(), buildPresetList(), buildFooter());
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
        list.getContent().addAll(nameRow, workspaceChooser, homeModeSelector);
        return list;
    }

    /// Builds the Node runtime section.
    ///
    /// @return the assembled component list
    private ComponentList buildRuntimeList() {
        nodeSelector.setTitle(i18n("dsh.node.title"));
        nodeSelector.setNullSafeConverter(selection -> DshNodeRuntime.SYSTEM.equals(selection)
                ? i18n("dsh.install.node.system")
                : selection);
        nodeSelector.setValue(DshNodeRuntime.SYSTEM);

        ComponentList list = new ComponentList();
        list.getContent().add(nodeSelector);
        return list;
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
