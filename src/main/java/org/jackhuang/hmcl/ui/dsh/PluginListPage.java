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
package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXListView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Skin;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshLocalPlugins;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.dsh.DshPluginPatch;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import com.jfoenix.controls.JFXTextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import java.util.Locale;
import org.jackhuang.hmcl.ui.ListPageBase;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.MDListCell;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import javafx.collections.ListChangeListener;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Lists the plugins installed into an instance's profile.
///
/// Modelled on HMCL's mod list, which is the original's answer to the same
/// question: what is installed, and is it actually in effect. The tick box is
/// that second part — a package can be a dependency without being an active
/// bundle, which happens whenever a reconciling `dsh plugin` run did not
/// finish, and a list that only showed the dependency would hide exactly the
/// case worth seeing.
///
/// The box toggles: a plugin is switched off by a row in the profile's own patch
/// layer, which the loader re-applies on every boot, so the state survives a
/// restart. Only a package that is an active bundle with its own insert rows can
/// be switched; one that is merely a dependency, or that declares no rows, keeps
/// its box disabled and its explanation.
@NotNullByDefault
public final class PluginListPage extends ListPageBase<PluginListPage.PluginRow> implements Refreshable {
    /// The instance whose profile is listed.
    private final DshInstance instance;

    /// Whether a load is already running.
    private boolean busy;

    /// Creates the page.
    ///
    /// @param instance the instance whose plugins are listed
    public PluginListPage(DshInstance instance) {
        this.instance = instance;
        refresh();
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
                Path home = instance.homeDirectory();
                String profile = instance.profile();
                Map<String, String> dependencies = DshPluginInstaller.readDependencies(home, profile);
                List<String> bundles = DshPluginInstaller.readBundles(home, profile);
                Set<String> disabled = DshPluginPatch.disabledIds(home, profile);
                Map<String, List<String>> inserted = new LinkedHashMap<>();
                for (String name : dependencies.keySet()) {
                    inserted.put(name, DshPluginPatch.insertedIds(home, profile, name));
                }
                return new Loaded(dependencies, bundles, disabled, inserted);
            } catch (DshException e) {
                throw new CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((loaded, throwable) -> runInFX(() -> {
            busy = false;
            setLoading(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause() : throwable;
                LOG.warning("Failed to read the profile", cause);
                setFailedReason(cause.getMessage());
                return;
            }

            List<PluginRow> rows = new ArrayList<>();
            for (Map.Entry<String, String> entry : loaded.dependencies().entrySet()) {
                if (!toolbar.accepts(entry.getKey())) {
                    continue;
                }
                List<String> own = loaded.inserted().getOrDefault(entry.getKey(), List.of());
                boolean active = loaded.bundles().contains(entry.getKey());
                boolean disabled = own.stream().anyMatch(loaded.disabled()::contains);
                rows.add(new PluginRow(entry.getKey(), entry.getValue(), active,
                        active && !own.isEmpty(), disabled));
            }
            getItems().setAll(rows);
        }));
    }

    /// Removes the given plugins after confirmation.
    ///
    /// Both the row's own button and the toolbar's go through here, so a batch
    /// and a single removal differ only in how many names are passed: the
    /// original's confirm dialog, one `dsh plugin remove a b c` run, and the list
    /// read again afterwards.
    ///
    /// @param rows the rows to remove
    private void removeSelected(Collection<PluginRow> rows) {
        List<String> names = rows.stream().map(PluginRow::name).toList();
        if (names.isEmpty()) {
            return;
        }

        String message = names.size() == 1
                ? i18n("dsh.instance.plugins.remove.confirm", names.get(0))
                : i18n("button.remove.confirm");
        Controllers.confirm(message, i18n("button.remove"), () ->
                ProgressDialog.run(i18n("dsh.instance.plugins.remove"),
                        progress -> DshPluginInstaller.removeSpecs(instance, names, progress::accept),
                        this::refresh), null);
    }

    /// Switches one plugin on or off in the profile's patch layer.
    ///
    /// @param row the row that was clicked
    /// @param box the tick box, disabled while the write runs
    private void toggle(PluginRow row, JFXCheckBox box) {
        box.setDisable(true);
        apply(List.of(row), !row.enabled());
    }

    /// Switches the selected plugins on or off.
    ///
    /// A plugin already in the asked-for state is left alone, so a batch of
    /// mixed rows writes only the ones that move.
    ///
    /// @param rows    the selected rows
    /// @param enabled whether they should run
    private void setEnabled(Collection<PluginRow> rows, boolean enabled) {
        List<PluginRow> targets = rows.stream()
                .filter(row -> row.toggleable() && row.enabled() != enabled)
                .toList();
        if (!targets.isEmpty()) {
            apply(targets, enabled);
        }
    }

    /// Writes the switch into the patch layer and reads the profile again.
    ///
    /// @param rows    the plugins to switch
    /// @param enabled whether they should run
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
                        ? throwable.getCause() : throwable;
                LOG.warning("Failed to switch plugins", cause);
                Controllers.dialog(cause.getMessage(),
                        i18n("dsh.instance.plugins.toggle_failed"), MessageType.ERROR);
            }
            refresh();
        }));
    }

    /// Opens the page that lists the community's plugins.
    private void openMarket() {
        DownloadPage page = Controllers.getDownloadPage();
        Controllers.navigate(page);
        page.openTab("plugins");
    }

    /// Installs a plugin from a packed file the user chooses.
    ///
    /// The file is copied into the instance before it is installed, because the
    /// profile records the path it installed from and resolves it again on every
    /// later operation: a plugin installed from wherever the file happened to be
    /// stops the whole profile from resolving the day that file moves. That is also
    /// why the copy is the thing worth saying out loud — the plugin is part of the
    /// instance now, and the file the user picked can go.
    private void installFromFile() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("dsh.instance.plugins.add"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                i18n("dsh.instance.plugins.add.filter"), "*.tgz"));
        java.io.File chosen = chooser.showOpenDialog(Controllers.getStage());
        if (chosen == null) {
            return;
        }

        Path file = chosen.toPath();
        PluginInstalls.run(instance, progress ->
                DshLocalPlugins.install(instance, file, progress::accept), this::refresh);
    }

    /// Holds the list the page is drawn in, and gives the page the toolbar that
    /// acts on a selection.
    ///
    /// The skin owns the list view, and the toolbar has to be built after it
    /// exists — the base skin builds its toolbars before it creates the list —
    /// so the skin hands the list over once it has one, rather than the page
    /// reaching for a list that does not exist yet.
    ///
    /// @param listView the page's list
    private void attachList(JFXListView<PluginRow> listView) {
        toolbar.setButtons(
                ToolbarListPageSkin.createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, this::refresh),
                // The original's mod list adds a mod from a file the same way; a
                // plugin is a package, so the file is a packed one.
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.plugins.add"), SVG.ADD,
                        this::installFromFile),
                // The original's mod list also carries 下载, which leads to the list
                // of what can be installed rather than to a file dialog; this is
                // that, pointing at the download page's plugin tab.
                ToolbarListPageSkin.createToolbarButton2(i18n("mods.download"), SVG.DOWNLOAD,
                        this::openMarket),
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.plugins.reveal"), SVG.FOLDER_OPEN,
                        this::revealProfile));

        JFXButton selectAll = ToolbarListPageSkin.createToolbarButton2(
                i18n("button.select_all"), SVG.SELECT_ALL,
                () -> listView.getSelectionModel().selectRange(0, listView.getItems().size()));
        selectingToolbar.getChildren().setAll(
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.plugins.enable"), SVG.CHECK,
                        () -> setEnabled(listView.getSelectionModel().getSelectedItems(), true)),
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.plugins.disable"), SVG.CLOSE,
                        () -> setEnabled(listView.getSelectionModel().getSelectedItems(), false)),
                ToolbarListPageSkin.createToolbarButton2(i18n("button.remove"), SVG.DELETE_FOREVER,
                        () -> removeSelected(listView.getSelectionModel().getSelectedItems())),
                selectAll,
                ToolbarListPageSkin.createToolbarButton2(i18n("button.cancel"), SVG.CANCEL,
                        () -> listView.getSelectionModel().clearSelection()));

        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        FXUtils.onChangeAndOperate(listView.getSelectionModel().selectedItemProperty(),
                selected -> showSelectingToolbar(selected != null));

        // Selecting everything through the button rather than `selectAll()`, which
        // clears first and makes the whole list flicker; and disabling the button
        // once there is nothing left to select, as the original does.
        ListChangeListener<Object> listener = change -> selectAll.setDisable(!listView.getItems().isEmpty()
                && listView.getSelectionModel().getSelectedItems().size() == listView.getItems().size());
        listView.getSelectionModel().getSelectedItems().addListener(listener);
        listView.getItems().addListener(listener);
    }

    /// Shows the toolbar that acts on a selection, or the ordinary one.
    ///
    /// @param selecting whether anything is selected
    private void showSelectingToolbar(boolean selecting) {
        selectingToolbar.setVisible(selecting);
        selectingToolbar.setManaged(selecting);
        toolbar.setVisible(!selecting);
        toolbar.setManaged(!selecting);
    }

    /// Opens the profile directory.
    private void revealProfile() {
        try {
            FXUtils.showFileInExplorer(instance.homeDirectory().resolve("profiles").resolve(instance.profile()));
        } catch (DshException e) {
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
        }
    }

    /// One installed plugin.
    ///
    /// @param name       the package name
    /// @param version    the declared version range
    /// @param active     whether the package is an active bundle
    /// @param toggleable whether its own loader rows can be switched
    /// @param disabled   whether the profile's patch layer switches it off
    public record PluginRow(String name, String version, boolean active, boolean toggleable, boolean disabled) {
        /// Returns whether the plugin is running.
        ///
        /// @return whether it is an active bundle the patch layer does not disable
        public boolean enabled() {
            return active && !disabled;
        }
    }

    /// The loaded dependency map, bundle list, patch state and insert rows.
    ///
    /// @param dependencies the declared dependencies
    /// @param bundles      the profile's active bundles
    /// @param disabled     the row ids the profile's patch layer disables
    /// @param inserted     each package's own loader row ids
    private record Loaded(@Unmodifiable Map<String, String> dependencies,
                          @Unmodifiable List<String> bundles,
                          @Unmodifiable Set<String> disabled,
                          @Unmodifiable Map<String, List<String>> inserted) {
    }

    /// The page's toolbar, which swaps itself for a search field.
    private final ListSearchBar toolbar = new ListSearchBar(this::refresh);

    /// The toolbar shown while plugins are selected.
    private final HBox selectingToolbar = new HBox(8);

    /// The box the two toolbars swap in.
    private final StackPane toolbarPane = new StackPane(toolbar, selectingToolbar);

    /// The page's skin: a toolbar above the plugin list.
    private static final class PluginListPageSkin extends ToolbarListPageSkin<PluginRow, PluginListPage> {
        /// Creates the skin.
        ///
        /// @param control the page
        PluginListPageSkin(PluginListPage control) {
            super(control);
            setPlaceholder(i18n("dsh.plugins.empty"));
            // After the base skin has made the list: the selection toolbar acts on
            // it, and it does not exist while the toolbars are being built.
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

    /// One row of the plugin list.
    ///
    /// The shape is HMCL's mod row: a tick box, the plugin's icon, the name with
    /// its version chip, the file it came from beneath, and the per-row actions
    /// on the trailing edge.
    private static final class PluginItemCell extends MDListCell<PluginRow> {
        /// The page whose actions the row's buttons run.
        private final PluginListPage page;

        /// The tick box reporting whether the plugin is in effect.
        private final JFXCheckBox active = new JFXCheckBox();

        /// The plugin icon.
        private final ImageContainer icon = new ImageContainer(32);

        /// The name, version and source line.
        private final TwoLineListItem content = new TwoLineListItem();

        /// The button that opens the plugin on npm.
        private final JFXButton info = FXUtils.newToggleButton4(SVG.INFO);

        /// The button that removes the plugin.
        private final JFXButton remove = FXUtils.newToggleButton4(SVG.DELETE);

        /// Creates the cell.
        ///
        /// @param listView the owning list
        /// @param page     the page the row's actions belong to
        PluginItemCell(JFXListView<PluginRow> listView, PluginListPage page) {
            super(listView);
            this.page = page;

            HBox container = new HBox(8);
            container.setPickOnBounds(false);
            container.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(content, Priority.ALWAYS);
            content.setMouseTransparent(true);
            setSelectable();

            icon.setImage(org.jackhuang.hmcl.dsh.DshInstanceIcon.COMMAND.load());
            FXUtils.installFastTooltip(info, i18n("dsh.instance.plugins.open"));
            FXUtils.installFastTooltip(remove, i18n("dsh.instance.plugins.remove"));

            active.setOnAction(event -> {
                PluginRow row = getItem();
                if (row != null) {
                    page.toggle(row, active);
                }
            });

            container.getChildren().setAll(active, icon, content, info, remove);
            javafx.scene.layout.StackPane.setMargin(container, new Insets(8));
            getContainer().getChildren().setAll(container);
        }

        @Override
        protected void updateControl(@Nullable PluginRow row, boolean empty) {
            if (empty || row == null) {
                return;
            }

            active.setSelected(row.enabled());
            active.setDisable(!row.toggleable());
            FXUtils.installFastTooltip(active, !row.active()
                    ? i18n("dsh.instance.plugins.inactive.short")
                    : !row.toggleable() ? i18n("dsh.instance.plugins.toggle.fixed")
                    : row.enabled() ? i18n("dsh.instance.plugins.toggle.off")
                    : i18n("dsh.instance.plugins.toggle.on"));

            content.setTitle(row.name());
            content.setSubtitle(i18n("dsh.instance.plugins.source", row.name()));
            content.getTags().clear();
            content.addTag(row.version());

            info.setOnAction(event -> FXUtils.openLink("https://www.npmjs.com/package/" + row.name()));
            remove.setOnAction(event -> page.removeSelected(List.of(row)));
        }
    }
}
