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
import com.jfoenix.controls.JFXPopup;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.jackhuang.hmcl.dsh.DshSession;
import org.jackhuang.hmcl.dsh.DshSessions;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Lists an instance's sessions and moves them to another instance.
///
/// This is the counterpart of HMCL's world management: the launcher does not
/// read a session's contents, it manages the files DeepSeek Harness persists.
/// Migration is offered because the alternative — reaching one history through
/// two homes — is what the DSH_HOME isolation policy exists to prevent, so a
/// user who wants a session somewhere else needs a supported way to move it.
///
/// Migration is refused unless the target can already read the session's log
/// format, and unless the session's write lease is free. Both refusals are shown
/// per target rather than as a failed attempt.
@NotNullByDefault
public final class SessionManagementPane extends ScrollPane implements Refreshable {
    /// The instance whose sessions are listed.
    private final DshInstance instance;

    /// The status line above the list.
    private final Label status = new Label();

    /// The session list, rebuilt on every refresh.
    private final ComponentList sessionList = new ComponentList();

    /// Whether migrating also removes the session from this instance.
    private final LineToggleButton moveToggle = new LineToggleButton();

    /// Creates the pane.
    ///
    /// @param instance the instance to list sessions for
    public SessionManagementPane(DshInstance instance) {
        this.instance = instance;

        setFitToWidth(true);

        moveToggle.setTitle(i18n("dsh.session.move"));
        moveToggle.setSubtitle(i18n("dsh.session.move.hint"));
        moveToggle.setSelected(true);

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        root.getChildren().addAll(buildToolbar(), status, moveToggle,
                ComponentList.createComponentListTitle(i18n("dsh.session.installed")), sessionList);
        setContent(root);

        // Must run after the content is installed: smooth scrolling binds to the
        // content node and throws on a null content.
        FXUtils.smoothScrolling(this);

        refresh();
    }

    /// Builds the toolbar above the list.
    ///
    /// @return the toolbar
    private HBox buildToolbar() {
        JFXButton refreshButton = new JFXButton(i18n("dsh.versions.refresh"));
        refreshButton.setOnAction(event -> refresh());

        HBox toolbar = new HBox(8, refreshButton);
        toolbar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(toolbar, Priority.NEVER);
        return toolbar;
    }

    @Override
    public void refresh() {
        sessionList.getContent().clear();

        List<DshSession> sessions;
        try {
            sessions = DshSessions.list(instance.homeDirectory());
        } catch (DshException e) {
            status.setText(e.getMessage());
            return;
        }

        if (sessions.isEmpty()) {
            LineTextPane empty = new LineTextPane();
            empty.setText(i18n("dsh.session.empty"));
            sessionList.getContent().add(empty);
            status.setText(i18n("dsh.session.count", 0));
            return;
        }

        for (DshSession session : sessions) {
            sessionList.getContent().add(buildSessionRow(session));
        }
        status.setText(i18n("dsh.session.count", sessions.size()));
    }

    /// Builds one row for a session.
    ///
    /// @param session the session
    /// @return the row
    private LineButton buildSessionRow(DshSession session) {
        JFXButton migrate = FXUtils.newToggleButton4(SVG.ARROW_FORWARD, 18);
        FXUtils.installFastTooltip(migrate, i18n("dsh.session.migrate"));
        migrate.setDisable(session.locked());
        migrate.setOnAction(event -> showTargets(migrate, session));

        LineButton row = new LineButton();
        row.setTitle(session.label());
        row.setSubtitle(describe(session));
        row.setRowTrailing(migrate);
        return row;
    }

    /// Describes a session in one line.
    ///
    /// @param session the session
    /// @return the text
    private String describe(DshSession session) {
        StringBuilder builder = new StringBuilder();
        builder.append('v').append(session.formatVersion())
                .append(" · ").append(DshSessions.formatSize(session.sizeBytes()));
        if (session.workingDirectory() != null) {
            builder.append(" · ").append(session.workingDirectory());
        }
        if (session.locked()) {
            builder.append(" · ").append(i18n("dsh.session.in_use"));
        }
        return builder.toString();
    }

    /// Shows the instances this session can move to.
    ///
    /// @param anchor  the button the popup is anchored to
    /// @param session the session being migrated
    private void showTargets(Node anchor, DshSession session) {
        List<DshInstance> targets = new ArrayList<>();
        for (DshInstance candidate : DshInstanceManager.list()) {
            if (!candidate.id().equals(instance.id())) {
                targets.add(candidate);
            }
        }

        JFXPopup[] popupRef = new JFXPopup[1];
        Runnable close = () -> {
            if (popupRef[0] != null) {
                popupRef[0].hide();
            }
        };

        AdvancedListBox menu = new AdvancedListBox();
        if (targets.isEmpty()) {
            LineTextPane none = new LineTextPane();
            none.setText(i18n("dsh.session.no_target"));
            menu.add(none);
        }
        for (DshInstance target : targets) {
            String refusal;
            try {
                refusal = DshSessions.refusalReason(instance, session, target);
            } catch (DshException e) {
                refusal = e.getMessage();
            }

            String title = target.id();
            String subtitle = refusal != null ? refusal : describe(target);
            Runnable action;
            if (refusal != null) {
                action = close;
            } else {
                action = () -> {
                    close.run();
                    migrate(session, target);
                };
            }
            // The subtitle carries the reason a target is unavailable, so it is
            // shown on the row rather than hidden behind a tooltip.
            LineButton row = new LineButton();
            row.setTitle(title);
            row.setSubtitle(subtitle);
            row.setLeading(refusal == null ? SVG.ARROW_FORWARD : SVG.CLOSE, 16);
            row.setDisable(refusal != null);
            row.setOnAction(event -> action.run());
            menu.add(row);
        }

        popupRef[0] = new JFXPopup(menu);
        popupRef[0].show(anchor, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.RIGHT,
                -anchor.getBoundsInLocal().getWidth(), 0);
    }

    /// Describes a target instance.
    ///
    /// @param target the instance
    /// @return the text
    private String describe(DshInstance target) {
        return i18n("dsh.session.target.hint", target.version(), target.homeMode().name());
    }

    /// Migrates a session after confirmation.
    ///
    /// @param session the session
    /// @param target  the destination instance
    private void migrate(DshSession session, DshInstance target) {
        boolean move = moveToggle.isSelected();

        Controllers.confirm(
                i18n(move ? "dsh.session.confirm.move" : "dsh.session.confirm.copy",
                        session.label(), target.id()),
                i18n("dsh.session.migrate"),
                () -> CompletableFuture.runAsync(() -> {
                    try {
                        DshSessions.migrate(instance, session, target, move);
                    } catch (DshException e) {
                        throw new CompletionException(e);
                    }
                }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
                    if (throwable != null) {
                        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                                ? throwable.getCause()
                                : throwable;
                        LOG.warning("Failed to migrate a session", cause);
                        Controllers.dialog(cause.getMessage(), i18n("dsh.session.migrate_failed"),
                                MessageType.ERROR);
                    } else {
                        Controllers.showToast(i18n("dsh.session.migrated", session.label(), target.id()));
                    }
                    refresh();
                })),
                null);
    }

    /// Reports whether the instance has work in flight.
    ///
    /// Migration out of a running instance is refused by the caller; this is
    /// exposed so the page can explain why the action is unavailable.
    ///
    /// @return whether the instance is running
    public boolean isInstanceRunning() {
        return DshProcessManager.find(instance.id()).isPresent();
    }
}
