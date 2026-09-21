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

import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXTextField;
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
import org.jackhuang.hmcl.ui.dsh.install.DshInstallWizardProvider;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
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
    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("dsh.versions.title")));

    /// The card listing locally installed versions.
    private final ComponentList installedList = new ComponentList();

    /// The card listing versions available from the registry.
    private final ComponentList remoteList = new ComponentList();

    /// The status line above the lists.
    private final Label status = new Label();

    /// The name box above the published list.
    private final JFXTextField nameField = new JFXTextField();

    /// The release-type filter above the published list.
    private final JFXComboBox<TypeFilter> typeFilter = new JFXComboBox();

    /// Every published release the last load returned, before filtering.
    private List<DshRelease> releases = List.of();

    /// What the type filter can be set to.
    ///
    /// Its own type with an "all" member, as the wizard step has: "all" has to be
    /// a real selection rather than a prompt, and a prompt is a different node
    /// with different padding.
    private enum TypeFilter {
        ALL, STABLE, RC, BETA, ALPHA, OTHER;

        /// Reports whether a release matches this filter.
        ///
        /// @param release the release
        /// @return whether it is shown
        boolean accepts(DshRelease release) {
            return this == ALL || release.type().name().equals(name());
        }

        /// Returns the translation key for this filter.
        ///
        /// @return the key suffix
        String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /// Covers the content while a refresh is running.
    private final SpinnerPane spinner = new SpinnerPane();

    /// Whether a refresh or install is currently in flight.
    private boolean busy;

    /// Creates the versions page and starts loading.
    public VersionsPage() {

        // No sidebar: the original's versions page has none, and a category
        // holding one item named after the page says the same thing twice.
        // Refresh belongs in the toolbar, where every other list page keeps it.
        // A raised button inside the card, as the download page's refresh is:
        // this is the same kind of list. The card goes on the container — both
        // classes set a background and the card's wins, so putting it on the
        // button turns the button into a panel.
        JFXButton refreshButton = FXUtils.newRaisedButton(i18n("button.refresh"));
        refreshButton.setOnAction(event -> refresh());

        nameField.setPromptText(i18n("download.name.prompt"));
        nameField.textProperty().addListener((observable, was, value) -> renderRemote());

        typeFilter.getItems().setAll(TypeFilter.values());
        typeFilter.getSelectionModel().select(TypeFilter.ALL);
        typeFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(@Nullable TypeFilter type) {
                return type == null ? i18n("download.type.all") : i18n("download.type." + type.id());
            }

            @Override
            public TypeFilter fromString(String string) {
                return TypeFilter.ALL;
            }
        });
        typeFilter.valueProperty().addListener((observable, was, value) -> renderRemote());

        // The wizard step's toolbar, on the page that manages the same list: a
        // list of published versions is searched and filtered the same way
        // wherever it appears, and a refresh button alone leaves the row looking
        // as though something had been dropped from it.
        HBox toolbar = new HBox(16,
                new Label(i18n("download.name")), nameField,
                new Label(i18n("download.type")), typeFilter,
                refreshButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("card");
        HBox.setHgrow(nameField, Priority.ALWAYS);
        BorderPane.setMargin(toolbar, new Insets(10, 10, 0, 10));

        VBox content = new VBox(10,
                status,
                ComponentList.createComponentListTitle(i18n("dsh.versions.section.installed")),
                installedList,
                ComponentList.createComponentListTitle(i18n("dsh.versions.section.available")),
                remoteList);
        content.setPadding(new Insets(10));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        // Must run after the content is installed: smooth scrolling binds to
        // the content node and fails on a null content.
        FXUtils.smoothScrolling(scroll);

        spinner.setContent(scroll);

        BorderPane layout = new BorderPane();
        layout.setTop(toolbar);
        layout.setCenter(spinner);
        setCenter(layout);

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

        releases = result.remote();
        renderRemote();
    }

    /// Rebuilds the published list from the last load and the filters.
    private void renderRemote() {
        remoteList.getContent().clear();

        String needle = nameField.getText() == null
                ? "" : nameField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        TypeFilter type = typeFilter.getValue();

        int shown = 0;
        for (DshRelease release : releases) {
            if (DshVersionManager.findInstalled(release.version()) != null) {
                continue;
            }
            if (type != null && !type.accepts(release)) {
                continue;
            }
            if (!needle.isEmpty() && !release.version().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                continue;
            }
            shown++;
            remoteList.getContent().add(buildRemoteRow(release));
        }
        if (shown == 0) {
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
        row.setRowTrailing(remove);
        row.setOnAction(event -> Controllers.getDecorator().startWizard(
                new DshInstallWizardProvider(version.version()), i18n("dsh.instance.create")));
        return row;
    }

    /// Builds one row for an installable version, with an install action.
    ///
    /// @param release the published release
    /// @return the row
    private LineButton buildRemoteRow(DshRelease release) {
        JFXButton install = FXUtils.newToggleButton4(SVG.ADD, 18);
        install.setOnAction(event -> createFrom(release.version()));
        FXUtils.installFastTooltip(install, i18n("dsh.versions.create_from"));

        // The row the wizard's version step uses. The two pages list the same
        // thing and a list reads better when its rows are one height: a thirty-
        // two pixel icon beside a tag and a date makes each row as tall as the
        // icon, and the dates make them uneven.
        LineButton row = new LineButton();
        row.setTitle(release.version());
        String tag = release.primaryTag();
        row.setSubtitle(tag == null ? i18n("dsh.versions.channel.prerelease") : tag);
        row.setLeading(SVG.DOWNLOAD, 16);
        row.setRowTrailing(install);
        row.setOnAction(event -> createFrom(release.version()));
        return row;
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
    /// Opens the create wizard for a version, installing it on the way.
    ///
    /// The version does not have to be on disk. The wizard downloads it as the
    /// first thing its task does and then installs the chosen plugins into the
    /// new profile, so the download has a progress dialog and a cancel button
    /// rather than a line of status text on a page the user has usually left.
    ///
    /// @param version the version to create an instance from
    private void createFrom(String version) {
        Controllers.getDecorator().startWizard(
                new DshInstallWizardProvider(version), i18n("dsh.instance.create"));
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
