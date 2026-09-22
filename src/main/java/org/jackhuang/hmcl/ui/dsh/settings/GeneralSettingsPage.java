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

import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import java.util.ArrayList;
import org.jackhuang.hmcl.dsh.NodeRuntimeManager;
import org.jackhuang.hmcl.dsh.NodeRuntime;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.setting.SettingsManager;
import javafx.scene.Node;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.i18n.SupportedLocale;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Locale;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The "general" tab of the launcher settings page.
///
/// Owns language selection, animation policy, log retention and the launcher
/// data directory. Everything here applies to the launcher itself rather than
/// to a DeepSeek Harness instance.
@NotNullByDefault
public final class GeneralSettingsPage extends ScrollPane {
    /// Log retention presets, in lines.
    private static final List<Integer> LOG_LINE_PRESETS = List.of(500, 1000, 2000, 5000, 10000);

    /// Creates the general settings tab.
    public GeneralSettingsPage() {
        setFitToWidth(true);

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        setContent(root);

        // Must run after the content is installed: smooth scrolling binds to
        // the content node and fails on a null content.
        FXUtils.smoothScrolling(this);

        root.getChildren().addAll(
                sectionTitle(i18n("settings.launcher.general")), buildInterfaceList(),
                buildLogList(),
                buildStorageList(),
                sectionTitle(i18n("dsh.settings.build_scripts")), buildBuildScriptsList());
    }

    /// Builds a section title.
    ///
    /// @param text the title
    /// @return the title node
    private static Node sectionTitle(String text) {
        return ComponentList.createComponentListTitle(text);
    }

    /// Builds the interface section: language and animations.
    ///
    /// @return the assembled component list
    private ComponentList buildInterfaceList() {
        LineSelectButton<SupportedLocale> language = new LineSelectButton<>();
        language.setTitle(i18n("dsh.settings.language"));
        language.setItems(SupportedLocale.getSupportedLocales());
        language.setNullSafeConverter(locale -> locale.getDisplayName(I18n.getLocale()));
        language.setValue(I18n.getLocale());
        language.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                I18n.setLocale(newValue);
            }
        });

        ComponentList list = new ComponentList();
        list.getContent().add(language);
        return list;
    }

    /// Builds the log section.
    ///
    /// @return the assembled component list
    /// Builds the row about install scripts.
    ///
    /// Off by default: an install script runs with the user's own rights and nobody
    /// has read it, so a launcher that ran them without being asked would be deciding
    /// something that is not its to decide.
    ///
    /// @return the list
    private ComponentList buildBuildScriptsList() {
        LineToggleButton approve = new LineToggleButton();
        approve.setTitle(i18n("dsh.settings.build_scripts.approve"));
        approve.selectedProperty().bindBidirectional(settings().approveBuildScriptsProperty());

        ComponentList list = new ComponentList();
        list.getContent().add(approve);
        return list;
    }

    private ComponentList buildLogList() {
        LineSelectButton<Integer> logLines = new LineSelectButton<>();
        logLines.setTitle(i18n("dsh.settings.log.lines"));
        logLines.setItems(LOG_LINE_PRESETS);
        logLines.setNullSafeConverter(lines -> i18n("dsh.settings.log.lines.value", lines));
        Integer current = settings().logLinesProperty().get();
        logLines.setValue(current != null && LOG_LINE_PRESETS.contains(current) ? current : 2000);
        logLines.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                settings().logLinesProperty().set(newValue);
            }
        });

        ComponentList list = new ComponentList();
        list.getContent().add(logLines);
        return list;
    }

    /// Builds the storage section, showing where HMCL-DSH keeps its data.
    ///
    /// @return the assembled component list
    private ComponentList buildStorageList() {
        LineButton home = new LineButton();
        home.setTitle(i18n("dsh.settings.home"));
        home.setSubtitle(Metadata.HMCL_USER_HOME.toString());
        home.setOnAction(event -> FXUtils.showFileInExplorer(Metadata.HMCL_USER_HOME));

        ComponentList list = new ComponentList();
        list.getContent().add(home);
        return list;
    }
}
