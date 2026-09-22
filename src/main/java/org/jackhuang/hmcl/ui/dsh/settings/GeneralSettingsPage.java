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
                sectionTitle(i18n("dsh.settings.build_scripts")), buildBuildScriptsList(),
                sectionTitle(i18n("dsh.settings.launcher")), buildLauncherList(),
                sectionTitle(i18n("dsh.settings.commands")), buildCommandsList(),
                sectionTitle(i18n("dsh.settings.debug")), buildDebugList());
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
    /// Builds the switch for the launcher's own debug lines.
    ///
    /// @return the list
    private ComponentList buildDebugList() {
        LineToggleButton debug = new LineToggleButton();
        debug.setTitle(i18n("dsh.settings.debug.log"));
        debug.setSubtitle(i18n("dsh.settings.debug.log.hint"));
        debug.selectedProperty().bindBidirectional(settings().debugLogProperty());
        debug.selectedProperty().addListener((observable, was, value) ->
                org.jackhuang.hmcl.util.logging.Logger.setDebugEnabled(Boolean.TRUE.equals(value)));

        ComponentList list = new ComponentList();
        list.getContent().add(debug);
        return list;
    }

    /// Builds the row about what the launcher does while an instance runs.
    ///
    /// @return the list
    private ComponentList buildLauncherList() {
        LineSelectButton<org.jackhuang.hmcl.dsh.DshLauncherVisibility> visibility = new LineSelectButton<>();
        visibility.setTitle(i18n("dsh.settings.launcher.visibility"));
        visibility.setItems(org.jackhuang.hmcl.dsh.DshLauncherVisibility.values());
        visibility.setConverter(choice -> choice == null ? ""
                : i18n("dsh.settings.launcher.visibility." + choice.id()));
        visibility.setValue(settings().launcherVisibilityProperty().get());
        visibility.valueProperty().addListener((observable, was, value) -> {
            if (value != null) {
                settings().launcherVisibilityProperty().set(value);
            }
        });

        LineToggleButton showLogs = new LineToggleButton();
        showLogs.setTitle(i18n("dsh.settings.launcher.show_logs"));
        showLogs.selectedProperty().bindBidirectional(settings().showLogsProperty());

        ComponentList list = new ComponentList();
        list.getContent().add(visibility);
        list.getContent().add(showLogs);
        return list;
    }

    /// Builds the two commands that run around an instance.
    ///
    /// The original keeps them in the launcher's settings because they are about the
    /// launcher's own behaviour rather than about a game: one runs before an instance
    /// starts and one after it has ended, and what they are for is fitting the launcher
    /// into somebody's workflow.
    ///
    /// @return the list
    private ComponentList buildCommandsList() {
        ComponentList list = new ComponentList();
        list.getContent().add(commandRow(i18n("dsh.settings.commands.pre"),
                i18n("dsh.settings.commands.pre.hint"),
                settings().preLaunchCommandProperty()));
        list.getContent().add(commandRow(i18n("dsh.settings.commands.post"),
                i18n("dsh.settings.commands.post.hint"),
                settings().postExitCommandProperty()));
        return list;
    }

    /// Builds one command row.
    ///
    /// @param title    the row's name
    /// @param hint     what the command is for
    /// @param property what is typed into it
    /// @return the row
    private javafx.scene.Node commandRow(String title, String hint,
                                         javafx.beans.property.StringProperty property) {
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(6);
        box.setPadding(new Insets(8, 12, 8, 12));

        javafx.scene.control.Label label = new javafx.scene.control.Label(title);
        javafx.scene.control.Label description = new javafx.scene.control.Label(hint);
        description.getStyleClass().add("desc");

        com.jfoenix.controls.JFXTextField field = new com.jfoenix.controls.JFXTextField();
        field.setPromptText(i18n("dsh.settings.commands.hint"));
        field.textProperty().bindBidirectional(property);

        box.getChildren().addAll(label, description, field);
        return box;
    }

    /// Builds the row about install scripts.
    ///
    /// Off by default: an install script runs with the user's own rights and nobody
    /// has read it, so a launcher that ran them without being asked would be deciding
    /// something that is not its to decide.
    ///
    /// @return the list
    private ComponentList buildBuildScriptsList() {
        LineSelectButton<org.jackhuang.hmcl.dsh.DshBuildScriptPolicy> approve = new LineSelectButton<>();
        approve.setTitle(i18n("dsh.settings.build_scripts.approve"));
        // The rows around it say what they are for, and this one is the least obvious of
        // them: what it decides is whether somebody is asked before a plugin's install
        // script runs.
        approve.setSubtitle(i18n("dsh.settings.build_scripts.approve.hint"));
        approve.setItems(java.util.List.of(org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.AUTO,
                org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.MANUAL,
                org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.NEVER));
        // The control asks for a display name before a value exists, so the converter
        // answers for nothing as well as for an answer.
        approve.setConverter(policy -> policy == null
                ? "" : i18n("dsh.settings.build_scripts." + policy.id()));
        approve.setValue(settings().buildScriptPolicy());
        approve.valueProperty().addListener((observable, was, value) -> {
            if (value != null) {
                settings().buildScriptPolicyProperty().set(value);
            }
        });

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
