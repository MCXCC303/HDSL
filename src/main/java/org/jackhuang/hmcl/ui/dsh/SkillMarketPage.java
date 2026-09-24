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
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXTextField;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshSkillSource;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Browses the community's published skills and either installs one into an instance or
/// saves it as an archive.
///
/// Shaped like the plugin market beside it, because it answers the same kind of question:
/// a form on a card, the results under it, and one row per result that opens what it is
/// about. The differences are the ones the data forces. The two catalogues behind it
/// publish what they publish — one has descriptions and the addresses, the other counts
/// installs — and between them they allow a few dozen searches a day, which is why this
/// searches when it is told to and not while somebody is typing.
///
/// A row opens a dialog rather than leading to a page: a skill is a name, a description
/// and two things that can be done with it, which is a dialog's worth of anything.
@NotNullByDefault
public final class SkillMarketPage extends StackPane implements Refreshable {
    /// The greatest number of skills one search asks each catalogue for.
    private static final int LIMIT = 50;

    /// The shortest query the catalogues answer.
    private static final int SHORTEST_QUERY = 2;

    /// Everything the last search returned.
    private final ObservableList<DshSkillSource.Offering> found = FXCollections.observableArrayList();

    /// What the list shows: the same skills in the chosen order.
    private final ObservableList<DshSkillSource.Offering> shown = FXCollections.observableArrayList();

    /// The list of results.
    private final JFXListView<DshSkillSource.Offering> listView = new JFXListView<>();

    /// The spinner the list is swapped for while a search runs.
    private final SpinnerPane spinner = new SpinnerPane();

    /// What to search for.
    private final JFXTextField nameField = new JFXTextField();

    /// The instance an install goes into.
    private final JFXComboBox<DshInstance> instanceBox = new JFXComboBox<>();

    /// How to order the results.
    private final JFXComboBox<Sort> sortBox = new JFXComboBox<>();

    /// Whether a search is running.
    private boolean busy;

    /// Creates the page.
    public SkillMarketPage() {
        VBox root = new VBox();
        root.setSpacing(10);
        root.setPadding(new Insets(10, 10, 0, 10));
        root.getChildren().add(buildSearchPane());

        VBox.setVgrow(spinner, Priority.ALWAYS);
        VBox.setMargin(spinner, new Insets(0, 0, 10, 0));
        listView.setPadding(Insets.EMPTY);
        listView.getStyleClass().add("no-horizontal-scrollbar");
        listView.setItems(shown);
        listView.setCellFactory(view -> new OfferingCell((JFXListView<DshSkillSource.Offering>) view));
        listView.setPlaceholder(placeholder(i18n("dsh.skills.market.prompt")));
        spinner.setContent(listView);
        root.getChildren().add(spinner);

        getChildren().add(root);
    }

    /// Builds the search form.
    ///
    /// The layout is the plugin market's, minus the parts a skill has nothing to say
    /// about: what everything below is for takes a line of its own across the card, then
    /// a row of what to look for and how to order it, then the button on the right.
    ///
    ///   [ 实例                                                ▾ ]
    ///   [ 名称 ..................... ] [ 排序 ............... ▾ ]
    ///                                                    [ 搜索 ]
    ///
    /// The button is the only thing that searches. Every search costs one of each
    /// catalogue's daily requests, so nothing is asked for until it is asked for.
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

        instanceBox.setMaxWidth(Double.MAX_VALUE);
        instanceBox.setConverter(FXUtils.stringConverter(
                instance -> instance == null ? i18n("dsh.market.no_instance") : instance.id()));
        instanceBox.getItems().setAll(DshInstanceManager.list());
        instanceBox.setValue(GameDirectoryManager.selectedInstanceProperty().get());

        nameField.setPromptText(i18n("dsh.skills.market.hint"));
        // A grid cell gives a child what it asks for, and a field asks for the width of
        // its prompt; without this the box stops short of the column it is in.
        nameField.setMaxWidth(Double.MAX_VALUE);
        nameField.setOnAction(event -> refresh());

        sortBox.setMaxWidth(Double.MAX_VALUE);
        sortBox.setConverter(FXUtils.stringConverter(Sort::displayName));
        sortBox.getItems().setAll(Sort.values());
        sortBox.setValue(Sort.POPULARITY);
        sortBox.valueProperty().addListener((observable, was, value) -> sort());

        // The instance the install goes into, across the top: it is what everything below
        // is for, which is why the original leads with it and gives it the width.
        pane.add(new Label(i18n("dsh.download.instance")), 0, 0);
        pane.add(instanceBox, 1, 0, 3, 1);
        pane.addRow(1, new Label(i18n("mods.name")), nameField,
                new Label(i18n("search.sort")), sortBox);

        JFXButton search = new JFXButton(i18n("search"));
        search.getStyleClass().add("jfx-button-raised");
        search.setOnAction(event -> refresh());
        HBox buttons = new HBox(8, search);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        pane.add(buttons, 0, 2, 4, 1);

        return pane;
    }

    @Override
    public void refresh() {
        if (busy) {
            return;
        }
        String query = nameField.getText() == null ? "" : nameField.getText().trim();
        if (query.length() < SHORTEST_QUERY) {
            // Not a failure and not an empty result: nothing has been asked for yet. The
            // catalogues refuse anything shorter, so the page says what it wants instead
            // of asking and showing the refusal.
            found.clear();
            shown.clear();
            listView.setPlaceholder(placeholder(i18n("dsh.skills.market.prompt")));
            return;
        }

        busy = true;
        spinner.setLoading(true);
        // A catalogue that fails is reported with the answer, not thrown: the point of
        // asking several is that one of them being down is not a failed search.
        CompletableFuture.supplyAsync(() -> DshSkillSource.find(query, LIMIT), Schedulers.io())
                .whenComplete((result, throwable) -> runInFX(() -> {
                    busy = false;
                    spinner.setLoading(false);
                    if (throwable != null) {
                        LOG.warning("Failed to search the skill catalogues", causeOf(throwable));
                        found.clear();
                        shown.clear();
                        listView.setPlaceholder(placeholder(causeOf(throwable).getMessage()));
                        return;
                    }
                    found.setAll(result.skills());
                    sort();
                    if (!result.failures().isEmpty()) {
                        LOG.warning("These catalogues did not answer: " + result.failures());
                    }
                    listView.setPlaceholder(placeholder(result.failures().isEmpty()
                            ? i18n("dsh.skills.market.empty")
                            : i18n("dsh.skills.market.partial",
                                    String.join(", ", result.failures()))));
                }));
    }

    /// Puts the results in the chosen order.
    private void sort() {
        Sort order = sortBox.getValue() == null ? Sort.POPULARITY : sortBox.getValue();
        List<DshSkillSource.Offering> ordered = new ArrayList<>(found);
        ordered.sort(order.order());
        shown.setAll(ordered);
    }

    /// Returns the failure a future completed with.
    ///
    /// @param throwable what the future reported
    /// @return the cause, when the wrapper has one
    private static Throwable causeOf(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
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

    /// How the results can be ordered.
    ///
    /// Ordered here rather than by the catalogues: one of them publishes an order and the
    /// other does not, and a merged list has to be ordered by one rule whatever it came
    /// from. The counts the two publish are not the same thing — installs on one, stars
    /// on the other — so this is a rough order, and the row shows the number rather than
    /// pretending the two are comparable.
    private enum Sort {
        /// The most popular first.
        POPULARITY("dsh.skills.market.sort.popularity",
                Comparator.comparingLong(DshSkillSource.Offering::popularity).reversed()),
        /// By name.
        NAME("dsh.skills.market.sort.name",
                Comparator.comparing(DshSkillSource.Offering::name, String.CASE_INSENSITIVE_ORDER)),
        /// By the repository it comes from.
        SOURCE("dsh.skills.market.sort.source",
                Comparator.comparing(DshSkillSource.Offering::source, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(DshSkillSource.Offering::name, String.CASE_INSENSITIVE_ORDER));

        /// The key its name is read from.
        private final String label;

        /// The order itself.
        private final Comparator<DshSkillSource.Offering> order;

        Sort(String label, Comparator<DshSkillSource.Offering> order) {
            this.label = label;
            this.order = order;
        }

        /// Returns what the box shows for this order.
        ///
        /// @return the name
        String displayName() {
            return i18n(label);
        }

        /// Returns the order itself.
        ///
        /// @return the comparator
        Comparator<DshSkillSource.Offering> order() {
            return order;
        }
    }

    /// Installs one skill into the instance the form is pointed at.
    ///
    /// @param offering the skill
    private void install(DshSkillSource.Offering offering) {
        DshInstance instance = instanceBox.getValue();
        if (instance == null) {
            Controllers.dialog(i18n("dsh.skills.market.no_instance"));
            return;
        }
        ProgressDialog.run(i18n("dsh.skills.market.install"), report -> {
            report.accept(offering.source());
            DshSkillSource.Bundle bundle = DshSkillSource.resolve(offering);
            DshSkillSource.install(instance.homeDirectory(), bundle, report::accept);
        }, null);
    }

    /// Saves one skill as an archive, where the user says to keep it.
    ///
    /// The launcher writes the file and stops there. What the archive is for — unpacking
    /// it into a skills directory, reading it first, keeping it for another machine — is
    /// the person's decision, which is the whole reason it exists beside the install.
    ///
    /// @param offering the skill
    private void save(DshSkillSource.Offering offering) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("dsh.skills.market.save"));
        chooser.setInitialFileName(offering.name() + ".zip");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("dsh.skills.market.save.filter"), "*.zip"));
        Path target = Controllers.showSaveDialog(chooser);
        if (target == null) {
            return;
        }
        ProgressDialog.run(i18n("dsh.skills.market.save"), report -> {
            report.accept(offering.source());
            DshSkillSource.Bundle bundle = DshSkillSource.resolve(offering);
            DshSkillSource.download(bundle, target, report::accept);
        }, null);
    }

    /// One result row.
    ///
    /// The plugin market's row: a two-line item on the surface the list cells wear, with
    /// the row's action wired to the rippler — which is what takes the press, and the
    /// reason a handler put on the pane inside it never fires. No icon, because the
    /// catalogues publish none and a made-up one would be a picture of nothing; no
    /// buttons, because what can be done with a skill is a decision to make after
    /// reading what it is.
    private final class OfferingCell extends MDListCell<DshSkillSource.Offering> {
        /// The skill's name, description and tags.
        private final TwoLineListItem content = new TwoLineListItem();

        /// Creates the cell.
        ///
        /// @param listView the list it belongs to
        OfferingCell(JFXListView<DshSkillSource.Offering> listView) {
            super(listView);
            onClicked(() -> {
                DshSkillSource.Offering item = getItem();
                if (item != null) {
                    Controllers.dialog(new SkillDetailDialog(SkillMarketPage.this, item));
                }
            });
        }

        @Override
        protected void updateControl(@Nullable DshSkillSource.Offering offering, boolean empty) {
            if (empty || offering == null) {
                return;
            }
            content.setTitle(offering.name());
            content.setSubtitle(offering.description().isEmpty()
                    ? offering.source() : offering.description());
            // A cell is reused for whatever scrolls into it, so what it said before has
            // to go: otherwise every row wears the tags of the rows it replaced.
            content.getTags().clear();
            content.addTag(offering.catalog());
            content.addTag(offering.source());
            content.addTag(i18n("dsh.skills.market.popularity",
                    String.valueOf(offering.popularity())));

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

    /// Shows one skill and the two things that can be done with it.
    ///
    /// A dialog rather than a page: a skill is a name, a description, and asking to have
    /// it installed or saved. There is nothing else to say about it, so a page would be a
    /// dialog with an empty half.
    private static final class SkillDetailDialog extends JFXDialogLayout {
        /// Creates the dialog.
        ///
        /// @param page     the page the actions belong to
        /// @param offering the skill
        SkillDetailDialog(SkillMarketPage page, DshSkillSource.Offering offering) {
            Label title = new Label(offering.name());
            title.getStyleClass().add("title");
            setHeading(title);

            Label source = new Label(offering.source());
            source.getStyleClass().add("subtitle");
            Label description = new Label(offering.description().isEmpty()
                    ? i18n("dsh.skills.market.no_description") : offering.description());
            description.setWrapText(true);

            VBox body = new VBox(10, source, description);
            body.setPadding(new Insets(4, 0, 4, 0));
            ScrollPane scroll = new ScrollPane(body);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setPrefViewportHeight(160);
            // After the content, never before: the original's smooth scrolling wraps the
            // scroll pane's own skin, and a pane with no content yet has none.
            FXUtils.smoothScrolling(scroll);
            setBody(scroll);

            JFXButton save = new JFXButton();
            save.setText(i18n("dsh.skills.market.save"));
            save.setOnAction(event -> {
                fireEvent(new DialogCloseEvent());
                page.save(offering);
            });
            JFXButton install = new JFXButton();
            install.getStyleClass().add("dialog-accept");
            install.setText(i18n("dsh.skills.market.install"));
            install.setOnAction(event -> {
                fireEvent(new DialogCloseEvent());
                page.install(offering);
            });
            JFXButton close = new JFXButton();
            close.setText(i18n("button.cancel"));
            close.setOnAction(event -> fireEvent(new DialogCloseEvent()));
            getActions().setAll(save, install, close);
        }
    }
}
