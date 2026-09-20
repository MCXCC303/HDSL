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
import javafx.scene.control.Skin;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
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
import java.util.List;
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
                rows.add(new PluginRow(entry.getKey(), entry.getValue(),
                        loaded.bundles().contains(entry.getKey())));
            }
            getItems().setAll(rows);
        }));
    }

    /// Removes a plugin after confirmation.
    ///
    /// @param name the package name
    private void remove(String name) {
        Controllers.confirm(i18n("dsh.instance.plugins.remove.confirm", name),
                i18n("dsh.instance.plugins.remove"),
                () -> {
                    setLoading(true);
                    CompletableFuture.runAsync(() -> {
                        try {
                            DshPluginInstaller.remove(instance, name, null);
                        } catch (DshException e) {
                            throw new CompletionException(e);
                        }
                    }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
                        setLoading(false);
                        if (throwable != null) {
                            Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                                    ? throwable.getCause() : throwable;
                            LOG.warning("Failed to remove " + name, cause);
                            Controllers.dialog(cause.getMessage(),
                                    i18n("dsh.instance.plugins.remove_failed"), MessageType.ERROR);
                        } else {
                            Controllers.showToast(i18n("dsh.instance.plugins.removed", name));
                        }
                        refresh();
                    }));
                },
                null);
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

    /// The page's skin: a toolbar above the plugin list.
    private static final class PluginListPageSkin extends ToolbarListPageSkin<PluginRow, PluginListPage> {
        /// Creates the skin.
        ///
        /// @param control the page
        PluginListPageSkin(PluginListPage control) {
            super(control);
        }

        @Override
        protected List<Node> initializeToolbar(PluginListPage page) {
            List<Node> toolbar = new ArrayList<>();
            toolbar.add(createToolbarButton2(i18n("button.refresh"), SVG.REFRESH, page::refresh));
            toolbar.add(createToolbarButton2(i18n("dsh.instance.plugins.reveal"), SVG.FOLDER_OPEN,
                    page::revealProfile));
            return toolbar;
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
            remove.setOnAction(event -> page.remove(row.name()));
        }
    }
}
