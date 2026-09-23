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
package org.jackhuang.hmcl.ui.dsh.settings;

import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.i18n.SupportedLocale;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The "general" tab of the launcher settings page.
///
/// What is left here configures the launcher and nothing else: the language it
/// speaks and the folder it keeps its data in. Everything an instance is given —
/// whether a plugin's install scripts may run, how the launcher behaves while an
/// instance is running, the commands that run around it and the debug switch —
/// belongs to the instance and sits on the global instance settings tab, which is
/// also where the original keeps it: it offers the same rows again inside a single
/// instance's settings, and a launcher-wide copy of a per-instance setting is a
/// second place for the same answer to live.
///
/// The retained log line count is not here either. The original keeps that number
/// in its log window's own toolbar, so the launcher's settings had a second control
/// for a number the window already decides.
@NotNullByDefault
public final class GeneralSettingsPage extends ScrollPane {
    /// Creates the general settings tab.
    public GeneralSettingsPage() {
        setFitToWidth(true);
        setFitToHeight(true);

        // One card for the tab's rows, not a card per row: the original's general
        // tab is one card under each of its section headings, and a section that
        // holds a single setting reads as a heading repeated beside its own row.
        ComponentList general = new ComponentList();
        general.getContent().add(buildLanguageRow());
        general.getContent().add(buildStorageRow());

        // What installing a pack does, in a card of its own. The original gives a group of settings
        // that decide one thing its own heading, and these decide how a pack's dependencies are
        // resolved — which belongs to the general tab because it is a launcher-wide preference and
        // not a property of any one instance.
        ComponentList packs = new ComponentList();
        packs.getContent().add(buildDependencyPolicyRow());

        VBox root = new VBox(
                ComponentList.createComponentListTitle(i18n("settings.launcher.general")),
                general,
                ComponentList.createComponentListTitle(i18n("dsh.settings.pack_install")),
                packs);
        root.getStyleClass().add("card-list");
        setContent(root);

        // Must run after the content is installed: smooth scrolling binds to
        // the content node and fails on a null content.
        FXUtils.smoothScrolling(this);
    }

    /// Builds the row that decides how a pack's dependencies are resolved.
    ///
    /// @return the row
    private LineSelectButton<org.jackhuang.hmcl.dsh.DshDependencyPolicy> buildDependencyPolicyRow() {
        LineSelectButton<org.jackhuang.hmcl.dsh.DshDependencyPolicy> row = new LineSelectButton<>();
        row.setTitle(i18n("dsh.settings.dependency_policy"));
        row.setItems(org.jackhuang.hmcl.dsh.DshDependencyPolicy.values());
        row.setNullSafeConverter(policy -> i18n(
                "dsh.settings.dependency_policy." + policy.name().toLowerCase(java.util.Locale.ROOT)));
        row.setValue(settings().dependencyPolicy());
        row.valueProperty().addListener((observable, was, value) -> {
            if (value != null && value != was) {
                settings().dependencyPolicyProperty().set(value);
                SettingsManager.save();
            }
        });
        return row;
    }

    /// Builds the language row.
    ///
    /// The choice is written to the settings file as well as handed to the string helper:
    /// the helper keeps its answer in memory, so a language chosen here used to last until
    /// the launcher was closed.
    ///
    /// @return the row
    private LineSelectButton<SupportedLocale> buildLanguageRow() {
        LineSelectButton<SupportedLocale> language = new LineSelectButton<>();
        language.setTitle(i18n("dsh.settings.language"));
        language.setItems(SupportedLocale.getSupportedLocales());
        language.setNullSafeConverter(locale -> locale.getDisplayName(I18n.getLocale()));
        language.setValue(I18n.getLocale());
        language.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                I18n.setLocale(newValue);
                settings().languageProperty().set(newValue);
                SettingsManager.save();
            }
        });
        return language;
    }

    /// Builds the row that opens where the launcher keeps its data.
    ///
    /// @return the row
    private LineButton buildStorageRow() {
        LineButton home = new LineButton();
        home.setTitle(i18n("dsh.settings.home"));
        home.setSubtitle(Metadata.HMCL_USER_HOME.toString());
        home.setOnAction(event -> FXUtils.showFileInExplorer(Metadata.HMCL_USER_HOME));
        return home;
    }
}
