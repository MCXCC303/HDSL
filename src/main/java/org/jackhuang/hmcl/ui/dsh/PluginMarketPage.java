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
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Skin;
import javafx.scene.control.SkinBase;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshPluginCatalog;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The community's plugin catalogue, browsable and installable.
///
/// The original's remote content pages are all one shape, and this is the shape:
/// a search form on a card at the top — what to search for, which category, how
/// to sort — the results beneath it, and a paging row under the form rather than
/// under the list, because the list is the part that scrolls. Its mod page
/// searches a repository server-side; the plugin catalogue is one document, so
/// the same form filters and pages what is already in hand.
///
/// Installing happens into the selected instance's profile, which is where a
/// plugin belongs: the original's page installs into the instance it is showing
/// for the same reason, and refuses without one rather than picking for the user.
@NotNullByDefault
public final class PluginMarketPage extends StackPane implements Refreshable {
    /// How many results a page holds.
    private static final int PAGE_SIZE = 24;

    /// Every plugin the catalogue listed.
    private final ObservableList<DshPluginCatalog.Plugin> all = FXCollections.observableArrayList();

    /// The plugins on the page being shown.
    private final ObservableList<DshPluginCatalog.Plugin> shown = FXCollections.observableArrayList();

    /// The list the results are drawn in.
    private final JFXListView<DshPluginCatalog.Plugin> listView = new JFXListView<>();

    /// The pane that reports loading and failure.
    private final SpinnerPane spinner = new SpinnerPane();

    /// What to search for.
    private final JFXTextField nameField = new JFXTextField();

    /// Which category to keep.
    private final JFXComboBox<String> categoryBox = new JFXComboBox<>();

    /// How to order the results.
    private final JFXComboBox<String> sortBox = new JFXComboBox<>();

    /// Where the page number is shown between the paging buttons.
    private final Label pageLabel = new Label();

    /// The instance the plugins are installed into.
    private final Label targetLabel = new Label();

    /// The page being shown, counted from one.
    private int page = 1;

    /// How many pages the current filter has.
    private int pageCount = 1;

    /// Whether the catalogue has been read.
    private boolean loaded;

    /// Whether a read is already running.
    private boolean busy;

    /// Creates the page.
    public PluginMarketPage() {
        getStyleClass().add("plugin-market-page");

        VBox root = new VBox();
        root.setSpacing(10);
        root.setPadding(new Insets(10, 10, 0, 10));

        root.getChildren().add(buildSearchPane());

        spinner.getStyleClass().add("card");
        VBox.setVgrow(spinner, Priority.ALWAYS);
        VBox.setMargin(spinner, new Insets(0, 0, 10, 0));

        listView.setPadding(Insets.EMPTY);
        listView.getStyleClass().add("no-horizontal-scrollbar");
        listView.setItems(shown);
        listView.setCellFactory(view -> new PluginCell());
        listView.setPlaceholder(placeholder(i18n("search.no_results_found")));
        spinner.setContent(listView);
        root.getChildren().add(spinner);

        getChildren().add(root);

        refresh();
    }

    /// Builds the search form.
    ///
    /// @return the form, on the surface the original puts it on
    private Node buildSearchPane() {
        GridPane pane = new GridPane();
        pane.getStyleClass().add("card");
        pane.setHgap(16);
        pane.setVgap(10);
        pane.setPadding(new Insets(10));

        ColumnConstraints first = new ColumnConstraints();
        ColumnConstraints second = new ColumnConstraints();
        second.setHgrow(Priority.ALWAYS);
        ColumnConstraints third = new ColumnConstraints();
        ColumnConstraints fourth = new ColumnConstraints();
        fourth.setHgrow(Priority.ALWAYS);
        pane.getColumnConstraints().setAll(first, second, third, fourth);

        nameField.setPromptText(i18n("search.hint.chinese"));
        HBox.setHgrow(nameField, Priority.ALWAYS);
        FXUtils.onChangeAndOperate(nameField.textProperty(), text -> search());
        pane.addRow(0, new Label(i18n("mods.name")), nameField, new Label(i18n("addon.category")), categoryBox);

        categoryBox.setMaxWidth(Double.MAX_VALUE);
        categoryBox.setConverter(FXUtils.stringConverter(Function.identity()));
        categoryBox.valueProperty().addListener((observable, was, value) -> search());

        sortBox.setMaxWidth(Double.MAX_VALUE);
        sortBox.setConverter(FXUtils.stringConverter(this::sortName));
        sortBox.getItems().setAll("downloads", "stars", "added");
        sortBox.setValue("downloads");
        sortBox.valueProperty().addListener((observable, was, value) -> search());
        pane.addRow(1, new Label(i18n("search.sort")), sortBox, new Label(i18n("dsh.download.instance")), targetLabel);

        JFXButton search = new JFXButton(i18n("search"));
        search.getStyleClass().add("jfx-button-raised");
        search.setOnAction(event -> search());

        HBox paging = new HBox(8);
        paging.setAlignment(Pos.CENTER_LEFT);
        paging.getChildren().setAll(pagingButtons());
        HBox.setHgrow(paging, Priority.ALWAYS);

        HBox buttons = new HBox(8, paging, search);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        pane.add(buttons, 0, 2, 4, 1);

        return pane;
    }

    /// Builds the paging buttons.
    ///
    /// @return the buttons, from the first page to the last
    private List<Node> pagingButtons() {
        JFXButton first = new JFXButton(i18n("search.first_page"));
        first.setOnAction(event -> showPage(1));
        JFXButton previous = new JFXButton(i18n("search.previous_page"));
        previous.setOnAction(event -> showPage(page - 1));
        JFXButton next = new JFXButton(i18n("search.next_page"));
        next.setOnAction(event -> showPage(page + 1));
        JFXButton last = new JFXButton(i18n("search.last_page"));
        last.setOnAction(event -> showPage(pageCount));

        return List.of(first, previous, pageLabel, next, last);
    }

    /// Returns the label for a sort key.
    ///
    /// @param key the sort key
    /// @return the label
    private String sortName(String key) {
        return switch (key == null ? "" : key) {
            case "stars" -> i18n("addon.sort.popularity");
            case "added" -> i18n("addon.sort.date_created");
            default -> i18n("addon.sort.total_downloads");
        };
    }

    /// Reads the catalogue if it has not been read, and draws the results.
    ///
    /// Reading is what a refresh means the first time; afterwards the filter is
    /// re-applied to what is already in hand, because the catalogue is one
    /// document rather than a query.
    @Override
    public void refresh() {
        if (loaded || busy) {
            search();
            return;
        }

        busy = true;
        spinner.setLoading(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                return DshPluginCatalog.fetch();
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((catalogue, failure) -> runInFX(() -> {
            busy = false;
            spinner.setLoading(false);

            if (failure != null || catalogue == null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                LOG.warning("Failed to read the plugin catalogue", cause);
                spinner.setFailedReason(i18n("dsh.versions.load_failed")
                        + (cause == null ? "" : ": " + cause.getMessage()));
                return;
            }

            List<String> categories = new ArrayList<>();
            categories.add("");
            categories.addAll(catalogue.categories());
            categoryBox.getItems().setAll(categories);
            categoryBox.setConverter(FXUtils.stringConverter(
                    key -> key == null || key.isEmpty() ? i18n("download.type.all") : key));
            categoryBox.setValue("");

            all.setAll(catalogue.plugins());
            loaded = true;
            LOG.info("Read " + all.size() + " plugins from the catalogue"
                    + (catalogue.updated() == null ? "" : ", updated " + catalogue.updated()));
            search();
        }));
    }

    /// Filters, sorts and pages what the catalogue holds.
    private void search() {
        DshInstance target = GameDirectoryManager.selectedInstanceProperty().get();
        targetLabel.setText(target == null ? i18n("dsh.market.no_instance") : target.id());

        String query = nameField.getText() == null ? "" : nameField.getText().trim().toLowerCase();
        List<DshPluginCatalog.Plugin> matching = new ArrayList<>();
        for (DshPluginCatalog.Plugin plugin : DshPluginCatalog.sorted(all, sortBox.getValue())) {
            if (!query.isEmpty() && !matches(plugin, query)) {
                continue;
            }
            matching.add(plugin);
        }

        pageCount = Math.max(1, (matching.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(Math.max(page, 1), pageCount);
        int from = (page - 1) * PAGE_SIZE;
        shown.setAll(matching.subList(from, Math.min(from + PAGE_SIZE, matching.size())));
        pageLabel.setText(page + " / " + pageCount);
    }

    /// Reports whether a plugin matches a search.
    ///
    /// @param plugin the plugin
    /// @param query  the query, already lower-cased
    /// @return whether it matches
    private static boolean matches(DshPluginCatalog.Plugin plugin, String query) {
        if (plugin.name().toLowerCase().contains(query)
                || plugin.owner().toLowerCase().contains(query)) {
            return true;
        }
        String description = plugin.localizedDescription();
        return description != null && description.toLowerCase().contains(query);
    }

    /// Shows a page of the results.
    ///
    /// @param number the page number, counted from one
    private void showPage(int number) {
        page = number;
        search();
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

    /// Installs one plugin into the selected instance.
    ///
    /// @param plugin the plugin
    private void install(DshPluginCatalog.Plugin plugin) {
        DshInstance instance = GameDirectoryManager.selectedInstanceProperty().get();
        if (instance == null) {
            Controllers.dialog(i18n("dsh.market.no_instance"), i18n("download.install"), MessageType.ERROR);
            return;
        }

        String spec = plugin.installSpec();
        if (spec == null) {
            Controllers.dialog(i18n("dsh.market.not_installable", plugin.name()),
                    i18n("download.install"), MessageType.ERROR);
            return;
        }

        InstallProgressDialog.run(i18n("download.install"),
                progress -> DshPluginInstaller.installSpecs(instance, List.of(spec), progress::accept),
                null);
    }

    /// One result row: the plugin, what it is, and what installing it takes.
    private final class PluginCell extends ListCell<DshPluginCatalog.Plugin> {
        @Override
        protected void updateItem(@Nullable DshPluginCatalog.Plugin plugin, boolean empty) {
            super.updateItem(plugin, empty);
            if (empty || plugin == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            BorderPane root = new BorderPane();
            root.getStyleClass().add("md-list-cell");
            root.setPadding(new Insets(8));

            TwoLineListItem content = new TwoLineListItem();
            content.setMouseTransparent(true);
            content.setTitle(plugin.name());
            content.setSubtitle(plugin.localizedDescription() == null
                    ? plugin.owner() : plugin.localizedDescription());
            content.addTags(List.of(plugin.category(), plugin.owner()));
            content.addTag(plugin.sourceKind());
            root.setCenter(content);

            HBox buttons = new HBox(8);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            buttons.setPickOnBounds(false);

            JFXButton install = FXUtils.newToggleButton4(SVG.DOWNLOAD);
            install.setDisable(plugin.installSpec() == null);
            FXUtils.installFastTooltip(install, i18n("download.install"));
            install.setOnAction(event -> {
                install(plugin);
                event.consume();
            });
            buttons.getChildren().add(install);

            if (plugin.hasRepository() && plugin.url().startsWith("http")) {
                JFXButton page = FXUtils.newToggleButton4(SVG.OPEN_IN_NEW);
                FXUtils.installFastTooltip(page, i18n("download.release_page"));
                page.setOnAction(event -> {
                    openPage(plugin.url());
                    event.consume();
                });
                buttons.getChildren().add(page);
            }

            root.setRight(buttons);
            setText(null);
            setGraphic(new RipplerContainer(root));
        }
    }

    /// Opens a plugin's page in the browser.
    ///
    /// @param url the address
    private static void openPage(String url) {
        Schedulers.io().execute(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                } else {
                    LOG.warning("No desktop integration to open " + url);
                }
            } catch (Exception e) {
                LOG.warning("Failed to open " + url, e);
            }
        });
    }
}
