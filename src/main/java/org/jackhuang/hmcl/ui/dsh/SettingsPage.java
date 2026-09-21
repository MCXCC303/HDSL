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

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.ui.construct.TabHeader;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.dsh.settings.AboutPage;
import org.jackhuang.hmcl.ui.dsh.settings.AppearanceSettingsPage;
import org.jackhuang.hmcl.ui.dsh.settings.DownloadSettingsPage;
import org.jackhuang.hmcl.ui.dsh.settings.GeneralSettingsPage;
import org.jackhuang.hmcl.ui.dsh.settings.InstanceDefaultsPage;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The launcher settings page.
///
/// Mirrors HMCL's launcher settings shell: a categorised sidebar on the left
/// and a tab-owned content pane on the right. The Minecraft-specific tabs
/// (game settings, Java runtimes, download sources, multiplayer) are gone;
/// what remains configures the launcher itself.
@NotNullByDefault
public final class SettingsPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {
    /// The page state published to the window decorator.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("settings")));

    /// The tab strip driving the content pane.
    private final TabHeader tab;

    /// The tab holding what a new instance is given.
    private final TabHeader.Tab<InstanceDefaultsPage> defaultsTab =
            new TabHeader.Tab<>("dshInstanceDefaults");

    /// The general settings tab.
    private final TabHeader.Tab<GeneralSettingsPage> generalTab = new TabHeader.Tab<>("dshGeneralSettings");

    /// The Node runtime tab, mirroring where HMCL puts Java management.
    private final TabHeader.Tab<NodeRuntimesPage> nodeTab = new TabHeader.Tab<>("dshNodeRuntimes");

    /// The appearance settings tab.
    private final TabHeader.Tab<AppearanceSettingsPage> appearanceTab = new TabHeader.Tab<>("dshAppearanceSettings");

    /// The download settings tab, mirroring where HMCL keeps its download sources.
    private final TabHeader.Tab<DownloadSettingsPage> downloadTab = new TabHeader.Tab<>("dshDownloadSettings");

    /// The about tab.
    private final TabHeader.Tab<AboutPage> aboutTab = new TabHeader.Tab<>("dshAbout");

    /// The animated content pane shared by all tabs.
    private final TransitionPane transitionPane = new TransitionPane();

    /// Creates the settings page and installs its sidebar.
    public SettingsPage() {

        defaultsTab.setNodeSupplier(InstanceDefaultsPage::new);
        generalTab.setNodeSupplier(GeneralSettingsPage::new);
        nodeTab.setNodeSupplier(NodeRuntimesPage::new);
        appearanceTab.setNodeSupplier(AppearanceSettingsPage::new);
        downloadTab.setNodeSupplier(DownloadSettingsPage::new);
        aboutTab.setNodeSupplier(AboutPage::new);
        tab = new TabHeader(transitionPane, defaultsTab, generalTab, nodeTab, appearanceTab, downloadTab, aboutTab);
        tab.select(defaultsTab, false);

        AdvancedListBox sideBar = new AdvancedListBox()
                // The original leads with the game's own defaults and the
                // runtime they use, before any category, and puts the launcher's
                // settings under one of their own. A tab that configures
                // instances is not a launcher preference.
                .addNavigationDrawerTab(tab, defaultsTab, i18n("dsh.settings.instance_defaults"),
                        SVG.STADIA_CONTROLLER, SVG.STADIA_CONTROLLER_FILL)
                .addNavigationDrawerTab(tab, nodeTab, i18n("dsh.node.title"),
                        SVG.LOCAL_CAFE, SVG.LOCAL_CAFE_FILL)
                .startCategory(i18n("launcher").toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, generalTab, i18n("settings.launcher.general"), SVG.TUNE)
                .addNavigationDrawerTab(tab, appearanceTab, i18n("settings.launcher.appearance"),
                        SVG.STYLE, SVG.STYLE_FILL)
                // Same mark as the original's download entry, and the same reason
                // it has no solid version: the icon set has none for a download.
                .addNavigationDrawerTab(tab, downloadTab, i18n("settings.launcher.download_source"), SVG.DOWNLOAD)
                .startCategory(i18n("help").toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, aboutTab, i18n("about"), SVG.INFO, SVG.INFO_FILL);

        FXUtils.setLimitWidth(sideBar, 200);
        setLeft(sideBar);
        setCenter(transitionPane);
    }

    /// Selects a tab by name.
    ///
    /// @param name the tab name: `defaults`, `node`, `general`, `appearance`,
    ///             `download` or `about`
    /// @return whether a tab was selected
    public boolean openTab(String name) {
        switch (name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "defaults" -> tab.select(defaultsTab, false);
            case "general" -> tab.select(generalTab, false);
            case "node" -> tab.select(nodeTab, false);
            case "appearance" -> tab.select(appearanceTab, false);
            case "download" -> tab.select(downloadTab, false);
            case "about" -> tab.select(aboutTab, false);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onPageShown() {
        tab.onPageShown();
    }

    @Override
    public void onPageHidden() {
        tab.onPageHidden();
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    @Override
    public void refresh() {
        // Each tab reloads its own state when shown.
    }
}
