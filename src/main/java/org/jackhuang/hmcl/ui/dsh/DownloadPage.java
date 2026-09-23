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
import com.jfoenix.controls.JFXSpinner;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.Event;
import javafx.scene.input.MouseButton;
import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshRelease;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.TabHeader;
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

    /// The name box and type filter, shared with the other version lists.
    private final VersionFilterBar filterBar = new VersionFilterBar(this::applyFilter, this::refresh);

    /// The tab showing the published versions.
    private final TabHeader.Tab<Node> versionsTab = new TabHeader.Tab<>("dshDownloadVersions");

    /// The tab showing the community's plugins.
    private final TabHeader.Tab<PluginMarketPage> marketTab = new TabHeader.Tab<>("dshDownloadPlugins");

    /// The tab showing the community's modpacks.
    ///
    /// A tab of its own rather than a section of the plugin market, because the two are different
    /// kinds of thing: a plugin is installed *into* an instance and a pack *brings* one. The
    /// original separates them the same way, its content tab listing packs, mods and resource packs
    /// as separate pages.
    private final TabHeader.Tab<PackMarketPage> packTab = new TabHeader.Tab<>("dshDownloadPacks");

    /// The pane the two tabs are shown in.
    private final TransitionPane tabs = new TransitionPane();

    /// The tab strip.
    private final TabHeader tab;


    /// The list of versions.
    private final JFXListView<DshRelease> releaseList = new JFXListView<>();

    /// What the page shows: the spinner, the list, or a notice.
    ///
    /// The original's version list is built this way, and the reason is that a
    /// list which is loading, empty and full are three different things to say —
    /// swapping them with a fade is how it says which one it means.
    private final TransitionPane contentPane = new TransitionPane();

    /// The spinner shown while the registry is being read.
    ///
    /// The original's own spinner control, because the rotation and its timing
    /// are the interface's: a hand-drawn busy indicator would spin differently.
    private final JFXSpinner spinner = new JFXSpinner();

    /// What the page is doing.
    private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.LOADING);

    /// Everything the last load returned, before filtering.
    private List<DshRelease> loaded = List.of();

    /// Whether a load is running.
    private boolean busy;

    /// What a load is doing, and therefore what the page shows.
    private enum Status {
        /// The registry is being read.
        LOADING,
        /// The registry answered.
        SUCCESS,
        /// The registry could not be read.
        FAILED
    }

    /// Creates the download page.
    public DownloadPage() {
        tab = new TabHeader(tabs);

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("download.new_game").toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, versionsTab, i18n("dsh.download.instance"),
                        SVG.STADIA_CONTROLLER, SVG.STADIA_CONTROLLER_FILL)
                // A pack is a **new game**, not content for one: it brings a whole profile with it,
                // and the original files it in this category rather than the next one — second,
                // directly under the version list, with the package icon. Putting it under 游戏内容
                // beside the plugins was my own arrangement, and the wrong one: installing a pack is
                // the other way of getting an instance, which is what this category is about.
                .addNavigationDrawerTab(tab, packTab, i18n("dsh.download.packs"),
                        SVG.PACKAGE2, SVG.PACKAGE2_FILL)
                // The community's plugins are content, not a game: the original
                // files its mods under the second category.
                .startCategory(i18n("download.content").toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, marketTab, i18n("dsh.download.plugins"),
                        SVG.EXTENSION, SVG.EXTENSION_FILL);
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

        // Nothing published at all reads differently from nothing matching what
        // was typed, so the first is a notice and the second is the list saying
        // so itself, as every other searchable list in the interface does.
        StackPane placeholder = new StackPane();
        placeholder.getStyleClass().add("notice-pane");
        placeholder.getChildren().add(new Label(i18n("search.no_results_found")));
        releaseList.setPlaceholder(placeholder);

        StackPane emptyPane = new StackPane();
        emptyPane.getStyleClass().add("notice-pane");
        emptyPane.getChildren().add(new Label(i18n("dsh.versions.available.empty")));

        // A failed read is worth retrying, and the original says so on the page
        // rather than in a dialog that has to be dismissed first.
        StackPane failedPane = new StackPane();
        failedPane.getStyleClass().add("notice-pane");
        Label retry = new Label(i18n("download.failed.refresh"));
        FXUtils.onClicked(retry, this::refresh);
        failedPane.getChildren().add(retry);

        FXUtils.onChangeAndOperate(status, now -> contentPane.setContent(switch (now) {
            case LOADING -> spinner;
            case SUCCESS -> loaded.isEmpty() ? emptyPane : pane;
            case FAILED -> failedPane;
        }, ContainerAnimations.FADE));

        Node toolbar = buildToolbar();
        toolbar.getStyleClass().add("card");
        BorderPane.setMargin(toolbar, new Insets(10, 10, 0, 10));

        BorderPane layout = new BorderPane();
        layout.setTop(toolbar);
        layout.setCenter(contentPane);

        versionsTab.setNodeSupplier(() -> layout);
        marketTab.setNodeSupplier(PluginMarketPage::new);
        packTab.setNodeSupplier(PackMarketPage::new);
        tab.getTabs().setAll(versionsTab, packTab, marketTab);
        tab.select(versionsTab, false);

        setCenter(tabs);

        releaseList.setCellFactory(view -> new ReleaseCell(this));

        refresh();
    }

    /// Selects one of the page's tabs by name.
    ///
    /// @param name the tab name: `versions` or `plugins`
    /// @return whether a tab was selected
    public boolean openTab(String name) {
        switch (name == null ? "" : name.trim().toLowerCase(Locale.ROOT)) {
            case "versions" -> tab.select(versionsTab, false);
            case "plugins" -> tab.select(marketTab, false);
            default -> {
                return false;
            }
        }
        return true;
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
        status.set(Status.LOADING);

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
                status.set(Status.FAILED);
                return;
            }
            loaded = releases;
            applyFilter();
            status.set(Status.SUCCESS);
        }));
    }

    /// Rebuilds the list from the loaded releases and the current filters.
    private void applyFilter() {
        List<DshRelease> shown = new ArrayList<>();
        for (DshRelease release : loaded) {
            if (filterBar.accepts(release.version())) {
                shown.add(release);
            }
        }

        releaseList.getItems().setAll(shown);
        releaseList.refresh();
    }

    /// Builds the toolbar above the list.
    ///
    /// @return the toolbar
    private VersionFilterBar buildToolbar() {
        Label nameLabel = new Label(i18n("download.name"));
        // The bar brings its own card and padding, as the page it came from did.
        BorderPane.setMargin(filterBar, new Insets(10, 10, 0, 10));
        return filterBar;
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
