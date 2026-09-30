/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
import org.jackhuang.hmcl.dsh.DshCommand;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceIcon;
import org.jackhuang.hmcl.dsh.DshLauncher;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.DshPackageRegistry;
import org.jackhuang.hmcl.dsh.DshPluginCatalog;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.dsh.DshPluginRequirements;
import org.jackhuang.hmcl.dsh.DshPluginVersions;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// One plugin, and the versions of it that can be installed.
///
/// The original's answer to the same question: a page per mod, opened from the
/// list, carrying what the mod is and which version to take. Its list row installs
/// nothing by itself — the row opens this, and the choice of version is made here,
/// where there is room to say what is being chosen.
///
/// Where the versions come from depends on what the catalogue said about the
/// plugin: one that publishes a package has a version list in the registry, and one
/// that only has a repository is installed from that repository at whatever it
/// currently is, so it gets a single row rather than a list.
@NotNullByDefault
public final class PluginDetailPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
    /// The plugin being described.
    private final DshPluginCatalog.Plugin plugin;

    /// The instance this page installs into, or `null` to use the selected one.
    ///
    /// A page reached from an instance's auto-install tab is about *that* instance,
    /// and one reached from the market is about whichever instance the market's own
    /// picker names; falling back to the launcher's selection is only what a page
    /// opened with nothing to say about its target can do.
    private final @Nullable DshInstance target;

    /// The page's state published to the window decorator.
    private final javafx.beans.property.ReadOnlyObjectWrapper<State> state;

    /// The rows the list draws: a heading, or one version under one.
    private final ObservableList<Row> rows = FXCollections.observableArrayList();

    /// The list the rows are drawn in.
    private final JFXListView<Row> listView = new JFXListView<>();

    /// The groups the registry's document arranged into, in the order they are drawn.
    private List<DshPluginVersions.Group> groups = List.of();

    /// The versions as the document described them, for a document that could not be read.
    ///
    /// The page keeps drawing a version list without a document — a registry that cannot be reached
    /// should not empty the page — and in that case the versions are all it has: no times, no
    /// grouping, and every row marked as having said nothing.
    private List<DshPluginVersions.Published> ungrouped = List.of();

    /// The harness version the groups were built for, or `null` when there was none.
    private @Nullable String harnessVersion;

    /// The harness packages that version holds, or `null` when they could not be read.
    private @Nullable Map<String, String> harnessCore;

    /// The groups that are open, by the harness version each is about.
    ///
    /// A group of versions that named nothing has no harness version and is kept under the empty
    /// string. The recommended group starts open and the others start closed, which is the original's
    /// own arrangement: the list answers "what should I install" first, and "what else is there" when
    /// it is asked.
    private final Set<String> opened = new LinkedHashSet<>();

    /// The pane that reports loading and failure.
    private final SpinnerPane spinner = new SpinnerPane();

    /// The version the chosen instance already has, or `null`.
    private @Nullable String installed;

    /// Whether a load is already running.
    private boolean busy;

    /// Whether this page has read the registry once already.
    ///
    /// The first load may answer from the copy the launcher already has; the refresh button asks
    /// again, which is what a person pressing it means.
    private boolean loadedOnce;

    /// Creates the page.
    ///
    /// @param plugin the plugin to describe
    public PluginDetailPage(DshPluginCatalog.Plugin plugin) {
        this(plugin, null);
    }

    /// Creates the page for one instance.
    ///
    /// @param plugin the plugin to describe
    /// @param target the instance to install into, or `null` for the selected one
    public PluginDetailPage(DshPluginCatalog.Plugin plugin, @Nullable DshInstance target) {
        this.plugin = plugin;
        this.target = target;
        this.state = new javafx.beans.property.ReadOnlyObjectWrapper<>(State.fromTitle(plugin.name()));

        // A border pane rather than a box: its centre takes whatever height is
        // left, where a box stops at the sum of its children's preferred heights —
        // which is what left the version list ending halfway down the window.
        BorderPane layout = new BorderPane();
        layout.setPadding(new Insets(10));
        layout.setTop(buildHeader());
        layout.setCenter(buildVersionList());
        BorderPane.setMargin(layout.getCenter(), new Insets(10, 0, 0, 0));

        setCenter(layout);
        refresh();
    }

    /// Builds the header: what the plugin is, and where its own page is.
    ///
    /// @return the header
    private Node buildHeader() {
        BorderPane card = new BorderPane();
        card.getStyleClass().add("card");
        card.setPadding(new Insets(10));

        StackPane icon = new StackPane();
        icon.getStyleClass().add("installer-item-image");
        Node mark = DshInstanceIcon.FABRIC.load() == null ? new Label() : new ImageContainer(32);
        if (mark instanceof ImageContainer container) {
            container.setImage(DshInstanceIcon.FABRIC.load());
        }
        icon.getChildren().add(mark);
        icon.setPadding(new Insets(0, 10, 0, 0));
        card.setLeft(icon);

        TwoLineListItem content = new TwoLineListItem();
        content.setTitle(plugin.name());
        content.setSubtitle(plugin.localizedDescription() == null
                ? plugin.owner() : plugin.localizedDescription());
        content.addTags(java.util.stream.Stream.of(plugin.category(), plugin.owner())
                .filter(tag -> tag != null && !tag.isBlank()).toList());
        content.addTag(plugin.sourceKind());
        if (plugin.version() != null && !plugin.version().isBlank()) {
            content.addTag(plugin.version());
        }
        card.setCenter(content);

        if (plugin.url() != null && plugin.url().startsWith("http")) {
            JFXButton page = new JFXButton(i18n("download.release_page"));
            // The label says what the link is: a plugin published as a package has a
            // package page rather than a repository one.
            if (!plugin.hasRepository()) {
                page.setText(i18n("dsh.market.package_page"));
            }
            page.setGraphic(SVG.OPEN_IN_NEW.createIcon(20));
            page.getStyleClass().add("jfx-tool-bar-button");
            page.setOnAction(event -> openExternal(plugin.url()));
            HBox right = new HBox(page);
            right.setAlignment(Pos.CENTER_RIGHT);
            card.setRight(right);
        }

        return card;
    }

    /// Builds the version list and the note above it.
    ///
    /// @return the list
    private Node buildVersionList() {
        VBox box = new VBox();

        Label title = new Label(i18n("dsh.market.versions"));
        title.setPadding(new Insets(0, 0, 6, 4));
        box.getChildren().add(title);

        listView.setPadding(Insets.EMPTY);
        listView.getStyleClass().add("no-horizontal-scrollbar");
        listView.setItems(rows);
        listView.setCellFactory(view -> new RowCell((JFXListView<Row>) view));
        listView.setPlaceholder(placeholder(i18n("dsh.market.versions.empty")));
        VBox.setVgrow(listView, Priority.ALWAYS);

        ComponentList surface = new ComponentList();
        surface.getStyleClass().add("no-padding");
        surface.getContent().add(listView);
        ComponentList.setVgrow(listView, Priority.ALWAYS);
        VBox.setVgrow(surface, Priority.ALWAYS);

        spinner.setContent(surface);
        VBox.setVgrow(spinner, Priority.ALWAYS);
        box.getChildren().add(spinner);
        return box;
    }

    /// Reads the versions the registry lists, and the version already installed.
    @Override
    public void refresh() {
        if (busy) {
            return;
        }
        busy = true;
        spinner.setLoading(true);

        DshInstance instance = target();
        boolean again = loadedOnce;
        loadedOnce = true;
        CompletableFuture.supplyAsync(() -> {
            List<DshPluginVersions.Published> published = new ArrayList<>();
            List<DshPluginVersions.Group> grouped = List.of();
            boolean fromDocument = false;
            if (plugin.npm() != null) {
                // One document rather than one query per version: what the page needs is something
                // about every version at once, and the registry holds exactly that. The first load
                // may take the copy already read; the refresh asks again.
                JsonObject document = DshPackageRegistry.packument(plugin.npm(), again);
                if (document != null) {
                    published.addAll(DshPluginVersions.read(document));
                    fromDocument = !published.isEmpty();
                }
                if (published.isEmpty()) {
                    // No document: the flat list this page always had, so a registry that cannot be
                    // read still shows which versions there are.
                    try {
                        // The instance's own runtime when it has one: an instance on a
                        // launcher-managed Node should not need the machine to have one.
                        DshNodeRuntime runtime = instance == null
                                ? DshNodeRuntime.detect().orElse(null)
                                : DshLauncher.resolveRuntime(instance);
                        for (String version : DshVersionManager.fetchPackageVersions(runtime, plugin.npm())) {
                            published.add(new DshPluginVersions.Published(version,
                                    DshPluginVersions.channelOf(version, null), null, new JsonObject(), List.of()));
                        }
                    } catch (DshException e) {
                        throw new CompletionException(e);
                    }
                }
            } else if (plugin.version() != null) {
                published.add(new DshPluginVersions.Published(plugin.version(),
                        DshPluginVersions.channelOf(plugin.version(), null), null, new JsonObject(), List.of()));
            }

            String version = instance == null ? null : instance.version();
            Map<String, String> core = instance == null
                    ? null : DshPluginRequirements.coreVersions(instance);
            if (fromDocument) {
                grouped = DshPluginVersions.group(published, version);
            }

            String held = null;
            if (instance != null) {
                try {
                    Map<String, String> dependencies = DshPluginInstaller.readDependencies(
                            instance.homeDirectory(), instance.profile());
                    held = dependencies.get(plugin.npm() == null ? plugin.name() : plugin.npm());
                } catch (DshException e) {
                    throw new CompletionException(e);
                }
            }
            return new Loaded(published, grouped, version, core, held);
        }, Schedulers.io()).whenComplete((loaded, failure) -> runInFX(() -> {
            busy = false;
            spinner.setLoading(false);
            if (failure != null || loaded == null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                LOG.warning("Failed to read the versions of " + plugin.name(), cause);
                spinner.setFailedReason(i18n("dsh.versions.load_failed")
                        + (cause == null ? "" : ": " + cause.getMessage()));
                return;
            }
            installed = loaded.held();
            groups = loaded.groups();
            ungrouped = loaded.versions();
            harnessVersion = loaded.harnessVersion();
            harnessCore = loaded.harnessCore();
            // The recommended group is open the first time a page for this instance is drawn; a group
            // somebody opened stays open across a refresh.
            if (!groups.isEmpty() && groups.get(0).recommended()) {
                opened.add(groups.get(0).harnessVersion() == null ? "" : groups.get(0).harnessVersion());
            }
            render();
        }));
    }

    /// Draws the groups the page loaded, honouring which of them are open.
    ///
    /// A group is drawn as its heading and, when it is open, the versions under it. Both are rows of
    /// one list rather than a list per group: a plugin with a hundred releases would otherwise put a
    /// hundred rows into a box that does not know how to scroll them.
    private void render() {
        List<Row> drawn = new ArrayList<>();
        if (groups.isEmpty()) {
            for (DshPluginVersions.Published version : ungrouped) {
                drawn.add(new VersionRow(version, fitOf(version), version.version().equals(installed)));
            }
        } else {
            for (DshPluginVersions.Group group : groups) {
                String key = group.harnessVersion() == null ? "" : group.harnessVersion();
                boolean open = opened.contains(key);
                drawn.add(new HeadingRow(key, headingOf(group), group.versions().size(), open));
                if (open) {
                    for (DshPluginVersions.Published version : group.versions()) {
                        drawn.add(new VersionRow(version, fitOf(version),
                                version.version().equals(installed)));
                    }
                }
            }
        }
        rows.setAll(drawn);
    }

    /// Returns what one version says about the harness the page is for.
    ///
    /// @param version the version
    /// @return what it says
    private DshPluginVersions.Fit fitOf(DshPluginVersions.Published version) {
        return DshPluginVersions.fitOf(version, harnessVersion, harnessCore);
    }

    /// Returns the heading a group is drawn under.
    ///
    /// @param group the group
    /// @return the heading
    private static String headingOf(DshPluginVersions.Group group) {
        if (group.recommended()) {
            return i18n("dsh.market.recommend", group.harnessVersion());
        }
        return group.harnessVersion() == null
                ? i18n("dsh.market.group.unclaimed")
                : i18n("dsh.market.group", group.harnessVersion());
    }

    /// Opens or closes one group.
    ///
    /// @param key the group's key
    private void toggleGroup(String key) {
        if (!opened.remove(key)) {
            opened.add(key);
        }
        render();
    }

    /// Returns the instance this page installs into.
    ///
    /// @return the selected instance, or `null` when none is selected
    private @Nullable DshInstance target() {
        return target != null ? target : GameDirectoryManager.selectedInstanceProperty().get();
    }

    /// Asks what to do with one version, the way the original does.
    ///
    /// @param version the version
    private void choose(String version) {
        DshInstance instance = target();
        String title = i18n("mods.download.title", plugin.name() + " " + version);
        MessageDialogPane.Builder builder = new MessageDialogPane.Builder(
                plugin.localizedDescription() == null ? plugin.name() : plugin.localizedDescription(),
                title, MessageType.QUESTION);

        builder.addAction(i18n("mods.install"), () -> installInto(instance, version));
        if (plugin.npm() != null) {
            builder.addAction(i18n("mods.save_as"), () -> saveToFolder(version));
        }
        builder.addCancel(null);
        Controllers.dialog(builder.build());
    }

    /// Installs one version into an instance.
    ///
    /// @param instance the instance, or `null` when none is selected
    /// @param version  the version
    private void installInto(@Nullable DshInstance instance, String version) {
        if (instance == null) {
            Controllers.dialog(i18n("dsh.market.no_instance"), i18n("download.install"), MessageType.ERROR);
            return;
        }

        String spec = plugin.npm() != null
                ? plugin.npm() + "@" + version
                : (plugin.installSpec() == null ? null : plugin.installSpec());
        if (spec == null) {
            Controllers.dialog(i18n("dsh.market.not_installable", plugin.name()),
                    i18n("download.install"), MessageType.ERROR);
            return;
        }

        String finalSpec = spec;
        // Back to where the page was opened from once it is installed: the versions
        // it lists are about to change, and the page a person came from is where
        // the result of installing is worth seeing.
        PluginInstalls.run(instance, progress ->
                        DshPluginInstaller.installSpecs(instance, List.of(finalSpec), progress::accept),
                () -> fireEvent(new org.jackhuang.hmcl.ui.construct.PageCloseEvent()));
    }

    /// Downloads one version into a folder the user chooses.
    ///
    /// The package is fetched by the package manager, which is what knows the
    /// registry the person's configuration points at — the same reason every other
    /// install here goes through it.
    ///
    /// @param version the version
    private void saveToFolder(String version) {
        if (plugin.npm() == null) {
            return;
        }
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle(i18n("mods.save_as"));
        java.io.File chosen = chooser.showDialog(Controllers.getStage());
        if (chosen == null) {
            return;
        }

        Path folder = chosen.toPath();
        String spec = plugin.npm() + "@" + version;
        ProgressDialog.run(i18n("mods.save_as"), progress -> {
            DshNodeRuntime runtime = DshNodeRuntime.detect().orElse(null);
            if (runtime == null || runtime.npm() == null) {
                throw new DshException("npm was not found on PATH; saving a package requires it");
            }
            progress.accept("Fetching " + spec);
            try {
                DshCommand.run(List.of(runtime.npm().toString(), "pack", spec,
                        "--pack-destination", folder.toString()), null, progress::accept);
            } catch (IOException e) {
                throw new DshException("Failed to run npm", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DshException("Saving the package was interrupted", e);
            }
        }, null);
    }

    /// Opens a plugin's own page in the browser.
    ///
    /// @param url the address
    private static void openExternal(String url) {
        Schedulers.io().execute(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                } else {
                    LOG.warning("No desktop integration to open " + url);
                }
            } catch (Exception e) {
                LOG.warning("Failed to open " + url, e);
            }
        });
    }

    @Override
    public javafx.beans.property.ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Builds the message a list with nothing in it shows.
    ///
    /// @param message the message
    /// @return the placeholder
    private static Node placeholder(String message) {
        Label label = new Label(message);
        StackPane container = new StackPane(label);
        container.getStyleClass().add("notice-pane");
        return container;
    }

    /// What one load read.
    ///
    /// @param versions       the versions, newest first
    /// @param groups         the groups they were arranged into, or an empty list when no document
    ///                       could be read and there is nothing to arrange them by
    /// @param harnessVersion the harness version the groups were built for, or `null`
    /// @param harnessCore    the harness packages that version holds, or `null`
    /// @param held           the installed version, or `null`
    private record Loaded(List<DshPluginVersions.Published> versions,
                          List<DshPluginVersions.Group> groups,
                          @Nullable String harnessVersion,
                          @Nullable Map<String, String> harnessCore,
                          @Nullable String held) {
    }

    /// One row of the list: a heading, or a version under one.
    private sealed interface Row permits HeadingRow, VersionRow {
    }

    /// A group's heading, which opens and closes it.
    ///
    /// @param key   the group's key
    /// @param title what it says
    /// @param count how many versions are under it
    /// @param open  whether they are shown
    private record HeadingRow(String key, String title, int count, boolean open) implements Row {
    }

    /// One installable version.
    ///
    /// @param published the version as the registry described it
    /// @param fit       what it says about the harness the page is for
    /// @param current   whether it is the version the instance holds
    private record VersionRow(DshPluginVersions.Published published, DshPluginVersions.Fit fit,
                              boolean current) implements Row {
    }

    /// One row: a heading, or a version.
    ///
    /// Built on the cell the other lists here use, so a row's own click is wired to the rippler —
    /// which is what takes the press — and the version's button does the same thing without making
    /// the row itself the only way in.
    private final class RowCell extends MDListCell<Row> {
        /// The version and its state.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The channel's mark, the circle the original puts beside a mod's file.
        private final StackPane mark = new StackPane();

        /// The button that chooses this version.
        private final JFXButton install = FXUtils.newToggleButton4(SVG.DOWNLOAD);

        /// Creates the cell.
        ///
        /// @param listView the list it belongs to
        RowCell(JFXListView<Row> listView) {
            super(listView);
            onClicked(() -> {
                Row item = getItem();
                if (item instanceof HeadingRow heading) {
                    toggleGroup(heading.key());
                } else if (item instanceof VersionRow version) {
                    choose(version.published().version());
                }
            });
            FXUtils.installFastTooltip(install, i18n("download.install"));
            install.setOnAction(event -> {
                Row item = getItem();
                if (item instanceof VersionRow version) {
                    installInto(target(), version.published().version());
                }
                event.consume();
            });
        }

        @Override
        protected void updateControl(@Nullable Row row, boolean empty) {
            if (empty || row == null) {
                return;
            }
            if (row instanceof HeadingRow heading) {
                updateHeading(heading);
            } else if (row instanceof VersionRow version) {
                updateVersion(version);
            }
        }

        /// Draws a group's heading: what it is about, how many versions are under it, and whether it
        /// is open.
        ///
        /// @param heading the heading
        private void updateHeading(HeadingRow heading) {
            Label title = new Label(heading.title());
            Label count = new Label(Integer.toString(heading.count()));
            count.getStyleClass().add("subtitle-label");
            SVG chevron = heading.open() ? SVG.KEYBOARD_ARROW_UP : SVG.KEYBOARD_ARROW_DOWN;
            HBox line = new HBox(6, title, count, chevron.createIcon(18));
            line.setAlignment(Pos.CENTER_LEFT);
            line.setPadding(new Insets(8, 8, 4, 8));
            getContainer().getChildren().setAll(line);
        }

        /// Draws one version: its channel, its name, when it was published, and what it says about
        /// the harness the page is for.
        ///
        /// @param version the version
        private void updateVersion(VersionRow version) {
            DshPluginVersions.Published published = version.published();

            // The cell is reused: everything the row held before must go, or every row shows the
            // same tags many times over.
            content.getTags().clear();
            mark.getChildren().clear();
            SVG channel = channelMark(published.channel());
            if (channel != null) {
                SVGContainer icon = channel.createIcon(24);
                icon.getStyleClass().addAll("addon-channel-icon", channelStyle(published.channel()));
                mark.getChildren().setAll(icon);
            }
            mark.setPadding(new Insets(0, 8, 0, 0));
            // A row without a mark still leaves the room for one, so the names line up down the list.
            mark.setMinWidth(24);

            content.setTitle(published.version());
            // `null` rather than an empty string: a version the registry gave no date for is drawn as
            // one line, and an empty second line is a gap under every row of it.
            content.setSubtitle(published.published() == null
                    ? null : I18n.formatDateTime(published.published()));
            content.addTag(i18n(channelKey(published.channel())));
            if (version.fit() == DshPluginVersions.Fit.NOT_FITS) {
                content.addTag(i18n("dsh.market.not_fitting"));
            }
            if (version.current()) {
                content.addTag(i18n("dsh.instance.upgrade.current"));
            }

            HBox left = new HBox(mark, content);
            left.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(content, Priority.ALWAYS);

            BorderPane row = new BorderPane();
            row.setPadding(new Insets(8));
            row.setLeft(left);
            HBox right = new HBox(install);
            right.setAlignment(Pos.CENTER_RIGHT);
            row.setRight(right);
            getContainer().getChildren().setAll(row);
        }
    }

    /// Returns the mark a channel is drawn with.
    ///
    /// The original's own circles: a release, a beta, an alpha. A candidate is drawn as a beta —
    /// there is no third circle, and a candidate is nearer a beta than either end — and a nightly
    /// gets none, because a mark meaning "something else" says less than no mark at all.
    ///
    /// @param channel the channel
    /// @return the mark, or `null` for a channel that is drawn without one
    private static @Nullable SVG channelMark(DshPluginVersions.Channel channel) {
        return switch (channel) {
            case STABLE -> SVG.RELEASE_CIRCLE;
            case RC, BETA -> SVG.BETA_CIRCLE;
            case ALPHA -> SVG.ALPHA_CIRCLE;
            case OTHER -> null;
        };
    }

    /// Returns the colour a channel's mark is drawn in.
    ///
    /// @param channel the channel
    /// @return the style class
    private static String channelStyle(DshPluginVersions.Channel channel) {
        return switch (channel) {
            case STABLE -> "release";
            case RC, BETA -> "beta";
            case ALPHA -> "alpha";
            case OTHER -> "";
        };
    }

    /// Returns the label a channel is shown with.
    ///
    /// The words the type filter already uses, so a row and the filter that hides it agree.
    ///
    /// @param channel the channel
    /// @return the translation key
    private static String channelKey(DshPluginVersions.Channel channel) {
        return switch (channel) {
            case STABLE -> "download.type.stable";
            case RC -> "download.type.rc";
            case BETA -> "download.type.beta";
            case ALPHA -> "download.type.alpha";
            case OTHER -> "download.type.other";
        };
    }
}
