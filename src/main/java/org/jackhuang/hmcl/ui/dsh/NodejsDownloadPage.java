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
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.runtime.NodeRelease;
import com.mcxcc303.hdsl.runtime.NodeRuntimeManager;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

@NotNullByDefault
public final class NodejsDownloadPage extends VBox implements DecoratorPage {
	private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n(
			"dsh.node.download.title")));
	private final ObservableList<Row> rows = FXCollections.observableArrayList();
	private final SpinnerPane spinner = new SpinnerPane();
	private final Set<Integer> openedGroups = new LinkedHashSet<>();
	private final Runnable onInstalled;

	private List<Group> groups = List.of();
	private boolean busy;

	public NodejsDownloadPage(Runnable onInstalled) {
		this.onInstalled = onInstalled;

		JFXListView<Row> listView = new JFXListView<>();
		listView.setPadding(Insets.EMPTY);
		listView.getStyleClass().add("no-horizontal-scrollbar");
		listView.setItems(rows);
		listView.setCellFactory(view -> new RowCell((JFXListView<Row>) view));

		ComponentList surface = new ComponentList();
		surface.getStyleClass().add("no-padding");
		surface.getContent().add(listView);
		ComponentList.setVgrow(listView, Priority.ALWAYS);

		spinner.setContent(surface);
		spinner.setOnFailedAction(event -> load());
		VBox.setVgrow(spinner, Priority.ALWAYS);

		setPadding(new Insets(10));
		getChildren().setAll(spinner);

		load();
	}

	@Override
	public ReadOnlyObjectProperty<State> stateProperty() {
		return state.getReadOnlyProperty();
	}

	private void load() {
		spinner.setLoading(true);
		CompletableFuture.supplyAsync(() -> {
			try {
				return NodeRuntimeManager.fetchReleases(settings().nodeSourceProperty().get());
			} catch (DshException e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io()).whenComplete((releases, throwable) -> runInFX(() -> {
			spinner.setLoading(false);
			if (throwable != null) {
				Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
				                  ? throwable.getCause()
				                  : throwable;
				LOG.warning("Failed to fetch Node releases", cause);
				spinner.setFailedReason(i18n("dsh.versions.load_failed") + ": " + cause.getMessage());
				return;
			}
			spinner.setFailedReason(null);
			groups = group(releases);
			if (!groups.isEmpty()) {
				openedGroups.add(groups.getFirst().major());
			}
			render();
		}));
	}

	private static List<Group> group(List<NodeRelease> releases) {
		Map<Integer, List<NodeRelease>> byMajor = new LinkedHashMap<>();
		for (NodeRelease release : releases) {
			byMajor.computeIfAbsent(majorOf(release.version()), major -> new ArrayList<>()).add(release);
		}

		List<Group> groups = new ArrayList<>();
		for (Map.Entry<Integer, List<NodeRelease>> entry : byMajor.entrySet()) {
			groups.add(new Group(entry.getKey(), List.copyOf(entry.getValue())));
		}
		return groups;
	}

	private static int majorOf(String version) {
		int dot = version.indexOf('.');
		try {
			return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private void render() {
		List<Row> drawn = new ArrayList<>();
		for (Group group : groups) {
			boolean open = openedGroups.contains(group.major());
			drawn.add(new HeadingRow(group.major(), group.releases().size(), open));
			if (!open) continue;

			for (NodeRelease release : group.releases()) {
				drawn.add(new ReleaseRow(release));
			}
		}
		rows.setAll(drawn);
	}

	private void toggle(int major) {
		if (!openedGroups.remove(major)) openedGroups.add(major);
		render();
	}

	private void install(NodeRelease release) {
		if (busy) return;
		busy = true;

		AtomicBoolean failed = new AtomicBoolean();
		ProgressDialog.run(i18n("dsh.node.download.title") + " " + release.version(),
		                   report -> NodeRuntimeManager.install(release.version(),
		                                                        settings().nodeSourceProperty().get(),
		                                                        report),
		                   () -> {
			                   busy = false;
			                   if (!failed.get()) {
				                   Controllers.showToast(i18n("download.install.success"));
				                   onInstalled.run();
			                   }
		                   },
		                   failure -> {
			                   failed.set(true);
			                   return false;
		                   });
	}

	/**
	 * A group of Node.js Versions.
	 *
	 * @param major    major version, 26.12.1 => major = 26
	 * @param releases versions of this major version has
	 */
	private record Group(int major, List<NodeRelease> releases) {}

	private sealed interface Row permits HeadingRow, ReleaseRow {}

	/**
	 * A heading part of a group.
	 *
	 * @param opened fold or unfold of this group card
	 */
	private record HeadingRow(int major, int releaseCount, boolean opened) implements Row {}

	/**
	 * Each version in a group.
	 */
	private record ReleaseRow(NodeRelease release) implements Row {}

	private final class RowCell extends MDListCell<Row> {
		private final TwoLineListItem content = new TwoLineListItem();
		private final StackPane mark = new StackPane();
		private final JFXButton downloadButton = FXUtils.newToggleButton4(SVG.ARROW_FORWARD);

		RowCell(JFXListView<Row> listView) {
			super(listView);
			onClicked(() -> {
				Row item = getItem();
				if (item instanceof HeadingRow heading) {
					toggle(heading.major());
				} else if (item instanceof ReleaseRow(NodeRelease release)) {
					Controllers.confirm(i18n("dsh.node.download.confirm_version", release.version()),
					                    i18n("dsh.node.download.title"),
					                    () -> install(release),
					                    null);
				}
			});

			downloadButton.setOnMouseClicked(event -> {
				Row item = getItem();
				if (item instanceof ReleaseRow(NodeRelease release)) {
					Controllers.confirm(i18n("dsh.node.download.confirm_version", release.version()),
					                    i18n("dsh.node.download.title"),
					                    () -> install(release),
					                    null);
					event.consume();
				}
			});
		}

		@Override
		protected void updateControl(@Nullable Row row, boolean empty) {
			if (empty || row == null) return;

			if (row instanceof HeadingRow heading) {
				updateHeading(heading);
			} else if (row instanceof ReleaseRow release) {
				updateRelease(release);
			}
		}

		private void updateHeading(HeadingRow heading) {
			Label title = new Label("Node.js " + heading.major());

			Label count = new Label(i18n("dsh.node.download.version_count", Integer.toString(heading.releaseCount())));
			count.getStyleClass().add("subtitle-label");

			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);

			SVG chevron = heading.opened() ? SVG.KEYBOARD_ARROW_UP : SVG.KEYBOARD_ARROW_DOWN;
			HBox line = new HBox(10, title, spacer, count, chevron.createIcon(18));
			line.setAlignment(Pos.CENTER_LEFT);
			line.setPadding(new Insets(8, 8, 4, 8));

			getContainer().getChildren().setAll(line);
		}

		private void updateRelease(ReleaseRow row) {
			NodeRelease release = row.release();

			content.getTags().clear();
			mark.getChildren().clear();

			boolean lts = release.isLts();
			SVGContainer icon = (lts ? SVG.RELEASE_CIRCLE : SVG.BETA_CIRCLE).createIcon(24);
			icon.getStyleClass().addAll("addon-channel-icon", lts ? "release" : "beta");
			mark.getChildren().setAll(icon);
			mark.setPadding(new Insets(0, 8, 0, 0));
			mark.setMinWidth(24);

			content.setTitle(release.version());
			content.setSubtitle(i18n("dsh.node.download.publish_date", release.date()));
			if (release.isSupported()) content.addTag(i18n("dsh.node.download.compatible"));
			if (release.isLts()) content.addTag(i18n("dsh.node.download.tag_lts", release.lts()));

			HBox line = new HBox(mark, content, downloadButton);
			line.setAlignment(Pos.CENTER_LEFT);
			line.setPadding(new Insets(8));
			HBox.setHgrow(content, Priority.ALWAYS);

			getContainer().getChildren().setAll(line);
		}
	}
}
