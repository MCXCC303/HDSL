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

import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshSession;
import org.jackhuang.hmcl.dsh.DshSessionPacks;
import org.jackhuang.hmcl.dsh.DshSessions;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The file-level session pack operations the two session pages share.
///
/// A workspace is exported as the list of sessions it holds, so the workspace
/// list and the session list differ only in which sessions they hand over; the
/// chooser, the progress dialog and the import are the same work, and living
/// here keeps that from being written twice.
@NotNullByDefault
final class SessionPackActions {
    private SessionPackActions() {
    }

    /// Writes a set of sessions into a pack the user chooses.
    ///
    /// @param instance the instance the sessions belong to
    /// @param sessions the sessions to write
    static void export(DshInstance instance, List<DshSession> sessions) {
        if (sessions.isEmpty()) {
            Controllers.dialog(i18n("dsh.session.pack.export.empty"), i18n("dsh.session.pack.export"),
                    MessageType.ERROR);
            return;
        }

        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("dsh.session.pack.export"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("dsh.session.pack.filter"), "*" + DshSessionPacks.FILE_EXTENSION));
        chooser.setInitialFileName("hdsl-sessions-" + instance.id() + "-"
                + java.time.LocalDate.now() + DshSessionPacks.FILE_EXTENSION);
        java.io.File chosen = chooser.showSaveDialog(Controllers.getStage());
        if (chosen == null) {
            return;
        }

        Path target = chosen.toPath();
        ProgressDialog.run(i18n("dsh.session.pack.export"), progress ->
                DshSessionPacks.export(instance, sessions, target, progress::accept), null);
    }

    /// Reads a pack the user chooses.
    ///
    /// @param instance the instance to import into
    /// @param onDone   run after a successful import
    static void importPack(DshInstance instance, Runnable onDone) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("dsh.session.pack.import"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("dsh.session.pack.filter"),
                DshSessionPacks.ACCEPTED_EXTENSIONS.stream()
                        .map(extension -> "*" + extension).toList()));
        java.io.File chosen = chooser.showOpenDialog(Controllers.getStage());
        if (chosen == null) {
            return;
        }

        Path pack = chosen.toPath();
        ProgressDialog.run(i18n("dsh.session.pack.import"), progress ->
                DshSessionPacks.importFrom(instance.homeDirectory(), pack, progress::accept), onDone);
    }

    /// Copies the sessions of the machine's own DeepSeek Harness installation.
    ///
    /// The source is read and never written: this launcher does not manage that
    /// installation, and importing is meant to take what it has without
    /// becoming responsible for it. Sessions already present are left alone, and
    /// ones whose lease is held are reported rather than skipped silently.
    ///
    /// @param instance the instance to import into
    /// @param onDone   run after the import, successful or not
    static void importFromSystem(DshInstance instance, Runnable onDone) {
        Path source = Path.of(System.getProperty("user.home"), ".dsh");
        if (!java.nio.file.Files.isDirectory(source)) {
            Controllers.dialog(i18n("dsh.session.import.missing", source.toString()),
                    i18n("dsh.session.import"), MessageType.ERROR);
            return;
        }

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
            onDone.run();
        }));
    }
}
