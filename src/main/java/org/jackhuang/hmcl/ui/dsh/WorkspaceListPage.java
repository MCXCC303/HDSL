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
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXListView;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.instance.DshWorkspace;
import com.mcxcc303.hdsl.launch.DshSession;
import com.mcxcc303.hdsl.launch.DshSessions;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.Skin;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.*;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Lists the workspaces where an instance's sessions were recorded.
 */
@NotNullByDefault
public final class WorkspaceListPage extends ListPageBase<DshWorkspace> implements Refreshable {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
	                                                                    .withZone(ZoneId.systemDefault());

	private final DshInstance instance;
	private final Set<String> selected = new LinkedHashSet<>();
	private final HBox toolbar = new HBox(8);
	private final HBox selectingToolbar = new HBox(8);
	private final StackPane toolbarPane = new StackPane(toolbar, selectingToolbar);
	private boolean busy;

	/**
	 * Creates the page.
	 *
	 * @param instance the instance whose workspaces are listed
	 */
	public WorkspaceListPage(DshInstance instance) {
		this.instance = instance;
		refresh();
	}

	private static String describe(DshWorkspace workspace) {
		StringBuilder builder = new StringBuilder();
		if (workspace.path() != null) {
			builder.append(workspace.path()).append(" · ");
		}
		builder.append(i18n("dsh.workspace.sessions", workspace.sessions().size()));
		builder.append(" · ").append(DshSessions.formatSize(workspace.sizeBytes()));
		if (workspace.modifiedAt() > 0) {
			builder.append(" · ").append(TIMESTAMP.format(Instant.ofEpochMilli(workspace.modifiedAt())));
		}
		return builder.toString();
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new WorkspaceListPageSkin(this);
	}

	@Override
	public void refresh() {
		if (busy) {
			return;
		}
		busy = true;
		setLoading(true);

		CompletableFuture.supplyAsync(() -> {
			try {
				return DshSessions.workspaces(instance.homeDirectory());
			} catch (DshException e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io()).whenComplete((workspaces, throwable) -> runInFX(() -> {
			busy = false;
			setLoading(false);
			if (throwable != null) {
				Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
				                  ? throwable.getCause()
				                  : throwable;
				LOG.warning("Failed to list workspaces", cause);
				setFailedReason(cause.getMessage());
				return;
			}
			getItems().setAll(workspaces);
			selected.retainAll(workspaces.stream().map(DshWorkspace::slug).toList());
			showSelectingToolbar(!selected.isEmpty());
		}));
	}

	private void attachList(JFXListView<DshWorkspace> listView) {
		toolbar.setAlignment(Pos.CENTER_LEFT);
		toolbar.getChildren()
		       .setAll(ToolbarListPageSkin.createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, this::refresh),
		               ToolbarListPageSkin.createToolbarButton2(i18n("dsh.session.import"),
		                                                        SVG.DOWNLOAD,
		                                                        () -> SessionPackActions.importFromSystem(instance,
		                                                                                                  this::refresh)),
		               ToolbarListPageSkin.createToolbarButton2(i18n("dsh.session.pack.import"),
		                                                        SVG.FILE_OPEN,
		                                                        () -> SessionPackActions.importPack(instance,
		                                                                                            this::refresh)));

		selectingToolbar.setAlignment(Pos.CENTER_LEFT);
		selectingToolbar.getChildren()
		                .setAll(ToolbarListPageSkin.createToolbarButton2(i18n("dsh.session.pack.export.workspaces"),
		                                                                 SVG.ARCHIVE,
		                                                                 this::exportSelected),
		                        ToolbarListPageSkin.createToolbarButton2(i18n("button.cancel"), SVG.CANCEL, () -> {
			                        selected.clear();
			                        listView.refresh();
			                        showSelectingToolbar(false);
		                        }));

		showSelectingToolbar(!selected.isEmpty());
	}

	private void showSelectingToolbar(boolean selecting) {
		selectingToolbar.setVisible(selecting);
		selectingToolbar.setManaged(selecting);
		toolbar.setVisible(!selecting);
		toolbar.setManaged(!selecting);
	}

	void setTicked(DshWorkspace workspace, boolean ticked) {
		if (ticked) {
			selected.add(workspace.slug());
		} else {
			selected.remove(workspace.slug());
		}
		showSelectingToolbar(!selected.isEmpty());
	}

	boolean isTicked(DshWorkspace workspace) {
		return selected.contains(workspace.slug());
	}

	private void open(DshWorkspace workspace) {
		Controllers.navigate(new SessionListPage(instance, workspace));
	}

	private void exportSelected() {
		List<DshSession> sessions = new ArrayList<>();
		for (DshWorkspace workspace : getItems()) {
			if (selected.contains(workspace.slug())) {
				sessions.addAll(workspace.sessions());
			}
		}
		SessionPackActions.export(instance, sessions);
	}

	private static final class WorkspaceListPageSkin extends ToolbarListPageSkin<DshWorkspace, WorkspaceListPage> {

		WorkspaceListPageSkin(WorkspaceListPage control) {
			super(control);
			setPlaceholder(i18n("dsh.workspaces.empty"));
			control.attachList(listView);
		}

		@Override
		protected List<Node> initializeToolbar(WorkspaceListPage page) {
			page.showSelectingToolbar(false);
			page.toolbar.setAlignment(Pos.CENTER_LEFT);
			return List.of(page.toolbarPane);
		}

		@Override
		protected ListCell<DshWorkspace> createListCell(JFXListView<DshWorkspace> listView) {
			return new WorkspaceListCell(listView, getSkinnable());
		}
	}

	private static final class WorkspaceListCell extends MDListCell<DshWorkspace> {
		private final WorkspaceListPage page;
		private final JFXCheckBox tick = new JFXCheckBox();
		private final ImageContainer icon = new ImageContainer(32);
		private final TwoLineListItem content = new TwoLineListItem();
		private final JFXButton open = FXUtils.newToggleButton4(SVG.ARROW_FORWARD);

		WorkspaceListCell(JFXListView<DshWorkspace> listView, WorkspaceListPage page) {
			super(listView);
			this.page = page;

			HBox container = new HBox(8);
			container.setPickOnBounds(false);
			container.setAlignment(Pos.CENTER_LEFT);
			HBox.setHgrow(content, Priority.ALWAYS);
			content.setMouseTransparent(true);

			icon.setImage(DshInstanceIcon.COMMAND.load());
			FXUtils.installFastTooltip(open, i18n("dsh.workspace.open"));

			tick.setOnAction(event -> {
				DshWorkspace workspace = getItem();
				if (workspace != null) {
					page.setTicked(workspace, tick.isSelected());
				}
			});
			open.setOnAction(event -> {
				DshWorkspace workspace = getItem();
				if (workspace != null) {
					page.open(workspace);
				}
			});

			setOnMouseClicked(event -> {
				DshWorkspace workspace = getItem();
				if (workspace == null || event.getButton() != MouseButton.PRIMARY) {
					return;
				}
				Node target = event.getTarget() instanceof Node node ? node : null;
				if (target != null && (isWithin(target, tick) || isWithin(target, open))) {
					return;
				}
				page.open(workspace);
			});

			StackPane left = new StackPane(icon);
			left.setPadding(new Insets(0, 8, 0, 0));
			container.getChildren().setAll(tick, left, content, open);
			StackPane.setMargin(container, new Insets(8));
			getContainer().getChildren().setAll(container);
		}

		private static boolean isWithin(Node node, Node ancestor) {
			for (Node current = node; current != null; current = current.getParent()) {
				if (current == ancestor) {
					return true;
				}
			}
			return false;
		}

		@Override
		protected void updateControl(@Nullable DshWorkspace workspace, boolean empty) {
			if (empty || workspace == null) {
				return;
			}

			tick.setSelected(page.isTicked(workspace));
			content.setTitle(workspace.title());
			content.setSubtitle(describe(workspace));
			content.getTags().clear();
		}
	}
}
