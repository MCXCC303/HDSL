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
package org.jackhuang.hmcl.ui.dsh.runtime;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXRadioButton;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshFileManager;
import com.mcxcc303.hdsl.runtime.pnpm.DshPnpm;
import com.mcxcc303.hdsl.runtime.DshToolchain;
import com.mcxcc303.hdsl.runtime.pnpm.PnpmRuntime;
import com.mcxcc303.hdsl.runtime.pnpm.PnpmRuntimeManager;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Skin;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.*;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

@NotNullByDefault
public final class PnpmRuntimesPage extends ListPageBase<PnpmRuntimesPage.PnpmRow> implements Refreshable {
	private boolean busy;
	private DshPnpm system = PnpmRuntimeManager.detect();
	private List<PnpmRuntime> installed = List.of();

	public PnpmRuntimesPage() {
		refresh();
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new PnpmRuntimesPageSkin(this);
	}

	@Override
	public void refresh() {
		if (busy) return;

		busy = true;
		setLoading(true);
		CompletableFuture.supplyAsync(() -> {
			system = PnpmRuntimeManager.detect();
			return PnpmRuntimeManager.listInstalled();
		}, Schedulers.io()).whenComplete((found, throwable) -> runInFX(() -> {
			busy = false;
			setLoading(false);
			if (throwable != null) {
				LOG.warning("Failed to load the pnpm versions", throwable);
				setFailedReason(i18n("dsh.versions.load_failed"));
				return;
			}
			installed = found;
			rebuildRows();
		}));
	}

	private void rebuildRows() {
		String chosen = settings().defaultPnpmProperty().get();
		List<PnpmRow> rows = new ArrayList<>();
		for (PnpmRuntime runtime : installed) {
			rows.add(PnpmRow.installed(runtime, runtime.version().equals(chosen)));
		}
		DshPnpm detected = system;
		rows.add(detected == null
		         ? PnpmRow.systemMissing()
		         : PnpmRow.system(detected, DshToolchain.SYSTEM.equals(chosen)));
		getItems().setAll(rows);
	}

	private void select(String value) {
		if (value.equals(settings().defaultPnpmProperty().get())) return;

		settings().defaultPnpmProperty().set(value);
		SettingsManager.save();
		rebuildRows();
	}

	private void uninstall(String version) {
		if (version.equals(settings().defaultPnpmProperty().get())) {
			Controllers.dialog(i18n("dsh.pnpm.uninstall.in_use", version), i18n("dsh.pnpm.uninstall"),
			                   MessageType.INFO);
			return;
		}

		Controllers.confirm(i18n("dsh.pnpm.uninstall.confirm", version), i18n("dsh.pnpm.uninstall"), () -> {
			if (busy) return;

			busy = true;
			setLoading(true);
			CompletableFuture.runAsync(() -> {
				try {
					PnpmRuntimeManager.uninstall(version);
				} catch (DshException e) {
					throw new CompletionException(e);
				}
			}, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
				busy = false;
				setLoading(false);
				if (throwable != null) {
					Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
					                  ? throwable.getCause()
					                  : throwable;
					Controllers.dialog(cause.getMessage(), i18n("message.error"), MessageType.ERROR);
				}
				refresh();
			}));
		}, null);
	}

	record PnpmRow(String title, String subtitle, String badge, @Unmodifiable List<String> tags,
	               @Nullable Path node, @Nullable String removable, @Nullable String selection, boolean selected) {

		static PnpmRow installed(PnpmRuntime runtime, boolean selected) {
			String major = runtime.version().split("\\.")[0];
			return new PnpmRow("pnpm " + runtime.version(),
			                   runtime.cli().toString(),
			                   major,
			                   List.of(i18n("dsh.pnpm.tag.managed")),
			                   runtime.cli(),
			                   runtime.version(),
			                   runtime.version(),
			                   selected);
		}

		static PnpmRow system(DshPnpm pnpm, boolean selected) {
			String version = pnpm.version() == null ? "-" : pnpm.version();
			return new PnpmRow("pnpm " + version,
			                   pnpm.command().toString(),
			                   version.split("\\.")[0],
			                   List.of(i18n("dsh.pnpm.tag.system")),
			                   pnpm.command(),
			                   null,
			                   DshToolchain.SYSTEM,
			                   selected);
		}

		static PnpmRow systemMissing() {
			return new PnpmRow(i18n("dsh.pnpm.system.missing.title"),
			                   "",
			                   "-",
			                   List.of(i18n("dsh.pnpm.tag.missing")),
			                   null,
			                   null,
			                   null,
			                   false);
		}
	}

	private static final class PnpmRuntimesPageSkin extends ToolbarListPageSkin<PnpmRow, PnpmRuntimesPage> {
		PnpmRuntimesPageSkin(PnpmRuntimesPage control) {
			super(control);
		}

		@Override
		protected List<Node> initializeToolbar(PnpmRuntimesPage page) {
			List<Node> toolbar = new ArrayList<>();
			toolbar.add(createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, page::refresh));
			toolbar.add(createToolbarButton2(i18n("dsh.pnpm.download"),
			                                 SVG.DOWNLOAD,
			                                 () -> Controllers.navigate(new PnpmDownloadPage(page::refresh))));
			return toolbar;
		}

		@Override
		protected ListCell<PnpmRow> createListCell(JFXListView<PnpmRow> listView) {
			return new PnpmItemCell(listView, getSkinnable());
		}
	}

	private static final class PnpmItemCell extends ListCell<PnpmRow> {
		private final JFXRadioButton selector = new JFXRadioButton() {
			@Override
			public void fire() {
				PnpmRow row = getItem();
				if (!isDisable() && !isSelected() && row != null && row.selection() != null) {
					fireEvent(new ActionEvent());
					page.select(row.selection());
				}
			}
		};

		private final Label badge = new Label();
		private final TwoLineListItem content = new TwoLineListItem();
		private final JFXButton reveal = FXUtils.newToggleButton4(SVG.FOLDER_OPEN);
		private final JFXButton action = FXUtils.newToggleButton4(SVG.DOWNLOAD);
		private final PnpmRuntimesPage page;
		private final RipplerContainer graphic;

		PnpmItemCell(JFXListView<PnpmRow> listView, PnpmRuntimesPage page) {
			this.page = page;
			BorderPane root = new BorderPane();
			root.getStyleClass().add("md-list-cell");
			root.setPadding(new Insets(8, 8, 8, 0));

			selector.setMouseTransparent(false);
			root.setLeft(selector);
			BorderPane.setAlignment(selector, Pos.CENTER);

			badge.setAlignment(Pos.CENTER);
			FXUtils.setLimitWidth(badge, 32);
			FXUtils.setLimitHeight(badge, 32);
			badge.setStyle(
					"-fx-background-color: -monet-secondary-container;" + " -fx-background-radius: 2; -fx-padding: 2;" +
					" -fx-font-weight: normal; -fx-font-size: 16px;");

			HBox center = new HBox(8);
			center.setAlignment(Pos.CENTER_LEFT);
			center.setMouseTransparent(true);
			BorderPane.setMargin(center, new Insets(0, 0, 0, 8));
			HBox.setHgrow(content, Priority.ALWAYS);
			center.getChildren().setAll(badge, content);
			root.setCenter(center);

			HBox right = new HBox();
			right.setAlignment(Pos.CENTER_RIGHT);
			FXUtils.installFastTooltip(reveal, i18n("reveal.in_file_manager"));
			reveal.setOnAction(event -> {
				PnpmRow row = getItem();
				if (row != null && row.node() != null) {
					DshFileManager.reveal(row.node());
				}
			});
			right.getChildren().setAll(reveal, action);
			root.setRight(right);

			this.graphic = new RipplerContainer(root);
			setGraphic(graphic);
			FXUtils.limitCellWidth(listView, this);
		}

		@Override
		protected void updateItem(@Nullable PnpmRow row, boolean empty) {
			super.updateItem(row, empty);

			if (empty || row == null) {
				setGraphic(null);
				return;
			}

			setGraphic(graphic);
			badge.setText(row.badge());
			content.setTitle(row.title());
			content.setSubtitle(row.subtitle());
			content.getTags().clear();
			content.addTags(row.tags());

			selector.setSelected(row.selected());
			selector.setDisable(row.selection() == null);
			selector.setVisible(row.selection() != null);
			selector.setManaged(row.selection() != null);

			boolean removable = row.removable() != null;

			reveal.setDisable(row.node() == null);
			reveal.setVisible(row.node() != null);
			reveal.setManaged(row.node() != null);

			action.setVisible(removable);
			action.setManaged(removable);
			StackPane removeIconPane = new StackPane();
			removeIconPane.setAlignment(Pos.CENTER);
			FXUtils.setLimitWidth(removeIconPane, 24);
			FXUtils.setLimitHeight(removeIconPane, 24);
			removeIconPane.getChildren().setAll(SVG.DELETE.createIcon(24));
			action.setGraphic(removeIconPane);
			FXUtils.installFastTooltip(action, i18n("dsh.pnpm.uninstall"));

			action.setOnAction(event -> {
				if (removable) page.uninstall(row.removable());
			});
		}
	}
}
