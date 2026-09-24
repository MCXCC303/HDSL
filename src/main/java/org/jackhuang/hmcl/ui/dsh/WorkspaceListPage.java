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
import javafx.scene.control.Skin;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshSession;
import org.jackhuang.hmcl.dsh.DshSessions;
import org.jackhuang.hmcl.dsh.DshWorkspace;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.ListPageBase;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Lists the workspaces an instance's sessions were recorded in.
///
/// DeepSeek Harness groups its history by workspace, and the sessions themselves
/// live under `sessions/<workspace-slug>/`, so a workspace is a directory rather
/// than a label. This page is the session manager's front door: click one to see
/// its conversations, or tick several and export them together as one pack.
///
/// Clicking a row opens it, which is the one thing a person comes here to do, so
/// the multi-select is a tick box instead: the list's own selection would have
/// nothing left to mean once a click navigates away.
@NotNullByDefault
public final class WorkspaceListPage extends ListPageBase<DshWorkspace> implements Refreshable {
    /// Formats a workspace's last activity the way a person reads it.
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /// The instance whose workspaces are listed.
    private final DshInstance instance;

    /// Whether a load is already running.
    private boolean busy;

    /// The slugs ticked for a batch export, kept across refreshes.
    private final Set<String> selected = new LinkedHashSet<>();

    /// The ordinary toolbar.
    private final HBox toolbar = new HBox(8);

    /// The toolbar shown while workspaces are ticked.
    private final HBox selectingToolbar = new HBox(8);

    /// The box the two toolbars swap in.
    private final StackPane toolbarPane = new StackPane(toolbar, selectingToolbar);

    /// Creates the page.
    ///
    /// @param instance the instance whose workspaces are listed
    public WorkspaceListPage(DshInstance instance) {
        this.instance = instance;
        refresh();
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new WorkspaceListPageSkin(this);
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
                return DshSessions.workspaces(instance.homeDirectory());
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((workspaces, throwable) -> runInFX(() -> {
            busy = false;
            setLoading(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause() : throwable;
                LOG.warning("Failed to list workspaces", cause);
                setFailedReason(cause.getMessage());
                return;
            }
            getItems().setAll(workspaces);
            // A workspace that is gone cannot stay ticked, or the export would
            // silently write nothing for it.
            selected.retainAll(workspaces.stream().map(DshWorkspace::slug).toList());
            showSelectingToolbar(!selected.isEmpty());
        }));
    }

    /// Describes a workspace's path, sessions and last activity.
    ///
    /// @param workspace the workspace
    /// @return the text
    private static String describe(DshWorkspace workspace) {
        StringBuilder builder = new StringBuilder();
        if (workspace.path() != null) {
            builder.append(workspace.path()).append(" · ");
        }
        builder.append(i18n("dsh.workspace.sessions", workspace.sessions().size()));
        builder.append(" · ").append(DshSessions.formatSize(workspace.sizeBytes()));
        if (workspace.modifiedAt() > 0) {
            builder.append(" · ").append(TIMESTAMP.format(Instant.ofEpochMilli(workspace.modifiedAt())));
        }
        return builder.toString();
    }

    /// Holds the list and gives the page the toolbar that acts on a tick box.
    ///
    /// @param listView the page's list
    private void attachList(JFXListView<DshWorkspace> listView) {
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getChildren().setAll(
                ToolbarListPageSkin.createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, this::refresh),
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.session.import"), SVG.DOWNLOAD,
                        () -> SessionPackActions.importFromSystem(instance, this::refresh)),
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.session.pack.import"), SVG.FILE_OPEN,
                        () -> SessionPackActions.importPack(instance, this::refresh)));

        selectingToolbar.setAlignment(Pos.CENTER_LEFT);
        selectingToolbar.getChildren().setAll(
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.session.pack.export.workspaces"), SVG.ARCHIVE,
                        this::exportSelected),
                ToolbarListPageSkin.createToolbarButton2(i18n("button.cancel"), SVG.CANCEL, () -> {
                    selected.clear();
                    listView.refresh();
                    showSelectingToolbar(false);
                }));

        showSelectingToolbar(!selected.isEmpty());
    }

    /// Shows the toolbar that acts on a tick, or the ordinary one.
    ///
    /// @param selecting whether anything is ticked
    private void showSelectingToolbar(boolean selecting) {
        selectingToolbar.setVisible(selecting);
        selectingToolbar.setManaged(selecting);
        toolbar.setVisible(!selecting);
        toolbar.setManaged(!selecting);
    }

    /// Ticks or unticks a workspace.
    ///
    /// @param workspace the workspace
    /// @param ticked    whether it is ticked
    void setTicked(DshWorkspace workspace, boolean ticked) {
        if (ticked) {
            selected.add(workspace.slug());
        } else {
            selected.remove(workspace.slug());
        }
        showSelectingToolbar(!selected.isEmpty());
    }

    /// Returns whether a workspace is ticked.
    ///
    /// @param workspace the workspace
    /// @return whether it is ticked
    boolean isTicked(DshWorkspace workspace) {
        return selected.contains(workspace.slug());
    }

    /// Opens a workspace's sessions.
    ///
    /// @param workspace the workspace
    private void open(DshWorkspace workspace) {
        Controllers.navigate(new SessionListPage(instance, workspace));
    }

    /// Exports every session of every ticked workspace as one pack.
    private void exportSelected() {
        List<DshSession> sessions = new ArrayList<>();
        for (DshWorkspace workspace : getItems()) {
            if (selected.contains(workspace.slug())) {
                sessions.addAll(workspace.sessions());
            }
        }
        SessionPackActions.export(instance, sessions);
    }

    /// The page's skin: a toolbar above the workspace list.
    private static final class WorkspaceListPageSkin extends ToolbarListPageSkin<DshWorkspace, WorkspaceListPage> {
        /// Creates the skin.
        ///
        /// @param control the page
        WorkspaceListPageSkin(WorkspaceListPage control) {
            super(control);
            setPlaceholder(i18n("dsh.workspaces.empty"));
            control.attachList(listView);
        }

        @Override
        protected List<Node> initializeToolbar(WorkspaceListPage page) {
            page.showSelectingToolbar(false);
            page.toolbar.setAlignment(Pos.CENTER_LEFT);
            return List.of(page.toolbarPane);
        }

        @Override
        protected ListCell<DshWorkspace> createListCell(JFXListView<DshWorkspace> listView) {
            return new WorkspaceListCell(listView, getSkinnable());
        }
    }

    /// One row of the workspace list.
    ///
    /// A tick box for the batch export, the workspace's own name and path, and an
    /// open button. Clicking the row opens it: that is the action a person comes
    /// here to take, so it is the click rather than a button on the edge.
    private static final class WorkspaceListCell extends MDListCell<DshWorkspace> {
        /// The page whose actions the row runs.
        private final WorkspaceListPage page;

        /// The tick box that adds the workspace to a batch export.
        private final JFXCheckBox tick = new JFXCheckBox();

        /// The workspace icon.
        private final ImageContainer icon = new ImageContainer(32);

        /// The name and its path and counts.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The button that opens the workspace's sessions.
        private final JFXButton open = FXUtils.newToggleButton4(SVG.ARROW_FORWARD);

        /// Creates the cell.
        ///
        /// @param listView the owning list
        /// @param page     the page the row belongs to
        WorkspaceListCell(JFXListView<DshWorkspace> listView, WorkspaceListPage page) {
            super(listView);
            this.page = page;

            HBox container = new HBox(8);
            container.setPickOnBounds(false);
            container.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(content, Priority.ALWAYS);
            content.setMouseTransparent(true);

            icon.setImage(org.jackhuang.hmcl.dsh.DshInstanceIcon.COMMAND.load());
            FXUtils.installFastTooltip(open, i18n("dsh.workspace.open"));

            tick.setOnAction(event -> {
                DshWorkspace workspace = getItem();
                if (workspace != null) {
                    page.setTicked(workspace, tick.isSelected());
                }
            });
            open.setOnAction(event -> {
                DshWorkspace workspace = getItem();
                if (workspace != null) {
                    page.open(workspace);
                }
            });

            // The tick and the button act for themselves; anywhere else opens the
            // workspace, so one click does the thing the page is for.
            setOnMouseClicked(event -> {
                DshWorkspace workspace = getItem();
                if (workspace == null || event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                Node target = event.getTarget() instanceof Node node ? node : null;
                if (target != null && (isWithin(target, tick) || isWithin(target, open))) {
                    return;
                }
                page.open(workspace);
            });

            StackPane left = new StackPane(icon);
            left.setPadding(new Insets(0, 8, 0, 0));
            container.getChildren().setAll(tick, left, content, open);
            StackPane.setMargin(container, new Insets(8));
            getContainer().getChildren().setAll(container);
        }

        /// Reports whether a node lies inside another.
        ///
        /// @param node     the node to walk up from
        /// @param ancestor the ancestor to look for
        /// @return whether the node is the ancestor or one of its descendants
        private static boolean isWithin(Node node, Node ancestor) {
            for (Node current = node; current != null; current = current.getParent()) {
                if (current == ancestor) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void updateControl(@Nullable DshWorkspace workspace, boolean empty) {
            if (empty || workspace == null) {
                return;
            }

            tick.setSelected(page.isTicked(workspace));
            content.setTitle(workspace.title());
            content.setSubtitle(describe(workspace));
            content.getTags().clear();
        }
    }
}
