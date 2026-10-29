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
import com.jfoenix.controls.JFXSpinner;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.DshRelease;
import com.mcxcc303.hdsl.harness.ReleaseQueries;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.dsh.install.DshInstallWizardProvider;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * The download page: a filterable list of the installable versions, and the pack, plugin and
 * skill markets.
 */
@NotNullByDefault
public final class DownloadPage extends DecoratorAnimatedPage implements DecoratorPage, Refreshable {
	// A version's upstream release page is this prefix followed by the version.
	private static final String RELEASES = "https://github.com/deepseek-ai/deepseek-harness/releases/tag/dsh-v";

	private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("download")));
	// The name and type filters, the same bar other version lists use.
	private final VersionFilterBar filterBar = new VersionFilterBar(this::applyFilter, this::refresh);
	private final TabHeader.Tab<Node> versionsTab = new TabHeader.Tab<>("dshDownloadVersions");
	private final TabHeader.Tab<PluginMarketPage> marketTab = new TabHeader.Tab<>("dshDownloadPlugins");
	// Named by openPluginsFor() before the market tab is built, and handed to the page when it is.
	private final AtomicReference<@Nullable DshInstance> marketInstance = new AtomicReference<>();
	private final TabHeader.Tab<PackMarketPage> packTab = new TabHeader.Tab<>("dshDownloadPacks");
	private final TabHeader.Tab<SkillMarketPage> skillsTab = new TabHeader.Tab<>("dshDownloadSkills");
	private final TransitionPane tabs = new TransitionPane();
	private final TabHeader tab;
	private final JFXListView<DshRelease> releaseList = new JFXListView<>();
	private final TransitionPane contentPane = new TransitionPane();
	private final JFXSpinner spinner = new JFXSpinner();
	private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.LOADING);

	// The last successful load, before filtering.
	private List<DshRelease> loaded = List.of();
	private boolean busy;

	public DownloadPage() {
		tab = new TabHeader(tabs);

		AdvancedListBox sideBar = new AdvancedListBox()
				.startCategory(i18n("download.new_game").toUpperCase(Locale.ROOT))
				.addNavigationDrawerTab(tab,
				                        versionsTab,
				                        i18n("dsh.download.instance"),
				                        SVG.STADIA_CONTROLLER,
				                        SVG.STADIA_CONTROLLER_FILL)
				.addNavigationDrawerTab(tab,
				                        packTab,
				                        i18n("dsh.download.packs"),
				                        SVG.PACKAGE2,
				                        SVG.PACKAGE2_FILL)
				.startCategory(i18n("download.content").toUpperCase(Locale.ROOT))
				.addNavigationDrawerTab(tab,
				                        marketTab,
				                        i18n("dsh.download.plugins"),
				                        SVG.EXTENSION,
				                        SVG.EXTENSION_FILL)
				.addNavigationDrawerTab(tab,
				                        skillsTab,
				                        i18n("dsh.download.skills"),
				                        SVG.TEXTURE);
		FXUtils.setLimitWidth(sideBar, 200);
		setLeft(sideBar);

		StackPane pane = new StackPane();
		pane.setPadding(new Insets(10));
		pane.getStyleClass().add("notice-pane");

		ComponentList root = new ComponentList();
		root.getStyleClass().add("no-padding");
		root.getContent().add(releaseList);
		ComponentList.setVgrow(releaseList, Priority.ALWAYS);
		pane.getChildren().setAll(root);

		StackPane placeholder = new StackPane();
		placeholder.getStyleClass().add("notice-pane");
		placeholder.getChildren().add(new Label(i18n("search.no_results_found")));
		releaseList.setPlaceholder(placeholder);

		StackPane emptyPane = new StackPane();
		emptyPane.getStyleClass().add("notice-pane");
		emptyPane.getChildren().add(new Label(i18n("dsh.versions.available.empty")));

		StackPane failedPane = new StackPane();
		failedPane.getStyleClass().add("notice-pane");
		Label retry = new Label(i18n("download.failed.refresh"));
		FXUtils.onClicked(retry, this::refresh);
		failedPane.getChildren().add(retry);

		FXUtils.onChangeAndOperate(status, now -> contentPane.setContent(switch (now) {
			case LOADING -> spinner;
			case SUCCESS -> loaded.isEmpty() ? emptyPane : pane;
			case FAILED -> failedPane;
		}, ContainerAnimations.FADE));

		Node toolbar = buildToolbar();
		toolbar.getStyleClass().add("card");
		BorderPane.setMargin(toolbar, new Insets(10, 10, 0, 10));

		BorderPane layout = new BorderPane();
		layout.setTop(toolbar);
		layout.setCenter(contentPane);

		versionsTab.setNodeSupplier(() -> layout);
		marketTab.setNodeSupplier(() -> {
			PluginMarketPage page = new PluginMarketPage();
			page.showInstance(marketInstance.get());
			return page;
		});
		packTab.setNodeSupplier(PackMarketPage::new);
		skillsTab.setNodeSupplier(SkillMarketPage::new);
		tab.getTabs().setAll(versionsTab, packTab, marketTab, skillsTab);
		tab.select(versionsTab, false);

		setCenter(tabs);
		releaseList.setCellFactory(view -> new ReleaseCell(this));
		refresh();
	}

	/**
	 * Selects a tab by name.
	 *
	 * @param name the tab name: `versions`, `packs`, `plugins` or `skills`
	 * @return whether a tab was selected
	 */
	public boolean openTab(String name) {
		switch (name.trim().toLowerCase(Locale.ROOT)) {
			case "versions" -> tab.select(versionsTab, false);
			case "packs" -> tab.select(packTab, false);
			case "plugins" -> tab.select(marketTab, false);
			case "skills" -> tab.select(skillsTab, false);
			default -> {
				return false;
			}
		}
		return true;
	}

	/**
	 * Opens the plugin market for an instance.
	 *
	 * @param instance the instance to install into, or `null` to follow the launcher's selection
	 * @return whether the market was opened
	 */
	public boolean openPluginsFor(@Nullable DshInstance instance) {
		marketInstance.set(instance);
		PluginMarketPage page = marketTab.getNode();
		// The tab is built once and reused, so an already built page is moved here.
		page.showInstance(instance);
		return openTab("plugins");
	}

	@Override
	public ReadOnlyObjectProperty<State> stateProperty() {
		return state.getReadOnlyProperty();
	}

	@Override
	public void refresh() {
		if (busy) return;

		busy = true;
		status.set(Status.LOADING);

		CompletableFuture.supplyAsync(() -> {
			try {
				return ReleaseQueries.fetchReleases();
			} catch (DshException e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io()).whenComplete((releases, throwable) -> runInFX(() -> {
			busy = false;
			if (throwable != null) {
				Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
				                  ? throwable.getCause()
				                  : throwable;
				LOG.warning("Failed to fetch releases", cause);
				status.set(Status.FAILED);
				return;
			}
			loaded = releases;
			applyFilter();
			status.set(Status.SUCCESS);
		}));
	}

	// Rebuilds the list from the last load and the current filters.
	private void applyFilter() {
		List<DshRelease> shown = new ArrayList<>();
		for (DshRelease release : loaded) {
			if (filterBar.accepts(release.version())) shown.add(release);
		}

		releaseList.getItems().setAll(shown);
		releaseList.refresh();
	}

	private VersionFilterBar buildToolbar() {
		Label nameLabel = new Label(i18n("download.name"));
		BorderPane.setMargin(filterBar, new Insets(10, 10, 0, 10));
		return filterBar;
	}

	/**
	 * Starts installing a version into a new instance.
	 *
	 * @param release the release to install
	 */
	private void install(DshRelease release) {
		Controllers.getDecorator()
		           .startWizard(new DshInstallWizardProvider(release.version()), i18n("dsh.instance.create"));
	}

	// What a load is doing; selects which pane contentPane shows.
	private enum Status {
		LOADING, SUCCESS, FAILED
	}

	private static final class ReleaseCell extends ListCell<DshRelease> {
		private final DownloadPage page;
		private final ImageContainer icon = new ImageContainer(32);
		private final TwoLineListItem content = new TwoLineListItem();
		private final JFXButton link = FXUtils.newToggleButton4(SVG.PUBLIC);
		private final JFXButton install = FXUtils.newToggleButton4(SVG.ARROW_FORWARD);
		// The same node is put back for every non-empty item; ListCell clears it for empty ones.
		private final RipplerContainer graphic;

		ReleaseCell(DownloadPage page) {
			this.page = page;
			StackPane root = new StackPane();
			root.getStyleClass().add("md-list-cell");

			content.setMouseTransparent(true);
			HBox.setHgrow(content, Priority.ALWAYS);

			HBox right = new HBox(8);
			right.setAlignment(Pos.CENTER_RIGHT);
			FXUtils.installFastTooltip(link, i18n("download.release_page"));
			FXUtils.installFastTooltip(install, i18n("download.install"));
			link.setOnAction(event -> {
				DshRelease release = getItem();
				FXUtils.openLink(RELEASES + release.version());
			});
			install.setOnAction(event -> {
				DshRelease release = getItem();
				page.install(release);
			});
			right.getChildren().setAll(link, install);

			HBox row = new HBox(16, icon, content, right);
			row.setAlignment(Pos.CENTER);
			content.setAlignment(Pos.CENTER);
			StackPane.setMargin(row, new Insets(10, 16, 10, 16));
			root.getChildren().setAll(row);

			// A press on the row installs the version, so the buttons consume their own clicks
			// otherwise clicking either of them installs as well.
			for (Node button : new Node[]{link, install}) {
				button.addEventFilter(MouseEvent.MOUSE_CLICKED, Event::consume);
			}

			RipplerContainer rippler = new RipplerContainer(root);
			rippler.setOnMouseClicked(event -> {
				DshRelease release = getItem();
				if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
					page.install(release);
					event.consume();
				}
			});
			root.setCursor(Cursor.HAND);

			this.graphic = rippler;
			setGraphic(graphic);
		}

		@Override
		protected void updateItem(@Nullable DshRelease release, boolean empty) {
			super.updateItem(release, empty);

			if (empty) {
				setGraphic(null);
				return;
			}

			setGraphic(graphic);
			icon.setImage(DshInstanceIcon.DSH_APPLICATION.load());

			content.setTitle(release.version());
			content.getTags().clear();
			content.addTag(i18n("download.type." + release.type().id()));
			content.setSubtitle(release.publishedAt() == null
			                    ? i18n("dsh.session.unknown_time")
			                    : I18n.formatDateTime(Instant.parse(release.publishedAt())));
		}
	}
}
