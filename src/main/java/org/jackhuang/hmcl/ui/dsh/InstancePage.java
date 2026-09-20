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
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.ui.construct.TabHeader;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Shows one instance and manages what is installed into its profile.
///
/// The plugins tab is the counterpart of the install wizard: the wizard adds
/// packages, this removes them. It shows both the declared dependencies and
/// whether each is an active bundle, because a package can be installed yet sit
/// outside `dsh.profile.bundles` — which is exactly what happens when a
/// reconciling `dsh plugin` run fails part way.
@NotNullByDefault
public final class InstancePage extends DecoratorAnimatedPage implements DecoratorPage, PageAware, Refreshable {
    /// The instance being shown.
    private final DshInstance instance;

    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state;

    /// The tab strip driving the content pane.
    private final TabHeader tab;

    /// The plugins tab.
    private final TabHeader.Tab<ScrollPane> pluginsTab = new TabHeader.Tab<>("dshInstancePlugins");

    /// The details tab.
    private final TabHeader.Tab<ScrollPane> detailsTab = new TabHeader.Tab<>("dshInstanceDetails");

    /// The animated content pane shared by the tabs.
    private final TransitionPane transitionPane = new TransitionPane();

    /// The plugin list, rebuilt on every refresh.
    private final ComponentList pluginList = new ComponentList();

    /// The status line above the plugin list.
    private final Label pluginStatus = new Label();

    /// Creates the page for an instance.
    ///
    /// @param instance the instance to show
    public InstancePage(DshInstance instance) {
        this.instance = instance;
        this.state = new ReadOnlyObjectWrapper<>(State.fromTitle(instance.id()));


        pluginsTab.setNodeSupplier(this::buildPluginsTab);
        detailsTab.setNodeSupplier(this::buildDetailsTab);
        tab = new TabHeader(transitionPane, pluginsTab, detailsTab);
        tab.select(pluginsTab, false);

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(instance.id().toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, pluginsTab, i18n("dsh.instance.plugins"), SVG.EXTENSION)
                .addNavigationDrawerTab(tab, detailsTab, i18n("dsh.instance.details"), SVG.INFO);
        FXUtils.setLimitWidth(sideBar, 200);
        setLeft(sideBar);
        setCenter(transitionPane);
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    @Override
    public void onPageShown() {
        tab.onPageShown();
        refresh();
    }

    @Override
    public void onPageHidden() {
        tab.onPageHidden();
    }

    @Override
    public void refresh() {
        refreshPlugins();
    }

    /// Builds the plugins tab.
    ///
    /// @return the tab content
    private ScrollPane buildPluginsTab() {
        VBox root = new VBox(10);
        root.setPadding(new javafx.geometry.Insets(10));
        root.getChildren().addAll(pluginStatus, pluginList);

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        FXUtils.smoothScrolling(scroll);
        return scroll;
    }

    /// Builds the details tab.
    ///
    /// @return the tab content
    private ScrollPane buildDetailsTab() {
        ComponentList list = new ComponentList();
        list.getContent().add(buildDetail(i18n("dsh.install.step.version"), instance.version()));
        list.getContent().add(buildDetail(i18n("dsh.instance.profile"), instance.profile()));
        list.getContent().add(buildDetail(i18n("dsh.install.home"),
                i18n("dsh.instance.home." + instance.homeMode().name().toLowerCase(Locale.ROOT))));
        list.getContent().add(buildDetail(i18n("dsh.node.title"), instance.nodeRuntimeOrDefault()));
        list.getContent().add(buildDetail(i18n("dsh.install.workspace"), instance.workspace()));

        LineButton open = new LineButton();
        open.setTitle(i18n("dsh.instance.open_home"));
        open.setOnAction(event -> {
            try {
                FXUtils.showFileInExplorer(instance.instanceDirectory());
            } catch (DshException e) {
                Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
            }
        });
        list.getContent().add(open);

        VBox root = new VBox(list);
        root.setPadding(new javafx.geometry.Insets(10));

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        FXUtils.smoothScrolling(scroll);
        return scroll;
    }

    /// Builds one read-only detail row.
    ///
    /// @param title the label
    /// @param value the value
    /// @return the row
    private Node buildDetail(String title, String value) {
        LineTextPane row = new LineTextPane();
        row.setTitle(title);
        row.setText(value == null ? "" : value);
        return row;
    }

    /// Rebuilds the plugin list from the profile manifest on disk.
    private void refreshPlugins() {
        pluginList.getContent().clear();

        Map<String, String> dependencies;
        List<String> bundles;
        try {
            java.nio.file.Path home = instance.homeDirectory();
            dependencies = DshPluginInstaller.readDependencies(home, instance.profile());
            bundles = DshPluginInstaller.readBundles(home, instance.profile());
        } catch (DshException e) {
            pluginStatus.setText(e.getMessage());
            return;
        }

        LineTextPane header = new LineTextPane();
        header.setTitle(i18n("dsh.instance.plugins.installed"));
        header.getStyleClass().add("section-header");
        pluginList.getContent().add(header);

        // Only the packages the user added; the in-box bundles every profile
        // starts with are not the user's to manage.
        int shown = 0;
        for (Map.Entry<String, String> entry : dependencies.entrySet()) {
            pluginList.getContent().add(buildPluginRow(entry.getKey(), entry.getValue(), bundles));
            shown++;
        }
        if (shown == 0) {
            LineTextPane note = new LineTextPane();
            note.setText(i18n("dsh.instance.plugins.empty"));
            pluginList.getContent().add(note);
        }

        pluginStatus.setText(i18n("dsh.instance.plugins.count", shown));
    }

    /// Builds one installed-plugin row.
    ///
    /// @param name    the package name
    /// @param version the declared version range
    /// @param bundles the profile's active bundle list
    /// @return the row
    private LineButton buildPluginRow(String name, String version, List<String> bundles) {
        boolean active = bundles.contains(name);

        JFXButton remove = FXUtils.newToggleButton4(SVG.DELETE, 18);
        remove.setOnAction(event -> removePlugin(name));

        LineButton row = new LineButton();
        row.setTitle(name);
        row.setSubtitle(active
                ? i18n("dsh.instance.plugins.active", version)
                : i18n("dsh.instance.plugins.inactive", version));
        row.setRowTrailing(remove);
        return row;
    }

    /// Removes an installed plugin after confirmation.
    ///
    /// @param name the package name
    private void removePlugin(String name) {
        Controllers.confirm(i18n("dsh.instance.plugins.remove.confirm", name),
                i18n("dsh.instance.plugins.remove"),
                () -> CompletableFuture.runAsync(() -> {
                    try {
                        DshPluginInstaller.remove(instance, name, null);
                    } catch (DshException e) {
                        throw new CompletionException(e);
                    }
                }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
                    if (throwable != null) {
                        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                                ? throwable.getCause()
                                : throwable;
                        LOG.warning("Failed to remove " + name, cause);
                        Controllers.dialog(cause.getMessage(), i18n("dsh.instance.plugins.remove_failed"),
                                MessageType.ERROR);
                    } else {
                        Controllers.showToast(i18n("dsh.instance.plugins.removed", name));
                    }
                    refreshPlugins();
                })),
                null);
    }
}
