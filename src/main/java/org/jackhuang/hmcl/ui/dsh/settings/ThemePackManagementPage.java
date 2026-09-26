/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.theme.Theme;
import org.jackhuang.hmcl.theme.ThemePackManager;
import org.jackhuang.hmcl.theme.ThemePackManifest;
import org.jackhuang.hmcl.theme.ThemeReference;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.ListPageBase;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.RipplerContainer;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.dsh.ListSearchBar;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The theme packs installed in this launcher.
///
/// The page the appearance tab's first row leads to. The original keeps theme packs on a
/// page of their own rather than in a dropdown because a pack is more than a name: it
/// carries a version, an author and usually several themes, and picking one is picking a
/// theme inside it — which is a list, not a menu.
///
/// The shape is the one this launcher's other list pages use — [ListPageBase] with a
/// [ToolbarListPageSkin] and rows derived from the original's own two-line cell — so a
/// reader of HMCL finds the toolbar, the search and the per-row actions where they expect
/// them.
@NotNullByDefault
public final class ThemePackManagementPage extends ListPageBase<ThemePackManagementPage.ThemePackRow>
        implements Refreshable {
    /// The toolbar, which swaps itself for a search field.
    private final ListSearchBar toolbar = new ListSearchBar(this::filter);

    /// Everything the last read returned, before the search narrows it.
    private final List<ThemePackRow> loaded = new ArrayList<>();

    /// Whether a read is already running.
    private boolean busy;

    /// Creates the page and reads the installed packs.
    public ThemePackManagementPage() {
        getStyleClass().add("theme-pack-management-page");
        refresh();
    }

    @Override
    protected javafx.scene.control.Skin<?> createDefaultSkin() {
        return new ThemePackManagementPageSkin(this);
    }

    /// Reads the installed theme packs and fills the list.
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

    /// Applies the name filter and redraws.
    ///
    /// The rows are re-listed rather than hidden: a `ListCell` that hides itself still
    /// takes part in the virtual flow's arithmetic, which leaves gaps where the matches
    /// it skipped would have been.
    private void filter() {
        getItems().setAll(loaded.stream().filter(row -> toolbar.accepts(row.title())).toList());
    }

    /// Reads the packs and redraws.
    void reload() {
        refresh();
    }

    /// Asks for a theme-pack file and installs it.
    void importThemePack() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("theme_pack.import.title"));
        chooser.getExtensionFilters().setAll(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("theme_pack.file"),
                "*" + org.jackhuang.hmcl.theme.ThemePackExporter.FILE_EXTENSION));

        @Nullable Path chosen = Controllers.showOpenDialog(chooser);
        if (chosen == null) {
            return;
        }
        try {
            ThemePackManager.InstalledThemePack installed = ThemePackManager.install(chosen);
            Controllers.dialog(i18n("theme_pack.import.success", displayName(installed)),
                    i18n("message.success"), MessageType.SUCCESS);
            reload();
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to install the theme pack", e);
            Controllers.dialog(i18n("theme_pack.import.failed") + "\n" + e.getMessage(),
                    i18n("message.error"), MessageType.ERROR);
        }
    }

    /// Applies one theme of one pack, after asking.
    ///
    /// @param pack  the theme pack
    /// @param theme the theme inside it, or `null` for a pack that holds none
    void applyTheme(ThemePackManager.InstalledThemePack pack, @Nullable Theme theme) {
        String name = displayName(pack) + (theme == null ? "" : " / " + text(theme.name()));
        Controllers.confirm(i18n("theme_pack.apply.confirm", name), i18n("theme_pack.apply"), () -> {
            try {
                if (theme == null) {
                    // A pack with no themes is a whole appearance on its own, which is
                    // what a null theme id means; there is no theme to hand to apply().
                    settings().getThemeAppearanceOverrides().clear();
                    settings().selectedThemeProperty().set(new ThemeReference(pack.manifest().id(), null));
                } else {
                    ThemePackManager.apply(pack, theme);
                }
                org.jackhuang.hmcl.setting.SettingsManager.save();
                reload();
            } catch (IOException | RuntimeException e) {
                LOG.warning("Failed to apply the theme", e);
                Controllers.dialog(i18n("theme_pack.apply.failed") + "\n" + e.getMessage(),
                        i18n("message.error"), MessageType.ERROR);
            }
        }, null);
    }

    /// Removes an installed theme pack, after asking.
    ///
    /// @param pack the pack
    void removeThemePack(ThemePackManager.InstalledThemePack pack) {
        Controllers.confirm(i18n("theme_pack.delete.confirm", displayName(pack)),
                i18n("theme_pack.delete"), () -> {
                    try {
                        ThemePackManager.uninstall(pack);
                        Controllers.showToast(i18n("theme_pack.delete.success", displayName(pack)));
                        reload();
                    } catch (IOException | RuntimeException e) {
                        LOG.warning("Failed to delete the theme pack", e);
                        Controllers.dialog(i18n("theme_pack.delete.failed") + "\n" + e.getMessage(),
                                i18n("message.error"), MessageType.ERROR);
                    }
                }, null);
    }

    /// Returns a pack's display name in the launcher's language.
    ///
    /// @param pack the pack
    /// @return its name, or its identifier when the manifest carries none
    static String displayName(ThemePackManager.InstalledThemePack pack) {
        String name = text(pack.manifest().name());
        return name == null || name.isBlank() ? pack.manifest().id() : name;
    }

    /// Renders a localized text in the launcher's language.
    ///
    /// @param text the text, or `null`
    /// @return the rendering, or `null` when there is nothing to render
    static @Nullable String text(@Nullable org.jackhuang.hmcl.util.i18n.LocalizedText text) {
        return text == null ? null : text.getText(org.jackhuang.hmcl.util.i18n.I18n.getLocale()
                .getCandidateLocales());
    }

    /// One installed theme pack.
    static final class ThemePackRow {
        /// The installed pack.
        private final ThemePackManager.InstalledThemePack pack;

        /// The name the search matches on.
        private final String title;

        /// The identifier, used to order the list.
        private final String id;

        /// Creates a row.
        ///
        /// @param pack  the installed pack
        /// @param title the name the search matches on
        ThemePackRow(ThemePackManager.InstalledThemePack pack, String title) {
            this.pack = pack;
            this.title = title;
            this.id = pack.manifest().id();
        }

        /// Returns the installed pack.
        ///
        /// @return the pack
        ThemePackManager.InstalledThemePack pack() {
            return pack;
        }

        /// Returns the name the search matches on.
        ///
        /// @return the name
        String title() {
            return title;
        }

        /// Returns the identifier.
        ///
        /// @return the identifier
        String id() {
            return id;
        }
    }

    /// The page's skin: a toolbar above the pack list.
    private static final class ThemePackManagementPageSkin
            extends ToolbarListPageSkin<ThemePackRow, ThemePackManagementPage> {
        /// Creates the skin.
        ///
        /// @param control the page
        ThemePackManagementPageSkin(ThemePackManagementPage control) {
            super(control);
            setPlaceholder(i18n("theme_pack.empty"));
        }

        @Override
        protected List<Node> initializeToolbar(ThemePackManagementPage page) {
            page.toolbar.setButtons(
                    ToolbarListPageSkin.createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, page::reload),
                    ToolbarListPageSkin.createToolbarButton2(i18n("theme_pack.import"), SVG.FILE_OPEN,
                            page::importThemePack),
                    ToolbarListPageSkin.createToolbarButton2(i18n("theme_pack.directory"), SVG.FOLDER_OPEN,
                            () -> FXUtils.openFolder(ThemePackManager.THEME_PACKS_DIRECTORY)));
            return List.of(page.toolbar);
        }

        @Override
        protected ListCell<ThemePackRow> createListCell(JFXListView<ThemePackRow> listView) {
            return new ThemePackItemCell(listView, getSkinnable());
        }
    }

    /// One row of the pack list.
    ///
    /// The shape follows the original's theme-pack rows: the name with what the pack says
    /// about itself beneath it, the themes it holds listed under it, and the actions on the
    /// trailing edge. A pack that holds several themes lists them inside the row rather than
    /// leading to another page: the choice is the point of the page, and the original puts
    /// the same choice one step further in only because its row is a tree.
    private static final class ThemePackItemCell extends ListCell<ThemePackRow> {
        /// The name, what the pack says about itself, and the themes it holds.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The themes of this pack, one button each.
        private final javafx.scene.layout.FlowPane themes = new javafx.scene.layout.FlowPane(6, 6);

        /// The actions on the trailing edge.
        private final HBox actions = new HBox(4);

        /// The pack's graphic, built once and re-installed for every non-empty item.
        ///
        /// A `ListCell` is recycled by the virtual flow, so the graphic has to be set
        /// again for every item: clearing it for an empty cell otherwise leaves every
        /// later row blank.
        private final RipplerContainer graphic;

        /// The page whose actions the row's buttons run.
        private final ThemePackManagementPage page;

        /// Creates the cell.
        ///
        /// @param listView the owning list
        /// @param page     the page the row's actions belong to
        ThemePackItemCell(JFXListView<ThemePackRow> listView, ThemePackManagementPage page) {
            this.page = page;

            themes.setAlignment(Pos.CENTER_LEFT);
            actions.setAlignment(Pos.CENTER_RIGHT);

            javafx.scene.layout.VBox center = new javafx.scene.layout.VBox(6, content, themes);
            center.setMouseTransparent(true);

            BorderPane root = new BorderPane();
            root.getStyleClass().add("md-list-cell");
            root.setPadding(new Insets(8));
            root.setCenter(center);
            root.setRight(actions);

            graphic = new RipplerContainer(root);
            // The row itself does nothing on a press: what can be done to a pack is one of
            // the buttons, and a press that applied a theme would make browsing the list
            // destructive.
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
            // A cell is reused for whatever scrolls into it, so what it said before has to
            // go: otherwise every row wears the tags of the rows it replaced.
            content.getTags().clear();
            content.addTag(i18n("theme_pack.themes", manifest.themes().size()));
            if (pack.builtin()) {
                content.addTag(i18n("theme_pack.builtin"));
            }

            // The pack's own themes, or the pack itself for one that declares none.
            List<Theme> choices = manifest.themes();
            List<Node> buttons = new ArrayList<>();
            if (choices.isEmpty()) {
                buttons.add(themeButton(pack, null, i18n("theme_pack.apply")));
            } else {
                for (Theme theme : choices) {
                    String name = text(theme.name());
                    buttons.add(themeButton(pack, theme, name == null || name.isBlank()
                            ? Objects.requireNonNullElse(theme.id(), i18n("theme_pack.apply"))
                            : name));
                }
            }
            themes.getChildren().setAll(buttons);

            actions.getChildren().setAll(ToolbarListPageSkin.createToolbarButton2(
                    i18n("theme_pack.delete"), SVG.DELETE_FOREVER,
                    () -> page.removeThemePack(pack)));

            setGraphic(graphic);
            // The row is only as tall as its own content: a fixed height would cut the
            // theme buttons off a pack that holds several.
            setPrefHeight(USE_COMPUTED_SIZE);
        }

        /// Builds the button that applies one theme of a pack.
        ///
        /// @param pack  the pack
        /// @param theme the theme, or `null` for a pack that holds none
        /// @param name  what the button says
        /// @return the button
        private JFXButton themeButton(
                ThemePackManager.InstalledThemePack pack, @Nullable Theme theme, String name) {
            JFXButton button = new JFXButton(name);
            button.getStyleClass().add("jfx-button-border");
            button.setOnAction(event -> page.applyTheme(pack, theme));
            return button;
        }
    }
}
