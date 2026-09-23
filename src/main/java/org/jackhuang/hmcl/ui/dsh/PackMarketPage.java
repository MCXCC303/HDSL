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
import org.jackhuang.hmcl.ui.construct.ImageContainer;
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

    /// Which harness version to keep.
    ///
    /// The original's second box on the name row, which it calls the game version and fills from its
    /// version list. Here the thing a pack pins is a **harness** version, so that is what is offered
    /// and what the row is called: the same question — "will this run on what I have" — asked about
    /// the only version this launcher has.
    private final JFXComboBox<String> versionBox = new JFXComboBox<>();

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
        // Four columns, as the original has: a name, a box that takes what is left, another name,
        // another such box. Two rows of them, then the paging with the buttons at the end.

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

        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.setConverter(FXUtils.stringConverter(choice -> choice));
        versionBox.getItems().setAll(i18n("download.type.all"));
        versionBox.setValue(i18n("download.type.all"));
        versionBox.valueProperty().addListener(observable -> search());

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

        // The original's own two rows, in its own order: what to look for and which version, then
        // which category and how to order the answers.
        pane.add(new Label(i18n("mods.name")), 0, 0);
        pane.add(nameField, 1, 0);
        pane.add(new Label(i18n("dsh.pack.instance_version")), 2, 0);
        pane.add(versionBox, 3, 0);

        pane.add(new Label(i18n("addon.category")), 0, 1);
        pane.add(categoryBox, 1, 1);
        pane.add(new Label(i18n("search.sort")), 2, 1);
        pane.add(sortBox, 3, 1);

        pane.add(buildPaging(), 0, 2, 2, 1);

        // The ends of the row, as the original has them: when the index was assembled, beside the
        // button that reads it again, and the button that installs a pack somebody already has.
        JFXButton reload = new JFXButton(i18n("button.refresh"));
        reload.getStyleClass().add("dialog-accept");
        reload.setOnAction(event -> refresh());

        JFXButton installLocal = FXUtils.newRaisedButton(i18n("install.modpack"));
        installLocal.setOnAction(event -> Controllers.navigate(new PackInstallPage()));

        // The buttons keep the width their own labels ask for, and the line about the index gives up
        // its own instead. Left to the grid, the opposite happened: the timestamp is long and took
        // what it wanted, and the button was drawn as "安…" — a button nobody can read is a button
        // nobody presses.
        for (JFXButton button : List.of(reload, installLocal)) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        updatedLabel.setMinWidth(0);
        HBox.setHgrow(updatedLabel, Priority.ALWAYS);
        updatedLabel.setAlignment(Pos.CENTER_RIGHT);

        HBox actions = new HBox(8, updatedLabel, reload, installLocal);
        actions.setAlignment(Pos.CENTER_RIGHT);
        pane.add(actions, 2, 2, 2, 1);

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
            // The date alone: the index is assembled once a day, so the time of day says nothing a
            // person acts on and costs the width the buttons need. The whole value is in the tooltip
            // for anybody who does want it.
            String shown = generatedAt == null ? null
                    : generatedAt.length() >= 10 ? generatedAt.substring(0, 10) : generatedAt;
            updatedLabel.setText(shown == null ? "" : i18n("dsh.market.updated", shown));
            FXUtils.installFastTooltip(updatedLabel, generatedAt == null ? "" : generatedAt);

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

            // The versions the index actually holds, newest first, by the same reasoning the
            // categories are gathered: a filter that matches nothing is worse than no filter, and a
            // fixed list of versions would go stale the day a pack pinned a new one.
            String chosenVersion = versionBox.getValue();
            List<String> versions = new java.util.ArrayList<>();
            versions.add(i18n("download.type.all"));
            index.entries().stream()
                    .map(DshPackMarket.Entry::dshVersion)
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .sorted(Comparator.reverseOrder())
                    .forEach(versions::add);
            versionBox.getItems().setAll(versions);
            // Kept when it is still there, so re-reading the index does not throw away what somebody
            // was looking at.
            versionBox.setValue(versions.contains(chosenVersion) ? chosenVersion : versions.get(0));

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
        String version = versionBox.getValue();
        boolean allVersions = version == null || version.equals(i18n("download.type.all"));

        List<DshPackMarket.Entry> matching = all.stream()
                .filter(entry -> needle.isEmpty()
                        || entry.title().toLowerCase(Locale.ROOT).contains(needle)
                        || entry.id().toLowerCase(Locale.ROOT).contains(needle)
                        || (entry.description() != null
                                && entry.description().toLowerCase(Locale.ROOT).contains(needle)))
                .filter(entry -> allCategories || category.equals(entry.category()))
                .filter(entry -> allVersions || version.equals(entry.dshVersion()))
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
    /// The original's row shape, which is the plugin market's too: a picture at the left, then the
    /// name, the description on a second line, and the facts as tags. The picture is the author's
    /// avatar — see [`Entry#iconUrl`] — because the index has no icon field and a made-up one would
    /// be a picture of nothing. It is fetched once per author and kept, and a cell that cannot get
    /// one keeps the space and shows nothing rather than shifting the row.
    @NotNullByDefault
    private final class PackCell extends MDListCell<DshPackMarket.Entry> {
        /// The pack's name, description and tags.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The author's avatar, or an empty box of the same size.
        private final ImageContainer icon = new ImageContainer(32);

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

            // Cleared first: a cell that has scrolled from one pack to another must not wear the
            // previous author's face while the new one is being fetched.
            icon.setImage(null);
            String url = entry.iconUrl();
            if (url != null) {
                javafx.scene.image.Image cached = ICONS.get(url);
                if (cached != null) {
                    icon.setImage(cached);
                } else {
                    loadIcon(url, loaded -> {
                        // The cell may have been reused while the fetch was in flight, and a picture
                        // of the wrong pack is worse than none.
                        if (getItem() == entry) {
                            icon.setImage(loaded);
                        }
                    });
                }
            }

            HBox row = new HBox(8);
            row.setPadding(new Insets(8));
            row.setAlignment(Pos.CENTER_LEFT);
            row.setCursor(javafx.scene.Cursor.HAND);
            HBox.setHgrow(content, Priority.ALWAYS);
            row.getChildren().addAll(icon, content);
            getContainer().getChildren().setAll(row);
            if (!getContainer().getStyleClass().contains("card-no-padding")) {
                getContainer().getStyleClass().add("card-no-padding");
            }
        }
    }

    /// The avatars already fetched, by address.
    ///
    /// Kept for the life of the page and shared by every cell: a list of three packs by one author
    /// must not be three requests, and a cell scrolled off and back must not be a fourth. `null` is
    /// not stored — a failed fetch is simply not remembered, so the next row that wants it tries
    /// again rather than being stuck with the network's bad minute.
    private static final java.util.Map<String, javafx.scene.image.Image> ICONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /// Fetches an avatar and hands it over on the interface thread.
    ///
    /// @param url      the address
    /// @param onLoaded what to do with it
    private static void loadIcon(String url, java.util.function.Consumer<javafx.scene.image.Image> onLoaded) {
        CompletableFuture.supplyAsync(() -> {
            try {
                java.net.http.HttpRequest request = java.net.http.HttpRequest
                        .newBuilder(java.net.URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(15))
                        .GET()
                        .build();
                try (java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(10))
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                        .build()) {
                    java.net.http.HttpResponse<byte[]> response = client.send(request,
                            java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return null;
                    }
                    javafx.scene.image.Image image = new javafx.scene.image.Image(
                            new java.io.ByteArrayInputStream(response.body()));
                    return image.isError() || image.getWidth() <= 0 ? null : image;
                }
            } catch (java.io.IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        }, Schedulers.io()).thenAccept(image -> Platform.runLater(() -> {
            if (image != null) {
                ICONS.put(url, image);
                onLoaded.accept(image);
            }
        }));
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
