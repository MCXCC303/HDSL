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

import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Skin;
import javafx.scene.image.ImageView;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshInstanceIcon;
import org.jackhuang.hmcl.dsh.DshPluginCatalog;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.dsh.DshPreset;
import org.jackhuang.hmcl.dsh.DshPresetCatalog;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
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

/// The instance's components, and what can be done to each of them.
///
/// The original's installer list, kept to what this launcher installs into an
/// instance: the DeepSeek Harness it runs, the boot library that runtime is
/// paired with, and the marketplace plugin. The original's page also offers a
/// local-file install and a row per loader; neither has a counterpart here — a
/// runtime is not loaded from a file, and the plugins beyond the marketplace are
/// the marketplace's own business.
///
/// The version of the runtime is changed from here rather than from the instance's
/// settings, which is where the original keeps it too: choosing a version is
/// installing something, not adjusting a preference, and it is the one change on
/// this page that reinstalls the instance's plugins afterwards.
@NotNullByDefault
public final class InstanceInstallersPage extends ListPageBase<InstallerListItem>
        implements Refreshable, PageAware {
    /// The instance whose components are listed.
    private DshInstance instance;

    /// Creates the page.
    ///
    /// @param instance the instance whose components are listed
    public InstanceInstallersPage(DshInstance instance) {
        this.instance = instance;
        refresh();
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new InstallersPageSkin(this);
    }

    /// Redraws the rows from what is installed now.
    ///
    /// The rows are read from the instance's own files, so this is what makes the
    /// page show the result of a change made elsewhere.
    @Override
    public void refresh() {
        // The version row is read from the instance record rather than from its
        // files, so a version changed on another page is only visible once the
        // record is read again — which is what coming back to this page means.
        DshInstance current = DshInstanceManager.find(instance.id());
        if (current != null) {
            instance = current;
        }

        List<InstallerListItem> rows = List.of(versionRow(), appBootRow(), marketRow());
        // One column width for all three, so what each row reports starts in the
        // same place: the runtime's name is longer than the original's column.
        rows.forEach(row -> row.setWideName(true));
        getItems().setAll(rows);
    }

    /// Redraws when the page comes back into view.
    ///
    /// The version chooser and the boot library chooser are pages of their own,
    /// and what they change is what these rows report.
    @Override
    public void onPageShown() {
        refresh();
    }

    /// Builds the row for the DeepSeek Harness the instance runs.
    ///
    /// @return the row
    private InstallerListItem versionRow() {
        InstallerListItem row = new InstallerListItem(
                DshInstanceIcon.DSH_APPLICATION.load(), i18n("dsh.install.version.name"));
        row.statusProperty().set(instance.version());
        row.setOnActivate(() -> Controllers.navigate(new VersionPickerPage(instance)));
        row.setOnChange(() -> Controllers.navigate(new VersionPickerPage(instance)),
                i18n("dsh.instance.upgrade.hint"));
        return row;
    }

    /// Builds the row for the boot library the runtime is paired with.
    ///
    /// @return the row
    private InstallerListItem appBootRow() {
        // The mark is white or black and the row's surface follows the theme, so
        // the picture follows it too — the same pairing the create page uses.
        ImageView mark = new ImageView();
        mark.imageProperty().bind(Bindings.createObjectBinding(
                InstanceInstallersPage::appBootMark, Themes.darkModeProperty()));

        InstallerListItem row = new InstallerListItem(mark, i18n("dsh.install.app_boot"));

        String appBoot = DshVersionManager.readAppBoot(instance);
        if (appBoot == null || appBoot.equals(instance.version())) {
            // Nothing pinned, or pinned to the version the runtime itself is:
            // both are the pairing, which is what "matched" says.
            row.statusProperty().set(i18n("dsh.install.app_boot.matched", instance.version()));
        } else {
            row.statusProperty().set(i18n("dsh.install.app_boot.chosen", appBoot));
        }

        row.setOnActivate(this::chooseAppBoot);
        row.setOnChange(this::chooseAppBoot, i18n("dsh.install.app_boot"));
        return row;
    }

    /// Returns the boot library's mark for the current theme.
    ///
    /// @return the mark, white on a dark surface and black on a light one
    private static javafx.scene.image.Image appBootMark() {
        return (Themes.darkModeProperty().get() ? DshInstanceIcon.DSH_WHITE : DshInstanceIcon.DSH_BLACK).load();
    }

    /// Builds the row for the marketplace plugin.
    ///
    /// @return the row
    private InstallerListItem marketRow() {
        DshPreset market = marketPreset();
        // The marketplace leads with the mark of the loader it stands in for, and
        // the create page's card carries that same picture; a row that showed
        // something else would be describing a different thing.
        InstallerListItem row = new InstallerListItem(DshInstanceIcon.FABRIC.load(),
                market == null ? "dsh-market" : market.name());

        String installed = market == null ? null : installedVersion(market);
        row.statusProperty().set(installed == null ? i18n("install.installer.not_installed") : installed);

        if (market != null) {
            // The button offers the versions rather than installing whatever the
            // catalogue last saw: that is what the original's version button does,
            // and the page it opens is where the version is chosen.
            Runnable versions = () -> Controllers.navigate(new PluginDetailPage(catalogueEntry(market), instance));
            row.setOnActivate(versions);
            row.setOnChange(versions, i18n("download.install"));
            row.setOnRemove(installed == null ? null : () -> removeMarket(market),
                    i18n("dsh.instance.plugins.remove"));
        }
        return row;
    }

    /// Returns the catalogue's marketplace entry.
    ///
    /// @return the preset, or `null` when the catalogue does not offer one
    private static @Nullable DshPreset marketPreset() {
        return DshPresetCatalog.builtin().stream()
                .filter(preset -> preset.id().equals("dshmarket"))
                .findFirst()
                .orElse(null);
    }

    /// Describes a preset as the catalogue would describe it.
    ///
    /// A preset is the catalogue's marketplace entry boiled down to what the create
    /// page chooses between: a name and the package to install. The plugin page
    /// wants the catalogue's shape, so this builds the part of it that is known —
    /// and nothing else, because inventing a repository or a download count would
    /// be describing something that does not exist.
    ///
    /// @param preset the preset
    /// @return the catalogue entry
    private static DshPluginCatalog.Plugin catalogueEntry(DshPreset preset) {
        String spec = preset.spec();
        int at = spec.lastIndexOf('@');
        String name = at > 0 ? spec.substring(0, at) : spec;
        String version = at > 0 ? spec.substring(at + 1) : null;

        // The package's own page is the link that always exists for a plugin, and
        // it is where its versions and its repository are named. The catalogue is
        // asked first, because a plugin it knows has a page of its own.
        DshPluginCatalog.Plugin known = org.jackhuang.hmcl.dsh.DshPluginCatalog.find(name).orElse(null);
        if (known != null) {
            return known;
        }
        // The record's fields in order: name, owner, address, category, description,
        // localised description, package, version, stars, downloads, tarball, source.
        return new DshPluginCatalog.Plugin(preset.name(), "",
                "https://www.npmjs.com/package/" + name, "market", preset.description(),
                preset.description(), name, version, 0, 0, null, null);
    }

    /// Returns the version of a package the instance's profile declares.
    ///
    /// @param preset the preset whose package is looked for
    /// @return the declared version, or `null` when the profile does not hold it
    private @Nullable String installedVersion(DshPreset preset) {
        try {
            Map<String, String> dependencies =
                    DshPluginInstaller.readDependencies(instance.homeDirectory(), instance.profile());
            return dependencies.get(preset.spec());
        } catch (DshException e) {
            LOG.warning("Failed to read the profile of " + instance.id(), e);
            return null;
        }
    }

    /// Asks which boot library version this instance should be held to.
    private void chooseAppBoot() {
        Controllers.getDecorator().startWizard(new AppBootWizardProvider(instance.version(), chosen -> {
            // The chooser reports the launcher's own version as `null`, because
            // a wizard that runs inside another one has no instance to pin it to.
            String appBoot = chosen == null ? instance.version() : chosen;
            String current = DshVersionManager.readAppBoot(instance);
            String from = current == null || current.isBlank() ? instance.version() : current;
            if (from.equals(appBoot)) {
                return;
            }
            Controllers.confirm(i18n("dsh.install.app_boot.change.confirm", from, appBoot),
                    i18n("dsh.install.app_boot"),
                    () -> ProgressDialog.run(i18n("dsh.install.app_boot"), progress ->
                            DshVersionManager.overrideAppBoot(instance, appBoot, progress::accept),
                            this::refresh),
                    null);
        }), i18n("dsh.install.app_boot"));
    }

    /// Installs the marketplace into the instance's profile.
    ///
    /// @param market the preset to install
    private void installMarket(DshPreset market) {
        ProgressDialog.run(i18n("download.install"), progress ->
                DshPluginInstaller.install(instance, List.of(market), progress::accept), this::refresh);
    }

    /// Removes the marketplace from the instance's profile.
    ///
    /// @param market the preset to remove
    private void removeMarket(DshPreset market) {
        Controllers.confirm(i18n("dsh.instance.plugins.remove.confirm", market.spec()),
                i18n("dsh.instance.plugins.remove"),
                () -> ProgressDialog.run(i18n("dsh.instance.plugins.remove"), progress ->
                        DshPluginInstaller.remove(instance, market.spec(), progress::accept), this::refresh),
                null);
    }

    /// The page's skin: the toolbar every list page has, over this page's rows.
    private static final class InstallersPageSkin
            extends ToolbarListPageSkin<InstallerListItem, InstanceInstallersPage> {
        /// Creates the skin.
        ///
        /// @param page the page being drawn
        InstallersPageSkin(InstanceInstallersPage page) {
            super(page);
        }

        @Override
        protected List<Node> initializeToolbar(InstanceInstallersPage page) {
            // One button, as the original has here: it offers the local-file
            // install, which this launcher has nothing to do with. What is left
            // is reading the instance's files again.
            return List.of(ToolbarListPageSkin.createToolbarButton2(
                    i18n("button.refresh"), SVG.REFRESH, page::refresh));
        }
    }
}
