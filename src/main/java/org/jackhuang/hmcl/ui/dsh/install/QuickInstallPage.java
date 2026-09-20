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
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
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

    /// The preset toggles, in catalogue order.
    ///
    /// The selection is read straight from the controls rather than mirrored
    /// into a map: a parallel copy can drift from what the user sees.
    private final List<PresetToggle> presetToggles = new ArrayList<>();

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

        root.getChildren().addAll(title, buildInstanceList(), buildRuntimeList(), buildPresetList(), buildFooter());
        VBox.setVgrow(root, Priority.ALWAYS);

        applyDefaults();
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
        list.getContent().addAll(nameRow, workspaceChooser, buildHomeWarning(), homeModeSelector);
        return list;
    }

    /// Builds the advisory shown under the home-policy selector.
    ///
    /// @return the warning row
    private LineTextPane buildHomeWarning() {
        LineTextPane warning = new LineTextPane();
        warning.setText(i18n("dsh.install.home.warning"));
        return warning;
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

    /// Builds the quick-install preset section.
    ///
    /// @return the assembled component list
    private ComponentList buildPresetList() {
        ComponentList list = new ComponentList();

        LineTextPane header = new LineTextPane();
        header.setTitle(i18n("dsh.install.presets"));
        header.getStyleClass().add("section-header");
        list.getContent().add(header);

        for (DshPreset preset : DshPresetCatalog.builtin()) {
            LineToggleButton toggle = new LineToggleButton();
            toggle.setTitle(preset.name());
            toggle.setSubtitle(preset.description());
            toggle.setSelected(preset.recommended());
            presetToggles.add(new PresetToggle(preset, toggle));
            list.getContent().add(toggle);
        }
        return list;
    }

    /// Builds the page footer.
    ///
    /// @return the footer
    private HBox buildFooter() {
        JFXButton back = new JFXButton(i18n("dsh.install.back"));
        back.setOnAction(event -> controller.onPrev(false));

        JFXButton finish = new JFXButton(i18n("dsh.install.start"));
        finish.getStyleClass().add("dialog-accept");
        finish.setOnAction(event -> {
            SettingsMap settings = controller.getSettings();
            settings.put(DshInstallWizardProvider.NAME, nameField.getText());
            settings.put(DshInstallWizardProvider.WORKSPACE, workspaceChooser.getLocation());
            settings.put(DshInstallWizardProvider.HOME_MODE, homeModeSelector.getValue());
            settings.put(DshInstallWizardProvider.NODE_RUNTIME, nodeSelector.getValue());
            settings.put(DshInstallWizardProvider.PRESETS, selectedPresets());
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
    private List<DshPreset> selectedPresets() {
        List<DshPreset> selected = new ArrayList<>();
        for (PresetToggle entry : presetToggles) {
            if (entry.toggle().isSelected()) {
                selected.add(entry.preset());
            }
        }
        return selected;
    }

    /// Pairs a catalogue entry with the control that selects it.
    ///
    /// @param preset the catalogue entry
    /// @param toggle the control
    private record PresetToggle(DshPreset preset, LineToggleButton toggle) {
    }

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
    public String getTitle() {
        return i18n("dsh.install.step.quick");
    }
}
