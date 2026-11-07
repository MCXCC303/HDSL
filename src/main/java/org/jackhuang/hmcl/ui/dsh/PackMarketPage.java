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
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXTextField;
import com.mcxcc303.hdsl.pack.DshPackMarket;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
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

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

// Browses the community's modpack market and allows installing packs.
@NotNullByDefault
public final class PackMarketPage extends StackPane implements DecoratorPage, Refreshable {
	private static final int PAGE_SIZE = 24;
	private final ObservableList<DshPackMarket.Entry> all = FXCollections.observableArrayList();
	private final ObservableList<DshPackMarket.Entry> shown = FXCollections.observableArrayList();
	private final JFXListView<DshPackMarket.Entry> listView = new JFXListView<>();
	private final SpinnerPane spinner = new SpinnerPane();
	private final JFXTextField nameField = new JFXTextField();
	private final JFXComboBox<String> categoryBox = new JFXComboBox<>();
	private final JFXComboBox<String> sortBox = new JFXComboBox<>();
	private final JFXComboBox<String> versionBox = new JFXComboBox<>();
	private final Label pageLabel = new Label();
	private final JFXButton firstPage = FXUtils.newBorderButton(i18n("search.first_page"));
	private final JFXButton previousPage = FXUtils.newBorderButton(i18n("search.previous_page"));
	private final JFXButton nextPage = FXUtils.newBorderButton(i18n("search.next_page"));
	private final JFXButton lastPage = FXUtils.newBorderButton(i18n("search.last_page"));
	private final Label updatedLabel = new Label();
	private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n(
			"dsh.download.packs")));
	private int page = 1;
	private int pageCount = 1;
	private @Nullable String generatedAt;

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

	private static Node placeholder(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("desc");
		StackPane pane = new StackPane(label);
		pane.setPadding(new Insets(20));
		return pane;
	}

	/**
	 * Formats a byte size as a human-readable string.
	 *
	 * @param bytes the size in bytes
	 * @return the formatted size string
	 */
	static String sizeOf(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return String.format("%.1f KB", bytes / 1024.0);
		}
		return String.format("%.1f MB", bytes / (1024.0 * 1024));
	}

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
		nameField.setMaxWidth(Double.MAX_VALUE);
		nameField.setOnAction(event -> refresh());

		versionBox.setMaxWidth(Double.MAX_VALUE);
		versionBox.setConverter(FXUtils.stringConverter(choice -> choice));
		versionBox.getItems().setAll(i18n("download.type.all"));
		versionBox.setValue(i18n("download.type.all"));

		categoryBox.setMaxWidth(Double.MAX_VALUE);
		categoryBox.setConverter(FXUtils.stringConverter(choice -> choice));
		categoryBox.getItems().setAll(i18n("download.type.all"));
		categoryBox.setValue(i18n("download.type.all"));

		sortBox.setMaxWidth(Double.MAX_VALUE);
		sortBox.getItems()
		       .setAll(i18n("addon.sort.date_created"), i18n("addon.sort.popularity"), i18n("dsh.market.sort.name"));
		sortBox.setValue(i18n("addon.sort.date_created"));

		pane.add(new Label(i18n("mods.name")), 0, 0);
		pane.add(nameField, 1, 0);
		pane.add(new Label(i18n("dsh.pack.instance_version")), 2, 0);
		pane.add(versionBox, 3, 0);

		pane.add(new Label(i18n("addon.category")), 0, 1);
		pane.add(categoryBox, 1, 1);
		pane.add(new Label(i18n("search.sort")), 2, 1);
		pane.add(sortBox, 3, 1);

		pane.add(buildPaging(), 0, 2, 2, 1);

		JFXButton search = new JFXButton(i18n("search"));
		search.getStyleClass().add("dialog-accept");
		search.setOnAction(event -> refresh());

		JFXButton installLocal = FXUtils.newRaisedButton(i18n("install.modpack"));
		installLocal.setOnAction(event -> Controllers.navigate(new PackInstallPage()));

		for (JFXButton button : List.of(search, installLocal)) {
			button.setMinWidth(Region.USE_PREF_SIZE);
		}
		updatedLabel.setMinWidth(0);
		HBox.setHgrow(updatedLabel, Priority.ALWAYS);
		updatedLabel.setAlignment(Pos.CENTER_RIGHT);

		HBox actions = new HBox(8, updatedLabel, search, installLocal);
		actions.setAlignment(Pos.CENTER_RIGHT);
		pane.add(actions, 2, 2, 2, 1);

		return pane;
	}

	private Node buildPaging() {
		firstPage.setOnAction(event -> goTo(1));
		previousPage.setOnAction(event -> goTo(page - 1));
		nextPage.setOnAction(event -> goTo(page + 1));
		lastPage.setOnAction(event -> goTo(pageCount));

		HBox row = new HBox(6, firstPage, previousPage, pageLabel, nextPage, lastPage);
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	@Override
	public void refresh() {
		spinner.showSpinner();
		CompletableFuture.supplyAsync(() -> {
			try {
				return DshPackMarket.fetch();
			} catch (Exception e) {
				throw new CompletionException(e);
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
				listView.setPlaceholder(placeholder(
						cause.getMessage() == null ? i18n("message.error") : cause.getMessage()));
				return;
			}
			listView.setPlaceholder(placeholder(i18n("search.no_results_found")));
			all.setAll(index.entries());
			generatedAt = index.generatedAt();
			String shown = generatedAt == null
			               ? null
			               : generatedAt.length() >= 10 ? generatedAt.substring(0, 10) : generatedAt;
			updatedLabel.setText(shown == null ? "" : i18n("dsh.market.updated", shown));
			FXUtils.installFastTooltip(updatedLabel, generatedAt == null ? "" : generatedAt);

			String chosen = categoryBox.getValue();
			List<String> categories = new ArrayList<>();
			categories.add(i18n("download.type.all"));
			for (DshPackMarket.Entry entry : index.entries()) {
				if (entry.category() != null && !entry.category().isBlank() && !categories.contains(entry.category())) {
					categories.add(entry.category());
				}
			}
			categoryBox.getItems().setAll(categories);
			categoryBox.setValue(categories.contains(chosen) ? chosen : categories.get(0));

			String chosenVersion = versionBox.getValue();
			List<String> versions = new ArrayList<>();
			versions.add(i18n("download.type.all"));
			index.entries()
			     .stream()
			     .map(DshPackMarket.Entry::dshVersion)
			     .filter(value -> value != null && !value.isBlank())
			     .distinct()
			     .sorted(Comparator.reverseOrder())
			     .forEach(versions::add);
			versionBox.getItems().setAll(versions);
			versionBox.setValue(versions.contains(chosenVersion) ? chosenVersion : versions.get(0));

			search();
		}));
	}

	private void search() {
		String needle = nameField.getText() == null ? "" : nameField.getText().trim().toLowerCase(Locale.ROOT);
		String category = categoryBox.getValue();
		boolean allCategories = category == null || category.equals(i18n("download.type.all"));
		String version = versionBox.getValue();
		boolean allVersions = version == null || version.equals(i18n("download.type.all"));

		List<DshPackMarket.Entry> matching = all.stream()
		                                        .filter(entry -> needle.isEmpty() || entry.title()
		                                                                                  .toLowerCase(Locale.ROOT)
		                                                                                  .contains(needle) ||
		                                                         entry.id().toLowerCase(Locale.ROOT).contains(needle) ||
		                                                         (entry.description() != null && entry.description()
		                                                                                              .toLowerCase(
				                                                                                              Locale.ROOT)
		                                                                                              .contains(needle)))
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

	private Comparator<DshPackMarket.Entry> comparator() {
		String sort = sortBox.getValue();
		if (sort != null && sort.equals(i18n("dsh.market.sort.name"))) {
			return Comparator.comparing(DshPackMarket.Entry::title, String.CASE_INSENSITIVE_ORDER);
		}
		if (sort != null && sort.equals(i18n("addon.sort.popularity"))) {
			return Comparator.comparingInt((DshPackMarket.Entry entry) -> entry.depCount() == null
			                                                              ? 0
			                                                              : entry.depCount()).reversed();
		}
		return Comparator.comparing((DshPackMarket.Entry entry) -> entry.updatedAt() == null ? "" : entry.updatedAt())
		                 .reversed();
	}

	private void goTo(int number) {
		page = Math.min(Math.max(1, number), pageCount);
		search();
	}

	@Override
	public ReadOnlyObjectWrapper<State> stateProperty() {
		return state;
	}

	@NotNullByDefault
	private final class PackCell extends MDListCell<DshPackMarket.Entry> {
		private final TwoLineListItem content = new TwoLineListItem();
		private final ImageContainer icon = new ImageContainer(32);

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
			content.getTags().clear();
			content.addTags(List.of(entry.subtitle()));
			content.addTag(sizeOf(entry.size()));
			if (entry.updatedAt() != null && !entry.updatedAt().isBlank()) {
				content.addTag(entry.updatedAt());
			}

			icon.setImage(null);
			String url = entry.iconUrl();
			if (url != null) {
				Image cached = RemoteIconLoader.cached(url);
				if (cached != null) {
					icon.setImage(cached);
				} else {
					RemoteIconLoader.load(url, loaded -> {
						if (getItem() == entry) {
							icon.setImage(loaded);
						}
					});
				}
			}

			HBox row = new HBox(8);
			row.setPadding(new Insets(8));
			row.setAlignment(Pos.CENTER_LEFT);
			row.setCursor(Cursor.HAND);
			HBox.setHgrow(content, Priority.ALWAYS);
			row.getChildren().addAll(icon, content);
			getContainer().getChildren().setAll(row);
			if (!getContainer().getStyleClass().contains("card-no-padding")) {
				getContainer().getStyleClass().add("card-no-padding");
			}
		}
	}
}
