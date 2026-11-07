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

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.AppBootPin;
import com.mcxcc303.hdsl.harness.DshPreset;
import com.mcxcc303.hdsl.harness.DshPresetCatalog;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.plugin.Catalog;
import com.mcxcc303.hdsl.plugin.CatalogClient;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Skin;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.ListPageBase;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.ui.dsh.install.AppBootWizardProvider;
import org.jackhuang.hmcl.ui.dsh.install.InstallerListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Component management page for an instance, listing DSH runtime, AppBoot, and plugins.
 */
@NotNullByDefault
public final class InstanceInstallersPage extends ListPageBase<InstallerListItem> implements Refreshable, PageAware {
	private DshInstance instance;

	/**
	 * Creates the components page for the specified instance.
	 *
	 * @param instance instance whose components are listed
	 */
	public InstanceInstallersPage(DshInstance instance) {
		this.instance = instance;
		refresh();
	}

	private static Image appBootMark() {
		return (Themes.darkModeProperty().get() ? DshInstanceIcon.DSH_WHITE : DshInstanceIcon.DSH_BLACK).load();
	}

	private static @Nullable DshPreset marketPreset() {
		return DshPresetCatalog.builtin()
		                       .stream()
		                       .filter(preset -> preset.id().equals("dshmarket"))
		                       .findFirst()
		                       .orElse(null);
	}

	private static Catalog.Plugin catalogueEntry(DshPreset preset) {
		String spec = preset.spec();
		int at = spec.lastIndexOf('@');
		String name = at > 0 ? spec.substring(0, at) : spec;
		String version = at > 0 ? spec.substring(at + 1) : null;

		Catalog.Plugin known = CatalogClient.find(name).orElse(null);
		if (known != null) {
			return known;
		}
		return new Catalog.Plugin(preset.name(),
		                          "",
		                          "https://www.npmjs.com/package/" + name,
		                          "market",
		                          preset.description(),
		                          preset.description(),
		                          name,
		                          version,
		                          0,
		                          0,
		                          null,
		                          null);
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new InstallersPageSkin(this);
	}

	@Override
	public void refresh() {
		DshInstance current = InstanceStore.find(instance.id());
		if (current != null) {
			instance = current;
		}

		List<InstallerListItem> rows = List.of(versionRow(), appBootRow(), marketRow());
		rows.forEach(row -> row.setWideName(true));
		getItems().setAll(rows);
	}

	@Override
	public void onPageShown() {
		refresh();
	}

	private InstallerListItem versionRow() {
		InstallerListItem row = new InstallerListItem(DshInstanceIcon.DSH_APPLICATION.load(),
		                                              i18n("dsh.install.version.name"));
		row.statusProperty().set(instance.version());
		row.setOnActivate(() -> Controllers.navigate(new VersionPickerPage(instance)));
		row.setOnChange(this::confirmReinstallDsh, i18n("dsh.instance.reinstall.hint"));
		return row;
	}

	private InstallerListItem appBootRow() {
		ImageView mark = new ImageView();
		mark.imageProperty()
		    .bind(Bindings.createObjectBinding(InstanceInstallersPage::appBootMark, Themes.darkModeProperty()));

		InstallerListItem row = new InstallerListItem(mark, i18n("dsh.install.app_boot"));

		String appBoot = AppBootPin.readAppBoot(instance);
		Runnable reinstallRun = () -> confirmReinstallAppBoot(appBoot != null ? appBoot : instance.version());

		row.statusProperty().set(appBoot != null ? appBoot : instance.version());
		row.setOnActivate(this::chooseAppBoot);
		row.setOnChange(reinstallRun, i18n("dsh.install.app_boot.reinstall.hint"));
		return row;
	}

	private InstallerListItem marketRow() {
		DshPreset market = marketPreset();
		InstallerListItem row = new InstallerListItem(DshInstanceIcon.FABRIC.load(),
		                                              market == null ? "dsh-market" : market.name());

		String installed = market == null ? null : installedVersion(market);
		row.statusProperty().set(installed == null ? i18n("install.installer.not_installed") : installed);

		if (market != null) {
			Runnable versions = () -> Controllers.navigate(new PluginDetailPage(catalogueEntry(market), instance));
			row.setOnActivate(versions);
			row.setOnChange(installed == null ? null : () -> confirmReinstallMarket(market),
			                i18n("dsh.install.market.reinstall.hint"));
			row.setOnRemove(installed == null ? null : () -> confirmRemoveMarket(market),
			                i18n("dsh.instance.plugins.remove"));
		}
		return row;
	}

	private @Nullable String installedVersion(DshPreset preset) {
		try {
			Map<String, String> dependencies = DshPluginInstaller.readDependencies(instance.homeDirectory(),
			                                                                       instance.profile());
			return dependencies.get(preset.spec());
		} catch (DshException e) {
			LOG.warning("Failed to read the profile of " + instance.id(), e);
			return null;
		}
	}

	private void chooseAppBoot() {
		Controllers.getDecorator().startWizard(new AppBootWizardProvider(instance.version(), chosen -> {
			String appBoot = chosen == null ? instance.version() : chosen;
			String current = AppBootPin.readAppBoot(instance);
			String from = current == null || current.isBlank() ? instance.version() : current;
			if (from.equals(appBoot)) {
				confirmReinstallAppBoot(appBoot);
				return;
			}
			confirmChangeAppBoot(from, appBoot);
		}), i18n("dsh.install.app_boot"));
	}

	private void confirmReinstallDsh() {
		Controllers.confirm(
				i18n("dsh.instance.reinstall.confirm", instance.id()),
				i18n("dsh.install.reinstall"),
				() -> DshInstanceUpgradeService.reinstall(instance, this::refresh),
				null);
	}

	private void confirmReinstallAppBoot(String currentAppBoot) {
		Controllers.confirm(
				i18n("dsh.install.app_boot.reinstall.confirm", currentAppBoot),
				i18n("dsh.install.reinstall"),
				() -> ProgressDialog.run(i18n("dsh.install.reinstall"),
				                         progress -> AppBootPin.overrideAppBoot(instance, currentAppBoot, progress),
				                         this::refresh),
				null);
	}

	private void confirmReinstallMarket(DshPreset market) {
		Controllers.confirm(
				i18n("dsh.install.market.reinstall.confirm", market.name(), installedVersion(market)),
				i18n("dsh.install.reinstall"),
				() -> PluginInstalls.run(instance,
				                         progress -> DshPluginInstaller.installSpecs(instance,
				                                                                     List.of(market.spec()),
				                                                                     progress),
				                         this::refresh),
				null);
	}

	private void confirmChangeAppBoot(String from, String to) {
		Controllers.confirm(
				i18n("dsh.install.app_boot.change.confirm", from, to),
				i18n("dsh.install.app_boot"),
				() -> ProgressDialog.run(i18n("dsh.install.app_boot"),
				                         progress -> AppBootPin.overrideAppBoot(instance, to, progress),
				                         this::refresh),
				null);
	}

	private void confirmRemoveMarket(DshPreset market) {
		Controllers.confirm(
				i18n("dsh.instance.plugins.remove.confirm", market.spec()),
				i18n("dsh.instance.plugins.remove"),
				() -> ProgressDialog.run(i18n("dsh.instance.plugins.remove"),
				                         progress -> DshPluginInstaller.remove(instance, market.spec(), progress),
				                         this::refresh),
				null);
	}

	private static final class InstallersPageSkin extends ToolbarListPageSkin<InstallerListItem, InstanceInstallersPage> {
		InstallersPageSkin(InstanceInstallersPage page) {
			super(page);
		}

		@Override
		protected List<Node> initializeToolbar(InstanceInstallersPage page) {
			return List.of(ToolbarListPageSkin.createToolbarButton2(i18n("button.refresh"),
			                                                        SVG.REFRESH,
			                                                        page::refresh));
		}
	}
}
