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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.Skin;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceIcons;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshSession;
import org.jackhuang.hmcl.dsh.DshSessionPacks;
import org.jackhuang.hmcl.dsh.DshSessions;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.ListPageBase;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Lists an instance's sessions and moves them between instances.
///
/// Modelled on HMCL's world list, which answers the same shape of question: a
/// list of things belonging to one instance, each with an identity, a when, and
/// a small set of things you can do to it. The row is that original's — a
/// thumbnail-sized icon, a two-line label with a tag, and the actions on the
/// trailing edge — because the launcher's job here is to show what DeepSeek
/// Harness persisted without reading it.
@NotNullByDefault
public final class SessionListPage extends ListPageBase<DshSession> implements Refreshable {
    /// Formats a session's last activity the way a person reads it.
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /// The instance whose sessions are listed.
    private final DshInstance instance;

    /// Whether migrating also removes the session here.
    private boolean moveAfterMigrating = true;

    /// Whether a load is already running.
    private boolean busy;

    /// Creates the page.
    ///
    /// @param instance the instance whose sessions are listed
    public SessionListPage(DshInstance instance) {
        this.instance = instance;
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
                return DshSessions.list(instance.homeDirectory());
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

    /// Copies the sessions of the machine's own DeepSeek Harness installation.
    ///
    /// The source is read and never written: this launcher does not manage that
    /// installation, and importing is meant to take what it has without
    /// becoming responsible for it. Sessions already present are left alone, and
    /// ones whose lease is held are reported rather than skipped silently.
    private void importFromSystem() {
        Path source = Path.of(System.getProperty("user.home"), ".dsh");
        if (!java.nio.file.Files.isDirectory(source)) {
            Controllers.dialog(i18n("dsh.session.import.missing", source.toString()),
                    i18n("dsh.session.import"), MessageType.ERROR);
            return;
        }

        setLoading(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                List<DshSession> sessions = DshSessions.readForeignHome(source);
                int imported = 0;
                int present = 0;
                int refused = 0;
                for (DshSession session : sessions) {
                    try {
                        DshSessions.importFrom(source, session, instance);
                        imported++;
                    } catch (DshException e) {
                        if (e.getMessage() != null && e.getMessage().contains("already has a session")) {
                            present++;
                        } else {
                            refused++;
                        }
                    }
                }
                // Asking for the grouping to be worked out again is what files the
                // imported conversations under their projects; the harness derives
                // it from the sessions themselves on the next start of the instance.
                DshSessions.regroup(instance.homeDirectory());
                return new int[]{imported, present, refused, sessions.size()};
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((counts, throwable) -> runInFX(() -> {
            setLoading(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause() : throwable;
                LOG.warning("Failed to import sessions", cause);
                Controllers.dialog(cause.getMessage(), i18n("dsh.session.import.failed"), MessageType.ERROR);
            } else {
                Controllers.dialog(i18n("dsh.session.import.done",
                        counts[0], counts[1], counts[2], counts[3]),
                        i18n("dsh.session.import"));
            }
            refresh();
        }));
    }

    /// Writes a set of sessions into a pack the user chooses.
    ///
    /// @param sessions the sessions to write
    private void exportPack(List<DshSession> sessions) {
        if (sessions.isEmpty()) {
            Controllers.dialog(i18n("dsh.session.pack.export.empty"), i18n("dsh.session.pack.export"),
                    MessageType.ERROR);
            return;
        }

        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("dsh.session.pack.export"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("dsh.session.pack.filter"), "*.zip"));
        chooser.setInitialFileName("hdsl-sessions-" + instance.id() + "-" + java.time.LocalDate.now() + ".zip");
        java.io.File chosen = chooser.showSaveDialog(Controllers.getStage());
        if (chosen == null) {
            return;
        }

        Path target = chosen.toPath();
        ProgressDialog.run(i18n("dsh.session.pack.export"), progress ->
                DshSessionPacks.export(instance, sessions, target, progress::accept), null);
    }

    /// Reads a pack the user chooses.
    private void importPack() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("dsh.session.pack.import"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("dsh.session.pack.filter"), "*.zip"));
        java.io.File chosen = chooser.showOpenDialog(Controllers.getStage());
        if (chosen == null) {
            return;
        }

        Path pack = chosen.toPath();
        ProgressDialog.run(i18n("dsh.session.pack.import"), progress ->
                DshSessionPacks.importFrom(instance.homeDirectory(), pack, progress::accept),
                this::refresh);
    }

    /// Deletes a session after confirmation.
    ///
    /// @param session the session to delete
    private void delete(DshSession session) {
        Controllers.confirm(i18n("dsh.session.delete.confirm", session.label()),
                i18n("dsh.session.delete"),
                () -> {
                    try {
                        DshSessions.delete(instance, session);
                    } catch (DshException e) {
                        Controllers.dialog(e.getMessage(), i18n("dsh.session.delete_failed"), MessageType.ERROR);
                    }
                    refresh();
                },
                null);
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
        }

        @Override
        protected List<Node> initializeToolbar(SessionListPage page) {
            List<Node> toolbar = new ArrayList<>();
            toolbar.add(createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, page::refresh));
            toolbar.add(createToolbarButton2(i18n("dsh.session.import"), SVG.DOWNLOAD, page::importFromSystem));
            toolbar.add(createToolbarButton2(i18n("dsh.session.pack.export.all"), SVG.ARCHIVE,
                    () -> page.exportPack(page.getItems())));
            toolbar.add(createToolbarButton2(i18n("dsh.session.pack.import"), SVG.FILE_OPEN,
                    page::importPack));
            return toolbar;
        }

        @Override
        protected ListCell<DshSession> createListCell(JFXListView<DshSession> listView) {
            return new SessionListCell(getSkinnable());
        }
    }

    /// One row of the session list.
    ///
    /// The shape is HMCL's world row: a small image on the left, the two-line
    /// label, and the actions on the trailing edge. A session has no thumbnail,
    /// so the instance's own icon stands in — it keeps the rows aligned without
    /// inventing artwork the data does not have.
    private static final class SessionListCell extends ListCell<DshSession> {
        /// The page whose actions the row's buttons run.
        private final SessionListPage page;

        /// The session image.
        private final ImageContainer icon = new ImageContainer(32);

        /// The label and its tags.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The button that moves the session elsewhere.
        private final JFXButton migrate = FXUtils.newToggleButton4(SVG.ARROW_FORWARD);

        /// The button that opens the row menu.
        private final JFXButton more = FXUtils.newToggleButton4(SVG.MORE_VERT);

        /// The row's graphic, re-installed for every item.
        private final RipplerContainer graphic;

        /// Creates the cell.
        ///
        /// @param page the page the row's actions belong to
        SessionListCell(SessionListPage page) {
            this.page = page;

            BorderPane root = new BorderPane();
            root.getStyleClass().add("md-list-cell");
            root.setPadding(new Insets(8));

            StackPane left = new StackPane(icon);
            left.setPadding(new Insets(0, 8, 0, 0));
            root.setLeft(left);

            content.setMouseTransparent(true);
            root.setCenter(content);

            HBox right = new HBox(8);
            right.setAlignment(Pos.CENTER_RIGHT);
            FXUtils.installFastTooltip(migrate, i18n("dsh.session.migrate"));
            FXUtils.installFastTooltip(more, i18n("dsh.instance.menu"));
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
            right.getChildren().setAll(migrate, more);
            root.setRight(right);

            this.graphic = new RipplerContainer(root);
            setGraphic(graphic);
        }

        /// Shows the row's own menu.
        ///
        /// @param session the session
        /// @param anchor  the button the popup is anchored to
        private void showRowMenu(DshSession session, Node anchor) {
            AdvancedListBox menu = new AdvancedListBox();

            LineButton pack = new LineButton();
            pack.setTitle(i18n("dsh.session.pack.export.project"));
            pack.setLeading(SVG.ARCHIVE, 16);
            menu.add(pack);

            LineButton reveal = new LineButton();
            reveal.setTitle(i18n("dsh.session.reveal"));
            reveal.setLeading(SVG.FOLDER_OPEN, 16);
            menu.add(reveal);

            LineButton delete = new LineButton();
            delete.setTitle(i18n("dsh.session.delete"));
            delete.setLeading(SVG.DELETE, 16);
            menu.add(delete);

            JFXPopup popup = new JFXPopup(menu);
            pack.setOnAction(event -> {
                popup.hide();
                // A subagent's conversation lives in its own session directory and
                // is reached through its parent, so the unit worth moving is the
                // project the sessions were recorded in — which is exactly the
                // directory they share.
                page.exportPack(page.getItems().stream()
                        .filter(other -> other.workspaceSlug().equals(session.workspaceSlug()))
                        .toList());
            });
            reveal.setOnAction(event -> {
                popup.hide();
                page.reveal(session);
            });
            delete.setOnAction(event -> {
                popup.hide();
                page.delete(session);
            });
            popup.show(anchor, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.RIGHT,
                    -anchor.getBoundsInLocal().getWidth(), 0);
        }

        @Override
        protected void updateItem(@Nullable DshSession session, boolean empty) {
            super.updateItem(session, empty);

            if (empty || session == null) {
                setGraphic(null);
                return;
            }

            setGraphic(graphic);
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
