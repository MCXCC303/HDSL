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
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXTextField;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshRelease;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.dsh.install.DshInstallWizardProvider;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Lists the DeepSeek Harness versions that can be installed.
///
/// The shape is HMCL's download page: a toolbar carrying a name filter, a type
/// filter and a refresh, above a flat list of versions. Each row states what the
/// release is and when it was published, offers its upstream release page, and
/// installs it.
///
/// Only the game section is reproduced. The original's other categories — mods,
/// resource packs, shaders, worlds — search for content to install *into* an
/// instance, which is a different problem and is deliberately not attempted
/// here.
@NotNullByDefault
public final class DownloadPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
    /// The upstream repository, where each version's release page lives.
    private static final String RELEASES = "https://github.com/deepseek-ai/deepseek-harness/releases/tag/dsh-v";

    /// Formats a publication time the way the original shows one.
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("download")));

    /// The name typed into the filter.
    private final JFXTextField searchField = new JFXTextField();

    /// The release types the list can be narrowed to.
    private final JFXComboBox<DshRelease.Type> typeFilter = new JFXComboBox<>();

    /// The list of versions.
    private final JFXListView<DshRelease> releaseList = new JFXListView<>();

    /// Everything the last load returned, before filtering.
    private List<DshRelease> loaded = List.of();

    /// Whether a load is running.
    private boolean busy;

    /// Creates the download page.
    public DownloadPage() {
        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("download.new_game").toUpperCase(Locale.ROOT))
                .addNavigationDrawerItem(i18n("dsh.download.instance"), SVG.STADIA_CONTROLLER, this::refresh);
        FXUtils.setLimitWidth(sideBar, 200);
        setLeft(sideBar);

        VBox content = new VBox(buildToolbar(), releaseList);
        VBox.setVgrow(releaseList, Priority.ALWAYS);
        setCenter(content);

        releaseList.setCellFactory(view -> new ReleaseCell(this));
        releaseList.getStyleClass().add("edge-to-edge");

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

        CompletableFuture.supplyAsync(() -> {
            try {
                return DshVersionManager.fetchReleases();
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((releases, throwable) -> runInFX(() -> {
            busy = false;
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause() : throwable;
                LOG.warning("Failed to fetch releases", cause);
                Controllers.dialog(cause.getMessage(), i18n("dsh.versions.load_failed"), MessageType.ERROR);
                return;
            }
            loaded = releases;
            applyFilter();
        }));
    }

    /// Rebuilds the list from the loaded releases and the current filters.
    private void applyFilter() {
        String needle = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        DshRelease.Type type = typeFilter.getValue();

        List<DshRelease> shown = new ArrayList<>();
        for (DshRelease release : loaded) {
            // The type filter defaults to no selection, which means everything:
            // DeepSeek Harness has published no stable release, so a list that
            // started on "stable" would open empty.
            if (type != null && release.type() != type) {
                continue;
            }
            if (!needle.isEmpty() && !release.version().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            shown.add(release);
        }

        releaseList.getItems().setAll(shown);
        releaseList.refresh();
    }

    /// Builds the toolbar above the list.
    ///
    /// @return the toolbar
    private HBox buildToolbar() {
        Label nameLabel = new Label(i18n("download.name"));
        searchField.setPromptText(i18n("download.name.prompt"));
        searchField.setPrefWidth(240);
        searchField.textProperty().addListener((observable, was, value) -> applyFilter());

        Label typeLabel = new Label(i18n("download.type"));
        typeFilter.getItems().setAll(DshRelease.Type.values());
        typeFilter.setValue(null);
        typeFilter.setPromptText(i18n("download.type.all"));
        typeFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(@Nullable DshRelease.Type type) {
                return type == null ? i18n("download.type.all") : i18n("download.type." + type.id());
            }

            @Override
            public DshRelease.Type fromString(String string) {
                return null;
            }
        });
        typeFilter.valueProperty().addListener((observable, was, value) -> applyFilter());

        JFXButton refresh = new JFXButton(i18n("button.refresh"));
        refresh.setGraphic(SVG.REFRESH.createIcon(18));
        refresh.getStyleClass().add("jfx-tool-bar-button");
        refresh.setOnAction(event -> refresh());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(8, nameLabel, searchField, typeLabel, typeFilter, spacer, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));
        return toolbar;
    }

    /// Starts installing a version into a new instance.
    ///
    /// Installing is creating an instance pinned to that version, which is what
    /// the original does too: there is no such thing as a version installed
    /// outside an instance here.
    ///
    /// @param release the release to install
    private void install(DshRelease release) {
        Controllers.getDecorator().startWizard(
                new DshInstallWizardProvider(release.version()),
                i18n("dsh.instance.create"));
    }

    /// One row of the version list.
    ///
    /// The shape is the original's download row: the version, a chip naming what
    /// kind of release it is, when it was published beneath, and the two actions
    /// on the trailing edge — the upstream release page and installing it.
    private static final class ReleaseCell extends ListCell<DshRelease> {
        /// The page whose action the install button runs.
        private final DownloadPage page;

        /// The version icon.
        private final ImageContainer icon = new ImageContainer(32);

        /// The version, its chip and its date.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The button opening the upstream release page.
        private final JFXButton link = FXUtils.newToggleButton4(SVG.PUBLIC);

        /// The button installing the version.
        private final JFXButton install = FXUtils.newToggleButton4(SVG.ARROW_FORWARD);

        /// The row's graphic, re-installed for every item.
        private final RipplerContainer graphic;

        /// Creates the cell.
        ///
        /// @param page the page the row's install action belongs to
        ReleaseCell(DownloadPage page) {
            this.page = page;
            BorderPane root = new BorderPane();
            root.getStyleClass().add("md-list-cell");
            root.setPadding(new Insets(8));

            StackPane left = new StackPane(icon);
            left.setPadding(new Insets(0, 8, 0, 0));
            root.setLeft(left);

            content.setMouseTransparent(true);
            HBox.setHgrow(content, Priority.ALWAYS);
            root.setCenter(content);

            HBox right = new HBox(8);
            right.setAlignment(Pos.CENTER_RIGHT);
            FXUtils.installFastTooltip(link, i18n("download.release_page"));
            FXUtils.installFastTooltip(install, i18n("download.install"));
            link.setOnAction(event -> {
                DshRelease release = getItem();
                if (release != null) {
                    FXUtils.openLink(RELEASES + release.version());
                }
            });
            install.setOnAction(event -> {
                DshRelease release = getItem();
                if (release != null) {
                    page.install(release);
                }
            });
            right.getChildren().setAll(link, install);
            root.setRight(right);

            this.graphic = new RipplerContainer(root);
            setGraphic(graphic);
        }

        @Override
        protected void updateItem(@Nullable DshRelease release, boolean empty) {
            super.updateItem(release, empty);

            if (empty || release == null) {
                setGraphic(null);
                return;
            }

            setGraphic(graphic);
            icon.setImage(org.jackhuang.hmcl.dsh.DshInstanceIcon.DSH_APPLICATION.load());

            content.setTitle(release.version());
            content.getTags().clear();
            content.addTag(i18n("download.type." + release.type().id()));
            content.setSubtitle(release.publishedAt() == null
                    ? i18n("dsh.session.unknown_time")
                    : TIMESTAMP.format(Instant.parse(release.publishedAt())));
        }
    }
}
