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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckTreeCell;
import com.jfoenix.controls.JFXTreeView;

import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshModpacks;
import org.jackhuang.hmcl.dsh.DshPackForge;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.dsh.DshSession;
import org.jackhuang.hmcl.dsh.DshSessions;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.NoneMultipleSelectionModel;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

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
///
/// The tree is drawn the way the original draws its own: the same view, the same
/// checkbox cell, and the same rule that a row is not selectable — only its box is.
@NotNullByDefault
public final class ModpackFilesPage extends VBox implements WizardPage {
    /// The wizard's settings.
    private final SettingsMap settings;

    /// The boxes for the bundles, so what was unticked can be read when the pack is written.
    private final List<CheckBoxTreeItem<String>> bundleItems = new ArrayList<>();

    /// The box for the conversations.
    private CheckBoxTreeItem<String> sessionsItem;

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
        // which is how the original's boxes behave too. The view, the cell and the selection model
        // are the original's three: without them the boxes are the platform's small square ones
        // rather than the round ones every other page of this launcher draws.
        JFXTreeView<String> tree = new JFXTreeView<>(buildTree());
        tree.setCellFactory(view -> new ModpackFileTreeCell());
        tree.setSelectionModel(new NoneMultipleSelectionModel<>());
        tree.setShowRoot(true);
        VBox.setVgrow(tree, Priority.ALWAYS);
        getChildren().add(tree);

        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        JFXButton write = FXUtils.newRaisedButton(i18n("modpack.export"));
        write.setPrefSize(100, 40);
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
    private ModpackFileTreeItem buildTree() {
        ModpackFileTreeItem root = new ModpackFileTreeItem(instance.id());
        root.setExpanded(true);
        // The root is not ticked by hand: what it says is what its branches say, and that is computed
        // at the end of this method. Setting it here is what made it read "everything travels" while
        // a whole branch was unticked.

        // The plugins, one box each: a bundle somebody unticks is not in the pack and is not
        // recorded as one of its plugins.
        List<String> bundles = List.of();
        try {
            bundles = DshPluginInstaller.readBundles(instance.homeDirectory(), instance.profile());
        } catch (DshException | RuntimeException e) {
            // The page still works without the list; the pack is then simply written whole.
        }
        ModpackFileTreeItem plugins = new ModpackFileTreeItem(i18n("dsh.modpack.files.plugins", bundles.size()));
        plugins.setExpanded(true);
        for (String bundle : bundles) {
            ModpackFileTreeItem item = new ModpackFileTreeItem(bundle);
            item.setSelected(true);
            plugins.getChildren().add(item);
        }
        bundleItems.clear();
        for (TreeItem<String> child : plugins.getChildren()) {
            bundleItems.add((CheckBoxTreeItem<String>) child);
        }
        root.getChildren().add(plugins);

        // The configuration always travels, so its boxes are there to be seen rather than used.
        ModpackFileTreeItem configuration =
                new ModpackFileTreeItem(i18n("dsh.modpack.files.configuration"),
                        i18n("dsh.modpack.files.configuration.detail"));
        for (String name : List.of("package.json", "cordis.patch.yml")) {
            ModpackFileTreeItem item = new ModpackFileTreeItem(name);
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

        sessionsItem = new ModpackFileTreeItem(i18n("dsh.modpack.files.sessions"),
                sessionCount > 0
                        ? i18n("dsh.modpack.files.sessions.count", sessionCount)
                        : i18n("dsh.modpack.files.sessions.none"));
        sessionsItem.setSelected(Boolean.TRUE.equals(settings.get(ModpackExportWizardProvider.SESSIONS))
                && sessionCount > 0);
        sessionsItem.setIndependent(sessionCount == 0);
        CheckBoxTreeItem<String> attachments = new ModpackFileTreeItem(i18n("dsh.modpack.files.attachments"));
        attachments.setSelected(sessionsItem.isSelected());
        sessionsItem.getChildren().add(attachments);
        root.getChildren().add(sessionsItem);

        // And now every branch is told what it is. JavaFX does not do this for us: a child that was
        // already ticked when it was attached never makes its parent recompute, and a branch with one
        // ticked child of two is drawn as fully ticked rather than as partially — which is how the
        // page came to show "plugins" empty with every plugin ticked, and the instance ticked with a
        // branch missing. The marks are what the person reads to know what will travel, so they are
        // ours to keep right, here and on every change under them.
        follow(plugins);
        follow(configuration);
        follow(sessionsItem);
        refreshBranch(root);

        return root;
    }

    /// Makes a branch's box say what the boxes under it say, now and after every change.
    ///
    /// @param branch the branch
    static void follow(CheckBoxTreeItem<String> branch) {
        for (TreeItem<String> child : branch.getChildren()) {
            if (child instanceof CheckBoxTreeItem<String> box) {
                box.selectedProperty().addListener((observable, was, now) -> refreshFrom(box));
                follow(box);
            }
        }
        refreshBranch(branch);
    }

    /// Refreshes a branch and every branch above it.
    ///
    /// @param box the box that changed
    private static void refreshFrom(CheckBoxTreeItem<String> box) {
        if (box.getParent() instanceof CheckBoxTreeItem<String> parent) {
            refreshBranch(parent);
            refreshFrom(parent);
        }
    }

    /// Sets one branch's box from the boxes directly under it.
    ///
    /// Three answers, not two: everything under it travels, nothing does, or something in between —
    /// and the third is the one JavaFX never draws on its own.
    ///
    /// **A partly ticked branch is marked and left otherwise alone.** A branch that is not independent
    /// pushes its own `selected` down onto its children — that is what makes ticking a branch take the
    /// plugins with it, and it is also why clearing `selected` here would untick every child under it.
    /// So the mark is set, and `selected` is only written when the children already agree with it.
    ///
    /// @param branch the branch
    static void refreshBranch(CheckBoxTreeItem<String> branch) {
        int travelling = 0;
        int some = 0;
        int boxes = 0;
        for (TreeItem<String> child : branch.getChildren()) {
            if (child instanceof CheckBoxTreeItem<String> box) {
                boxes++;
                boolean ticked = box.isSelected() || box.isIndeterminate();
                if (ticked) {
                    some++;
                }
                if (box.isSelected() && !box.isIndeterminate()) {
                    travelling++;
                }
            }
        }
        if (boxes == 0) {
            return;
        }
        branch.setIndeterminate(some > 0 && travelling < boxes);
        if (travelling == boxes) {
            branch.setSelected(true);
        } else if (some == 0) {
            branch.setSelected(false);
        }
    }

    @Override
    public void onNavigate(SettingsMap settings) {
        // Nothing to load: what the instance holds was read when the page was built.
    }

    @Override
    public void cleanup(SettingsMap settings) {
        settings.put(ModpackExportWizardProvider.SESSIONS,
                sessionsItem != null && sessionsItem.isSelected());
        Set<String> excluded = new LinkedHashSet<>();
        for (CheckBoxTreeItem<String> item : bundleItems) {
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
                      Consumer<String> report) throws DshException {
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
    /// @param report   receives progress lines
    /// @throws DshException when the pack cannot be written
    static void writePackForge(DshInstance instance, java.nio.file.Path target,
                               DshPackForge.Options options,
                               Consumer<String> report) throws DshException {
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

    /// A row of the tree, which may carry the note shown beside it.
    ///
    /// The note is how the original explains a folder it recognises — the grey line after the name —
    /// and it is read off the row rather than off the cell, because a cell is reused as the tree
    /// scrolls and takes its content from whichever row it is given.
    private static final class ModpackFileTreeItem extends CheckBoxTreeItem<String> {
        /// The localized explanation for this row, or `null` if there is none.
        private final @Nullable String comment;

        /// Creates a row showing only its name.
        ///
        /// @param name what the row says
        ModpackFileTreeItem(String name) {
            this(name, null);
        }

        /// Creates a row showing its name and, after it, an explanation.
        ///
        /// @param name    what the row says
        /// @param comment the explanation, or `null` for none
        ModpackFileTreeItem(String name, @Nullable String comment) {
            super(name);
            this.comment = comment;
        }
    }

    /// Draws a row as the original does: the bound checkbox, then the name, then the note.
    ///
    /// The checkbox comes from [JFXCheckTreeCell], which is the material checkbox this launcher
    /// draws everywhere else and the one the original's export page uses.
    @NotNullByDefault
    private static final class ModpackFileTreeCell extends JFXCheckTreeCell<String> {
        /// Holds the inherited checkbox followed by the labels.
        private final HBox content = new HBox(3);

        /// Shows the current row's name.
        private final Label name = new Label();

        /// Shows the current row's note.
        private final Label comment = new Label();

        /// Creates reusable labels whose mouse events pass through to the cell.
        private ModpackFileTreeCell() {
            name.setMouseTransparent(true);
            comment.setStyle("-fx-text-fill: -monet-on-surface-variant;");
            comment.setMouseTransparent(true);
            content.setAlignment(Pos.CENTER_LEFT);
            content.setPickOnBounds(false);
        }

        /// Refreshes the labels and removes the content when the cell is cleared.
        @Override
        protected void updateDisplay(@Nullable String item, boolean empty) {
            content.getChildren().clear();
            super.updateDisplay(item, empty);

            name.setText(null);
            comment.setText(null);
            if (empty || item == null) {
                return;
            }

            // Setting the text again makes the skin reattach the current graphic to the cell, so it
            // is cleared first and the checkbox graphic is moved into the container instead.
            setText(null);
            @Nullable Node graphic = getGraphic();
            if (graphic != null) {
                content.getChildren().add(graphic);
            }
            name.setText(item);
            content.getChildren().add(name);
            if (getTreeItem() instanceof ModpackFileTreeItem treeItem && treeItem.comment != null) {
                comment.setText(treeItem.comment);
                content.getChildren().add(comment);
            }
            setGraphic(content);
        }
    }
}
