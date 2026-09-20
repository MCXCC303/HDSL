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
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.ui.dsh.DshLaunchService;
import org.jackhuang.hmcl.dsh.DshProcessManager;
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
import org.jackhuang.hmcl.ui.dsh.settings.InstanceSettingsPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /// The settings tab.
    private final TabHeader.Tab<InstanceSettingsPage> settingsTab = new TabHeader.Tab<>("dshInstanceSettings");

    /// The sessions tab.
    private final TabHeader.Tab<SessionListPage> sessionsTab = new TabHeader.Tab<>("dshInstanceSessions");

    /// The plugins tab.
    private final TabHeader.Tab<PluginListPage> pluginsTab = new TabHeader.Tab<>("dshInstancePlugins");

    /// The details tab.
    private final TabHeader.Tab<ScrollPane> detailsTab = new TabHeader.Tab<>("dshInstanceDetails");

    /// The animated content pane shared by the tabs.
    private final TransitionPane transitionPane = new TransitionPane();

    /// The plugin list, rebuilt on every refresh.
    private final ComponentList pluginList = new ComponentList();

    /// The status line above the plugin list.
    private final Label pluginStatus = new Label();

    /// Creates the page for an instance, opening its first tab.
    ///
    /// @param instance the instance to show
    public InstancePage(DshInstance instance) {
        this(instance, null);
    }

    /// Creates the page for an instance.
    ///
    /// @param instance   the instance to show
    /// @param initialTab the tab to open: `settings`, `plugins`, `sessions`, `browse` or
    ///                   `details`, or `null` for the first
    public InstancePage(DshInstance instance, @Nullable String initialTab) {
        this.instance = instance;
        this.state = new ReadOnlyObjectWrapper<>(State.fromTitle(instance.id()));


        settingsTab.setNodeSupplier(() -> new InstanceSettingsPage(instance, this::refresh));
        sessionsTab.setNodeSupplier(() -> new SessionListPage(instance));
        pluginsTab.setNodeSupplier(() -> new PluginListPage(instance));
        detailsTab.setNodeSupplier(this::buildDetailsTab);
        tab = new TabHeader(transitionPane, settingsTab, pluginsTab, sessionsTab, detailsTab);
        TabHeader.Tab<?> initial = switch (initialTab == null ? "" : initialTab.trim().toLowerCase(Locale.ROOT)) {
            case "plugins" -> pluginsTab;
            case "sessions" -> sessionsTab;
            case "details" -> detailsTab;
            default -> settingsTab;
        };
        tab.select(initial, false);

        AdvancedListBox sideBar = new AdvancedListBox()
                .addNavigationDrawerTab(tab, settingsTab, i18n("instance.manage.manage"), SVG.SETTINGS_FILL)
                .addNavigationDrawerTab(tab, pluginsTab, i18n("dsh.instance.plugins"), SVG.EXTENSION)
                .addNavigationDrawerTab(tab, sessionsTab, i18n("dsh.instance.sessions"), SVG.FOLDER_COPY)
                .addNavigationDrawerTab(tab, detailsTab, i18n("dsh.instance.details"), SVG.INFO);

        // The actions are a second box rather than a category inside the first.
        // HMCL splits them the same way, and the split is why there is no
        // divider: a category draws one, a separate box sits on its own.
        AdvancedListBox actions = new AdvancedListBox()
                .addNavigationDrawerItem(i18n("dsh.instance.test_launch"), SVG.ROCKET_LAUNCH,
                        this::testLaunch)
                .addNavigationDrawerItem(i18n("settings.game.exploration"), SVG.FOLDER_OPEN, null,
                        item -> item.setOnAction(event -> showBrowsePopup(item)))
                .addNavigationDrawerItem(i18n("settings.game.management"), SVG.MENU, null,
                        item -> item.setOnAction(event -> showManagePopup(item)));
        actions.getStyleClass().add("advanced-list-box-clear-padding");

        FXUtils.setLimitWidth(sideBar, 200);
        FXUtils.setLimitHeight(actions, 40 * 3 + 12 * 2);
        // The navigation box takes the room so the actions settle at the bottom.
        // Its maximum height has to be lifted first: a control sized to its
        // content will not grow just because the box asks it to.
        sideBar.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(sideBar, Priority.ALWAYS);

        getLeft().getStyleClass().add("gray-background");
        setLeft(sideBar, actions);
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

    /// Shows the folders this instance owns.
    ///
    /// The original puts these in a popup on the sidebar rather than on a tab:
    /// they are places to go, not a view to read, and a popup says so.
    ///
    /// @param anchor the sidebar item the popup is anchored to
    private void showBrowsePopup(org.jackhuang.hmcl.ui.construct.AdvancedListItem anchor) {
        org.jackhuang.hmcl.ui.construct.PopupMenu menu = new org.jackhuang.hmcl.ui.construct.PopupMenu();
        com.jfoenix.controls.JFXPopup popup = new com.jfoenix.controls.JFXPopup(menu);

        List<javafx.scene.Node> entries = new ArrayList<>();
        entries.add(new org.jackhuang.hmcl.ui.construct.IconedMenuItem(
                SVG.STADIA_CONTROLLER, i18n("dsh.instance.open_home"),
                () -> revealQuietly(instanceDirectoryOrNull()), popup));
        entries.add(new org.jackhuang.hmcl.ui.construct.IconedMenuItem(
                SVG.FOLDER, i18n("dsh.instance.open_home.dsh"),
                () -> revealQuietly(homeChild("")), popup));
        entries.add(new org.jackhuang.hmcl.ui.construct.IconedMenuItem(
                SVG.FOLDER_OPEN, i18n("dsh.instance.open_workspace"),
                () -> revealQuietly(instance.workspacePath()), popup));
        entries.add(new org.jackhuang.hmcl.ui.construct.IconedMenuItem(
                SVG.FOLDER_COPY, i18n("dsh.instance.open.sessions"),
                () -> revealQuietly(homeChild("sessions")), popup));
        entries.add(new org.jackhuang.hmcl.ui.construct.IconedMenuItem(
                SVG.EXTENSION, i18n("dsh.instance.open.profiles"),
                () -> revealQuietly(homeChild("profiles")), popup));
        entries.add(new org.jackhuang.hmcl.ui.construct.IconedMenuItem(
                SVG.ARCHIVE, i18n("dsh.instance.open.storages"),
                () -> revealQuietly(homeChild("storages")), popup));
        menu.getContent().setAll(entries);

        popup.show(anchor, com.jfoenix.controls.JFXPopup.PopupVPosition.BOTTOM,
                com.jfoenix.controls.JFXPopup.PopupHPosition.LEFT, anchor.getWidth(), 0);
    }

    /// Shows the operations available on this instance.
    ///
    /// @param anchor the sidebar item the popup is anchored to
    private void showManagePopup(org.jackhuang.hmcl.ui.construct.AdvancedListItem anchor) {
        org.jackhuang.hmcl.ui.construct.PopupMenu menu = new org.jackhuang.hmcl.ui.construct.PopupMenu();
        com.jfoenix.controls.JFXPopup popup = new com.jfoenix.controls.JFXPopup(menu);

        List<javafx.scene.Node> entries = new ArrayList<>();
        entries.add(new org.jackhuang.hmcl.ui.construct.IconedMenuItem(
                SVG.ROCKET_LAUNCH, i18n("dsh.instance.test_launch"), this::testLaunch, popup));
        entries.add(new org.jackhuang.hmcl.ui.construct.IconedMenuItem(
                SVG.SCRIPT, i18n("dsh.instance.open_logs"), this::openLogs, popup));
        entries.add(new org.jackhuang.hmcl.ui.construct.MenuSeparator());
        entries.add(new org.jackhuang.hmcl.ui.construct.IconedMenuItem(
                SVG.EDIT, i18n("instance.manage.rename"), this::renameInstance, popup));
        entries.add(new org.jackhuang.hmcl.ui.construct.IconedMenuItem(
                SVG.FOLDER_COPY, i18n("instance.manage.duplicate"), this::duplicateInstance, popup));
        entries.add(new org.jackhuang.hmcl.ui.construct.IconedMenuItem(
                SVG.DELETE, i18n("instance.manage.remove"), this::removeInstance, popup));
        menu.getContent().setAll(entries);

        popup.show(anchor, com.jfoenix.controls.JFXPopup.PopupVPosition.BOTTOM,
                com.jfoenix.controls.JFXPopup.PopupHPosition.LEFT, anchor.getWidth(), 0);
    }

    /// Returns the instance directory, or `null` when it cannot be resolved.
    ///
    /// @return the directory
    private Path instanceDirectoryOrNull() {
        try {
            return instance.instanceDirectory();
        } catch (DshException e) {
            return null;
        }
    }

    /// Returns a directory inside the instance's home, or the home itself.
    ///
    /// @param name the directory name, or an empty string for the home
    /// @return the path, or `null` when the home cannot be resolved
    private Path homeChild(String name) {
        try {
            Path home = instance.homeDirectory();
            return name.isEmpty() ? home : home.resolve(name);
        } catch (DshException e) {
            return null;
        }
    }

    /// Reveals a directory, reporting rather than throwing when it is missing.
    ///
    /// @param directory the directory to reveal, or `null`
    private void revealQuietly(@Nullable Path directory) {
        if (directory == null || !java.nio.file.Files.isDirectory(directory)) {
            Controllers.dialog(i18n("dsh.instance.open.unavailable"), i18n("message.error"), MessageType.ERROR);
            return;
        }
        FXUtils.showFileInExplorer(directory);
    }

    /// Opens the launcher log directory.
    private void openLogs() {
        revealQuietly(org.jackhuang.hmcl.Metadata.HMCL_USER_HOME.resolve("logs"));
    }

    /// Renames this instance.
    private void renameInstance() {
        Controllers.dialog(new org.jackhuang.hmcl.ui.construct.InputDialogPane(
                i18n("instance.manage.rename"), instance.id(),
                (newId, handler) -> {
                    try {
                        DshInstanceManager.rename(instance.id(), newId);
                        handler.resolve();
                        Controllers.navigate(new InstancesPage());
                    } catch (DshException e) {
                        handler.reject(e.getMessage());
                    }
                }));
    }

    /// Copies this instance's configuration into a new one.
    private void duplicateInstance() {
        try {
            DshInstanceManager.duplicate(instance.id(), DshInstanceManager.nextId(instance.id()));
            Controllers.showToast(i18n("dsh.instance.duplicated"));
        } catch (DshException e) {
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
        }
    }

    /// Starts or stops this instance from its own page.
    private void testLaunch() {
        if (DshProcessManager.find(instance.id()).isPresent()) {
            DshLaunchService.stop(instance.id(), this::refresh);
        } else {
            // Test launch shows the output: the point of launching from here is
            // to see what the program does, which is what the original's test
            // game does too.
            DshLaunchService.launch(instance, ignored -> refresh(), true);
        }
    }

    /// Deletes this instance after confirmation, leaving the page afterwards.
    private void removeInstance() {
        Controllers.confirm(i18n("dsh.instance.remove.confirm", instance.id()),
                i18n("dsh.instance.remove"),
                () -> {
                    try {
                        DshInstanceManager.delete(instance.id());
                        Controllers.navigate(new InstancesPage());
                    } catch (DshException e) {
                        Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
                    }
                },
                null);
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
