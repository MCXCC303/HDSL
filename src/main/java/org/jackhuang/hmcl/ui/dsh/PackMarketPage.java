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
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshPackMarket;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The community's modpack market, browsable and installable.
///
/// The same page as the plugin market beside it, because they answer the same question and are
/// reached from the same place: a search form on a card at the top — what to search for, which
/// category, how to sort — the results beneath it, and the paging under the form rather than under
/// the list, because the list is the part that scrolls. That is the shape the original's remote
/// content pages have, and a second page with a different shape for the same job would be a second
/// thing to learn for no reason.
///
/// One difference is worth stating, because it is the whole of the market's design: **this page reads
/// one document**. The index carries everything a list needs — the name, the description, the author,
/// the size, and the three values needed to fetch the pack — and deliberately does not carry the
/// manifests. Those are fetched one at a time, when somebody opens a pack, which is what keeps three
/// packs and three thousand packs the same amount of work to list.
@NotNullByDefault
public final class PackMarketPage extends StackPane implements DecoratorPage, Refreshable {
    /// How many results a page holds.
    private static final int PAGE_SIZE = 24;

    /// Every pack the index listed.
    private final ObservableList<DshPackMarket.Entry> all = FXCollections.observableArrayList();

    /// The packs on the page being shown.
    private final ObservableList<DshPackMarket.Entry> shown = FXCollections.observableArrayList();

    /// The list the results are drawn in.
    private final JFXListView<DshPackMarket.Entry> listView = new JFXListView<>();

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
    private final JFXButton firstPage = FXUtils.newBorderButton(i18n("search.first_page"));
    private final JFXButton previousPage = FXUtils.newBorderButton(i18n("search.previous_page"));
    private final JFXButton nextPage = FXUtils.newBorderButton(i18n("search.next_page"));
    private final JFXButton lastPage = FXUtils.newBorderButton(i18n("search.last_page"));

    /// Which page is being shown, counting from one.
    private int page = 1;

    /// How many pages the current filter has.
    private int pageCount = 1;

    /// Where the index came from, for the line under the form.
    private @Nullable String generatedAt;

    /// When the index was assembled, or empty.
    private final Label updatedLabel = new Label();

    /// The page's state for the title bar.
    private final javafx.beans.property.ReadOnlyObjectWrapper<State> state =
            new javafx.beans.property.ReadOnlyObjectWrapper<>(State.fromTitle(i18n("dsh.download.packs")));

    /// Creates the page.
    public PackMarketPage() {
        getStyleClass().add("pack-market-page");

        VBox root = new VBox();
        root.setSpacing(10);
        root.setPadding(new Insets(10, 10, 0, 10));
        root.getChildren().add(buildSearchPane());

        VBox.setVgrow(spinner, Priority.ALWAYS);
        VBox.setMargin(spinner, new Insets(0, 0, 10, 0));

        listView.setPadding(Insets.EMPTY);
        listView.getStyleClass().add("no-horizontal-scrollbar");
        listView.setItems(shown);
        listView.setCellFactory(view -> new PackCell((JFXListView<DshPackMarket.Entry>) view));
        listView.setPlaceholder(placeholder(i18n("search.no_results_found")));
        spinner.setContent(listView);
        root.getChildren().add(spinner);

        getChildren().add(root);
        refresh();
    }

    /// Builds the search form.
    ///
    /// The original's own search panel, from the tab it keeps its mods in: the thing being searched
    /// takes the top line, then two rows of name-and-box, and the paging sits with the button at the
    /// end.
    ///
    ///   `[ 名称 ............ ] [ 类别 ......... ▾ ]`
    ///   `[ 排序 ........... ▾ ]`
    ///   `[ 分页 … ]                                   [ 刷新 ]`
    ///
    /// @return the form, on the surface the original puts it on
    private Node buildSearchPane() {
        GridPane pane = new GridPane();
        pane.getStyleClass().add("card");
        pane.setHgap(16);
        pane.setVgap(10);
        pane.setPadding(new Insets(10));

        ColumnConstraints nameColumn = new ColumnConstraints();
        nameColumn.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        ColumnConstraints secondFieldColumn = new ColumnConstraints();
        secondFieldColumn.setHgrow(Priority.ALWAYS);
        pane.getColumnConstraints().setAll(nameColumn, fieldColumn, nameColumn, secondFieldColumn);

        nameField.setPromptText(i18n("search.hint.chinese"));
        // A grid cell gives a child what it asks for, and a field asks for the width of its prompt;
        // without this the box stops short of the column it is in.
        nameField.setMaxWidth(Double.MAX_VALUE);
        FXUtils.onChangeAndOperate(nameField.textProperty(), text -> search());

        categoryBox.setMaxWidth(Double.MAX_VALUE);
        categoryBox.setConverter(FXUtils.stringConverter(choice -> choice));
        categoryBox.getItems().setAll(i18n("download.type.all"));
        categoryBox.setValue(i18n("download.type.all"));
        categoryBox.valueProperty().addListener(observable -> search());

        sortBox.setMaxWidth(Double.MAX_VALUE);
        sortBox.getItems().setAll(i18n("addon.sort.date_created"), i18n("addon.sort.popularity"),
                i18n("dsh.market.sort.name"));
        sortBox.setValue(i18n("addon.sort.date_created"));
        sortBox.valueProperty().addListener(observable -> search());

        pane.add(new Label(i18n("modpack.name")), 0, 0);
        pane.add(nameField, 1, 0);
        pane.add(new Label(i18n("addon.category")), 2, 0);
        pane.add(categoryBox, 3, 0);

        pane.add(new Label(i18n("search.sort")), 0, 1);
        pane.add(sortBox, 1, 1);
        pane.add(buildPaging(), 2, 1, 2, 1);

        // When the index was assembled, beside the button that reads it again. The original's own
        // download page carries the same line for the same reason: a list is only as good as its
        // age, and this is the one place that says how old it is.
        JFXButton reload = new JFXButton(i18n("button.refresh"));
        reload.getStyleClass().add("dialog-accept");
        reload.setOnAction(event -> refresh());
        HBox actions = new HBox(8, updatedLabel, reload);
        actions.setAlignment(Pos.CENTER_RIGHT);
        pane.add(actions, 3, 2);

        return pane;
    }

    /// Builds the paging row.
    ///
    /// Under the form rather than under the list, which is the original's arrangement and not an
    /// arbitrary one: the list is what scrolls, so anything below it would move as it scrolls.
    ///
    /// @return the row
    private Node buildPaging() {
        firstPage.setOnAction(event -> goTo(1));
        previousPage.setOnAction(event -> goTo(page - 1));
        nextPage.setOnAction(event -> goTo(page + 1));
        lastPage.setOnAction(event -> goTo(pageCount));

        HBox row = new HBox(6, firstPage, previousPage, pageLabel, nextPage, lastPage);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /// Builds a placeholder for an empty list.
    ///
    /// @param text what to say
    /// @return the node
    private static Node placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("desc");
        StackPane pane = new StackPane(label);
        pane.setPadding(new Insets(20));
        return pane;
    }

    /// Reads the index again.
    @Override
    public void refresh() {
        spinner.showSpinner();
        CompletableFuture.supplyAsync(() -> {
            try {
                return DshPackMarket.fetch();
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((index, failure) -> Platform.runLater(() -> {
            spinner.hideSpinner();
            if (getScene() == null) {
                return;
            }
            if (failure != null) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                shown.clear();
                all.clear();
                listView.setPlaceholder(placeholder(cause.getMessage() == null
                        ? i18n("message.error") : cause.getMessage()));
                return;
            }
            listView.setPlaceholder(placeholder(i18n("search.no_results_found")));
            all.setAll(index.entries());
            generatedAt = index.generatedAt();
            updatedLabel.setText(generatedAt == null ? ""
                    : i18n("dsh.market.updated", generatedAt));

            // The categories are the ones the index actually holds, not a list invented here: the
            // collector's categories are whatever the authors' manifests said, and a fixed list would
            // offer filters that match nothing and hide ones that match something.
            String chosen = categoryBox.getValue();
            List<String> categories = new java.util.ArrayList<>();
            categories.add(i18n("download.type.all"));
            for (DshPackMarket.Entry entry : index.entries()) {
                if (entry.category() != null && !entry.category().isBlank()
                        && !categories.contains(entry.category())) {
                    categories.add(entry.category());
                }
            }
            categoryBox.getItems().setAll(categories);
            categoryBox.setValue(categories.contains(chosen) ? chosen : categories.get(0));

            search();
        }));
    }

    /// Filters, sorts, and pages what is in hand.
    ///
    /// Nothing is fetched here: the index is one document and is already in memory, so the filter is
    /// a filter. Fetching per keystroke would be a request per keystroke for an answer this launcher
    /// already has.
    private void search() {
        String needle = nameField.getText() == null ? "" : nameField.getText().trim().toLowerCase(Locale.ROOT);
        String category = categoryBox.getValue();
        boolean allCategories = category == null || category.equals(i18n("download.type.all"));

        List<DshPackMarket.Entry> matching = all.stream()
                .filter(entry -> needle.isEmpty()
                        || entry.title().toLowerCase(Locale.ROOT).contains(needle)
                        || entry.id().toLowerCase(Locale.ROOT).contains(needle)
                        || (entry.description() != null
                                && entry.description().toLowerCase(Locale.ROOT).contains(needle)))
                .filter(entry -> allCategories || category.equals(entry.category()))
                .sorted(comparator())
                .toList();

        pageCount = Math.max(1, (matching.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(Math.max(1, page), pageCount);
        int from = (page - 1) * PAGE_SIZE;
        shown.setAll(matching.subList(from, Math.min(from + PAGE_SIZE, matching.size())));

        pageLabel.setText(i18n("search.page_n", page, pageCount));
        firstPage.setDisable(page <= 1);
        previousPage.setDisable(page <= 1);
        nextPage.setDisable(page >= pageCount);
        lastPage.setDisable(page >= pageCount);
    }

    /// Returns how the results are ordered.
    ///
    /// @return the comparator
    private Comparator<DshPackMarket.Entry> comparator() {
        String sort = sortBox.getValue();
        if (sort != null && sort.equals(i18n("dsh.market.sort.name"))) {
            return Comparator.comparing(DshPackMarket.Entry::title, String.CASE_INSENSITIVE_ORDER);
        }
        if (sort != null && sort.equals(i18n("addon.sort.popularity"))) {
            // "Popularity" for this market is how much a pack brings: there are no download counts
            // anywhere in an index that is assembled from GitHub releases, and inventing a number
            // would be worse than ordering by a real one.
            return Comparator.comparingInt(
                    (DshPackMarket.Entry entry) -> entry.depCount() == null ? 0 : entry.depCount())
                    .reversed();
        }
        return Comparator.comparing(
                (DshPackMarket.Entry entry) -> entry.updatedAt() == null ? "" : entry.updatedAt())
                .reversed();
    }

    /// Shows a page.
    ///
    /// @param number the page, clamped to what there is
    private void goTo(int number) {
        page = Math.min(Math.max(1, number), pageCount);
        search();
    }

    /// Returns the page's state for the title bar.
    ///
    /// @return the state
    @Override
    public javafx.beans.property.ReadOnlyObjectWrapper<State> stateProperty() {
        return state;
    }

    /// One row of the results.
    ///
    /// A row is the pack and nothing else: its name, what it says about itself, and the facts the
    /// index carries. The description is on the second line and the tags carry the rest, which is the
    /// same division the plugin market's rows use — there is no icon, because the ecosystem publishes
    /// none and a made-up one would be a picture of nothing.
    @NotNullByDefault
    private final class PackCell extends MDListCell<DshPackMarket.Entry> {
        /// The pack's name, description and tags.
        private final TwoLineListItem content = new TwoLineListItem();

        /// Creates the cell.
        ///
        /// @param listView the list it belongs to
        PackCell(JFXListView<DshPackMarket.Entry> listView) {
            super(listView);
            onClicked(() -> {
                DshPackMarket.Entry item = getItem();
                if (item != null) {
                    Controllers.navigate(new PackDetailPage(item));
                }
            });
        }

        @Override
        protected void updateControl(@Nullable DshPackMarket.Entry entry, boolean empty) {
            if (empty || entry == null) {
                return;
            }
            content.setTitle(entry.title());
            content.setSubtitle(entry.description() == null ? entry.id() : entry.description());
            // A cell is reused for whatever scrolls into it, so what it said before has to go.
            content.getTags().clear();
            content.addTags(List.of(entry.subtitle()));
            content.addTag(sizeOf(entry.size()));
            if (entry.updatedAt() != null && !entry.updatedAt().isBlank()) {
                content.addTag(entry.updatedAt());
            }

            HBox row = new HBox(8);
            row.setPadding(new Insets(8));
            row.setAlignment(Pos.CENTER_LEFT);
            row.setCursor(javafx.scene.Cursor.HAND);
            HBox.setHgrow(content, Priority.ALWAYS);
            row.getChildren().add(content);
            getContainer().getChildren().setAll(row);
            if (!getContainer().getStyleClass().contains("card-no-padding")) {
                getContainer().getStyleClass().add("card-no-padding");
            }
        }
    }

    /// Writes a size the way a person reads one.
    ///
    /// @param bytes the size
    /// @return the text
    static String sizeOf(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
