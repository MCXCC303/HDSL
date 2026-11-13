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
import com.jfoenix.controls.JFXRadioButton;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshFileManager;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import com.mcxcc303.hdsl.runtime.NodeRuntime;
import com.mcxcc303.hdsl.runtime.NodeRuntimeManager;
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
import javafx.stage.DirectoryChooser;
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
public final class NodeRuntimesPage extends ListPageBase<NodeRuntimesPage.NodeRow> implements Refreshable {
	private boolean busy;
	private DshNodeRuntime system = DshNodeRuntime.detect().orElse(null);
	private List<NodeRuntime> installed = List.of();

	public NodeRuntimesPage() {
		refresh();
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new NodeRuntimesPageSkin(this);
	}

	@Override
	public void refresh() {
		if (busy) return;

		busy = true;
		setLoading(true);
		CompletableFuture.supplyAsync(() -> {
			system = DshNodeRuntime.detect().orElse(null);
			return NodeRuntimeManager.listInstalled();
		}, Schedulers.io()).whenComplete((found, throwable) -> runInFX(() -> {
			busy = false;
			setLoading(false);
			if (throwable != null) {
				LOG.warning("Failed to load Node runtimes", throwable);
				setFailedReason(i18n("dsh.versions.load_failed"));
				return;
			}
			installed = found;
			rebuildRows();
		}));
	}

	private void rebuildRows() {
		String chosen = settings().defaultNodeRuntimeProperty().get();
		List<NodeRow> rows = new ArrayList<>();
		for (NodeRuntime runtime : installed) {
			rows.add(NodeRow.installed(runtime, runtime.version().equals(chosen)));
		}
		DshNodeRuntime detected = system;
		rows.add(detected == null
		         ? NodeRow.systemMissing()
		         : NodeRow.system(detected, DshNodeRuntime.SYSTEM.equals(chosen)));
		getItems().setAll(rows);
	}

	private void select(String value) {
		if (value.equals(settings().defaultNodeRuntimeProperty().get())) return;

		settings().defaultNodeRuntimeProperty().set(value);
		SettingsManager.save();
		rebuildRows();
	}

	private void addLocal() {
		DirectoryChooser chooser = new DirectoryChooser();
		chooser.setTitle(i18n("dsh.node.add"));
		Path chosen = Controllers.showDialog(chooser);
		if (chosen == null) return;

		String version = NodeRuntimeManager.versionOfDirectory(chosen);
		if (version == null) {
			Controllers.dialog(i18n("dsh.node.add.not_node"), i18n("dsh.node.add"), MessageType.ERROR);
			return;
		}

		setLoading(true);
		CompletableFuture.runAsync(() -> {
			try {
				NodeRuntimeManager.adopt(chosen, version);
			} catch (DshException e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
			setLoading(false);
			if (throwable != null) {
				Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
				                  ? throwable.getCause()
				                  : throwable;
				Controllers.dialog(cause.getMessage(), i18n("dsh.node.add"), MessageType.ERROR);
			}
			refresh();
		}));
	}

	private void uninstall(String version) {
		Controllers.confirm(i18n("dsh.node.uninstall.confirm", version), i18n("dsh.node.uninstall"), () -> {
			if (busy) return;

			busy = true;
			setLoading(true);
			CompletableFuture.runAsync(() -> {
				try {
					NodeRuntimeManager.uninstall(version);
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

	record NodeRow(String title, String subtitle, String badge, @Unmodifiable List<String> tags, @Nullable Path node,
	               @Nullable String removable, @Nullable String selection, boolean selected) {

		static NodeRow installed(NodeRuntime runtime, boolean selected) {
			String major = runtime.version().split("\\.")[0];
			return new NodeRow("Node.js " + runtime.version(),
			                   runtime.node().toString(),
			                   major,
			                   List.of(i18n("dsh.node.tag.managed")),
			                   runtime.node(),
			                   runtime.version(),
			                   runtime.version(),
			                   selected);
		}

		static NodeRow system(DshNodeRuntime runtime, boolean selected) {
			String version = runtime.nodeVersion();
			return new NodeRow("Node.js " + version,
			                   runtime.node().toString(),
			                   version.split("\\.")[0],
			                   List.of(i18n("dsh.node.tag.system"),
			                           runtime.isNodeSupported()
			                           ? i18n("dsh.node.tag.supported")
			                           : i18n("dsh.node.tag.unsupported")),
			                   runtime.node(),
			                   null,
			                   DshNodeRuntime.SYSTEM,
			                   selected);
		}

		static NodeRow systemMissing() {
			return new NodeRow(i18n("dsh.node.system.missing.title"),
			                   DshNodeRuntime.requirement(),
			                   "-",
			                   List.of(i18n("dsh.node.tag.missing")),
			                   null,
			                   null,
			                   null,
			                   false);
		}
	}

	private static final class NodeRuntimesPageSkin extends ToolbarListPageSkin<NodeRow, NodeRuntimesPage> {
		NodeRuntimesPageSkin(NodeRuntimesPage control) {
			super(control);
		}

		@Override
		protected List<Node> initializeToolbar(NodeRuntimesPage page) {
			List<Node> toolbar = new ArrayList<>();
			toolbar.add(createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, page::refresh));
			toolbar.add(createToolbarButton2(i18n("dsh.node.download"),
			                                 SVG.DOWNLOAD,
			                                 () -> Controllers.navigate(new NodejsDownloadPage(page::refresh))));
			toolbar.add(createToolbarButton2(i18n("dsh.node.add"), SVG.ADD, page::addLocal));
			return toolbar;
		}

		@Override
		protected ListCell<NodeRow> createListCell(JFXListView<NodeRow> listView) {
			return new NodeItemCell(listView, getSkinnable());
		}
	}

	private static final class NodeItemCell extends ListCell<NodeRow> {
		private final JFXRadioButton selector = new JFXRadioButton() {
			@Override
			public void fire() {
				NodeRow row = getItem();
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
		private final NodeRuntimesPage page;
		private final RipplerContainer graphic;

		NodeItemCell(JFXListView<NodeRow> listView, NodeRuntimesPage page) {
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
				NodeRow row = getItem();
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
		protected void updateItem(@Nullable NodeRow row, boolean empty) {
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
			FXUtils.installFastTooltip(action, i18n("dsh.node.uninstall"));

			action.setOnAction(event -> {
				if (removable) page.uninstall(row.removable());
			});
		}
	}
}
