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
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshRelease;
import org.jackhuang.hmcl.dsh.DshVersion;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Manages the DeepSeek Harness versions installed on this machine.
///
/// This is the HMCL-DSH counterpart of HMCL's installer list: instead of
/// Forge, Fabric and NeoForge, the entries are published `dsh` versions.
/// Every version is installed into its own npm prefix, so installing one never
/// disturbs another.
@NotNullByDefault
public final class VersionsPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
    /// How many remote versions are rendered at once.
    ///
    /// Upstream publishes a fast-moving pre-release stream; showing the whole
    /// history would swamp the page. Installed versions are always listed.
    private static final int REMOTE_DISPLAY_LIMIT = 25;

    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("dsh.versions.title")));

    /// The card listing locally installed versions.
    private final ComponentList installedList = new ComponentList();

    /// The card listing versions available from the registry.
    private final ComponentList remoteList = new ComponentList();

    /// The status line above the lists.
    private final Label status = new Label();

    /// Covers the content while a refresh is running.
    private final SpinnerPane spinner = new SpinnerPane();

    /// Whether a refresh or install is currently in flight.
    private boolean busy;

    /// Creates the versions page and starts loading.
    public VersionsPage() {
        getStyleClass().remove("gray-background");

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("dsh.versions.title").toUpperCase(java.util.Locale.ROOT))
                .addNavigationDrawerItem(i18n("dsh.versions.refresh"), SVG.UPDATE, this::refresh);
        FXUtils.setLimitWidth(sideBar, 200);
        getLeft().getStyleClass().add("gray-background");
        getLeft().getStyleClass().add("gray-background");
        setLeft(sideBar);

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.getChildren().addAll(status, installedList, remoteList);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        // Must run after the content is installed: smooth scrolling binds to
        // the content node and fails on a null content.
        FXUtils.smoothScrolling(scroll);

        spinner.setContent(scroll);
        setCenter(spinner);

        refresh();
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
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
            List<DshVersion> installed = DshVersionManager.listInstalled();
            List<DshRelease> remote;
            @Nullable String error = null;
            try {
                remote = DshVersionManager.fetchReleases();
            } catch (DshException e) {
                LOG.warning("Failed to fetch DSH releases", e);
                remote = List.of();
                error = e.getMessage();
            }
            return new LoadResult(installed, remote, error);
        }, Schedulers.io()).whenComplete((result, throwable) -> runInFX(() -> {
            busy = false;
            spinner.setLoading(false);
            if (throwable != null) {
                LOG.warning("Failed to load DSH versions", throwable);
                status.setText(i18n("dsh.versions.load_failed"));
                return;
            }
            applyLoaded(result);
        }));
    }

    /// Renders a finished load.
    ///
    /// @param result the loaded versions and the optional registry error
    private void applyLoaded(LoadResult result) {
        installedList.getContent().clear();
        remoteList.getContent().clear();

        installedList.getContent().add(buildSectionHeader(i18n("dsh.versions.section.installed")));
        remoteList.getContent().add(buildSectionHeader(i18n("dsh.versions.section.available")));

        if (result.installed().isEmpty()) {
            installedList.getContent().add(buildNote(i18n("dsh.versions.installed.empty")));
        } else {
            for (DshVersion version : result.installed()) {
                installedList.getContent().add(buildInstalledRow(version));
            }
        }

        if (result.error() != null) {
            status.setText(i18n("dsh.versions.load_failed") + ": " + result.error());
        } else {
            status.setText(i18n("dsh.versions.remote_count", result.remote().size()));
        }

        int shown = 0;
        for (DshRelease release : result.remote()) {
            if (DshVersionManager.findInstalled(release.version()) != null) {
                continue;
            }
            if (shown++ >= REMOTE_DISPLAY_LIMIT) {
                break;
            }
            remoteList.getContent().add(buildRemoteRow(release));
        }
        if (shown == 0 && result.error() == null) {
            remoteList.getContent().add(buildNote(i18n("dsh.versions.available.empty")));
        }
    }

    /// Builds one row for an installed version, with a remove action.
    ///
    /// @param version the installed version
    /// @return the row
    private LineButton buildInstalledRow(DshVersion version) {
        JFXButton remove = FXUtils.newToggleButton4(SVG.DELETE, 18);
        remove.setOnAction(event -> remove(version));

        LineButton row = new LineButton();
        row.setTitle(version.version());
        row.setSubtitle(version.directory().toString());
        row.setTitleTrailing(remove);
        row.setOnAction(event -> FXUtils.showFileInExplorer(version.directory()));
        return row;
    }

    /// Builds one row for an installable version, with an install action.
    ///
    /// @param release the published release
    /// @return the row
    private LineButton buildRemoteRow(DshRelease release) {
        JFXButton install = FXUtils.newToggleButton4(SVG.ADD, 18);
        install.setOnAction(event -> install(release.version()));

        LineButton row = new LineButton();
        row.setTitle(release.version());
        String tag = release.primaryTag();
        row.setSubtitle(tag == null ? i18n("dsh.versions.channel.prerelease") : tag);
        row.setTitleTrailing(install);
        row.setOnAction(event -> install(release.version()));
        return row;
    }

    /// Builds a bold section heading rendered as the first row of a card.
    ///
    /// @param text the heading text
    /// @return the heading row
    private Node buildSectionHeader(String text) {
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

    /// Installs a version in the background and refreshes on completion.
    ///
    /// @param version the version to install
    private void install(String version) {
        if (busy) {
            return;
        }
        busy = true;
        spinner.setLoading(true);
        status.setText(i18n("dsh.versions.installing", version));

        CompletableFuture.runAsync(() -> {
            try {
                DshVersionManager.install(version, null);
            } catch (DshException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
            busy = false;
            spinner.setLoading(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null
                        ? throwable.getCause()
                        : throwable;
                LOG.warning("Failed to install DSH " + version, cause);
                Controllers.dialog(cause.getMessage(), i18n("dsh.versions.install_failed"),
                        org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.ERROR);
            } else {
                Controllers.showToast(i18n("dsh.versions.installed", version));
            }
            refresh();
        }));
    }

    /// Removes an installed version after confirmation.
    ///
    /// @param version the installed version
    private void remove(DshVersion version) {
        Controllers.confirm(i18n("dsh.versions.remove.confirm", version.version()),
                i18n("dsh.versions.remove"),
                () -> CompletableFuture.runAsync(() -> {
                    try {
                        DshVersionManager.uninstall(version.version());
                    } catch (DshException e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
                    if (throwable != null) {
                        LOG.warning("Failed to remove DSH " + version.version(), throwable);
                        Controllers.dialog(String.valueOf(throwable.getMessage()),
                                i18n("dsh.versions.remove_failed"),
                                org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.ERROR);
                    } else {
                        Controllers.showToast(i18n("dsh.versions.removed", version.version()));
                    }
                    refresh();
                })),
                null);
    }

    /// The result of one background load.
    ///
    /// @param installed the locally installed versions
    /// @param remote    the published releases, empty when the registry failed
    /// @param error     the registry error message, or `null` on success
    private record LoadResult(List<DshVersion> installed, List<DshRelease> remote, @Nullable String error) {
    }
}
