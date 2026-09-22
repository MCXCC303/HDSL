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
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.DshPluginCatalog;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /// The versions the registry lists, newest first.
    private final ObservableList<String> versions = FXCollections.observableArrayList();

    /// The list the versions are drawn in.
    private final JFXListView<String> listView = new JFXListView<>();

    /// The pane that reports loading and failure.
    private final SpinnerPane spinner = new SpinnerPane();

    /// The version the chosen instance already has, or `null`.
    private @Nullable String installed;

    /// Whether a load is already running.
    private boolean busy;

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
        listView.setItems(versions);
        listView.setCellFactory(view -> new VersionCell((JFXListView<String>) view));
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
        CompletableFuture.supplyAsync(() -> {
            List<String> published = new ArrayList<>();
            try {
                if (plugin.npm() != null) {
                    published.addAll(DshVersionManager.fetchPackageVersions(plugin.npm()));
                } else if (plugin.version() != null) {
                    published.add(plugin.version());
                }
            } catch (DshException e) {
                throw new CompletionException(e);
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
            return new Loaded(published, held);
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
            versions.setAll(loaded.versions());
        }));
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
        ProgressDialog.run(i18n("download.install"), progress ->
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

    /// The versions the registry lists and the version already held.
    ///
    /// @param versions the versions, newest first
    /// @param held     the installed version, or `null`
    private record Loaded(List<String> versions, @Nullable String held) {
    }

    /// One version row: the version, and what installing it would do.
    ///
    /// Built on the cell the other lists here use, so the row's own click is wired
    /// to the rippler — which is what takes the press — and the version's button
    /// does the same thing without making the row itself the only way in.
    private final class VersionCell extends MDListCell<String> {
        /// The version and its state.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The button that chooses this version.
        private final JFXButton install = FXUtils.newToggleButton4(SVG.DOWNLOAD);

        /// Creates the cell.
        ///
        /// @param listView the list it belongs to
        VersionCell(JFXListView<String> listView) {
            super(listView);
            onClicked(() -> {
                String item = getItem();
                if (item != null) {
                    choose(item);
                }
            });
            FXUtils.installFastTooltip(install, i18n("download.install"));
            install.setOnAction(event -> {
                String item = getItem();
                if (item != null) {
                    installInto(target(), item);
                }
                event.consume();
            });
        }

        @Override
        protected void updateControl(@Nullable String version, boolean empty) {
            if (empty || version == null) {
                return;
            }
            content.setTitle(version);
            boolean current = version.equals(installed);
            content.setSubtitle(current ? i18n("dsh.instance.upgrade.current")
                    : (plugin.owner() == null || plugin.owner().isBlank() ? plugin.name() : plugin.owner()));
            // The original marks a mod's versions the same way, and a plugin's
            // versions are marked by the same rule: a pre-release says so in its
            // name, and anything that does not is a release.
            content.addTag(version.contains("-") ? i18n("addon.channel.beta") : i18n("addon.channel.release"));
            if (current) {
                content.addTag(i18n("dsh.instance.upgrade.current"));
            }

            BorderPane row = new BorderPane();
            row.setPadding(new Insets(8));
            row.setCenter(content);
            HBox right = new HBox(install);
            right.setAlignment(Pos.CENTER_RIGHT);
            row.setRight(right);
            getContainer().getChildren().setAll(row);
        }
    }
}
