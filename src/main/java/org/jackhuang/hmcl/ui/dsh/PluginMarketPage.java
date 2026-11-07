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

import com.google.gson.JsonObject;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXTextField;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.DshPackageRegistry;
import com.mcxcc303.hdsl.harness.ReleaseQueries;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.InstanceListing;
import com.mcxcc303.hdsl.plugin.Catalog;
import com.mcxcc303.hdsl.plugin.CatalogClient;
import com.mcxcc303.hdsl.plugin.CatalogParser;
import com.mcxcc303.hdsl.plugin.DshPluginRequirements;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Marketplace page for browsing and installing community plugins.
 */
@NotNullByDefault
public final class PluginMarketPage extends StackPane implements Refreshable, PageAware {

	private static final int PAGE_SIZE = 24;

	private final ObservableList<Catalog.Plugin> all = FXCollections.observableArrayList();
	private final ObservableList<Catalog.Plugin> shown = FXCollections.observableArrayList();
	private final JFXListView<Catalog.Plugin> listView = new JFXListView<>();
	private final SpinnerPane spinner = new SpinnerPane();
	private final JFXTextField nameField = new JFXTextField();
	private final JFXComboBox<CategoryFilter> categoryBox = new JFXComboBox<>();
	private final JFXComboBox<String> versionBox = new JFXComboBox<>();
	private final Map<String, Boolean> fitting = new ConcurrentHashMap<>();
	private final JFXComboBox<String> sortBox = new JFXComboBox<>();
	private final Label pageLabel = new Label();
	private final JFXComboBox<DshInstance> instanceBox = new JFXComboBox<>();
	private String chosenVersion;
	private boolean versionsAsked;
	private @Nullable JFXButton firstPage;
	private @Nullable JFXButton previousPage;
	private @Nullable JFXButton nextPage;
	private @Nullable JFXButton lastPage;
	private int page = 1;
	private int pageCount = 1;
	private boolean loaded;
	private boolean busy;

	/**
	 * Creates the page.
	 */
	public PluginMarketPage() {
		getStyleClass().add("plugin-market-page");

		VBox root = new VBox();
		root.setSpacing(10);
		root.setPadding(new Insets(10, 10, 0, 10));

		root.getChildren().add(buildSearchPane());

		VBox.setVgrow(spinner, Priority.ALWAYS);
		VBox.setMargin(spinner, new Insets(0, 0, 10, 0));

		listView.setPadding(Insets.EMPTY);
		listView.getStyleClass().add("no-horizontal-scrollbar");
		listView.setItems(shown);
		listView.setCellFactory(view -> new PluginCell((JFXListView<Catalog.Plugin>) view));
		listView.setPlaceholder(placeholder(i18n("search.no_results_found")));
		spinner.setContent(listView);
		root.getChildren().add(spinner);

		getChildren().add(root);

		refresh();
	}

	private static boolean matches(Catalog.Plugin plugin, String query) {
		if (plugin.name().toLowerCase().contains(query) || plugin.owner().toLowerCase().contains(query)) {
			return true;
		}
		String description = plugin.localizedDescription();
		return description != null && description.toLowerCase().contains(query);
	}

	private static Node placeholder(String message) {
		Label label = new Label(message);
		StackPane container = new StackPane(label);
		container.getStyleClass().add("notice-pane");
		return container;
	}

	/**
	 * Returns the instance in a list matching the desired instance ID.
	 *
	 * @param instances the candidate instances
	 * @param wanted    the desired instance
	 * @return the matching instance, or {@code null}
	 */
	static @Nullable DshInstance matchingInstance(List<DshInstance> instances, DshInstance wanted) {
		for (DshInstance candidate : instances) {
			if (candidate.id().equals(wanted.id())) {
				return candidate;
			}
		}
		return null;
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

		instanceBox.setMaxWidth(Double.MAX_VALUE);
		instanceBox.setConverter(FXUtils.stringConverter(instance -> instance == null
		                                                             ? i18n("dsh.market.no_instance")
		                                                             : instance.id()));
		instanceBox.getItems().setAll(InstanceListing.list());
		instanceBox.setValue(GameDirectoryManager.selectedInstanceProperty().get());

		versionBox.setMaxWidth(Double.MAX_VALUE);
		versionBox.setConverter(FXUtils.stringConverter(choice -> choice));
		versionBox.getItems().setAll(i18n("download.type.all"));
		versionBox.setValue(i18n("download.type.all"));
		versionBox.valueProperty().addListener((observable, was, value) -> {
			chosenVersion = value == null || value.equals(i18n("download.type.all")) ? null : value;
			fitting.clear();
		});
		loadVersions();

		categoryBox.setMaxWidth(Double.MAX_VALUE);
		categoryBox.setConverter(FXUtils.stringConverter(CategoryFilter::displayName));
		categoryBox.getItems().add(CategoryFilter.ALL);
		categoryBox.getSelectionModel().select(CategoryFilter.ALL);

		sortBox.setMaxWidth(Double.MAX_VALUE);
		sortBox.setConverter(FXUtils.stringConverter(this::sortName));
		sortBox.getItems().setAll("downloads", "stars", "added");
		sortBox.setValue("downloads");

		pane.add(new Label(i18n("dsh.download.instance")), 0, 0);
		pane.add(instanceBox, 1, 0, 3, 1);

		pane.addRow(1, new Label(i18n("mods.name")), nameField, new Label(i18n("dsh.market.dsh_version")), versionBox);
		pane.addRow(2, new Label(i18n("addon.category")), categoryBox, new Label(i18n("search.sort")), sortBox);

		JFXButton search = new JFXButton(i18n("search"));
		search.getStyleClass().add("jfx-button-raised");
		search.setOnAction(event -> refresh());

		HBox paging = new HBox(8);
		paging.setAlignment(Pos.CENTER_LEFT);
		paging.getChildren().setAll(pagingButtons());
		HBox.setHgrow(paging, Priority.ALWAYS);

		HBox buttons = new HBox(8, paging, search);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		pane.add(buttons, 0, 3, 4, 1);

		return pane;
	}

	private List<Node> pagingButtons() {
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

	private void updatePagingButtons() {
		if (firstPage == null) {
			return;
		}
		firstPage.setDisable(page <= 1);
		previousPage.setDisable(page <= 1);
		nextPage.setDisable(page >= pageCount);
		lastPage.setDisable(page >= pageCount);
	}

	private String sortName(String key) {
		return switch (key == null ? "" : key) {
			case "stars" -> i18n("addon.sort.popularity");
			case "added" -> i18n("addon.sort.date_created");
			default -> i18n("addon.sort.total_downloads");
		};
	}

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
				return CatalogClient.fetch();
			} catch (DshException e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io()).whenComplete((catalogue, failure) -> runInFX(() -> {
			busy = false;
			spinner.setLoading(false);

			if (failure != null || catalogue == null) {
				Throwable cause = failure instanceof CompletionException && failure.getCause() != null
				                  ? failure.getCause()
				                  : failure;
				LOG.warning("Failed to read the plugin catalogue", cause);
				spinner.setFailedReason(
						i18n("dsh.versions.load_failed") + (cause == null ? "" : ": " + cause.getMessage()));
				return;
			}

			List<CategoryFilter> categories = new ArrayList<>();
			categories.add(CategoryFilter.ALL);
			for (String category : catalogue.categories()) {
				categories.add(new CategoryFilter(category));
			}
			CategoryFilter chosen = categoryBox.getValue();
			categoryBox.getItems().setAll(categories);
			categoryBox.getSelectionModel().select(chosen == null ? CategoryFilter.ALL : chosen);

			all.setAll(catalogue.plugins());
			loaded = true;
			LOG.info("Read " + all.size() + " plugins from the catalogue" +
			         (catalogue.updated() == null ? "" : ", updated " + catalogue.updated()));
			search();
		}));
	}

	private void search() {
		String query = nameField.getText() == null ? "" : nameField.getText().trim().toLowerCase();
		CategoryFilter category = categoryBox.getValue();
		List<Catalog.Plugin> matching = new ArrayList<>();
		for (Catalog.Plugin plugin : CatalogParser.sorted(all, sortBox.getValue())) {
			if (!query.isEmpty() && !matches(plugin, query)) {
				continue;
			}
			if (category != null && !category.accepts(plugin.category())) {
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

	private void showPage(int number) {
		page = number;
		search();
	}

	private void loadVersions() {
		if (versionsAsked) {
			return;
		}
		versionsAsked = true;

		CompletableFuture.supplyAsync(() -> {
			try {
				return ReleaseQueries.fetchReleases().stream().map(release -> release.version()).toList();
			} catch (DshException e) {
				throw new CompletionException(e);
			}
		}).whenComplete((versions, failure) -> runInFX(() -> {
			if (failure != null || versions == null || versions.isEmpty()) {
				return;
			}
			String chosen = versionBox.getValue();
			versionBox.getItems()
			          .setAll(Stream.concat(Stream.of(i18n("download.type.all")), versions.stream()).toList());
			versionBox.setValue(chosen == null ? i18n("download.type.all") : chosen);
		}));
	}

	private boolean fits(Catalog.Plugin plugin) {
		String name = plugin.npm() == null ? plugin.name() : plugin.npm();
		Boolean known = fitting.get(name);
		if (known != null) {
			return known;
		}

		DshInstance instance = target();
		if (instance == null || plugin.npm() == null) {
			return true;
		}

		fitting.put(name, Boolean.TRUE);
		String version = chosenVersion;
		CompletableFuture.supplyAsync(() -> {
			Map<String, String> core = version == null || version.equals(instance.version())
			                           ? DshPluginRequirements.coreVersions(instance)
			                           : DshPluginRequirements.coreVersionsOf(version);
			JsonObject peers = DshPackageRegistry.peerDependencies(plugin.npm(), plugin.version());
			return DshPluginRequirements.fits(peers, core);
		}).whenComplete((result, failure) -> FXUtils.runInFX(() -> {
			fitting.put(name, failure != null || result == null ? Boolean.TRUE : result);
			if (chosenVersion != null) {
				search();
			}
		}));
		return true;
	}

	private @Nullable DshInstance target() {
		DshInstance chosen = instanceBox.getValue();
		return chosen != null ? chosen : GameDirectoryManager.selectedInstanceProperty().get();
	}

	/**
	 * Selects the specified instance in the target instance picker.
	 *
	 * @param instance the instance to select, or {@code null}
	 */
	public void showInstance(@Nullable DshInstance instance) {
		if (instance == null) {
			return;
		}
		DshInstance matching = matchingInstance(instanceBox.getItems(), instance);
		if (matching != null) {
			instanceBox.setValue(matching);
			return;
		}
		LOG.warning("The marketplace was opened for instance " + instance.id() +
		            ", which is not in the list of instances; the picker keeps its own choice");
	}

	record CategoryFilter(@Nullable String name) {
		static final CategoryFilter ALL = new CategoryFilter(null);

		boolean accepts(@Nullable String category) {
			return name == null || name.equals(category);
		}

		String displayName() {
			return name == null ? i18n("download.type.all") : name;
		}
	}

	private final class PluginCell extends MDListCell<Catalog.Plugin> {
		private final TwoLineListItem content = new TwoLineListItem();
		private final ImageContainer icon = new ImageContainer(32);

		PluginCell(JFXListView<Catalog.Plugin> listView) {
			super(listView);
			onClicked(() -> {
				Catalog.Plugin item = getItem();
				if (item != null) {
					Controllers.navigate(new PluginDetailPage(item, target()));
				}
			});
		}

		@Override
		protected void updateControl(@Nullable Catalog.Plugin plugin, boolean empty) {
			if (empty || plugin == null) {
				icon.setImage(null);
				return;
			}
			icon.setImage(null);
			String url = plugin.ownerAvatarUrl();
			if (url != null) {
				Image cached = RemoteIconLoader.cached(url);
				if (cached != null) {
					icon.setImage(cached);
				} else {
					RemoteIconLoader.load(url, loaded -> {
						if (getItem() == plugin) {
							icon.setImage(loaded);
						}
					});
				}
			}

			content.setTitle(plugin.name());
			content.setSubtitle(plugin.localizedDescription() == null ? plugin.owner() : plugin.localizedDescription());
			content.getTags().clear();
			content.addTags(List.of(plugin.category(), plugin.owner()));
			content.addTag(plugin.sourceKind());

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
