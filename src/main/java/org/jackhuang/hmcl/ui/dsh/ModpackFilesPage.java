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
package org.jackhuang.hmcl.ui.dsh;

import java.nio.file.Files;

import org.jackhuang.hmcl.dsh.DshPackForge;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;

import org.jackhuang.hmcl.dsh.DshModpacks;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshSession;
import org.jackhuang.hmcl.dsh.DshSessions;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LinePane;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Asks what of the instance goes into the pack.
///
/// The original's step, which offers a tree of what a game instance holds — mods,
/// worlds, settings — and lets each be left out. What an instance of DeepSeek
/// Harness holds is a configuration and a history: the configuration is what makes
/// the pack a pack and always goes, and the history is the conversations, which is
/// the one thing worth asking about. A pack of a hundred conversations is a
/// hundred megabytes and one of none is a kilobyte, and which of those somebody
/// wants depends on whether they are handing over an environment or a workspace.
@NotNullByDefault
public final class ModpackFilesPage extends VBox implements WizardPage {
    /// The wizard's settings.
    private final SettingsMap settings;

    /// The boxes for the bundles, so what was unticked can be read when the pack is written.
    private final List<javafx.scene.control.CheckBoxTreeItem<String>> bundleItems = new java.util.ArrayList<>();

    /// The box for the conversations.
    private javafx.scene.control.CheckBoxTreeItem<String> sessionsItem;

    /// How many conversations the instance has.
    private int sessionCount;

    /// The wizard this page belongs to.
    private final WizardController controller;

    /// The instance being written out.
    private final DshInstance instance;

    /// Creates the page.
    ///
    /// @param controller the wizard controller
    /// @param instance   the instance being written out
    /// @param settings   the wizard's settings
    public ModpackFilesPage(WizardController controller, DshInstance instance, SettingsMap settings) {
        this.controller = controller;
        this.instance = instance;
        this.settings = settings;

        setSpacing(10);
        setPadding(new Insets(10));

        Label title = new Label(i18n("dsh.modpack.files.title"));
        getChildren().add(title);

        // A tree, as the original draws this step: the instance at the root, what it holds beneath
        // it, and a box on everything that can travel. Ticking a branch takes its children with it,
        // which is how the original's boxes behave too.
        javafx.scene.control.TreeView<String> tree = new javafx.scene.control.TreeView<>(buildTree());
        tree.setShowRoot(true);
        tree.setCellFactory(view -> new javafx.scene.control.cell.CheckBoxTreeCell<>() {
            @Override
            public void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
            }
        });
        javafx.scene.layout.VBox.setVgrow(tree, javafx.scene.layout.Priority.ALWAYS);
        getChildren().add(tree);

        javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(8);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        com.jfoenix.controls.JFXButton write = new com.jfoenix.controls.JFXButton(i18n("modpack.export"));
        write.getStyleClass().add("jfx-button-raised");
        write.setOnAction(event -> {
            cleanup(settings);
            controller.onFinish();
        });
        buttons.getChildren().add(write);
        getChildren().add(buttons);
    }

    /// Builds the tree of what the pack can carry.
    ///
    /// @return the root
    private javafx.scene.control.CheckBoxTreeItem<String> buildTree() {
        javafx.scene.control.CheckBoxTreeItem<String> root =
                new javafx.scene.control.CheckBoxTreeItem<>(instance.id());
        root.setExpanded(true);
        root.setSelected(true);

        // The plugins, one box each: a bundle somebody unticks is not in the pack and is not
        // recorded as one of its plugins.
        List<String> bundles = List.of();
        try {
            bundles = DshPluginInstaller.readBundles(instance.homeDirectory(), instance.profile());
        } catch (DshException | RuntimeException e) {
            // The page still works without the list; the pack is then simply written whole.
        }
        javafx.scene.control.CheckBoxTreeItem<String> plugins = new javafx.scene.control.CheckBoxTreeItem<>(
                i18n("dsh.modpack.files.plugins", bundles.size()));
        plugins.setExpanded(true);
        for (String bundle : bundles) {
            javafx.scene.control.CheckBoxTreeItem<String> item =
                    new javafx.scene.control.CheckBoxTreeItem<>(bundle);
            item.setSelected(true);
            plugins.getChildren().add(item);
        }
        bundleItems.clear();
        for (javafx.scene.control.TreeItem<String> child : plugins.getChildren()) {
            bundleItems.add((javafx.scene.control.CheckBoxTreeItem<String>) child);
        }
        root.getChildren().add(plugins);

        // The configuration always travels, so its boxes are there to be seen rather than used.
        javafx.scene.control.CheckBoxTreeItem<String> configuration =
                new javafx.scene.control.CheckBoxTreeItem<>(i18n("dsh.modpack.files.configuration"));
        configuration.setSelected(true);
        for (String name : List.of("package.json", "cordis.patch.yml")) {
            javafx.scene.control.CheckBoxTreeItem<String> item =
                    new javafx.scene.control.CheckBoxTreeItem<>(name);
            item.setSelected(true);
                configuration.getChildren().add(item);
        }
        root.getChildren().add(configuration);

        // The conversations, and the attachments that go with them.
        List<DshSession> sessions;
        try {
            sessions = DshSessions.list(instance.homeDirectory());
        } catch (DshException e) {
            sessions = List.of();
        }
        sessionCount = sessions.size();

        sessionsItem = new javafx.scene.control.CheckBoxTreeItem<>(i18n("dsh.modpack.files.sessions"));
        sessionsItem.setSelected(Boolean.TRUE.equals(settings.get(ModpackExportWizardProvider.SESSIONS))
                && sessionCount > 0);
        sessionsItem.setIndependent(sessionCount == 0);
        javafx.scene.control.CheckBoxTreeItem<String> attachments =
                new javafx.scene.control.CheckBoxTreeItem<>(i18n("dsh.modpack.files.attachments"));
        attachments.setSelected(sessionsItem.isSelected());
        sessionsItem.getChildren().add(attachments);
        root.getChildren().add(sessionsItem);

        return root;
    }

    @Override
    public void onNavigate(SettingsMap settings) {
        // Nothing to load: what the instance holds was read when the page was built.
    }

    @Override
    public void cleanup(SettingsMap settings) {
        settings.put(ModpackExportWizardProvider.SESSIONS,
                sessionsItem != null && sessionsItem.isSelected());
        java.util.Set<String> excluded = new java.util.LinkedHashSet<>();
        for (javafx.scene.control.CheckBoxTreeItem<String> item : bundleItems) {
            if (!item.isSelected()) {
                excluded.add(item.getValue());
            }
        }
        settings.put(ModpackExportWizardProvider.EXCLUDED_BUNDLES, excluded);
    }

    @Override
    public String getTitle() {
        return i18n("dsh.modpack.files");
    }


    /// Writes a pack, reporting what it is doing.
    ///
    /// The dialog the person watches is the wizard's own: this returns the work, and the wizard shows
    /// it — which is also what puts the completion message at the end of it. Wrapping it in a dialog
    /// here would put two of them on the screen, and the one that closed last would be the silent one.
    ///
    /// @param instance the instance to write out
    /// @param target   where to write it
    /// @param options  what it should say about itself
    /// @param report   receives progress lines
    /// @throws DshException when the pack cannot be written
    static void write(DshInstance instance, java.nio.file.Path target, DshModpacks.Options options,
                      java.util.function.Consumer<String> report) throws DshException {
        DshModpacks.export(instance, target, options, report);
    }

    /// Writes a pack the community's tooling reads, and the digest beside it.
    ///
    /// A publisher writes the digest next to the pack, which is what the community's index and its
    /// installers check against; writing it here means an exported pack is publishable without
    /// another step.
    ///
    /// @param instance the instance to write out
    /// @param target   where to write it
    /// @param options  what it should say about itself
    /// @param instance the instance to write out
    /// @param target   where to write it
    /// @param options  what it should say about itself
    /// @param report   receives progress lines
    /// @throws DshException when the pack cannot be written
    static void writePackForge(DshInstance instance, java.nio.file.Path target,
                               DshPackForge.Options options,
                               java.util.function.Consumer<String> report) throws DshException {
        DshPackForge.Result result = DshPackForge.export(instance, target, options, report);
        try {
            Files.writeString(target.resolveSibling(target.getFileName() + ".sha256"),
                    result.sha256() + "  " + target.getFileName() + "\n");
            report.accept("Wrote " + target.getFileName() + ".sha256");
        } catch (java.io.IOException e) {
            // The pack is written and is usable; only its digest is missing, and saying so beats
            // failing an export that succeeded.
            report.accept("The pack was written, but its digest was not: " + e.getMessage());
        }
    }
}
