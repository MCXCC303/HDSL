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
import com.jfoenix.controls.JFXListView;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.DshPackageRegistry;
import com.mcxcc303.hdsl.harness.ReleaseQueries;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.launch.DshLauncher;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import com.mcxcc303.hdsl.plugin.DshPluginRequirements;
import com.mcxcc303.hdsl.plugin.DshPluginVersions;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * One package's published versions, arranged by the harness each of them was written for.
 */
@NotNullByDefault
public final class PluginVersionList extends VBox {
	private final @Nullable String packageName;
	private final @Nullable String pinnedVersion;
	private final @Nullable String profileKey;
	private final Consumer<String> onChoose;
	private final Consumer<String> onInstall;
	private final ObservableList<Row> rows = FXCollections.observableArrayList();
	private final JFXListView<Row> listView = new JFXListView<>();
	private final Set<String> opened = new LinkedHashSet<>();
	private final SpinnerPane spinner = new SpinnerPane();

	private List<DshPluginVersions.Group> groups = List.of();
	private List<DshPluginVersions.Published> ungrouped = List.of();
	private @Nullable String harnessVersion;
	private @Nullable Map<String, String> harnessCore;
	private @Nullable String installed;
	private boolean busy;
	private boolean loadedOnce;

	/**
	 * Creates the version list.
	 *
	 * @param packageName   the package whose versions the registry lists, or null when the
	 *                      catalogue names a repository rather than a package
	 * @param pinnedVersion the one version to list when there is no package, or null
	 * @param profileKey    the name a profile records the install under, or null when the caller
	 *                      names the version in force
	 * @param onChoose      what clicking a version does
	 * @param onInstall     what the button beside a version does
	 */
	public PluginVersionList(@Nullable String packageName,
	                         @Nullable String pinnedVersion,
	                         @Nullable String profileKey,
	                         Consumer<String> onChoose,
	                         Consumer<String> onInstall) {
		this.packageName = packageName;
		this.pinnedVersion = pinnedVersion;
		this.profileKey = profileKey;
		this.onChoose = onChoose;
		this.onInstall = onInstall;

		Label title = new Label(i18n("dsh.market.versions"));
		title.setPadding(new Insets(0, 0, 6, 4));

		listView.setPadding(Insets.EMPTY);
		listView.getStyleClass().add("no-horizontal-scrollbar");
		listView.setItems(rows);
		listView.setCellFactory(view -> new RowCell((JFXListView<Row>) view));
		listView.setPlaceholder(placeholder(i18n("dsh.market.versions.empty")));
		VBox.setVgrow(listView, Priority.ALWAYS);

		ComponentList surface = new ComponentList();
		surface.getStyleClass().add("no-padding");
		surface.getContent().add(listView);
		ComponentList.setVgrow(listView, Priority.ALWAYS);
		VBox.setVgrow(surface, Priority.ALWAYS);

		spinner.setContent(surface);
		VBox.setVgrow(spinner, Priority.ALWAYS);

		getChildren().addAll(title, spinner);
	}

	/**
	 * Reads the versions to draw.
	 *
	 * @param instance the instance they are for, or null when they are chosen before it exists
	 * @param against  the harness they are judged against, or null to use the instance's own
	 * @param current  the version already in force, or null to read the instance's profile
	 */
	public void refresh(@Nullable DshInstance instance, @Nullable String against, @Nullable String current) {
		if (busy) return;

		busy = true;
		spinner.setLoading(true);

		boolean again = loadedOnce;
		loadedOnce = true;
		CompletableFuture.supplyAsync(() -> {
			List<DshPluginVersions.Published> published = new ArrayList<>();
			List<DshPluginVersions.Group> grouped = List.of();
			boolean fromDocument = false;
			if (packageName != null) {
				JsonObject document = DshPackageRegistry.packument(packageName, again);
				if (document != null) {
					published.addAll(DshPluginVersions.read(document));
					fromDocument = !published.isEmpty();
				}
				if (published.isEmpty()) {
					try {
						DshNodeRuntime runtime = instance == null
						                         ? DshNodeRuntime.detect().orElse(null)
						                         : DshLauncher.resolveRuntime(instance);
						for (String version : ReleaseQueries.fetchPackageVersions(runtime, packageName)) {
							published.add(new DshPluginVersions.Published(version,
							                                              DshPluginVersions.channelOf(version, null),
							                                              null,
							                                              new JsonObject(),
							                                              List.of()));
						}
					} catch (DshException e) {
						throw new CompletionException(e);
					}
				}
			} else if (pinnedVersion != null) {
				published.add(new DshPluginVersions.Published(pinnedVersion,
				                                              DshPluginVersions.channelOf(pinnedVersion, null),
				                                              null,
				                                              new JsonObject(),
				                                              List.of()));
			}

			String judged = against != null ? against : instance == null ? null : instance.version();
			Map<String, String> core = instance != null
			                           ? DshPluginRequirements.coreVersions(instance)
			                           : judged == null ? null : DshPluginRequirements.coreVersionsOf(judged);
			if (fromDocument) grouped = DshPluginVersions.group(published, judged);

			String held = current;
			if (held == null && instance != null && profileKey != null) {
				try {
					Map<String, String> dependencies = DshPluginInstaller.readDependencies(instance.homeDirectory(),
					                                                                       instance.profile());
					held = dependencies.get(profileKey);
				} catch (DshException e) {
					throw new CompletionException(e);
				}
			}
			return new Loaded(published, grouped, judged, core, held);
		}, Schedulers.io()).whenComplete((loaded, failure) -> runInFX(() -> {
			busy = false;
			spinner.setLoading(false);
			if (failure != null || loaded == null) {
				Throwable cause = failure instanceof CompletionException && failure.getCause() != null
				                  ? failure.getCause()
				                  : failure;
				LOG.warning("Failed to read the versions of " + packageName, cause);
				spinner.setFailedReason(
						i18n("dsh.versions.load_failed") + (cause == null ? "" : ": " + cause.getMessage()));
				return;
			}

			installed = loaded.held();
			groups = loaded.groups();
			ungrouped = loaded.versions();
			harnessVersion = loaded.harnessVersion();
			harnessCore = loaded.harnessCore();
			if (!groups.isEmpty() && groups.getFirst().recommended()) {
				opened.add(groups.getFirst().harnessVersion() == null ? "" : groups.getFirst().harnessVersion());
			}
			render();
		}));
	}

	// Draws the groups that were loaded, honouring which of them are open.
	// One list rather than one per group: a package with a hundred releases would otherwise put a hundred rows into a box that does not know how to scroll them.
	private void render() {
		List<Row> drawn = new ArrayList<>();
		if (groups.isEmpty()) {
			for (DshPluginVersions.Published version : ungrouped) {
				drawn.add(new VersionRow(version, fitOf(version), version.version().equals(installed)));
			}
		} else {
			for (DshPluginVersions.Group group : groups) {
				String key = group.harnessVersion() == null ? "" : group.harnessVersion();
				boolean open = opened.contains(key);
				drawn.add(new HeadingRow(key, headingOf(group), group.versions().size(), open));
				if (!open) continue;

				for (DshPluginVersions.Published version : group.versions()) {
					drawn.add(new VersionRow(version, fitOf(version), version.version().equals(installed)));
				}
			}
		}
		rows.setAll(drawn);
	}

	/**
	 * Returns what one version says about the harness the list is for.
	 *
	 * @param version the version
	 * @return what it says
	 */
	private DshPluginVersions.Fit fitOf(DshPluginVersions.Published version) {
		return DshPluginVersions.fitOf(version, harnessVersion, harnessCore);
	}

	/**
	 * Opens or closes one group.
	 *
	 * @param key the group's key
	 */
	private void toggleGroup(String key) {
		if (!opened.remove(key)) opened.add(key);
		render();
	}

	/**
	 * Returns the heading a group is drawn under.
	 *
	 * @param group the group
	 * @return the heading
	 */
	private static String headingOf(DshPluginVersions.Group group) {
		if (group.recommended()) return i18n("dsh.market.recommend", group.harnessVersion());

		return group.harnessVersion() == null
		       ? i18n("dsh.market.group.unclaimed")
		       : i18n("dsh.market.group", group.harnessVersion());
	}

	/**
	 * Builds the message a list with nothing in it shows.
	 *
	 * @param message the message
	 * @return the placeholder
	 */
	private static Node placeholder(String message) {
		Label label = new Label(message);
		StackPane container = new StackPane(label);
		container.getStyleClass().add("notice-pane");
		return container;
	}

	/**
	 * Returns the mark a channel is drawn with.
	 *
	 * @param channel the channel
	 * @return the mark, or null for a channel that is drawn without one
	 */
	private static @Nullable SVG channelMark(DshPluginVersions.Channel channel) {
		return switch (channel) {
			case STABLE -> SVG.RELEASE_CIRCLE;
			case RC, BETA -> SVG.BETA_CIRCLE;
			case ALPHA -> SVG.ALPHA_CIRCLE;
			case OTHER -> null;
		};
	}

	/**
	 * Returns the colour a channel's mark is drawn in.
	 *
	 * @param channel the channel
	 * @return the style class
	 */
	private static String channelStyle(DshPluginVersions.Channel channel) {
		return switch (channel) {
			case STABLE -> "release";
			case RC, BETA -> "beta";
			case ALPHA -> "alpha";
			case OTHER -> "";
		};
	}

	/**
	 * Returns the label a channel is shown with.
	 *
	 * @param channel the channel
	 * @return the translation key
	 */
	private static String channelKey(DshPluginVersions.Channel channel) {
		return switch (channel) {
			case STABLE -> "download.type.stable";
			case RC -> "download.type.rc";
			case BETA -> "download.type.beta";
			case ALPHA -> "download.type.alpha";
			case OTHER -> "download.type.other";
		};
	}

	// A heading, or a version under one
	private sealed interface Row permits HeadingRow, VersionRow {}

	/**
	 * What one load read.
	 *
	 * @param versions       the versions, newest first
	 * @param groups         the groups they were arranged into, or an empty list when no document
	 *                       could be read and there is nothing to arrange them by
	 * @param harnessVersion the harness version the groups were built for, or null
	 * @param harnessCore    the harness packages that version holds, or null
	 * @param held           the version in force, or null
	 */
	private record Loaded(List<DshPluginVersions.Published> versions, List<DshPluginVersions.Group> groups,
	                      @Nullable String harnessVersion, @Nullable Map<String, String> harnessCore,
	                      @Nullable String held) {}

	/**
	 * A group's heading, which opens and closes it.
	 *
	 * @param key   the group's key
	 * @param title what it says
	 * @param count how many versions are under it
	 * @param open  whether they are shown
	 */
	private record HeadingRow(String key, String title, int count, boolean open) implements Row {}

	/**
	 * One version.
	 *
	 * @param published the version as the registry described it
	 * @param fit       what it says about the harness the list is for
	 * @param current   whether it is the version in force
	 */
	private record VersionRow(DshPluginVersions.Published published, DshPluginVersions.Fit fit,
	                          boolean current) implements Row {}

	// One row: a heading, or a version.
	private final class RowCell extends MDListCell<Row> {
		private final TwoLineListItem content = new TwoLineListItem();
		private final StackPane mark = new StackPane();
		private final JFXButton installButton = FXUtils.newToggleButton4(SVG.DOWNLOAD);

		RowCell(JFXListView<Row> listView) {
			super(listView);
			onClicked(() -> {
				Row item = getItem();
				if (item instanceof HeadingRow heading) {
					toggleGroup(heading.key());
				} else if (item instanceof VersionRow version) {
					onChoose.accept(version.published().version());
				}
			});

			FXUtils.installFastTooltip(installButton, i18n("download.install"));
			installButton.setOnAction(event -> {
				Row item = getItem();
				if (item instanceof VersionRow version) onInstall.accept(version.published().version());
				event.consume();
			});
		}

		@Override
		protected void updateControl(@Nullable Row row, boolean empty) {
			if (empty || row == null) return;
			if (row instanceof HeadingRow heading) {
				updateHeading(heading);
			} else if (row instanceof VersionRow version) {
				updateVersion(version);
			}
		}

		/**
		 * Draws a group's heading: what it is about, how many versions are under it, and whether it is open.
		 *
		 * @param heading the heading
		 */
		private void updateHeading(HeadingRow heading) {
			Label title = new Label(heading.title());

			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);

			Label count = new Label(i18n("dsh.market.version_count", Integer.toString(heading.count())));
			count.getStyleClass().add("subtitle-label");

			SVG chevron = heading.open() ? SVG.KEYBOARD_ARROW_UP : SVG.KEYBOARD_ARROW_DOWN;
			HBox line = new HBox(10, title, spacer, count, chevron.createIcon(18));
			line.setAlignment(Pos.CENTER_LEFT);
			line.setPadding(new Insets(8, 8, 4, 8));

			getContainer().getChildren().setAll(line);
		}

		/**
		 * Draws one version: its channel, its name, when it was published, and what it says about the harness the list is for.
		 *
		 * @param version the version
		 */
		private void updateVersion(VersionRow version) {
			DshPluginVersions.Published published = version.published();

			content.getTags().clear();
			mark.getChildren().clear();
			SVG channel = channelMark(published.channel());
			if (channel != null) {
				SVGContainer icon = channel.createIcon(24);
				icon.getStyleClass().addAll("addon-channel-icon", channelStyle(published.channel()));
				mark.getChildren().setAll(icon);
			}
			mark.setPadding(new Insets(0, 8, 0, 0));
			mark.setMinWidth(24);

			content.setTitle(published.version());
			content.setSubtitle(published.published() == null ? null : I18n.formatDateTime(published.published()));
			content.addTag(i18n(channelKey(published.channel())));
			if (version.fit() == DshPluginVersions.Fit.NOT_FITS) content.addTag(i18n("dsh.market.not_fitting"));
			if (version.current()) content.addTag(i18n("dsh.instance.upgrade.current"));

			HBox left = new HBox(mark, content);
			left.setAlignment(Pos.CENTER_LEFT);
			HBox.setHgrow(content, Priority.ALWAYS);

			BorderPane row = new BorderPane();
			row.setPadding(new Insets(8));
			row.setLeft(left);

			HBox right = new HBox(installButton);
			right.setAlignment(Pos.CENTER_RIGHT);
			row.setRight(right);

			getContainer().getChildren().setAll(row);
		}
	}
}
