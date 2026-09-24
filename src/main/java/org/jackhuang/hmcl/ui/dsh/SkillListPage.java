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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXListView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Skin;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshSkill;
import org.jackhuang.hmcl.dsh.DshSkills;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.ListPageBase;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Lists the skill packs installed in an instance's DSH home.
///
/// Modelled on HMCL's resource-pack list, which is the original's answer to the same
/// question: what has been put here, and which of it is in effect. The tick box is that
/// second part — the harness's own frontmatter key, {@code disable-model-invocation},
/// is what hides a skill from the agent, so switching one off writes exactly that line
/// and switching it on takes it away.
///
/// What is listed is what the harness discovers: one level under {@code <dshHome>/skills},
/// either a directory holding a {@code SKILL.md} or a flat {@code .md} file, and nothing
/// else. A pack the user adds from disk is validated against the same frontmatter rules
/// before it is copied, because the harness silently ignores a pack without a name or a
/// description.
@NotNullByDefault
public final class SkillListPage extends ListPageBase<DshSkill> implements Refreshable {
    /// The instance whose skills are listed.
    private final DshInstance instance;

    /// Whether a load is already running.
    private boolean busy;

    /// What the page accepts when the user drops files on it.
    ///
    /// The same two shapes the harness reads: a folder that is a bundle, and a Markdown
    /// file. Both are checked again when the pack is read, so this is only what makes the
    /// cursor say yes while the drag is over the list.
    private static final PathMatcher SKILL_PACKS = path -> Files.isDirectory(path)
            || (path.getFileName() != null && path.getFileName().toString().endsWith(".md"));

    /// Creates the page.
    ///
    /// @param instance the instance whose skills are listed
    public SkillListPage(DshInstance instance) {
        this.instance = instance;
        FXUtils.applyDragListener(this, SKILL_PACKS, this::addFiles);
        refresh();
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new SkillListPageSkin(this);
    }

    @Override
    public void refresh() {
        if (busy) {
            return;
        }
        busy = true;
        setLoading(true);

        CompletableFuture.supplyAsync(() -> {
            try {
                return DshSkills.list(instance.homeDirectory());
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((skills, throwable) -> runInFX(() -> {
            busy = false;
            setLoading(false);
            if (throwable != null) {
                LOG.warning("Failed to read the skill packs", causeOf(throwable));
                setFailedReason(causeOf(throwable).getMessage());
                return;
            }

            List<DshSkill> rows = new ArrayList<>();
            for (DshSkill skill : skills) {
                if (toolbar.accepts(skill.name())) {
                    rows.add(skill);
                }
            }
            getItems().setAll(rows);
        }));
    }

    /// Returns the failure a {@link CompletableFuture} completed with.
    ///
    /// @param throwable what the future reported
    /// @return the cause, when the wrapper has one
    private static Throwable causeOf(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
    }

    /// Switches the given skills off for the agent, or back on.
    ///
    /// A skill already in the asked-for state is left alone, so a mixed batch writes only
    /// the ones that actually move.
    ///
    /// @param skills  the skills to switch
    /// @param enabled whether the agent should be able to invoke them
    private void setEnabled(Collection<DshSkill> skills, boolean enabled) {
        List<DshSkill> targets = skills.stream().filter(skill -> skill.enabled() != enabled).toList();
        if (targets.isEmpty()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                for (DshSkill skill : targets) {
                    DshSkills.setEnabled(skill, enabled);
                }
            } catch (DshException | RuntimeException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
            if (throwable != null) {
                LOG.warning("Failed to switch the skill packs", causeOf(throwable));
                Controllers.dialog(causeOf(throwable).getMessage(),
                        i18n("dsh.instance.skills.toggle_failed"), MessageType.ERROR);
            }
            refresh();
        }));
    }

    /// Removes the given packs after confirmation.
    ///
    /// @param skills the packs to remove
    private void removeSelected(Collection<DshSkill> skills) {
        List<DshSkill> targets = List.copyOf(skills);
        if (targets.isEmpty()) {
            return;
        }
        String message = targets.size() == 1
                ? i18n("dsh.instance.skills.remove.confirm", targets.get(0).name())
                : i18n("button.remove.confirm");
        Controllers.confirm(message, i18n("button.remove"), () -> CompletableFuture.runAsync(() -> {
            try {
                for (DshSkill skill : targets) {
                    DshSkills.remove(skill);
                }
            } catch (DshException | RuntimeException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
            if (throwable != null) {
                LOG.warning("Failed to remove the skill packs", causeOf(throwable));
                Controllers.dialog(i18n("dsh.instance.skills.remove_failed", causeOf(throwable).getMessage()),
                        i18n("message.error"), MessageType.ERROR);
            }
            refresh();
        })), null);
    }

    /// Copies the given packs into the instance, saying which ones would not go.
    ///
    /// @param files the packs the user picked or dropped
    private void addFiles(List<Path> files) {
        if (files.isEmpty()) {
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            List<String> failures = new ArrayList<>();
            for (Path file : files) {
                try {
                    DshSkills.install(instance.homeDirectory(), file);
                } catch (DshException | RuntimeException e) {
                    failures.add(file.getFileName() + ": " + e.getMessage());
                }
            }
            return failures;
        }, Schedulers.io()).whenComplete((failures, throwable) -> runInFX(() -> {
            if (throwable != null) {
                LOG.warning("Failed to add the skill packs", causeOf(throwable));
                Controllers.dialog(causeOf(throwable).getMessage(),
                        i18n("dsh.instance.skills.add_failed", ""), MessageType.ERROR);
            } else if (!failures.isEmpty()) {
                Controllers.dialog(i18n("dsh.instance.skills.add_failed", String.join("; ", failures)),
                        i18n("message.error"), MessageType.ERROR);
            }
            refresh();
        }));
    }

    /// Asks the user for the Markdown packs to add.
    ///
    /// A folder bundle cannot be picked in the same dialog as a file, which is what the
    /// drop target is for: dragging one on is how a bundle is added by hand.
    private void onAdd() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("dsh.instance.skills.add"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("dsh.instance.skills.add.filter"), "*.md"));
        List<java.io.File> chosen = chooser.showOpenMultipleDialog(Controllers.getStage());
        if (chosen != null) {
            addFiles(chosen.stream().map(java.io.File::toPath).toList());
        }
    }

    /// Opens the skills directory, making it first when the instance has none yet.
    private void reveal() {
        try {
            Path directory = DshSkills.directory(instance.homeDirectory());
            Files.createDirectories(directory);
            FXUtils.showFileInExplorer(directory);
        } catch (Exception e) {
            LOG.warning("Failed to open the skills directory", e);
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
        }
    }

    /// Holds the list the page is drawn in, and gives the page the toolbars that act on it.
    ///
    /// The skin owns the list view, and the toolbar has to be built after it exists — the
    /// base skin builds its toolbars before it creates the list — so the skin hands the
    /// list over once it has one.
    ///
    /// @param listView the page's list
    private void attachList(JFXListView<DshSkill> listView) {
        toolbar.setButtons(
                ToolbarListPageSkin.createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, this::refresh),
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.skills.add"), SVG.ADD, this::onAdd),
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.skills.reveal"),
                        SVG.FOLDER_OPEN, this::reveal));

        JFXButton selectAll = ToolbarListPageSkin.createToolbarButton2(
                i18n("button.select_all"), SVG.SELECT_ALL,
                () -> listView.getSelectionModel().selectRange(0, listView.getItems().size()));
        selectingToolbar.getChildren().setAll(
                ToolbarListPageSkin.createToolbarButton2(i18n("button.enable"), SVG.CHECK,
                        () -> setEnabled(listView.getSelectionModel().getSelectedItems(), true)),
                ToolbarListPageSkin.createToolbarButton2(i18n("button.disable"), SVG.CLOSE,
                        () -> setEnabled(listView.getSelectionModel().getSelectedItems(), false)),
                ToolbarListPageSkin.createToolbarButton2(i18n("button.remove"), SVG.DELETE_FOREVER,
                        () -> removeSelected(listView.getSelectionModel().getSelectedItems())),
                selectAll,
                ToolbarListPageSkin.createToolbarButton2(i18n("button.cancel"), SVG.CANCEL,
                        () -> listView.getSelectionModel().clearSelection()));

        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        FXUtils.onChangeAndOperate(listView.getSelectionModel().selectedItemProperty(),
                selected -> showSelectingToolbar(selected != null));
    }

    /// Shows the toolbar that acts on a selection, or the ordinary one.
    ///
    /// @param selecting whether anything is selected
    private void showSelectingToolbar(boolean selecting) {
        selectingToolbar.setVisible(selecting);
        selectingToolbar.setManaged(selecting);
        toolbar.setVisible(!selecting);
        toolbar.setManaged(!selecting);
    }

    /// The page's toolbar, which swaps itself for a search field.
    private final ListSearchBar toolbar = new ListSearchBar(this::refresh);

    /// The toolbar shown while packs are selected.
    private final HBox selectingToolbar = new HBox(8);

    /// The box the two toolbars swap in.
    private final StackPane toolbarPane = new StackPane(toolbar, selectingToolbar);

    /// The page's skin: a toolbar above the skill list, and the placeholder the harness's
    /// own empty state reads as.
    private static final class SkillListPageSkin extends ToolbarListPageSkin<DshSkill, SkillListPage> {
        /// Creates the skin.
        ///
        /// @param control the page
        SkillListPageSkin(SkillListPage control) {
            super(control);
            setPlaceholder(i18n("dsh.skills.empty"));
            // After the base skin has made the list: the selection toolbar acts on it, and
            // it does not exist while the toolbars are being built.
            control.attachList(listView);
        }

        @Override
        protected List<Node> initializeToolbar(SkillListPage page) {
            page.showSelectingToolbar(false);
            page.selectingToolbar.setAlignment(Pos.CENTER_LEFT);
            return List.of(page.toolbarPane);
        }

        @Override
        protected ListCell<DshSkill> createListCell(JFXListView<DshSkill> listView) {
            return new SkillItemCell(listView, getSkinnable());
        }
    }

    /// One row of the skill list.
    ///
    /// The shape is HMCL's resource-pack row: a tick box, the pack's mark, the name with
    /// its description beneath, and the per-row actions on the trailing edge.
    private static final class SkillItemCell extends MDListCell<DshSkill> {
        /// The page whose actions the row's buttons run.
        private final SkillListPage page;

        /// The tick box reporting whether the agent can invoke the skill.
        private final JFXCheckBox active = new JFXCheckBox();

        /// The pack's mark. A skill has no icon of its own, so this is the mark every pack
        /// shares.
        private final ImageContainer icon = new ImageContainer(32);

        /// The name and description line.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The button that opens the pack in a file manager.
        private final JFXButton reveal = FXUtils.newToggleButton4(SVG.FOLDER);

        /// The button that removes the pack.
        private final JFXButton remove = FXUtils.newToggleButton4(SVG.DELETE);

        /// Creates the cell.
        ///
        /// @param listView the owning list
        /// @param page     the page the row's actions belong to
        SkillItemCell(JFXListView<DshSkill> listView, SkillListPage page) {
            super(listView);
            this.page = page;

            HBox container = new HBox(8);
            container.setPickOnBounds(false);
            container.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(content, Priority.ALWAYS);
            content.setMouseTransparent(true);
            setSelectable();

            icon.setImage(org.jackhuang.hmcl.dsh.DshInstanceIcon.COMMAND.load());
            FXUtils.installFastTooltip(reveal, i18n("reveal.in_file_manager"));
            FXUtils.installFastTooltip(remove, i18n("button.remove"));

            active.setOnAction(event -> {
                DshSkill skill = getItem();
                if (skill != null) {
                    page.setEnabled(List.of(skill), active.isSelected());
                }
            });

            container.getChildren().setAll(active, icon, content, reveal, remove);
            StackPane.setMargin(container, new Insets(8));
            getContainer().getChildren().setAll(container);
        }

        @Override
        protected void updateControl(@Nullable DshSkill skill, boolean empty) {
            if (empty || skill == null) {
                return;
            }

            active.setSelected(skill.enabled());
            FXUtils.installFastTooltip(active, skill.enabled()
                    ? i18n("dsh.instance.skills.toggle.off")
                    : i18n("dsh.instance.skills.toggle.on"));

            content.setTitle(skill.name());
            content.setSubtitle(skill.description().isEmpty() ? skill.fileName() : skill.description());
            content.getTags().clear();
            if (!skill.enabled()) {
                content.addTagWarning(i18n("dsh.instance.skills.disabled"));
            }

            reveal.setOnAction(event -> FXUtils.showFileInExplorer(skill.entry()));
            remove.setOnAction(event -> page.removeSelected(List.of(skill)));
        }
    }
}
