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
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javafx.collections.ListChangeListener;
import java.util.Map;
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
/// The box reports rather than toggles: DeepSeek Harness activates a package by
/// rewriting the profile's bundle list during an install, and offers no command
/// that switches one off in place.
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
                return new Loaded(DshPluginInstaller.readDependencies(instance.homeDirectory(), instance.profile()),
                        DshPluginInstaller.readBundles(instance.homeDirectory(), instance.profile()));
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
                rows.add(new PluginRow(entry.getKey(), entry.getValue(),
                        loaded.bundles().contains(entry.getKey())));
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
                InstallProgressDialog.run(i18n("dsh.instance.plugins.remove"),
                        progress -> DshPluginInstaller.removeSpecs(instance, names, progress::accept),
                        this::refresh), null);
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
                ToolbarListPageSkin.createToolbarButton2(i18n("dsh.instance.plugins.reveal"), SVG.FOLDER_OPEN,
                        this::revealProfile));

        // The original's selection toolbar, minus the two entries it has and this
        // launcher cannot offer: enabling and disabling a plugin means rewriting
        // the profile's bundle list by hand, which the harness has no command for.
        JFXButton selectAll = ToolbarListPageSkin.createToolbarButton2(
                i18n("button.select_all"), SVG.SELECT_ALL,
                () -> listView.getSelectionModel().selectRange(0, listView.getItems().size()));
        selectingToolbar.getChildren().setAll(
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
    /// @param name    the package name
    /// @param version the declared version range
    /// @param active  whether the package is an active bundle
    public record PluginRow(String name, String version, boolean active) {
    }

    /// The loaded dependency map and bundle list.
    ///
    /// @param dependencies the declared dependencies
    /// @param bundles      the profile's active bundles
    private record Loaded(@Unmodifiable Map<String, String> dependencies,
                          @Unmodifiable List<String> bundles) {
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

            container.getChildren().setAll(active, icon, content, info, remove);
            javafx.scene.layout.StackPane.setMargin(container, new Insets(8));
            getContainer().getChildren().setAll(container);
        }

        @Override
        protected void updateControl(@Nullable PluginRow row, boolean empty) {
            if (empty || row == null) {
                return;
            }

            active.setSelected(row.active());
            // A report, not a control: see the page's documentation.
            active.setDisable(true);
            FXUtils.installFastTooltip(active, row.active()
                    ? i18n("dsh.instance.plugins.active.short")
                    : i18n("dsh.instance.plugins.inactive.short"));

            content.setTitle(row.name());
            content.setSubtitle(i18n("dsh.instance.plugins.source", row.name()));
            content.getTags().clear();
            content.addTag(row.version());

            info.setOnAction(event -> FXUtils.openLink("https://www.npmjs.com/package/" + row.name()));
            remove.setOnAction(event -> page.removeSelected(List.of(row)));
        }
    }
}
