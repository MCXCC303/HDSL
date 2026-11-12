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
import com.jfoenix.controls.JFXSwitch;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshFileManager;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.skill.DshSkill;
import com.mcxcc303.hdsl.skill.DshSkillManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Skin;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.*;
import org.jackhuang.hmcl.ui.animation.AnimationUtils;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Lists the skill packs installed in an instance's DSH home.
@NotNullByDefault
public final class SkillListPage extends ListPageBase<DshSkill> implements Refreshable {
	/// The two shapes the harness reads; this only drives the drop cursor.
	private static final PathMatcher SKILL_PACKS = path ->
			Files.isDirectory(path) || (path.getFileName() != null && (path.getFileName().toString().endsWith(".md") ||
			                                                           path.getFileName().toString().endsWith(".zip")));
	private final DshInstance instance;
	private final ListSearchBar toolbar = new ListSearchBar(this::refresh);
	private final HBox selectingToolbar = new HBox(8);
	private final StackPane toolbarPane = new StackPane(toolbar, selectingToolbar);
	private boolean busy;

	public SkillListPage(DshInstance instance) {
		this.instance = instance;
		FXUtils.applyDragListener(this, SKILL_PACKS, this::addFiles);
		refresh();
	}

	/// Returns the failure a {@link CompletableFuture} completed with.
	private static Throwable causeOf(Throwable throwable) {
		return throwable instanceof CompletionException && throwable.getCause() != null
		       ? throwable.getCause()
		       : throwable;
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new SkillListPageSkin(this);
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
				return DshSkillManager.list(instance.homeDirectory());
			} catch (DshException e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io()).whenComplete((skills, throwable) -> runInFX(() -> {
			busy = false;
			setLoading(false);
			if (throwable != null) {
				LOG.warning("Failed to read the skill packs", causeOf(throwable));
				setFailedReason(causeOf(throwable).getMessage());
				return;
			}

			List<DshSkill> rows = new ArrayList<>();
			for (DshSkill skill : skills) {
				if (toolbar.accepts(skill.name())) {
					rows.add(skill);
				}
			}
			getItems().setAll(rows);
		}));
	}

	/// Switches the given skills off for the agent, or back on.
	private void setEnabled(Collection<DshSkill> skills, boolean enabled) {
		List<DshSkill> targets = skills.stream().filter(skill -> skill.enabled() != enabled).toList();
		if (targets.isEmpty()) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				for (DshSkill skill : targets) {
					DshSkillManager.setEnabled(skill, enabled);
				}
			} catch (DshException | RuntimeException e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
			if (throwable != null) {
				LOG.warning("Failed to switch the skill packs", causeOf(throwable));
				Controllers.dialog(causeOf(throwable).getMessage(),
				                   i18n("dsh.instance.skills.toggle_failed"),
				                   MessageType.ERROR);
			}
			refresh();
		}));
	}

	/// Removes the given packs after confirmation.
	private void removeSelected(Collection<DshSkill> skills) {
		List<DshSkill> targets = List.copyOf(skills);
		if (targets.isEmpty()) {
			return;
		}
		String message = targets.size() == 1
		                 ? i18n("dsh.instance.skills.remove.confirm", targets.get(0).name())
		                 : i18n("button.remove.confirm");
		Controllers.confirm(message, i18n("button.remove"), () -> CompletableFuture.runAsync(() -> {
			try {
				for (DshSkill skill : targets) {
					DshSkillManager.remove(skill);
				}
			} catch (DshException | RuntimeException e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
			if (throwable != null) {
				LOG.warning("Failed to remove the skill packs", causeOf(throwable));
				Controllers.dialog(i18n("dsh.instance.skills.remove_failed", causeOf(throwable).getMessage()),
				                   i18n("message.error"),
				                   MessageType.ERROR);
			}
			refresh();
		})), null);
	}

	/// Copies the given packs into the instance, saying which ones would not go.
	private void addFiles(List<Path> files) {
		if (files.isEmpty()) {
			return;
		}
		CompletableFuture.supplyAsync(() -> {
			List<String> failures = new ArrayList<>();
			for (Path file : files) {
				try {
					DshSkillManager.install(instance.homeDirectory(), file);
				} catch (DshException | RuntimeException e) {
					failures.add(file.getFileName() + ": " + e.getMessage());
				}
			}
			return failures;
		}, Schedulers.io()).whenComplete((failures, throwable) -> runInFX(() -> {
			if (throwable != null) {
				LOG.warning("Failed to add the skill packs", causeOf(throwable));
				Controllers.dialog(causeOf(throwable).getMessage(),
				                   i18n("dsh.instance.skills.add_failed", ""),
				                   MessageType.ERROR);
			} else if (!failures.isEmpty()) {
				Controllers.dialog(i18n("dsh.instance.skills.add_failed", String.join("; ", failures)),
				                   i18n("message.error"),
				                   MessageType.ERROR);
			}
			refresh();
		}));
	}

	/// Asks the user for the Markdown packs to add.
	private void onAdd() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(i18n("dsh.instance.skills.add"));
		chooser.getExtensionFilters()
		       .add(new FileChooser.ExtensionFilter(i18n("dsh.instance.skills.add.filter"), "*.md", "*.zip"));
		List<File> chosen = chooser.showOpenMultipleDialog(Controllers.getStage());
		if (chosen != null) {
			addFiles(chosen.stream().map(File::toPath).toList());
		}
	}

	/// Opens the page that lists the community's skill packs.
	private void openMarket() {
		DownloadPage page = Controllers.getDownloadPage();
		Controllers.navigate(page);
		page.openTab("skills");
	}

	/// Opens the skills directory, making it first when the instance has none yet.
	private void reveal() {
		try {
			Path directory = DshSkillManager.directory(instance.homeDirectory());
			Files.createDirectories(directory);
			DshFileManager.openDirectory(directory);
		} catch (Exception e) {
			LOG.warning("Failed to open the skills directory", e);
			Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
		}
	}

	/// Gives the page the toolbars that act on the selection in the list.
	private void attachList(JFXListView<DshSkill> listView) {
		toolbar.setButtons(ToolbarListPageSkin.createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, this::refresh),
		                   ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.skills.add"),
		                                                            SVG.ADD,
		                                                            this::onAdd),
		                   ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.skills.download"),
		                                                            SVG.DOWNLOAD,
		                                                            this::openMarket),
		                   ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.skills.reveal"),
		                                                            SVG.FOLDER_OPEN,
		                                                            this::reveal));

		JFXButton selectAll = ToolbarListPageSkin.createToolbarButton2(i18n("button.select_all"),
		                                                               SVG.SELECT_ALL,
		                                                               () -> listView.getSelectionModel()
		                                                                             .selectRange(0,
		                                                                                          listView.getItems()
		                                                                                                  .size()));
		selectingToolbar.getChildren()
		                .setAll(ToolbarListPageSkin.createToolbarButton2(i18n("button.enable"),
		                                                                 SVG.CHECK,
		                                                                 () -> setEnabled(listView.getSelectionModel()
		                                                                                          .getSelectedItems(),
		                                                                                  true)),
		                        ToolbarListPageSkin.createToolbarButton2(i18n("button.disable"),
		                                                                 SVG.CLOSE,
		                                                                 () -> setEnabled(listView.getSelectionModel()
		                                                                                          .getSelectedItems(),
		                                                                                  false)),
		                        ToolbarListPageSkin.createToolbarButton2(i18n("button.remove"),
		                                                                 SVG.DELETE_FOREVER,
		                                                                 () -> removeSelected(listView.getSelectionModel()
		                                                                                              .getSelectedItems())),
		                        selectAll,
		                        ToolbarListPageSkin.createToolbarButton2(i18n("button.cancel"),
		                                                                 SVG.CANCEL,
		                                                                 () -> listView.getSelectionModel()
		                                                                               .clearSelection()));

		listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		FXUtils.onChangeAndOperate(listView.getSelectionModel().selectedItemProperty(),
		                           selected -> showSelectingToolbar(selected != null));
	}

	/// Shows the toolbar that acts on a selection, or the ordinary one.
	private void showSelectingToolbar(boolean selecting) {
		selectingToolbar.setVisible(selecting);
		selectingToolbar.setManaged(selecting);
		toolbar.setVisible(!selecting);
		toolbar.setManaged(!selecting);
	}

	/// The page's skin: a toolbar above the skill list, and the placeholder the harness's
	/// own empty state reads as.
	private static final class SkillListPageSkin extends ToolbarListPageSkin<DshSkill, SkillListPage> {
		SkillListPageSkin(SkillListPage control) {
			super(control);
			setPlaceholder(i18n("dsh.skills.empty"));
			// The selection toolbar acts on the list, which the base skin creates after the toolbars.
			control.attachList(listView);
		}

		@Override
		protected List<Node> initializeToolbar(SkillListPage page) {
			page.showSelectingToolbar(false);
			page.selectingToolbar.setAlignment(Pos.CENTER_LEFT);
			return List.of(page.toolbarPane);
		}

		@Override
		protected ListCell<DshSkill> createListCell(JFXListView<DshSkill> listView) {
			return new SkillItemCell(listView, getSkinnable());
		}
	}

	// One row of the skill list.
	private static final class SkillItemCell extends MDListCell<DshSkill> {
		private final SkillListPage page;
		private final JFXCheckBox tick = createSelectionTick();
		private final JFXSwitch active = new JFXSwitch();
		private final ImageContainer icon = new ImageContainer(32);
		private final TwoLineListItem content = new TwoLineListItem();
		private final JFXButton reveal = FXUtils.newToggleButton4(SVG.FOLDER);
		private final JFXButton remove = FXUtils.newToggleButton4(SVG.DELETE);

		SkillItemCell(JFXListView<DshSkill> listView, SkillListPage page) {
			super(listView);
			this.page = page;

			HBox container = new HBox(8);
			container.setPickOnBounds(false);
			container.setAlignment(Pos.CENTER_LEFT);
			HBox.setHgrow(content, Priority.ALWAYS);
			content.setMouseTransparent(true);
			setSelectable();

			icon.setImage(DshInstanceIcon.COMMAND.load());
			FXUtils.installFastTooltip(reveal, i18n("reveal.in_file_manager"));
			FXUtils.installFastTooltip(remove, i18n("button.remove"));

			active.setOnAction(event -> {
				DshSkill skill = getItem();
				if (skill != null) {
					page.setEnabled(List.of(skill), active.isSelected());
				}
			});

			HBox actions = new HBox(8);
			actions.setAlignment(Pos.CENTER);
			actions.getChildren().setAll(reveal, remove, active);
			active.setSize(20);
			active.setMinSize(30, 30);
			active.setPrefSize(30, 30);
			active.setMaxSize(30, 30);

			container.getChildren().setAll(tick, icon, content, actions);
			StackPane.setMargin(container, new Insets(8));
			getContainer().getChildren().setAll(container);
		}

		@Override
		protected void updateControl(@Nullable DshSkill skill, boolean empty) {
			if (empty || skill == null) {
				return;
			}

			active.setDisableAnimation(true);
			active.setSelected(skill.enabled());
			active.setDisableAnimation(!AnimationUtils.isAnimationEnabled());
			String hint = skill.enabled()
			               ? i18n("dsh.instance.skills.toggle.off")
			               : i18n("dsh.instance.skills.toggle.on");
			FXUtils.installFastTooltip(active, hint);
			active.setAccessibleText(hint);

			content.setTitle(skill.name());
			content.setSubtitle(skill.description().isEmpty() ? skill.fileName() : skill.description());
			content.getTags().clear();
			if (!skill.enabled()) {
				content.addTagWarning(i18n("dsh.instance.skills.disabled"));
			}

			reveal.setOnAction(event -> DshFileManager.reveal(skill.entry()));
			remove.setOnAction(event -> page.removeSelected(List.of(skill)));
		}
	}
}
