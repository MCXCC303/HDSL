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
import org.jackhuang.hmcl.ui.dsh.settings.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

@NotNullByDefault
public final class SettingsPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {
	private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("settings")));

	private final TabHeader tab;
	private final TabHeader.Tab<InstanceDefaultsPage> defaultsTab = new TabHeader.Tab<>("dshInstanceDefaults");
	private final TabHeader.Tab<GeneralSettingsPage> generalTab = new TabHeader.Tab<>("dshGeneralSettings");
	private final TabHeader.Tab<NodeRuntimesPage> nodeTab = new TabHeader.Tab<>("dshNodeRuntimes");
	private final TabHeader.Tab<PnpmRuntimesPage> pnpmTab = new TabHeader.Tab<>("dshPnpmRuntimes");
	private final TabHeader.Tab<AppearanceSettingsPage> appearanceTab = new TabHeader.Tab<>("dshAppearanceSettings");
	private final TabHeader.Tab<DownloadSettingsPage> downloadTab = new TabHeader.Tab<>("dshDownloadSettings");
	private final TabHeader.Tab<AboutPage> aboutTab = new TabHeader.Tab<>("dshAbout");

	private final TransitionPane transitionPane = new TransitionPane();

	public SettingsPage() {

		defaultsTab.setNodeSupplier(InstanceDefaultsPage::new);
		generalTab.setNodeSupplier(GeneralSettingsPage::new);
		nodeTab.setNodeSupplier(NodeRuntimesPage::new);
		pnpmTab.setNodeSupplier(PnpmRuntimesPage::new);
		appearanceTab.setNodeSupplier(AppearanceSettingsPage::new);
		downloadTab.setNodeSupplier(DownloadSettingsPage::new);
		aboutTab.setNodeSupplier(AboutPage::new);
		tab = new TabHeader(transitionPane,
		                    defaultsTab,
		                    generalTab,
		                    nodeTab,
		                    pnpmTab,
		                    appearanceTab,
		                    downloadTab,
		                    aboutTab);
		tab.select(defaultsTab, false);

		AdvancedListBox sideBar = new AdvancedListBox()
				.addNavigationDrawerTab(tab,
				                        defaultsTab,
				                        i18n("dsh.settings.instance_defaults"),
				                        SVG.STADIA_CONTROLLER,
				                        SVG.STADIA_CONTROLLER_FILL)
				.addNavigationDrawerTab(tab, nodeTab, i18n("dsh.node.title"), SVG.LOCAL_CAFE, SVG.LOCAL_CAFE_FILL)
				.addNavigationDrawerTab(tab, pnpmTab, i18n("dsh.pnpm.title"), SVG.LOCAL_CAFE, SVG.LOCAL_CAFE_FILL)
				.startCategory(i18n("launcher").toUpperCase(Locale.ROOT))
				.addNavigationDrawerTab(tab, generalTab, i18n("settings.launcher.general"), SVG.TUNE)
				.addNavigationDrawerTab(tab,
				                        appearanceTab,
				                        i18n("settings.launcher.appearance"),
				                        SVG.STYLE,
				                        SVG.STYLE_FILL)
				.addNavigationDrawerTab(tab, downloadTab, i18n("settings.launcher.download_source"), SVG.DOWNLOAD)
				.startCategory(i18n("help").toUpperCase(Locale.ROOT))
				.addNavigationDrawerTab(tab, aboutTab, i18n("about"), SVG.INFO, SVG.INFO_FILL);

		FXUtils.setLimitWidth(sideBar, 200);
		setLeft(sideBar);
		setCenter(transitionPane);
	}

	public boolean openTab(@Nullable String name) {
		switch (name == null ? "" : name.trim().toLowerCase(Locale.ROOT)) {
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
