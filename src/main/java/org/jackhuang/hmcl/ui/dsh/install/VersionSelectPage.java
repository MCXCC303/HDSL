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
package org.jackhuang.hmcl.ui.dsh.install;

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshVersion;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshRelease;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The first step of the install wizard: choose which DeepSeek Harness version
/// the new instance pins.
///
/// Mirrors HMCL's version-selection step. Unlike HMCL, the chosen version must
/// already be installed, and offers the published ones that are not: choosing an
/// uninstalled version downloads it as the first step of the install task, so a
/// new instance can be created without visiting the Versions page first.
/// keeping the two apart means the wizard never has to explain two different
/// progress bars.
@NotNullByDefault
public final class VersionSelectPage extends VBox implements WizardPage {
    /// The wizard controller used to advance.
    private final WizardController controller;

    /// The placeholder shown while the published versions load.
    private final LineTextPane remoteStatus = new LineTextPane();

    /// The card holding the published versions.
    private final ComponentList remoteList = new ComponentList();

    /// Creates the version-selection page.
    ///
    /// @param controller the wizard controller
    public VersionSelectPage(WizardController controller) {
        this.controller = controller;

        setSpacing(10);
        setPadding(new Insets(20));
        setAlignment(Pos.TOP_LEFT);

        Label title = new Label(i18n("dsh.install.step.version"));
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        List<DshVersion> installed = DshVersionManager.listInstalled();

        ComponentList installedList = new ComponentList();
        if (installed.isEmpty()) {
            installedList.getContent().add(buildNote(i18n("dsh.install.step.version.empty")));
        } else {
            for (DshVersion version : installed) {
                installedList.getContent().add(buildVersionRow(version, true));
            }
        }

        remoteStatus.setText(i18n("dsh.versions.loading"));
        remoteList.getContent().add(remoteStatus);

        // Titles sit between the cards rather than inside them, which is how
        // HMCL lays a titled list out.
        VBox body = new VBox(10,
                ComponentList.createComponentListTitle(i18n("dsh.install.version.installed")),
                installedList,
                ComponentList.createComponentListTitle(i18n("dsh.install.version.remote")),
                remoteList);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        FXUtils.smoothScrolling(scroll);

        getChildren().addAll(title, scroll, buildFooter());
        VBox.setVgrow(scroll, Priority.ALWAYS);

        loadRemote(installed);
    }

    /// Loads the published versions that are not installed yet.
    ///
    /// A failure here is not fatal: the page still offers what is installed, so
    /// a network problem must not block creating an instance from a local copy.
    ///
    /// @param installed the already-installed versions
    private void loadRemote(List<DshVersion> installed) {
        java.util.Set<String> installedNames = new java.util.HashSet<>();
        for (DshVersion version : installed) {
            installedNames.add(version.version());
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return DshVersionManager.fetchReleases();
            } catch (DshException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((releases, throwable) -> runInFX(() -> {
            remoteList.getContent().remove(remoteStatus);

            if (throwable != null) {
                Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                        && throwable.getCause() != null ? throwable.getCause() : throwable;
                LOG.warning("Failed to list published versions", cause);
                LineTextPane failure = new LineTextPane();
                failure.setText(i18n("dsh.versions.load_failed") + ": " + cause.getMessage());
                remoteList.getContent().add(failure);
                return;
            }

            int added = 0;
            for (DshRelease release : releases) {
                if (installedNames.contains(release.version())) {
                    continue;
                }
                remoteList.getContent().add(buildReleaseRow(release));
                added++;
            }
            if (added == 0) {
                LineTextPane empty = new LineTextPane();
                empty.setText(i18n("dsh.versions.remote.empty"));
                remoteList.getContent().add(empty);
            }
        }));
    }

    /// Builds a row for a published version that still has to be downloaded.
    ///
    /// @param release the published release
    /// @return the row
    private LineButton buildReleaseRow(DshRelease release) {
        LineButton row = new LineButton();
        row.setTitle(release.version());
        row.setSubtitle(release.isPrerelease()
                ? i18n("dsh.versions.prerelease")
                : i18n("dsh.versions.will_download"));
        row.setLeading(SVG.DOWNLOAD, 16);
        row.setOnAction(event -> {
            choose(release.version());
        });
        return row;
    }

    /// Builds one selectable version row.
    ///
    /// @param version the installed version
    /// @return the row
    private LineButton buildVersionRow(DshVersion version, boolean installed) {
        LineButton row = new LineButton();
        row.setTitle(version.version());
        row.setSubtitle(installed ? version.directory().toString() : i18n("dsh.versions.will_download"));
        row.setOnAction(event -> {
            choose(version.version());
        });
        return row;
    }

    /// Builds the page footer.
    ///
    /// @return the footer
    private HBox buildFooter() {
        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.setOnAction(event -> controller.onCancel());

        JFXButton next = new JFXButton(i18n("button.next"));
        next.getStyleClass().add("dialog-accept");
        String selected = controller.getSettings().get(DshInstallWizardProvider.VERSION);
        next.setDisable(selected == null || selected.isBlank());
        next.setOnAction(event -> {
            if (selected != null && !selected.isBlank()) {
                choose(selected);
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(8, cancel, spacer, next);
        footer.setAlignment(Pos.CENTER_RIGHT);
        return footer;
    }

    /// Records the version and moves on to the create page.
    ///
    /// Choosing the version is a step, not a detour: it is what is being
    /// installed, and the page after it states that choice rather than asking
    /// for it again.
    ///
    /// @param version the chosen version
    private void choose(String version) {
        controller.getSettings().put(DshInstallWizardProvider.VERSION, version);
        controller.onNext();
    }

    /// Builds a non-interactive note row.
    ///
    /// @param text the text to show
    /// @return the row
    private javafx.scene.Node buildNote(String text) {
        LineTextPane note = new LineTextPane();
        note.setText(text);
        return note;
    }

    @Override
    public void onNavigate(SettingsMap settings) {
        // Rebuild the footer so the next button reflects a selection made after
        // the page was first created.
        getChildren().set(getChildren().size() - 1, buildFooter());
    }

    @Override
    public String getTitle() {
        return i18n("dsh.install.step.version");
    }
}
