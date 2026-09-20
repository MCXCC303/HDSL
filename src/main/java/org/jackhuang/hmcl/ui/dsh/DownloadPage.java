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
import javafx.event.Event;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.Cursor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
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
import org.jackhuang.hmcl.ui.construct.ComponentList;
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

    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("download")));

    /// The name typed into the filter.
    private final JFXTextField searchField = new JFXTextField();

    /// The release types the list can be narrowed to.
    private final JFXComboBox<TypeFilter> typeFilter = new JFXComboBox<>();

    /// What the type filter can be set to.
    ///
    /// Its own type rather than [DshRelease.Type], as in the original: "all" is a
    /// filter, not a kind of release, and it has to be a real selection. Shown as
    /// a prompt instead it renders through a different node with different
    /// padding, which puts the text a few pixels from where the original's sits.
    private enum TypeFilter {
        ALL,
        STABLE,
        RC,
        BETA,
        ALPHA,
        OTHER;

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

        // The original puts the toolbar above the list rather than inside it:
        // a BorderPane's top, carrying the `card` class and inset by ten on
        // three sides. `card` is what makes it float — a surface-container at
        // eighty per cent with a drop shadow — and sitting on the list instead
        // of in it is what lets the shadow read.
        //
        // The list below keeps the ComponentList surface: it wraps each child in
        // a node wearing `options-list-item`, whose rule carries `-monet-surface`.
        StackPane pane = new StackPane();
        pane.setPadding(new Insets(10));
        pane.getStyleClass().add("notice-pane");

        ComponentList root = new ComponentList();
        root.getStyleClass().add("no-padding");
        root.getContent().add(releaseList);
        // See the note on the instance list: the box applies this to its own wrapper.
        ComponentList.setVgrow(releaseList, Priority.ALWAYS);
        pane.getChildren().setAll(root);

        Node toolbar = buildToolbar();
        toolbar.getStyleClass().add("card");
        BorderPane.setMargin(toolbar, new Insets(10, 10, 0, 10));

        BorderPane layout = new BorderPane();
        layout.setTop(toolbar);
        layout.setCenter(pane);

        setCenter(layout);

        releaseList.setCellFactory(view -> new ReleaseCell(this));

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
        // The filter opens on "all": DeepSeek Harness has published no stable
        // release, so a list that started on "stable" would open empty.
        TypeFilter type = typeFilter.getValue();

        List<DshRelease> shown = new ArrayList<>();
        for (DshRelease release : loaded) {
            if (type != null && !type.accepts(release)) {
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
    private GridPane buildToolbar() {
        Label nameLabel = new Label(i18n("download.name"));
        searchField.setPromptText(i18n("download.name.prompt"));
        searchField.textProperty().addListener((observable, was, value) -> applyFilter());

        Label typeLabel = new Label(i18n("download.type"));
        typeFilter.getItems().setAll(TypeFilter.values());
        // A selection, not a prompt: the original selects its "all" entry.
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
        typeFilter.valueProperty().addListener((observable, was, value) -> applyFilter());

        // The original's refresh is a raised button, not the flat toolbar button
        // its other pages use, and it follows the filter rather than being pushed
        // to the far edge. A raised button is taller than a flat one, and the
        // difference was the whole of this row's missing height.
        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(event -> refresh());

        // The original lays this row out as a grid rather than a box, and the
        // columns are the point: the labels take their own width, the name field
        // takes everything left over, and the type filter is capped. A box with
        // fixed widths gives a field that is narrower than the original's and a
        // filter that is wider.
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        ColumnConstraints filterColumn = new ColumnConstraints();
        filterColumn.setMaxWidth(150);
        ColumnConstraints actionColumn = new ColumnConstraints();

        GridPane toolbar = new GridPane();
        toolbar.getColumnConstraints().setAll(labelColumn, fieldColumn, labelColumn, filterColumn, actionColumn);
        toolbar.setHgap(16);
        toolbar.setVgap(10);
        toolbar.addRow(0, nameLabel, searchField, typeLabel, typeFilter, refresh);
        // The card class supplies the padding and the surface.
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
            // The original's row: a stack carrying the separator, an HBox inside
            // it, and the whole thing inside a rippler. The margin is the part
            // that shows — ten above and below against the eight this had, which
            // is the four pixels by which its rows were shorter.
            StackPane root = new StackPane();
            root.getStyleClass().add("md-list-cell");

            content.setMouseTransparent(true);
            HBox.setHgrow(content, Priority.ALWAYS);

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

            // Sixteen between the icon, the text and the actions, centred — the
            // original's row. Without the spacing the text sits against the icon,
            // which reads as a tighter list than the original's.
            HBox row = new HBox(16, icon, content, right);
            row.setAlignment(Pos.CENTER);
            content.setAlignment(Pos.CENTER);
            StackPane.setMargin(row, new Insets(10, 16, 10, 16));
            root.getChildren().setAll(row);

            // The whole row opens the install page, as its arrow does — the
            // arrow is the mark that says so, not the only place that works.
            // The two buttons take their clicks first, so a press on either does
            // not also open what the row would.
            for (Node button : new Node[]{link, install}) {
                button.addEventFilter(MouseEvent.MOUSE_CLICKED, Event::consume);
            }

            RipplerContainer rippler = new RipplerContainer(root);
            rippler.setOnMouseClicked(event -> {
                DshRelease release = getItem();
                if (release != null && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
                    page.install(release);
                    event.consume();
                }
            });
            root.setCursor(Cursor.HAND);

            this.graphic = rippler;
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
            // The original formats a release date with the localising helper
            // rather than a fixed pattern, so the date reads the way dates read
            // in the interface's language.
            content.setSubtitle(release.publishedAt() == null
                    ? i18n("dsh.session.unknown_time")
                    : org.jackhuang.hmcl.util.i18n.I18n.formatDateTime(Instant.parse(release.publishedAt())));
        }
    }
}
