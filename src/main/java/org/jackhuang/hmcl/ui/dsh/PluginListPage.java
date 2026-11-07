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
import com.mcxcc303.hdsl.plugin.*;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Skin;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.*;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Lists the plugins installed into an instance's profile.
 */
@NotNullByDefault
public final class PluginListPage extends ListPageBase<PluginListPage.PluginRow> implements Refreshable {

	private final DshInstance instance;
	private final ListSearchBar toolbar = new ListSearchBar(this::refresh);
	private final HBox selectingToolbar = new HBox(8);
	private final StackPane toolbarPane = new StackPane(toolbar, selectingToolbar);
	private boolean busy;

	/**
	 * Creates the page.
	 *
	 * @param instance the instance whose plugins are listed
	 */
	public PluginListPage(DshInstance instance) {
		this.instance = instance;
		refresh();
	}

	/**
	 * Reports whether the patch layer can switch the plugin off.
	 *
	 * <p>A switch writes {@code disabled: true} onto the rows the bundle inserts, so only a
	 * loaded bundle with rows qualifies.
	 *
	 * @return whether the plugin has switchable rows
	 */
	private static boolean switchable(DshInstalledPlugins.Installed plugin) {
		return !plugin.rowIds().isEmpty() &&
		       (plugin.state() == DshInstalledPlugins.State.ACTIVE ||
		        plugin.state() == DshInstalledPlugins.State.DISABLED);
	}

	/**
	 * Reads the picture a package declares for itself.
	 *
	 * @param file the picture, or {@code null} when the package declares none
	 * @return the picture, or {@code null} when it cannot be shown
	 */
	private static @Nullable Image iconOf(@Nullable Path file) {
		if (file == null) {
			return null;
		}
		try {
			Image image = FXUtils.loadImage(file, 32, 32, true, true);
			return image.isError() || image.getWidth() <= 0 ? null : image;
		} catch (Exception e) {
			LOG.warning("Could not read the icon " + file, e);
			return null;
		}
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new PluginListPageSkin(this);
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
				return DshInstalledPlugins.of(instance);
			} catch (DshException e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io()).whenComplete((installed, throwable) -> runInFX(() -> {
			busy = false;
			setLoading(false);
			if (throwable != null) {
				Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
				                  ? throwable.getCause()
				                  : throwable;
				LOG.warning("Failed to read the profile", cause);
				setFailedReason(cause.getMessage());
				return;
			}

			List<PluginRow> rows = new ArrayList<>();
			for (DshInstalledPlugins.Installed plugin : installed) {
				if (!toolbar.accepts(plugin.name()) && !toolbar.accepts(plugin.displayName())) {
					continue;
				}
				rows.add(new PluginRow(plugin.name(), plugin.displayName(), plugin.declared(), plugin.state(),
				                       switchable(plugin), iconOf(plugin.icon())));
			}
			getItems().setAll(rows);
		}));
	}

	private void removeSelected(Collection<PluginRow> rows) {
		List<String> names = rows.stream().map(PluginRow::name).toList();
		if (names.isEmpty()) {
			return;
		}

		String message = names.size() == 1
		                 ? i18n("dsh.instance.plugins.remove.confirm", names.get(0))
		                 : i18n("button.remove.confirm");
		Controllers.confirm(message,
		                    i18n("button.remove"),
		                    () -> ProgressDialog.run(i18n("dsh.instance.plugins.remove"),
		                                             progress -> DshPluginInstaller.removeSpecs(instance,
		                                                                                        names,
		                                                                                        progress::accept),
		                                             this::refresh),
		                    null);
	}

	private void toggle(PluginRow row, JFXCheckBox box) {
		box.setDisable(true);
		apply(List.of(row), row.state() != DshInstalledPlugins.State.ACTIVE);
	}

	private void setEnabled(Collection<PluginRow> rows, boolean enabled) {
		DshInstalledPlugins.State wanted = enabled
		                                   ? DshInstalledPlugins.State.DISABLED
		                                   : DshInstalledPlugins.State.ACTIVE;
		List<PluginRow> targets = rows.stream().filter(row -> row.switchable() && row.state() == wanted).toList();
		if (!targets.isEmpty()) {
			apply(targets, enabled);
		}
	}

	private void apply(List<PluginRow> rows, boolean enabled) {
		CompletableFuture.runAsync(() -> {
			try {
				for (PluginRow row : rows) {
					DshPluginPatch.setEnabled(instance.homeDirectory(), instance.profile(), row.name(), enabled);
				}
			} catch (DshException | RuntimeException e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
			if (throwable != null) {
				Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
				                  ? throwable.getCause()
				                  : throwable;
				LOG.warning("Failed to switch plugins", cause);
				Controllers.dialog(cause.getMessage(), i18n("dsh.instance.plugins.toggle_failed"), MessageType.ERROR);
			}
			refresh();
		}));
	}

	private void openMarket() {
		DownloadPage page = Controllers.getDownloadPage();
		page.openPluginsFor(instance);
		Controllers.navigate(page);
	}

	private void installFromFile() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(i18n("dsh.instance.plugins.add"));
		chooser.getExtensionFilters()
		       .add(new FileChooser.ExtensionFilter(i18n("dsh.instance.plugins.add.filter"), "*.tgz"));
		File chosen = chooser.showOpenDialog(Controllers.getStage());
		if (chosen == null) {
			return;
		}

		Path file = chosen.toPath();
		PluginInstalls.run(instance,
		                   progress -> DshLocalPlugins.install(instance, file, progress::accept),
		                   this::refresh);
	}

	private void installFromSource() {
		Controllers.dialog(new PluginSourceDialog((source, handler) -> {
			if (source.repository() == null || source.repository().isBlank()) {
				handler.reject(i18n("dsh.plugin.source.repository.empty"));
				return;
			}
			handler.resolve();
			PluginInstalls.run(instance,
			                   progress -> DshPluginSources.install(instance,
			                                                        source.repository(),
			                                                        source.reference(),
			                                                        progress::accept),
			                   this::refresh);
		}));
	}

	private void attachList(JFXListView<PluginRow> listView) {
		toolbar.setButtons(ToolbarListPageSkin.createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, this::refresh),
		                   ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.plugins.add"),
		                                                            SVG.ADD,
		                                                            this::installFromFile),
		                   ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.plugins.add.url"),
		                                                            SVG.PUBLIC,
		                                                            this::installFromSource),
		                   ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.plugins.download"),
		                                                            SVG.DOWNLOAD,
		                                                            this::openMarket),
		                   ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.plugins.reveal"),
		                                                            SVG.FOLDER_OPEN,
		                                                            this::revealProfile));

		JFXButton selectAll = ToolbarListPageSkin.createToolbarButton2(i18n("button.select_all"),
		                                                               SVG.SELECT_ALL,
		                                                               () -> listView.getSelectionModel()
		                                                                             .selectRange(0,
		                                                                                          listView.getItems()
		                                                                                                  .size()));
		selectingToolbar.getChildren()
		                .setAll(ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.plugins.enable"),
		                                                                 SVG.CHECK,
		                                                                 () -> setEnabled(listView.getSelectionModel()
		                                                                                          .getSelectedItems(),
		                                                                                  true)),
		                        ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.plugins.disable"),
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

		ListChangeListener<Object> listener = change -> selectAll.setDisable(!listView.getItems().isEmpty() &&
		                                                                     listView.getSelectionModel()
		                                                                             .getSelectedItems()
		                                                                             .size() ==
		                                                                     listView.getItems().size());
		listView.getSelectionModel().getSelectedItems().addListener(listener);
		listView.getItems().addListener(listener);
	}

	private void showSelectingToolbar(boolean selecting) {
		selectingToolbar.setVisible(selecting);
		selectingToolbar.setManaged(selecting);
		toolbar.setVisible(!selecting);
		toolbar.setManaged(!selecting);
	}

	private void revealProfile() {
		try {
			FXUtils.showFileInExplorer(instance.homeDirectory().resolve("profiles").resolve(instance.profile()));
		} catch (DshException e) {
			Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
		}
	}

	/**
	 * One installed plugin.
	 *
	 * @param name        the package name
	 * @param displayName the name the plugin goes by in the harness
	 * @param declared    the declared version range
	 * @param state       whether the profile loads it, and whether its own rows are switched off
	 * @param switchable  whether its own loader rows can be switched
	 * @param icon        the picture the package declares, or {@code null} when it declares none
	 *                    that can be read
	 */
	public record PluginRow(String name, String displayName, String declared, DshInstalledPlugins.State state,
	                        boolean switchable, @Nullable Image icon) {
	}

	private static final class PluginListPageSkin extends ToolbarListPageSkin<PluginRow, PluginListPage> {

		PluginListPageSkin(PluginListPage control) {
			super(control);
			setPlaceholder(i18n("dsh.plugins.empty"));
			control.attachList(listView);
		}

		@Override
		protected List<Node> initializeToolbar(PluginListPage page) {
			page.showSelectingToolbar(false);
			page.selectingToolbar.setAlignment(Pos.CENTER_LEFT);
			return List.of(page.toolbarPane);
		}

		@Override
		protected ListCell<PluginRow> createListCell(JFXListView<PluginRow> listView) {
			return new PluginItemCell(listView, getSkinnable());
		}
	}

	private static final class PluginItemCell extends MDListCell<PluginRow> {
		private final PluginListPage page;
		private final JFXCheckBox active = new JFXCheckBox();
		private final ImageContainer icon = new ImageContainer(32);
		private final TwoLineListItem content = new TwoLineListItem();
		private final JFXButton info = FXUtils.newToggleButton4(SVG.INFO);
		private final JFXButton remove = FXUtils.newToggleButton4(SVG.DELETE);

		PluginItemCell(JFXListView<PluginRow> listView, PluginListPage page) {
			super(listView);
			this.page = page;

			HBox container = new HBox(8);
			container.setPickOnBounds(false);
			container.setAlignment(Pos.CENTER_LEFT);
			HBox.setHgrow(content, Priority.ALWAYS);
			content.setMouseTransparent(true);
			setSelectable();

			icon.setImage(DshInstanceIcon.COMMAND.load());
			FXUtils.installFastTooltip(info, i18n("dsh.instance.plugins.open"));
			FXUtils.installFastTooltip(remove, i18n("dsh.instance.plugins.remove"));

			active.setOnAction(event -> {
				PluginRow row = getItem();
				if (row != null) {
					page.toggle(row, active);
				}
			});

			container.getChildren().setAll(active, icon, content, info, remove);
			StackPane.setMargin(container, new Insets(8));
			getContainer().getChildren().setAll(container);
		}

		private static String stateHint(DshInstalledPlugins.State state, boolean switchable) {
			return switch (state) {
				case ACTIVE -> switchable
				               ? i18n("dsh.instance.plugins.toggle.off")
				               : i18n("dsh.instance.plugins.toggle.fixed");
				case DISABLED -> i18n("dsh.instance.plugins.toggle.on");
				case INACTIVE -> i18n("dsh.instance.plugins.inactive.short");
				case MISSING -> i18n("dsh.instance.plugins.missing");
			};
		}

		@Override
		protected void updateControl(@Nullable PluginRow row, boolean empty) {
			if (empty || row == null) {
				icon.setImage(DshInstanceIcon.COMMAND.load());
				return;
			}

			icon.setImage(row.icon() != null ? row.icon() : DshInstanceIcon.COMMAND.load());

			active.setSelected(row.state() == DshInstalledPlugins.State.ACTIVE);
			active.setDisable(!row.switchable());
			FXUtils.installFastTooltip(active, stateHint(row.state(), row.switchable()));

			content.setTitle(row.displayName());
			content.setSubtitle(i18n("dsh.instance.plugins.source", row.name()));
			content.getTags().clear();
			content.addTag(row.declared());

			info.setOnAction(event -> FXUtils.openLink("https://www.npmjs.com/package/" + row.name()));
			remove.setOnAction(event -> page.removeSelected(List.of(row)));
		}
	}
}
