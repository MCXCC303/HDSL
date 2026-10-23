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
	private final ObservableList<Catalog.Plugin> all = FXCollections.observableArrayList();

	/// The plugins on the page being shown.
	private final ObservableList<Catalog.Plugin> shown = FXCollections.observableArrayList();

	/// The list the results are drawn in.
	private final JFXListView<Catalog.Plugin> listView = new JFXListView<>();

	/// The pane that reports loading and failure.
	private final SpinnerPane spinner = new SpinnerPane();

	/// What to search for.
	private final JFXTextField nameField = new JFXTextField();

	/// Which category to keep.
	///
	/// Its own type with an "all" member rather than an empty string: an empty value is drawn
	/// together with the prompt, because the prompt is a second node rather than a placeholder for
	/// the value, so the box showed the word twice. It is also what lets the filter be a filter —
	/// an empty string cannot be told apart from "nothing chosen" when the search asks.
	private final JFXComboBox<CategoryFilter> categoryBox = new JFXComboBox<>();

	/// The version picker, in the position the original keeps its game version in.
	private final JFXComboBox<String> versionBox = new JFXComboBox<>();
	/// Which plugins fit, by package name, once it has been worked out.
	private final Map<String, Boolean> fitting = new ConcurrentHashMap<>();
	/// How to order the results.
	private final JFXComboBox<String> sortBox = new JFXComboBox<>();
	/// Where the page number is shown between the paging buttons.
	private final Label pageLabel = new Label();
	/// The instance the plugins are installed into.
	///
	/// A picker rather than a label, which is the original's own arrangement: its
	/// mod page leads with the instance the download will go into, because the
	/// same list is browsed for whichever instance the person is working on. It
	/// starts at whatever instance the launcher has selected and does not change
	/// that selection, so browsing the market never moves the launch button.
	private final JFXComboBox<DshInstance> instanceBox = new JFXComboBox<>();
	/// The harness version the search is filtered by, or `null` for all of them.
	private String chosenVersion;
	/// Whether the version list has been asked for.
	private boolean versionsAsked;
	/// The paging buttons, kept so their states can follow the page.
	private @Nullable JFXButton firstPage;
	private @Nullable JFXButton previousPage;
	private @Nullable JFXButton nextPage;
	private @Nullable JFXButton lastPage;
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

	/// Reports whether a plugin matches a search.
	///
	/// @param plugin the plugin
	/// @param query  the query, already lower-cased
	/// @return whether it matches
	private static boolean matches(Catalog.Plugin plugin, String query) {
		if (plugin.name().toLowerCase().contains(query) || plugin.owner().toLowerCase().contains(query)) {
			return true;
		}
		String description = plugin.localizedDescription();
		return description != null && description.toLowerCase().contains(query);
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

	/// Returns the instance in a picker's list that answers to an id.
	///
	/// @param instances what the picker holds
	/// @param wanted    the instance the market was opened for
	/// @return the one among them with that id, or `null` when none has it
	static @Nullable DshInstance matchingInstance(List<DshInstance> instances, DshInstance wanted) {
		for (DshInstance candidate : instances) {
			if (candidate.id().equals(wanted.id())) {
				return candidate;
			}
		}
		return null;
	}

	/// Builds the search form.
	///
	/// The first row is the original's version-list toolbar, row for row: the name on
	/// the left with its box taking what is left, then the filter's own name and its
	/// capped box, then the button. That is what the original's download page looks
	/// like — its plugins are reached from the same page as its game versions, and
	/// both lead with 名称 — so a version list and a plugin list that are opened from
	/// the same place are searched the same way.
	///
	/// What is left over goes on a second row: the instance the install will go into,
	/// and the two ways of narrowing the catalogue further, which the original's
	/// version list does not have because it has nothing to narrow by.
	///
	/// @return the form, on the surface the original puts it on
	private Node buildSearchPane() {
		GridPane pane = new GridPane();
		pane.getStyleClass().add("card");
		pane.setHgap(16);
		pane.setVgap(10);
		pane.setPadding(new Insets(10));

		// The original's own search panel, from the tab it keeps its mods in: the thing everything
		// below is about takes a line of its own across the whole card, then two rows of
		// name-and-box — what to search for and which version, then which category and how to order
		// the answers — and the paging with the button at the end.
		//
		//   [ 游戏                                   ▾ ]
		//   [ 名称 ............ ] [ 游戏版本 ......... ▾ ]
		//   [ 类别 ......... ▾  ] [ 排序 ........... ▾ ]
		//   [ 分页 … ]                                     [ 搜索 ]
		//
		// Four columns: a name, a box that takes what is left, another name, another such box.
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
		// The form searches when it is told to, and not while somebody is typing: a button
		// that is not what searches is a button with nothing to do. Enter is the form's own
		// way of submitting, so it does the same thing.
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
			// The cache of what fits is dropped here because the answer depends on the
			// choice; the list is redrawn when the form is submitted.
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

		// The instance the install goes into, across the top: it is what every filter below is
		// narrowing a list *for*, which is why the original leads with it and gives it the width.
		pane.add(new Label(i18n("dsh.download.instance")), 0, 0);
		pane.add(instanceBox, 1, 0, 3, 1);

		pane.addRow(1, new Label(i18n("mods.name")), nameField, new Label(i18n("dsh.market.dsh_version")), versionBox);
		pane.addRow(2, new Label(i18n("addon.category")), categoryBox, new Label(i18n("search.sort")), sortBox);

		JFXButton search = new JFXButton(i18n("search"));
		search.getStyleClass().add("jfx-button-raised");
		// Reading is what a refresh means the first time and filtering is what it means
		// afterwards, which is what pressing search should do: the catalogue is one document,
		// and what changes between two presses is the form.
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

			// The categories the catalogue publishes, after the "all" member the box starts on.
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

	/// Filters, sorts and pages what the catalogue holds.
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

	/// Shows a page of the results.
	///
	/// @param number the page number, counted from one
	private void showPage(int number) {
		page = number;
		search();
	}

	/// Fills the version picker with every published version of the harness.
	///
	/// The original's picker lists every game version there is rather than only the one
	/// the instance runs, because a search is about what exists; so does this.
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
				// Without the list the picker keeps 全部, which is not a failure of
				// the search: nothing is filtered unless somebody chooses to.
				return;
			}
			String chosen = versionBox.getValue();
			versionBox.getItems()
			          .setAll(Stream.concat(Stream.of(i18n("download.type.all")), versions.stream()).toList());
			versionBox.setValue(chosen == null ? i18n("download.type.all") : chosen);
		}));
	}

	/// Reports whether a plugin fits the instance the page installs into.
	///
	/// What fits is decided from the plugin's own peer requirements against the
	/// packages the instance's harness holds, and the requirements are read from the
	/// registry for the rows that are shown — once each, in the background, and the
	/// list is redrawn when an answer arrives.
	///
	/// @param plugin the plugin
	/// @return whether it fits, or whether it fits as far as is known
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
			// Judged against what the chosen version ships: the instance's own
			// installation when the choice is its version, and what that release
			// declares when it is another one.
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

	/// Points the picker at one instance.
	///
	/// What a page reached from an instance's own plugin list does on the way in: 下载 there means
	/// "install one into *this* instance", and the picker's default is not that instance. It follows
	/// the launcher's selection instead, and opening an instance does not select it — the radio button
	/// in the list does — so the two come apart exactly when a person has selected one instance and is
	/// looking at another. Reported as: installed into `0.1.7-rc.2-2`, and it appeared in
	/// `0.0.1-rc.5`, which was the selected one.
	///
	/// The instance is matched by id rather than by value: the page's own list was read from disk, and
	/// the caller holds whatever it read, which may differ in a field that has since changed.
	///
	/// @param instance the instance the market was opened for, or `null` to leave the picker alone
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

	/// One category, or every one of them.
	///
	/// The "all" answer is a member rather than a null or an empty string so that it can be the
	/// box's selection: the original's version filter is built the same way, and for the same
	/// reason — a filter that is "not chosen yet" draws differently from one that says "all".
	///
	/// @param name the catalogue's category, or `null` for the member that keeps every one
	record CategoryFilter(@Nullable String name) {
		/// The member that keeps everything.
		static final CategoryFilter ALL = new CategoryFilter(null);

		/// Reports whether a plugin's category passes this filter.
		///
		/// @param category the plugin's category
		/// @return whether to keep it
		boolean accepts(@Nullable String category) {
			return name == null || name.equals(category);
		}

		/// Returns what the box shows for this member.
		///
		/// @return the category's name, or the word for all of them
		String displayName() {
			return name == null ? i18n("download.type.all") : name;
		}
	}

	/// One result row: the plugin, what it is, and the way to its own page.
	///
	/// The shape the session and plugin lists use: a two-line item on the surface
	/// the list cells wear, with the row's action wired to the rippler — which is
	/// what takes the press, and the reason a handler put on the pane inside it
	/// never fires.
	private final class PluginCell extends MDListCell<Catalog.Plugin> {
		/// The plugin's name, description and tags.
		private final TwoLineListItem content = new TwoLineListItem();

		/**
		 * The repository owner's picture.
		 *
		 * <p>The catalogue carries no icon of the plugin's own, so the owner's picture stands in
		 * for one.
		 */
		private final ImageContainer icon = new ImageContainer(32);

		/// Creates the cell.
		///
		/// @param listView the list it belongs to
		/// Creates the cell.
		/// @param listView the list it belongs to
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
				// A cell that has scrolled past its last plugin must not keep the picture of
				// the plugin it held.
				icon.setImage(null);
				return;
			}
			// Cleared first: a cell that has scrolled from one plugin to another must not wear
			// the previous author's face while the new one is being fetched.
			icon.setImage(null);
			String url = plugin.ownerAvatarUrl();
			if (url != null) {
				Image cached = RemoteIconLoader.cached(url);
				if (cached != null) {
					icon.setImage(cached);
				} else {
					RemoteIconLoader.load(url, loaded -> {
						// The cell may have been reused while the fetch was in flight, and a
						// picture of the wrong plugin is worse than none.
						if (getItem() == plugin) {
							icon.setImage(loaded);
						}
					});
				}
			}

			content.setTitle(plugin.name());
			content.setSubtitle(plugin.localizedDescription() == null ? plugin.owner() : plugin.localizedDescription());
			// A cell is reused for whatever scrolls into it, so what it said before
			// has to go: otherwise every row wears the tags of the rows it replaced.
			content.getTags().clear();
			content.addTags(List.of(plugin.category(), plugin.owner()));
			content.addTag(plugin.sourceKind());

			// The original's mod rows carry no arrow at the end, so this row does not either.
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
