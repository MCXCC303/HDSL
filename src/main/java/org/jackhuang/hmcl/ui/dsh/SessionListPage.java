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
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXPopup;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Skin;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceIcons;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshSession;
import org.jackhuang.hmcl.dsh.DshSessions;
import org.jackhuang.hmcl.dsh.DshWorkspace;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.ListPageBase;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.IconedMenuItem;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.MenuSeparator;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.PopupMenu;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Lists one workspace's sessions and moves them between instances.
///
/// Modelled on HMCL's world list, which answers the same shape of question: a
/// list of things belonging to one instance, each with an identity, a when, and
/// a small set of things you can do to it. The row is that original's — a
/// thumbnail-sized icon, a two-line label with a tag, and the actions on the
/// trailing edge — because the launcher's job here is to show what DeepSeek
/// Harness persisted without reading it.
///
/// The page is one **workspace**, not the whole instance: the sessions are filed
/// by the directory they were recorded in, so a workspace is the natural unit to
/// look at, export, or move. The whole instance is the [WorkspaceListPage] one
/// step back.
@NotNullByDefault
public final class SessionListPage extends ListPageBase<DshSession> implements Refreshable {
    /// Formats a session's last activity the way a person reads it.
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /// The instance whose sessions are listed.
    private final DshInstance instance;

    /// The workspace being looked at.
    private final DshWorkspace workspace;

    /// Whether migrating also removes the session here.
    private boolean moveAfterMigrating = true;

    /// Whether a load is already running.
    private boolean busy;

    /// The ordinary toolbar.
    private final HBox toolbar = new HBox(8);

    /// The toolbar shown while sessions are selected.
    private final HBox selectingToolbar = new HBox(8);

    /// The box the two toolbars swap in.
    private final StackPane toolbarPane = new StackPane(toolbar, selectingToolbar);

    /// Creates the page.
    ///
    /// @param instance  the instance whose sessions are listed
    /// @param workspace the workspace whose sessions are listed
    public SessionListPage(DshInstance instance, DshWorkspace workspace) {
        this.instance = instance;
        this.workspace = workspace;
        refresh();
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new SessionListPageSkin(this);
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
                List<DshSession> sessions = new ArrayList<>();
                for (DshSession session : DshSessions.list(instance.homeDirectory())) {
                    if (session.workspaceSlug().equals(workspace.slug())) {
                        sessions.add(session);
                    }
                }
                return sessions;
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((sessions, throwable) -> runInFX(() -> {
            busy = false;
            setLoading(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause() : throwable;
                LOG.warning("Failed to list sessions", cause);
                setFailedReason(cause.getMessage());
                return;
            }
            getItems().setAll(sessions);
        }));
    }

    /// Describes a session's last activity and working directory.
    ///
    /// @param session the session
    /// @return the text
    private static String describe(DshSession session) {
        StringBuilder builder = new StringBuilder();
        builder.append(session.modifiedAt() > 0
                ? TIMESTAMP.format(Instant.ofEpochMilli(session.modifiedAt()))
                : i18n("dsh.session.unknown_time"));
        builder.append(" · ").append(DshSessions.formatSize(session.sizeBytes()));
        if (session.workingDirectory() != null) {
            builder.append(" · ").append(session.workingDirectory());
        }
        return builder.toString();
    }

    /// Holds the list and gives the page the toolbar that acts on a selection.
    ///
    /// The skin owns the list view, and the selection toolbar acts on it, so the
    /// skin hands it over once it exists rather than the page reaching for a list
    /// that is not built yet.
    ///
    /// @param listView the page's list
    private void attachList(JFXListView<DshSession> listView) {
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getChildren().setAll(
                ToolbarListPageSkin.createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, this::refresh),
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.session.pack.export.project"), SVG.ARCHIVE,
                        () -> SessionPackActions.export(instance, new ArrayList<>(getItems()))),
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.session.pack.import"), SVG.FILE_OPEN,
                        () -> SessionPackActions.importPack(instance, this::refresh)));

        JFXButton selectAll = ToolbarListPageSkin.createToolbarButton2(
                i18n("button.select_all"), SVG.SELECT_ALL,
                () -> listView.getSelectionModel().selectRange(0, listView.getItems().size()));
        selectingToolbar.setAlignment(Pos.CENTER_LEFT);
        selectingToolbar.getChildren().setAll(
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.session.pack.export.selected"), SVG.ARCHIVE,
                        () -> SessionPackActions.export(instance,
                                new ArrayList<>(listView.getSelectionModel().getSelectedItems()))),
                ToolbarListPageSkin.createToolbarButton2(i18n("button.remove"), SVG.DELETE_FOREVER,
                        () -> deleteSelected(listView.getSelectionModel().getSelectedItems())),
                selectAll,
                ToolbarListPageSkin.createToolbarButton2(i18n("button.cancel"), SVG.CANCEL,
                        () -> listView.getSelectionModel().clearSelection()));

        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        FXUtils.onChangeAndOperate(listView.getSelectionModel().selectedItemProperty(),
                selected -> showSelectingToolbar(selected != null));

        // Selecting everything through the button rather than `selectAll()`, which
        // clears first and makes the whole list flicker; and disabling the button
        // once there is nothing left to select, as the original does.
        ListChangeListener<Object> listener = change -> selectAll.setDisable(!listView.getItems().isEmpty()
                && listView.getSelectionModel().getSelectedItems().size() == listView.getItems().size());
        listView.getSelectionModel().getSelectedItems().addListener(listener);
        listView.getItems().addListener(listener);
        showSelectingToolbar(false);
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

    /// Deletes the selected sessions after one confirmation.
    ///
    /// @param sessions the sessions to delete
    private void deleteSelected(Collection<DshSession> sessions) {
        if (sessions.isEmpty()) {
            return;
        }
        String message = sessions.size() == 1
                ? i18n("dsh.session.delete.confirm", sessions.iterator().next().label())
                : i18n("dsh.session.delete.confirm.many", sessions.size());
        Controllers.confirm(message, i18n("dsh.session.delete"), () -> {
            List<String> failed = new ArrayList<>();
            for (DshSession session : sessions) {
                try {
                    DshSessions.delete(instance, session);
                } catch (DshException e) {
                    LOG.warning("Could not delete session " + session.id(), e);
                    failed.add(session.label());
                }
            }
            if (!failed.isEmpty()) {
                Controllers.dialog(i18n("dsh.session.delete_failed.many", String.join(", ", failed)),
                        i18n("dsh.session.delete_failed"), MessageType.ERROR);
            }
            refresh();
        }, null);
    }

    /// Opens a session's directory.
    ///
    /// @param session the session
    private void reveal(DshSession session) {
        FXUtils.showFileInExplorer(session.directory());
    }

    /// Shows the instances a session can move to.
    ///
    /// @param session the session to migrate
    /// @param anchor  the button the popup is anchored to
    private void showTargets(DshSession session, Node anchor) {
        List<DshInstance> targets = new ArrayList<>();
        for (DshInstance candidate : DshInstanceManager.list()) {
            if (!candidate.id().equals(instance.id())) {
                targets.add(candidate);
            }
        }

        AdvancedListBox menu = new AdvancedListBox();
        LineButton moveToggleRow = new LineButton();
        moveToggleRow.setTitle(i18n("dsh.session.move"));
        moveToggleRow.setSubtitle(i18n("dsh.session.move.hint"));
        moveToggleRow.setOnAction(event -> moveAfterMigrating = !moveAfterMigrating);
        menu.add(moveToggleRow);

        if (targets.isEmpty()) {
            LineButton none = new LineButton();
            none.setTitle(i18n("dsh.session.no_target"));
            none.setDisable(true);
            menu.add(none);
        }
        for (DshInstance target : targets) {
            String refusal;
            try {
                refusal = DshSessions.refusalReason(instance, session, target);
            } catch (DshException e) {
                refusal = e.getMessage();
            }

            LineButton row = new LineButton();
            row.setTitle(target.id());
            row.setSubtitle(refusal != null ? refusal
                    : i18n("dsh.session.target.hint", target.version(), target.homeMode().name()));
            row.setLeading(refusal == null ? SVG.ARROW_FORWARD : SVG.CLOSE, 16);
            row.setDisable(refusal != null);
            String finalRefusal = refusal;
            row.setOnAction(event -> {
                popupOf(anchor).ifPresent(JFXPopup::hide);
                if (finalRefusal == null) {
                    migrate(session, target);
                }
            });
            menu.add(row);
        }

        JFXPopup popup = new JFXPopup(menu);
        popups.put(anchor, popup);
        popup.show(anchor, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.RIGHT,
                -anchor.getBoundsInLocal().getWidth(), 0);
    }

    /// The popups this page has opened, so a menu row can close its own.
    private final java.util.Map<Node, JFXPopup> popups = new java.util.HashMap<>();

    /// Returns the popup anchored to a node.
    ///
    /// @param anchor the anchor
    /// @return the popup, or empty when none was opened for it
    private java.util.Optional<JFXPopup> popupOf(Node anchor) {
        return java.util.Optional.ofNullable(popups.get(anchor));
    }

    /// Migrates a session after confirmation.
    ///
    /// @param session the session
    /// @param target  the destination instance
    private void migrate(DshSession session, DshInstance target) {
        boolean move = moveAfterMigrating;
        Controllers.confirm(
                i18n(move ? "dsh.session.confirm.move" : "dsh.session.confirm.copy",
                        session.label(), target.id()),
                i18n("dsh.session.migrate"),
                () -> {
                    try {
                        DshSessions.migrate(instance, session, target, move);
                        Controllers.showToast(i18n("dsh.session.migrated", session.label(), target.id()));
                    } catch (DshException e) {
                        Controllers.dialog(e.getMessage(), i18n("dsh.session.migrate_failed"), MessageType.ERROR);
                    }
                    refresh();
                },
                null);
    }

    /// The page's skin: a toolbar above the session list.
    private static final class SessionListPageSkin extends ToolbarListPageSkin<DshSession, SessionListPage> {
        /// Creates the skin.
        ///
        /// @param control the page
        SessionListPageSkin(SessionListPage control) {
            super(control);
            setPlaceholder(i18n("dsh.sessions.empty"));
            // After the base skin has made the list: the selection toolbar acts on
            // it, and it does not exist while the toolbars are being built.
            control.attachList(listView);
        }

        @Override
        protected List<Node> initializeToolbar(SessionListPage page) {
            page.showSelectingToolbar(false);
            page.toolbar.setAlignment(Pos.CENTER_LEFT);
            return List.of(page.toolbarPane);
        }

        @Override
        protected ListCell<DshSession> createListCell(JFXListView<DshSession> listView) {
            return new SessionListCell(listView, getSkinnable());
        }
    }

    /// One row of the session list.
    ///
    /// The shape is HMCL's world row: a small image on the left, the two-line
    /// label, and the actions on the trailing edge. A session has no thumbnail,
    /// so the instance's own icon stands in — it keeps the rows aligned without
    /// inventing artwork the data does not have.
    private static final class SessionListCell extends MDListCell<DshSession> {
        /// The page whose actions the row's buttons run.
        private final SessionListPage page;

        /// The session image.
        private final ImageContainer icon = new ImageContainer(32);

        /// The label and its tags.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The button that exports this session alone.
        private final JFXButton export = FXUtils.newToggleButton4(SVG.ARCHIVE);

        /// The button that moves the session elsewhere.
        private final JFXButton migrate = FXUtils.newToggleButton4(SVG.ARROW_FORWARD);

        /// The button that opens the row menu.
        private final JFXButton more = FXUtils.newToggleButton4(SVG.MORE_VERT);

        /// Creates the cell.
        ///
        /// @param listView the owning list
        /// @param page     the page the row's actions belong to
        SessionListCell(JFXListView<DshSession> listView, SessionListPage page) {
            super(listView);
            this.page = page;

            HBox container = new HBox(8);
            container.setPickOnBounds(false);
            container.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(content, Priority.ALWAYS);
            content.setMouseTransparent(true);
            setSelectable();

            StackPane left = new StackPane(icon);
            left.setPadding(new Insets(0, 8, 0, 0));

            FXUtils.installFastTooltip(export, i18n("dsh.session.pack.export.session"));
            FXUtils.installFastTooltip(migrate, i18n("dsh.session.migrate"));
            FXUtils.installFastTooltip(more, i18n("dsh.instance.menu"));

            export.setOnAction(event -> {
                DshSession session = getItem();
                if (session != null) {
                    SessionPackActions.export(page.instance, List.of(session));
                }
            });
            migrate.setOnAction(event -> {
                DshSession session = getItem();
                if (session != null) {
                    page.showTargets(session, migrate);
                }
            });
            more.setOnAction(event -> {
                DshSession session = getItem();
                if (session != null) {
                    showRowMenu(session, more);
                }
            });

            container.getChildren().setAll(left, content, export, migrate, more);
            StackPane.setMargin(container, new Insets(8));
            getContainer().getChildren().setAll(container);
        }

        /// Shows the row's own menu.
        ///
        /// @param session the session
        /// @param anchor  the button the popup is anchored to
        private void showRowMenu(DshSession session, Node anchor) {
            PopupMenu menu = new PopupMenu();
            JFXPopup popup = new JFXPopup(menu);

            menu.getContent().setAll(
                    new IconedMenuItem(SVG.ARCHIVE, i18n("dsh.session.pack.export.session"),
                            () -> SessionPackActions.export(page.instance, List.of(session)), popup),
                    // A subagent's conversation lives in its own session directory and is reached
                    // through its parent, so the unit worth moving is the project the sessions were
                    // recorded in — which is exactly the directory they share, and exactly this
                    // page's list.
                    new IconedMenuItem(SVG.ARCHIVE, i18n("dsh.session.pack.export.project"),
                            () -> SessionPackActions.export(page.instance,
                                    new ArrayList<>(page.getItems())), popup),
                    new IconedMenuItem(SVG.FOLDER_OPEN, i18n("dsh.session.reveal"),
                            () -> page.reveal(session), popup),
                    // What is gone cannot be got back, so it is fenced off from what can.
                    new MenuSeparator(),
                    new IconedMenuItem(SVG.DELETE, i18n("dsh.session.delete"),
                            () -> page.deleteSelected(List.of(session)), popup));

            popup.show(anchor, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.RIGHT,
                    -anchor.getBoundsInLocal().getWidth(), 0);
        }

        @Override
        protected void updateControl(@Nullable DshSession session, boolean empty) {
            if (empty || session == null) {
                return;
            }

            icon.setImage(DshInstanceIcons.load(page.instance));
            content.setTitle(session.label());
            content.setSubtitle(describe(session));
            content.getTags().clear();
            content.addTag("v" + session.formatVersion());
            if (session.locked()) {
                content.addTag(i18n("dsh.session.in_use"));
            }
        }
    }
}
