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
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.NodeRelease;
import org.jackhuang.hmcl.dsh.NodeRuntime;
import org.jackhuang.hmcl.dsh.NodeRuntimeManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Manages the Node.js runtimes HMCL-DSH owns.
///
/// This is the counterpart of HMCL's Java management page: DeepSeek Harness
/// needs Node `^22.19.0 || >=24.0.0`, so the launcher can install a specific
/// release and pin an instance to it instead of relying on whatever the
/// distribution happens to ship.
@NotNullByDefault
public final class NodeRuntimesPane extends ScrollPane implements Refreshable {
    /// How many downloadable releases are offered at once.
    private static final int REMOTE_LIMIT = 20;

    /// The card listing installed runtimes.
    private final ComponentList installedList = new ComponentList();

    /// The card listing downloadable releases.
    private final ComponentList remoteList = new ComponentList();

    /// The status line above the cards.
    private final Label status = new Label();

    /// Covers the content while work is in flight.
    private final SpinnerPane spinner = new SpinnerPane();

    /// Whether a refresh or install is running.
    private boolean busy;

    /// Creates the runtime management pane.
    public NodeRuntimesPane() {
        setFitToWidth(true);

        JFXButton refreshButton = new JFXButton(i18n("dsh.versions.refresh"));
        refreshButton.setOnAction(event -> refresh());
        HBox toolbar = new HBox(8, refreshButton);
        toolbar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.getChildren().addAll(toolbar, status, installedList, remoteList);
        spinner.setContent(content);

        // Must run after the content is installed: smooth scrolling binds to
        // the content node and fails on a null content.
        FXUtils.smoothScrolling(this);
        setContent(spinner);

        refresh();
    }

    @Override
    public void refresh() {
        if (busy) {
            return;
        }
        busy = true;
        spinner.setLoading(true);
        status.setText(i18n("dsh.versions.loading"));

        CompletableFuture.supplyAsync(() -> {
            List<NodeRuntime> installed = NodeRuntimeManager.listInstalled();
            List<NodeRelease> remote;
            @Nullable String error = null;
            try {
                remote = NodeRuntimeManager.fetchReleases();
            } catch (DshException e) {
                LOG.warning("Failed to fetch Node releases", e);
                remote = List.of();
                error = e.getMessage();
            }
            return new LoadResult(installed, remote, error);
        }, Schedulers.io()).whenComplete((result, throwable) -> runInFX(() -> {
            busy = false;
            spinner.setLoading(false);
            if (throwable != null) {
                LOG.warning("Failed to load Node runtimes", throwable);
                status.setText(i18n("dsh.versions.load_failed"));
                return;
            }
            applyLoaded(result);
        }));
    }

    /// Renders a finished load.
    ///
    /// @param result the loaded runtimes and the optional index error
    private void applyLoaded(LoadResult result) {
        installedList.getContent().clear();
        remoteList.getContent().clear();

        installedList.getContent().add(buildHeader(i18n("dsh.node.installed")));
        remoteList.getContent().add(buildHeader(i18n("dsh.node.available")));

        if (result.installed().isEmpty()) {
            installedList.getContent().add(buildNote(i18n("dsh.node.installed.empty")));
        } else {
            for (NodeRuntime runtime : result.installed()) {
                installedList.getContent().add(buildInstalledRow(runtime));
            }
        }

        DshNodeRuntime system = DshNodeRuntime.detect().orElse(null);
        if (system == null) {
            status.setText(i18n("dsh.node.system.missing", DshNodeRuntime.requirement()));
        } else if (result.error() != null) {
            status.setText(i18n("dsh.versions.load_failed") + ": " + result.error());
        } else {
            status.setText(i18n("dsh.node.system", system.nodeVersion(),
                    system.isNodeSupported() ? i18n("dsh.node.system.ok") : i18n("dsh.node.system.unsupported")));
        }

        int shown = 0;
        for (NodeRelease release : result.remote()) {
            if (!release.isSupported()) {
                continue;
            }
            if (shown++ >= REMOTE_LIMIT) {
                break;
            }
            remoteList.getContent().add(buildRemoteRow(release));
        }
        if (shown == 0 && result.error() == null) {
            remoteList.getContent().add(buildNote(i18n("dsh.node.available.empty")));
        }
    }

    /// Builds one row for an installed runtime.
    ///
    /// @param runtime the installed runtime
    /// @return the row
    private LineButton buildInstalledRow(NodeRuntime runtime) {
        JFXButton remove = FXUtils.newToggleButton4(SVG.DELETE, 18);
        remove.setOnAction(event -> uninstall(runtime));

        LineButton row = new LineButton();
        row.setTitle(runtime.version());
        row.setSubtitle(runtime.directory().toString());
        row.setTitleTrailing(remove);
        row.setOnAction(event -> FXUtils.showFileInExplorer(runtime.directory()));
        return row;
    }

    /// Builds one row for a downloadable release.
    ///
    /// @param release the published release
    /// @return the row
    private LineButton buildRemoteRow(NodeRelease release) {
        JFXButton install = FXUtils.newToggleButton4(SVG.ADD, 18);
        install.setOnAction(event -> install(release.version()));

        LineButton row = new LineButton();
        row.setTitle(release.version());
        row.setSubtitle(release.isLts()
                ? i18n("dsh.node.lts", release.label())
                : i18n("dsh.node.current"));
        row.setTitleTrailing(install);
        row.setOnAction(event -> install(release.version()));
        return row;
    }

    /// Installs a release in the background.
    ///
    /// @param version the version to install
    private void install(String version) {
        if (busy) {
            return;
        }
        busy = true;
        spinner.setLoading(true);
        status.setText(i18n("dsh.node.installing", version));

        CompletableFuture.runAsync(() -> {
            try {
                NodeRuntimeManager.install(version, null);
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
            busy = false;
            spinner.setLoading(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause()
                        : throwable;
                LOG.warning("Failed to install Node.js " + version, cause);
                Controllers.dialog(cause.getMessage(), i18n("dsh.node.install_failed"), MessageType.ERROR);
            } else {
                Controllers.showToast(i18n("dsh.node.installed.toast", version));
            }
            refresh();
        }));
    }

    /// Removes an installed runtime after confirmation.
    ///
    /// @param runtime the runtime to remove
    private void uninstall(NodeRuntime runtime) {
        Controllers.confirm(i18n("dsh.node.remove.confirm", runtime.version()),
                i18n("dsh.node.remove"),
                () -> CompletableFuture.runAsync(() -> {
                    try {
                        NodeRuntimeManager.uninstall(runtime.version());
                    } catch (DshException e) {
                        throw new CompletionException(e);
                    }
                }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
                    if (throwable != null) {
                        LOG.warning("Failed to remove Node.js " + runtime.version(), throwable);
                        Controllers.dialog(String.valueOf(throwable.getMessage()),
                                i18n("dsh.node.remove_failed"), MessageType.ERROR);
                    } else {
                        Controllers.showToast(i18n("dsh.node.removed", runtime.version()));
                    }
                    refresh();
                })),
                null);
    }

    /// Builds a bold heading for a card.
    ///
    /// @param text the heading text
    /// @return the heading row
    private Node buildHeader(String text) {
        LineTextPane header = new LineTextPane();
        header.setTitle(text);
        header.getStyleClass().add("section-header");
        return header;
    }

    /// Builds a non-interactive note row.
    ///
    /// @param text the text to show
    /// @return the row
    private Node buildNote(String text) {
        LineTextPane note = new LineTextPane();
        note.setText(text);
        return note;
    }

    /// The result of one background load.
    ///
    /// @param installed the locally installed runtimes
    /// @param remote    the published releases, empty when the index failed
    /// @param error     the index error message, or `null` on success
    private record LoadResult(List<NodeRuntime> installed, List<NodeRelease> remote, @Nullable String error) {
    }
}
