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
import com.mcxcc303.hdsl.runtime.DshToolchain;
import com.mcxcc303.hdsl.runtime.PnpmRuntimeManager;
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
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

@NotNullByDefault
public final class PnpmDownloadPage extends VBox implements DecoratorPage {
	private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n(
			"dsh.pnpm.download.title")));
	private final ObservableList<Row> rows = FXCollections.observableArrayList();
	private final SpinnerPane spinner = new SpinnerPane();
	private final Set<Integer> openedGroups = new LinkedHashSet<>();
	private final Runnable onInstalled;

	private List<Group> groups = List.of();
	private boolean busy;

	public PnpmDownloadPage(Runnable onInstalled) {
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
				return PnpmRuntimeManager.fetchVersions(DshToolchain.npm());
			} catch (DshException e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io()).whenComplete((versions, throwable) -> runInFX(() -> {
			spinner.setLoading(false);
			if (throwable != null) {
				Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
				                  ? throwable.getCause()
				                  : throwable;
				LOG.warning("Failed to fetch the pnpm versions", cause);
				spinner.setFailedReason(i18n("dsh.versions.load_failed") + ": " + cause.getMessage());
				return;
			}
			spinner.setFailedReason(null);
			groups = group(versions);
			if (!groups.isEmpty()) {
				openedGroups.add(groups.getFirst().major());
			}
			render();
		}));
	}

	private static List<Group> group(List<String> versions) {
		Map<Integer, List<String>> byMajor = new LinkedHashMap<>();
		for (String version : versions) {
			byMajor.computeIfAbsent(majorOf(version), major -> new ArrayList<>()).add(version);
		}

		List<Group> groups = new ArrayList<>();
		for (Map.Entry<Integer, List<String>> entry : byMajor.entrySet()) {
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
			drawn.add(new HeadingRow(group.major(), group.versions().size(), open));
			if (!open) continue;

			for (String version : group.versions()) {
				drawn.add(new ReleaseRow(version));
			}
		}
		rows.setAll(drawn);
	}

	private void toggle(int major) {
		if (!openedGroups.remove(major)) openedGroups.add(major);
		render();
	}

	private void install(String version) {
		if (busy) return;
		busy = true;

		AtomicBoolean failed = new AtomicBoolean();
		ProgressDialog.run(i18n("dsh.pnpm.download.title"),
		                   report -> {
			                   try {
				                   PnpmRuntimeManager.install(version, DshToolchain.npm(), report);
		                   } catch (DshException e) {
				                   throw new CompletionException(e);
		                   }
		                   },
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

	private record Group(int major, List<String> versions) {}

	private sealed interface Row permits HeadingRow, ReleaseRow {}

	private record HeadingRow(int major, int versionCount, boolean opened) implements Row {}

	private record ReleaseRow(String version) implements Row {}

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
				} else if (item instanceof ReleaseRow release) {
					confirmInstall(release.version());
				}
			});

			downloadButton.setOnMouseClicked(event -> {
				Row item = getItem();
				if (item instanceof ReleaseRow release) {
					confirmInstall(release.version());
					event.consume();
				}
			});
		}

		private void confirmInstall(String version) {
			Controllers.confirm(i18n("dsh.pnpm.download.confirm_version", version),
			                    i18n("dsh.pnpm.download.title"),
			                    () -> install(version),
			                    null);
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
			Label title = new Label("pnpm " + heading.major());

			Label count = new Label(i18n("dsh.node.download.version_count", Integer.toString(heading.versionCount())));
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
			content.getTags().clear();
			mark.getChildren().clear();

			mark.getChildren().setAll(SVG.PACKAGE2.createIcon(24));
			mark.setPadding(new Insets(0, 8, 0, 0));
			mark.setMinWidth(24);

			content.setTitle(row.version());

			HBox line = new HBox(mark, content, downloadButton);
			line.setAlignment(Pos.CENTER_LEFT);
			line.setPadding(new Insets(8));
			HBox.setHgrow(content, Priority.ALWAYS);

			getContainer().getChildren().setAll(line);
		}
	}
}
