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
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshPluginCatalog;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.PageAware;
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
public final class PluginMarketPage extends StackPane implements Refreshable, PageAware {
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

    /// The paging buttons, kept so their states can follow the page.
    private @Nullable JFXButton firstPage;
    private @Nullable JFXButton previousPage;
    private @Nullable JFXButton nextPage;
    private @Nullable JFXButton lastPage;

    /// The instance the plugins are installed into.
    ///
    /// A picker rather than a label, which is the original's own arrangement: its
    /// mod page leads with the instance the download will go into, because the
    /// same list is browsed for whichever instance the person is working on. It
    /// starts at whatever instance the launcher has selected and does not change
    /// that selection, so browsing the market never moves the launch button.
    private final JFXComboBox<DshInstance> instanceBox = new JFXComboBox<>();

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
        listView.setCellFactory(view -> new PluginCell((JFXListView<DshPluginCatalog.Plugin>) view));
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
        instanceBox.setMaxWidth(Double.MAX_VALUE);
        instanceBox.setConverter(FXUtils.stringConverter(
                instance -> instance == null ? i18n("dsh.market.no_instance") : instance.id()));
        instanceBox.getItems().setAll(DshInstanceManager.list());
        instanceBox.setValue(GameDirectoryManager.selectedInstanceProperty().get());
        pane.addRow(0, new Label(i18n("dsh.download.instance")), instanceBox,
                new Label(i18n("mods.name")), nameField);
        pane.addRow(1, new Label(i18n("addon.category")), categoryBox, new Label(i18n("search.sort")), sortBox);

        categoryBox.setMaxWidth(Double.MAX_VALUE);
        categoryBox.setConverter(FXUtils.stringConverter(Function.identity()));
        categoryBox.valueProperty().addListener((observable, was, value) -> search());

        sortBox.setMaxWidth(Double.MAX_VALUE);
        sortBox.setConverter(FXUtils.stringConverter(this::sortName));
        sortBox.getItems().setAll("downloads", "stars", "added");
        sortBox.setValue("downloads");
        sortBox.valueProperty().addListener((observable, was, value) -> search());

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
        // The original's own border buttons, which is what makes these look like
        // buttons rather than like labels that happen to be grey.
        firstPage = FXUtils.newBorderButton(i18n("search.first_page"));
        firstPage.setOnAction(event -> showPage(1));
        previousPage = FXUtils.newBorderButton(i18n("search.previous_page"));
        previousPage.setOnAction(event -> showPage(page - 1));
        nextPage = FXUtils.newBorderButton(i18n("search.next_page"));
        nextPage.setOnAction(event -> showPage(page + 1));
        lastPage = FXUtils.newBorderButton(i18n("search.last_page"));
        lastPage.setOnAction(event -> showPage(pageCount));
        updatePagingButtons();

        return List.of(firstPage, previousPage, pageLabel, nextPage, lastPage);
    }

    /// Disables the buttons whose direction has nowhere to go.
    ///
    /// A button that does nothing when pressed is worse than one that says so: the
    /// original leaves them pressable and ignores the press, and this says the same
    /// thing by greying them out at the ends of the range.
    private void updatePagingButtons() {
        if (firstPage == null) {
            return;
        }
        firstPage.setDisable(page <= 1);
        previousPage.setDisable(page <= 1);
        nextPage.setDisable(page >= pageCount);
        lastPage.setDisable(page >= pageCount);
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
    ///
    /// A catalogue that failed to load is not remembered as loaded, so entering the
    /// page again tries again: a network that was down for a moment should not cost
    /// the page for the rest of the session.
    @Override
    public void onPageShown() {
        if (!loaded) {
            refresh();
        }
    }

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
        updatePagingButtons();
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

    /// Returns the instance the page installs into.
    ///
    /// The picker's own choice, falling back to whatever the launcher has selected
    /// when the picker has none — which is what a fresh page shows, so browsing the
    /// market starts where the person already is.
    ///
    /// @return the instance, or `null` when there is none
    private @Nullable DshInstance target() {
        DshInstance chosen = instanceBox.getValue();
        return chosen != null ? chosen : GameDirectoryManager.selectedInstanceProperty().get();
    }

    /// One result row: the plugin, what it is, and the way to its own page.
    ///
    /// The shape the session and plugin lists use: a two-line item on the surface
    /// the list cells wear, with the row's action wired to the rippler — which is
    /// what takes the press, and the reason a handler put on the pane inside it
    /// never fires.
    private final class PluginCell extends MDListCell<DshPluginCatalog.Plugin> {
        /// The plugin's name, description and tags.
        private final TwoLineListItem content = new TwoLineListItem();

        /// Creates the cell.
        ///
        /// @param listView the list it belongs to
        /// Creates the cell.
        ///
        /// @param listView the list it belongs to
        PluginCell(JFXListView<DshPluginCatalog.Plugin> listView) {
            super(listView);
            onClicked(() -> {
                DshPluginCatalog.Plugin item = getItem();
                if (item != null) {
                    Controllers.navigate(new PluginDetailPage(item, target()));
                }
            });
        }

        @Override
        protected void updateControl(@Nullable DshPluginCatalog.Plugin plugin, boolean empty) {
            if (empty || plugin == null) {
                return;
            }
            content.setTitle(plugin.name());
            content.setSubtitle(plugin.localizedDescription() == null
                    ? plugin.owner() : plugin.localizedDescription());
            // A cell is reused for whatever scrolls into it, so what it said before
            // has to go: otherwise every row wears the tags of the rows it replaced.
            content.getTags().clear();
            content.addTags(List.of(plugin.category(), plugin.owner()));
            content.addTag(plugin.sourceKind());

            JFXButton open = FXUtils.newToggleButton4(SVG.ARROW_FORWARD);
            open.setMouseTransparent(true);
            BorderPane row = new BorderPane();
            row.setPadding(new Insets(8));
            row.setCenter(content);
            HBox right = new HBox(open);
            right.setAlignment(Pos.CENTER_RIGHT);
            row.setRight(right);
            getContainer().getChildren().setAll(row);
        }
    }
}
