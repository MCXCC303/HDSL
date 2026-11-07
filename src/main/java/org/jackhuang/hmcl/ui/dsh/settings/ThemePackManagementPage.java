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
package org.jackhuang.hmcl.ui.dsh.settings;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXListView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.Skin;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.theme.*;
import org.jackhuang.hmcl.ui.*;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.dsh.ListSearchBar;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.i18n.LocalizedText;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Management page for installed theme packs.
 */
@NotNullByDefault
public final class ThemePackManagementPage extends ListPageBase<ThemePackManagementPage.ThemePackRow> implements Refreshable {

	private final ListSearchBar toolbar = new ListSearchBar(this::filter);
	private final List<ThemePackRow> loaded = new ArrayList<>();
	private boolean busy;

	/**
	 * Creates the page and reads the installed packs.
	 */
	public ThemePackManagementPage() {
		getStyleClass().add("theme-pack-management-page");
		refresh();
	}

	/**
	 * Returns a pack's display name in the launcher's language.
	 *
	 * @param pack the pack
	 * @return its name, or its identifier when the manifest carries none
	 */
	static String displayName(ThemePackManager.InstalledThemePack pack) {
		String name = text(pack.manifest().name());
		return name == null || name.isBlank() ? pack.manifest().id() : name;
	}

	/**
	 * Renders a localized text in the launcher's language.
	 *
	 * @param text the text, or {@code null}
	 * @return the rendering, or {@code null} when there is nothing to render
	 */
	static @Nullable String text(@Nullable LocalizedText text) {
		return text == null ? null : text.getText(I18n.getLocale().getCandidateLocales());
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new ThemePackManagementPageSkin(this);
	}

	@Override
	public void refresh() {
		if (busy) {
			return;
		}
		busy = true;
		setLoading(true);
		try {
			List<ThemePackRow> rows = new ArrayList<>();
			for (ThemePackManager.InstalledThemePack pack : ThemePackManager.listInstalled()) {
				rows.add(new ThemePackRow(pack, displayName(pack)));
			}
			rows.sort((left, right) -> left.id().compareToIgnoreCase(right.id()));
			loaded.clear();
			loaded.addAll(rows);
			setFailedReason(null);
			filter();
		} catch (IOException | RuntimeException e) {
			LOG.warning("Failed to read the installed theme packs", e);
			setFailedReason(i18n("theme_pack.load.failed") + " " + e.getMessage());
		} finally {
			busy = false;
			setLoading(false);
		}
	}

	private void filter() {
		getItems().setAll(loaded.stream().filter(row -> toolbar.accepts(row.title())).toList());
	}

	void reload() {
		refresh();
	}

	void importThemePack() {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(i18n("theme_pack.import.title"));
		chooser.getExtensionFilters()
		       .setAll(new FileChooser.ExtensionFilter(i18n("theme_pack.file"),
		                                               "*" + ThemePackExporter.FILE_EXTENSION));

		@Nullable Path chosen = Controllers.showOpenDialog(chooser);
		if (chosen == null) {
			return;
		}
		try {
			ThemePackManager.InstalledThemePack installed = ThemePackManager.install(chosen);
			Controllers.dialog(i18n("theme_pack.import.success", displayName(installed)),
			                   i18n("message.success"),
			                   MessageType.SUCCESS);
			reload();
		} catch (IOException | RuntimeException e) {
			LOG.warning("Failed to install the theme pack", e);
			Controllers.dialog(i18n("theme_pack.import.failed") + "\n" + e.getMessage(),
			                   i18n("message.error"),
			                   MessageType.ERROR);
		}
	}

	void applyTheme(ThemePackManager.InstalledThemePack pack, @Nullable Theme theme) {
		String name = displayName(pack) + (theme == null ? "" : " / " + text(theme.name()));
		Controllers.confirm(i18n("theme_pack.apply.confirm", name), i18n("theme_pack.apply"), () -> {
			try {
				if (theme == null) {
					settings().getThemeAppearanceOverrides().clear();
					settings().selectedThemeProperty().set(new ThemeReference(pack.manifest().id(), null));
				} else {
					ThemePackManager.apply(pack, theme);
				}
				SettingsManager.save();
				reload();
			} catch (IOException | RuntimeException e) {
				LOG.warning("Failed to apply the theme", e);
				Controllers.dialog(i18n("theme_pack.apply.failed") + "\n" + e.getMessage(),
				                   i18n("message.error"),
				                   MessageType.ERROR);
			}
		}, null);
	}

	void removeThemePack(ThemePackManager.InstalledThemePack pack) {
		Controllers.confirm(i18n("theme_pack.delete.confirm", displayName(pack)), i18n("theme_pack.delete"), () -> {
			try {
				ThemePackManager.uninstall(pack);
				Controllers.showToast(i18n("theme_pack.delete.success", displayName(pack)));
				reload();
			} catch (IOException | RuntimeException e) {
				LOG.warning("Failed to delete the theme pack", e);
				Controllers.dialog(i18n("theme_pack.delete.failed") + "\n" + e.getMessage(),
				                   i18n("message.error"),
				                   MessageType.ERROR);
			}
		}, null);
	}

	static final class ThemePackRow {
		private final ThemePackManager.InstalledThemePack pack;
		private final String title;
		private final String id;

		ThemePackRow(ThemePackManager.InstalledThemePack pack, String title) {
			this.pack = pack;
			this.title = title;
			this.id = pack.manifest().id();
		}

		ThemePackManager.InstalledThemePack pack() {
			return pack;
		}

		String title() {
			return title;
		}

		String id() {
			return id;
		}
	}

	private static final class ThemePackManagementPageSkin extends ToolbarListPageSkin<ThemePackRow, ThemePackManagementPage> {

		ThemePackManagementPageSkin(ThemePackManagementPage control) {
			super(control);
			setPlaceholder(i18n("theme_pack.empty"));
		}

		@Override
		protected List<Node> initializeToolbar(ThemePackManagementPage page) {
			page.toolbar.setButtons(ToolbarListPageSkin.createToolbarButton2(i18n("button.refresh"),
			                                                                 SVG.REFRESH,
			                                                                 page::reload),
			                        ToolbarListPageSkin.createToolbarButton2(i18n("theme_pack.import"),
			                                                                 SVG.FILE_OPEN,
			                                                                 page::importThemePack),
			                        ToolbarListPageSkin.createToolbarButton2(i18n("theme_pack.directory"),
			                                                                 SVG.FOLDER_OPEN,
			                                                                 () -> FXUtils.openFolder(ThemePackManager.THEME_PACKS_DIRECTORY)));
			return List.of(page.toolbar);
		}

		@Override
		protected ListCell<ThemePackRow> createListCell(JFXListView<ThemePackRow> listView) {
			return new ThemePackItemCell(listView, getSkinnable());
		}
	}

	private static final class ThemePackItemCell extends ListCell<ThemePackRow> {
		private final TwoLineListItem content = new TwoLineListItem();
		private final FlowPane themes = new FlowPane(6, 6);
		private final HBox actions = new HBox(4);
		private final RipplerContainer graphic;
		private final ThemePackManagementPage page;

		ThemePackItemCell(JFXListView<ThemePackRow> listView, ThemePackManagementPage page) {
			this.page = page;

			themes.setAlignment(Pos.CENTER_LEFT);
			actions.setAlignment(Pos.CENTER_RIGHT);

			VBox center = new VBox(6, content, themes);
			center.setMouseTransparent(true);

			BorderPane root = new BorderPane();
			root.getStyleClass().add("md-list-cell");
			root.setPadding(new Insets(8));
			root.setCenter(center);
			root.setRight(actions);

			graphic = new RipplerContainer(root);
			graphic.setPickOnBounds(true);
		}

		@Override
		protected void updateItem(@Nullable ThemePackRow row, boolean empty) {
			super.updateItem(row, empty);
			if (empty || row == null) {
				setGraphic(null);
				return;
			}

			ThemePackManager.InstalledThemePack pack = row.pack();
			ThemePackManifest manifest = pack.manifest();

			content.setTitle(displayName(pack));
			String description = text(manifest.description());
			content.setSubtitle(description == null || description.isBlank()
			                    ? i18n("theme_pack.version", manifest.version())
			                    : description);
			content.getTags().clear();
			content.addTag(i18n("theme_pack.themes", manifest.themes().size()));
			if (pack.builtin()) {
				content.addTag(i18n("theme_pack.builtin"));
			}

			List<Theme> choices = manifest.themes();
			List<Node> buttons = new ArrayList<>();
			if (choices.isEmpty()) {
				buttons.add(themeButton(pack, null, i18n("theme_pack.apply")));
			} else {
				for (Theme theme : choices) {
					String name = text(theme.name());
					buttons.add(themeButton(pack,
					                        theme,
					                        name == null || name.isBlank()
					                        ? Objects.requireNonNullElse(theme.id(),
					                                                     i18n("theme_pack.apply"))
					                        : name));
				}
			}
			themes.getChildren().setAll(buttons);

			actions.getChildren()
			       .setAll(ToolbarListPageSkin.createToolbarButton2(i18n("theme_pack.delete"),
			                                                        SVG.DELETE_FOREVER,
			                                                        () -> page.removeThemePack(pack)));

			setGraphic(graphic);
			setPrefHeight(USE_COMPUTED_SIZE);
		}

		private JFXButton themeButton(ThemePackManager.InstalledThemePack pack, @Nullable Theme theme, String name) {
			JFXButton button = new JFXButton(name);
			button.getStyleClass().add("jfx-button-border");
			button.setOnAction(event -> page.applyTheme(pack, theme));
			return button;
		}
	}
}
