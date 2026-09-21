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
import com.jfoenix.controls.JFXDialogLayout;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.NodeRelease;
import org.jackhuang.hmcl.dsh.NodeRuntimeManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The list of Node.js versions that can be downloaded.
///
/// This is a separate entry point for the same reason HMCL's Java download is:
/// the management page lists what is already on the machine and must not wait on
/// a network round trip to do it, while the versions offered for download come
/// from an index that only this dialog needs.
@NotNullByDefault
public final class NodeDownloadDialog extends JFXDialogLayout {
    /// The list the releases are added to.
    private final ComponentList releases = new ComponentList();

    /// Covers the list while the index loads.
    private final SpinnerPane spinner = new SpinnerPane();

    /// Called after a successful install.
    private final Runnable onInstalled;

    /// Creates the dialog.
    ///
    /// @param onInstalled run after a version is installed
    public NodeDownloadDialog(Runnable onInstalled) {
        this.onInstalled = onInstalled;

        setHeading(new Label(i18n("dsh.node.download.title")));

        VBox body = new VBox(10, spinner);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(360);
        setBody(scroll);

        spinner.setContent(releases);
        load();

        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.getStyleClass().add("dialog-cancel");
        cancel.setOnAction(event -> fireEvent(new DialogCloseEvent()));
        onEscPressed(this, cancel::fire);
        setActions(cancel);
    }

    /// Loads the published versions.
    ///
    /// A failure is reported in place rather than as a dialog: the index is the
    /// only content here, so there is nothing else to fall back to.
    private void load() {
        spinner.setLoading(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                return NodeRuntimeManager.fetchReleases(settings().nodeSourceProperty().get());
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((result, throwable) -> runInFX(() -> {
            spinner.setLoading(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause() : throwable;
                LOG.warning("Failed to fetch Node releases", cause);
                LineTextPane failure = new LineTextPane();
                failure.setText(i18n("dsh.versions.load_failed") + ": " + cause.getMessage());
                releases.getContent().add(failure);

                // The one failure here that has something to do about it is the
                // route to the publisher: the message names the host that could
                // not be reached, and where to fetch from instead is a setting.
                LineTextPane hint = new LineTextPane();
                hint.setText(i18n("dsh.versions.load_failed.source"));
                releases.getContent().add(hint);
                return;
            }
            for (NodeRelease release : result) {
                releases.getContent().add(buildRow(release));
            }
        }));
    }

    /// Builds one downloadable version row.
    ///
    /// @param release the release
    /// @return the row
    private LineButton buildRow(NodeRelease release) {
        LineButton row = new LineButton();
        row.setTitle("Node.js " + release.version());
        row.setSubtitle(release.isLts()
                ? i18n("dsh.node.tag.lts") + " · " + release.date()
                : i18n("dsh.node.tag.current") + " · " + release.date());
        row.setLeading(org.jackhuang.hmcl.ui.SVG.DOWNLOAD, 16);
        row.setOnAction(event -> install(release.version()));
        return row;
    }

    /// Installs a version and closes.
    ///
    /// @param version the version to install
    private void install(String version) {
        spinner.setLoading(true);
        CompletableFuture.runAsync(() -> {
            try {
                NodeRuntimeManager.install(version, settings().nodeSourceProperty().get(),
                        line -> LOG.info("[node] " + line));
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
            spinner.setLoading(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause() : throwable;
                LOG.warning("Failed to install Node.js", cause);
                Controllers.dialog(cause.getMessage(), i18n("dsh.node.install_failed"), MessageType.ERROR);
                return;
            }
            onInstalled.run();
            fireEvent(new DialogCloseEvent());
        }));
    }
}
