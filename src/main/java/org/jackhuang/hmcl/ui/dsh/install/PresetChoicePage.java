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
package org.jackhuang.hmcl.ui.dsh.install;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshPackageRegistry;
import org.jackhuang.hmcl.dsh.DshPreset;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.dsh.VersionFilterBar;
import java.util.List;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardPage;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The page opened by clicking a plugin card.
///
/// Mirrors HMCL's per-component version pages: pick a version, or pick "do not
/// install", and return to the quick-install grid. Nothing is downloaded here —
/// the wizard only records the choice, and every download happens once the
/// user presses Install on the create page.
@NotNullByDefault
public final class PresetChoicePage extends ScrollPane implements WizardPage {
    /// The wizard controller used to return.
    private final WizardController controller;

    /// The catalogue entry being configured.
    private final DshPreset preset;

    /// The version rows, filtered by the name box and the type filter.
    private final ComponentList choices = new ComponentList();

    /// The name box and type filter, shared with the other version lists.
    private final VersionFilterBar filterBar = new VersionFilterBar(this::render, this::loadVersions);

    /// Every version the last load returned, before filtering.
    private List<String> versions = List.of();

    /// The status line.
    private final Label status = new Label();

    /// Covers the list while versions load.
    private final SpinnerPane spinner = new SpinnerPane();

    /// How many versions are offered.
    private static final int VERSION_LIMIT = 20;

    /// Creates the page.
    ///
    /// @param controller the wizard controller
    /// @param preset     the catalogue entry to configure
    public PresetChoicePage(WizardController controller, DshPreset preset) {
        this.controller = controller;
        this.preset = preset;

        setFitToWidth(true);

        VBox root = new VBox(10);
        // The original's install pages pad their root by sixteen.
        root.setPadding(new Insets(16));

        Label title = new Label(preset.name());
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label description = new Label(preset.description());
        description.setWrapText(true);

        root.getChildren().addAll(title, description, filterBar, status, choices);
        spinner.setContent(root);
        setContent(spinner);
        FXUtils.smoothScrolling(this);

        loadVersions();
    }

    /// Loads the package's published versions in the background.
    private void loadVersions() {
        spinner.setLoading(true);
        status.setText(i18n("dsh.versions.loading"));

        CompletableFuture.supplyAsync(() -> {
            try {
                return DshPackageRegistry.fetchVersions(preset.spec());
            } catch (DshException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Schedulers.io()).whenComplete((versions, throwable) -> runInFX(() -> {
            spinner.setLoading(false);
            if (throwable != null) {
                Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                        && throwable.getCause() != null ? throwable.getCause() : throwable;
                LOG.warning("Failed to list versions of " + preset.spec(), cause);
                status.setText(i18n("dsh.versions.load_failed") + ": " + cause.getMessage());
                return;
            }
            status.setText(i18n("dsh.install.plugin.choose"));
            this.versions = versions;
            render();
        }));
    }

    /// Rebuilds the version rows from the last load and the filters.
    private void render() {
        choices.getContent().clear();

        int shown = 0;
        for (String version : versions) {
            if (!filterBar.accepts(version)) {
                continue;
            }
            if (shown++ >= VERSION_LIMIT) {
                break;
            }
            choices.getContent().add(buildVersion(version));
        }
    }

    /// @return the row
    private LineButton buildVersion(String version) {
        LineButton row = new LineButton();
        row.setTitle(version);
        row.setSubtitle(preset.spec() + "@" + version);
        row.setOnAction(event -> choose(version));
        return row;
    }

    /// Records the choice and returns to the quick-install page.
    ///
    /// @param version the chosen version, an empty string for "latest", or `null` for "do not install"
    private void choose(@Nullable String version) {
        SettingsMap settings = controller.getSettings();
        DshInstallWizardProvider.presetChoices(settings).put(preset.id(), version);
        controller.onPrev(false);
    }

    @Override
    public void onNavigate(SettingsMap settings) {
        // Reaching this page again means returning from it; nothing to refresh.
    }

    @Override
    public String getTitle() {
        return i18n("dsh.install.plugin.choose");
    }
}
