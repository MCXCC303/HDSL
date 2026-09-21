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
import org.jackhuang.hmcl.dsh.DshInstallProgress;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceIcon;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.dsh.DshPreset;
import org.jackhuang.hmcl.dsh.DshPresetCatalog;
import org.jackhuang.hmcl.dsh.DshVersionManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.ListPageBase;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.ToolbarListPageSkin;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.ui.construct.TaskExecutorDialogPane;
import org.jackhuang.hmcl.ui.dsh.install.AppBootWizardProvider;
import org.jackhuang.hmcl.ui.dsh.install.InstallerListItem;
import org.jackhuang.hmcl.ui.wizard.Refreshable;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
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
    private final DshInstance instance;

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
        InstallerListItem row = new InstallerListItem(SVG.EXTENSION, market == null ? "dsh-market" : market.name());

        String installed = market == null ? null : installedVersion(market);
        row.statusProperty().set(installed == null ? i18n("install.installer.not_installed") : installed);

        if (market != null) {
            row.setOnChange(() -> installMarket(market), i18n("download.install"));
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
            runWithProgress(i18n("dsh.install.app_boot"), progress ->
                    DshVersionManager.overrideAppBoot(instance, appBoot, progress::accept));
        }), i18n("dsh.install.app_boot"));
    }

    /// Installs the marketplace into the instance's profile.
    ///
    /// @param market the preset to install
    private void installMarket(DshPreset market) {
        runWithProgress(i18n("download.install"), progress ->
                DshPluginInstaller.install(instance, List.of(market), progress::accept));
    }

    /// Removes the marketplace from the instance's profile.
    ///
    /// @param market the preset to remove
    private void removeMarket(DshPreset market) {
        Controllers.confirm(i18n("dsh.instance.plugins.remove.confirm", market.spec()),
                i18n("dsh.instance.plugins.remove"),
                () -> runWithProgress(i18n("dsh.instance.plugins.remove"), progress ->
                        DshPluginInstaller.remove(instance, market.spec(), progress::accept)),
                null);
    }

    /// Runs something that installs into the instance, with the original's
    /// progress dialog and the package manager's own output in it.
    ///
    /// A change here runs a package manager, which takes as long as it takes and
    /// says why when it fails: doing it quietly would leave the page's rows
    /// unchanged with no way to tell whether anything happened at all.
    ///
    /// @param title the line the dialog is titled with
    /// @param work  the work, given a sink for the package manager's output
    private void runWithProgress(String title, Installation work) {
        DshInstallProgress progress = new DshInstallProgress();

        Task<Void> task = Task.runAsync(title, () -> work.run(progress::accept));
        TaskExecutorDialogPane pane = new TaskExecutorDialogPane(
                new TaskCancellationAction(it -> it.fireEvent(new DialogCloseEvent())));
        pane.titleProperty().bind(progress.messageProperty());

        TaskExecutor executor = task.executor();
        executor.addTaskListener(new TaskListener() {
            @Override
            public void onStop(boolean success, TaskExecutor stopped) {
                runInFX(() -> {
                    if (!success) {
                        Exception failure = stopped.getException();
                        LOG.warning("Failed to change " + instance.id(), failure);
                        Controllers.dialog(failure == null ? i18n("message.error") : failure.getMessage(),
                                title, MessageType.ERROR);
                    }
                    refresh();
                });
            }
        });

        pane.setExecutor(executor, true);
        Controllers.dialog(pane);
        executor.start();
    }

    /// Work that changes what the instance holds and says what the package
    /// manager reports while it does.
    @FunctionalInterface
    private interface Installation {
        /// Runs the installation.
        ///
        /// @param report receives the package manager's output
        /// @throws DshException when the work fails
        void run(java.util.function.Consumer<String> report) throws DshException;
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
