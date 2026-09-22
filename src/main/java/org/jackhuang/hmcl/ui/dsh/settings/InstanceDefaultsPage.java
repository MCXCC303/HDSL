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
import org.jackhuang.hmcl.dsh.DshBuildScriptPolicy;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshLauncherVisibility;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.NodeRuntime;
import org.jackhuang.hmcl.dsh.NodeRuntimeManager;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LinePane;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// What a new instance is given.
///
/// The original keeps the defaults an instance starts from apart from the
/// launcher's own settings: Java and memory are not the same kind of thing as the
/// interface language, and they are reached from a different place. The same
/// split is kept here, and it is also where the rows about an instance's own
/// behaviour live — whether a plugin's install scripts may run, how the launcher
/// behaves while an instance runs, the commands that run around it and the debug
/// switch. Every one of them is a value an instance can state for itself, which is
/// exactly what the original's own launcher-settings card inside a game's settings
/// holds; a launcher-wide copy of a per-instance setting would be a second place
/// for the same answer to live.
@NotNullByDefault
public final class InstanceDefaultsPage extends ScrollPane {
    /// Creates the page.
    public InstanceDefaultsPage() {
        setFitToWidth(true);
        setFitToHeight(true);

        VBox root = new VBox(
                ComponentList.createComponentListTitle(i18n("dsh.settings.environment")),
                buildEnvironmentList(),
                ComponentList.createComponentListTitle(i18n("dsh.settings.build_scripts")),
                buildBuildScriptsList(),
                ComponentList.createComponentListTitle(i18n("dsh.settings.launcher")),
                buildLauncherList(),
                ComponentList.createComponentListTitle(i18n("dsh.settings.env_vars")),
                buildEnvironmentVariablesList(),
                ComponentList.createComponentListTitle(i18n("dsh.settings.commands")),
                buildCommandsList(),
                ComponentList.createComponentListTitle(i18n("dsh.settings.debug")),
                buildDebugList());
        root.getStyleClass().add("card-list");
        setContent(root);

        // After the content: smooth scrolling binds to it and throws on null.
        FXUtils.smoothScrolling(this);
    }

    /// Builds the environment section: what a new instance is given.
    ///
    /// HMCL keeps Java management at the top level of its settings rather than
    /// per instance, and the reasoning carries over: a runtime is a launcher's
    /// business, not something every instance should have its own copy of. An
    /// instance may still pin its own, which is what these are the defaults for.
    ///
    /// @return the assembled component list
    private ComponentList buildEnvironmentList() {
        ComponentList list = new ComponentList();

        LineSelectButton<String> node = new LineSelectButton<>();
        node.setTitle(i18n("dsh.settings.default_node"));

        List<String> runtimes = new ArrayList<>();
        runtimes.add(DshNodeRuntime.SYSTEM);
        for (NodeRuntime runtime : NodeRuntimeManager.listInstalled()) {
            runtimes.add(runtime.version());
        }
        node.setItems(runtimes);
        node.setNullSafeConverter(selection -> DshNodeRuntime.SYSTEM.equals(selection)
                ? i18n("dsh.install.node.system")
                : selection);
        node.setValue(settings().defaultNodeRuntimeProperty().get());
        node.valueProperty().addListener((observable, was, value) -> {
            if (value != null && !value.equals(was)) {
                settings().defaultNodeRuntimeProperty().set(value);
                SettingsManager.save();
            }
        });
        list.getContent().add(node);

        LineSelectButton<DshHomeMode> home = new LineSelectButton<>();
        home.setTitle(i18n("dsh.settings.default_home"));
        // The policy first, then what "not isolated" means — the original's arrangement, and the
        // one that reads correctly: the rule decides, and the mode below says what it falls back to.
        LineSelectButton<org.jackhuang.hmcl.dsh.DshIsolationPolicy> policy = new LineSelectButton<>();
        policy.setTitle(i18n("dsh.settings.isolation"));
        policy.setItems(org.jackhuang.hmcl.dsh.DshIsolationPolicy.values());
        policy.setConverter(choice -> choice == null ? ""
                : i18n("dsh.settings.isolation." + choice.id()));
        policy.setValue(settings().isolationPolicy());
        policy.valueProperty().addListener((observable, was, value) -> {
            if (value != null) {
                settings().isolationPolicyProperty().set(value);
            }
        });
        home.setItems(DshHomeMode.ISOLATED, DshHomeMode.VERSION_SHARED);
        home.setNullSafeConverter(mode -> i18n("dsh.instance.home." + mode.name().toLowerCase(Locale.ROOT)));
        home.setValue(settings().defaultHomeModeProperty().get());
        home.valueProperty().addListener((observable, was, value) -> {
            if (value != null && value != was) {
                settings().defaultHomeModeProperty().set(value);
                SettingsManager.save();
            }
        });
        list.getContent().add(policy);
        list.getContent().add(home);

        return list;
    }

    /// Builds the row about install scripts.
    ///
    /// Off by default: an install script runs with the user's own rights and nobody
    /// has read it, so a launcher that ran them without being asked would be deciding
    /// something that is not its to decide. An instance states its own answer, and the
    /// instance's settings page is where it does.
    ///
    /// @return the assembled component list
    private ComponentList buildBuildScriptsList() {
        LineSelectButton<DshBuildScriptPolicy> approve = new LineSelectButton<>();
        approve.setTitle(i18n("dsh.settings.build_scripts.approve"));
        // The rows around it say what they are for, and this one is the least obvious of
        // them: what it decides is whether somebody is asked before a plugin's install
        // script runs.
        approve.setItems(List.of(DshBuildScriptPolicy.AUTO, DshBuildScriptPolicy.MANUAL,
                DshBuildScriptPolicy.NEVER));
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

    /// Builds the rows about what the launcher does while an instance runs.
    ///
    /// The original's own rows in its launcher-settings card, in its order: how the
    /// launcher gets out of the way, and whether the log window is opened.
    ///
    /// @return the assembled component list
    private ComponentList buildLauncherList() {
        LineSelectButton<DshLauncherVisibility> visibility = new LineSelectButton<>();
        visibility.setTitle(i18n("dsh.settings.launcher.visibility"));
        visibility.setItems(DshLauncherVisibility.values());
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
    /// A command is about the launcher's own behaviour rather than about the harness:
    /// one runs before an instance starts and one after it has ended, and what they are
    /// for is fitting the launcher into somebody's workflow. An instance may state its
    /// own, which is what the instance's settings page is for.
    ///
    /// @return the assembled component list
    private ComponentList buildCommandsList() {
        ComponentList list = new ComponentList();
        list.getContent().add(commandRow(i18n("dsh.settings.commands.pre"),
                settings().preLaunchCommandProperty()));
        list.getContent().add(commandRow(i18n("dsh.settings.commands.post"),
                settings().postExitCommandProperty()));
        return list;
    }

    /// Builds one command row.
    ///
    /// The original's shape for a row with a box to type in: the name on the left and the field
    /// beside it, which is how every other row here is built.
    ///
    /// @param title    the row's name
    /// @param property what is typed into it
    /// @return the row
    private javafx.scene.Node commandRow(String title, javafx.beans.property.StringProperty property) {
        LinePane pane = new LinePane();
        pane.setTitle(title);

        com.jfoenix.controls.JFXTextField field = new com.jfoenix.controls.JFXTextField();
        field.setMinWidth(420);
        field.textProperty().bindBidirectional(property);
        pane.setRight(field);
        return pane;
    }

    /// Builds the switch for the launcher's own debug lines.
    ///
    /// @return the assembled component list
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

    /// Builds the editor for the variables every instance runs with.
    ///
    /// One line of `NAME=VALUE` pairs, which is the shape the instance's own row has: the two are
    /// the same setting seen from either end, and reading one should not mean learning a second
    /// shape. What an instance sets is laid over this, so a variable written here reaches every
    /// instance that has not overridden it.
    ///
    /// @return the assembled component list
    private ComponentList buildEnvironmentVariablesList() {
        LinePane pane = new LinePane();
        pane.setTitle(i18n("dsh.settings.env_vars"));

        com.jfoenix.controls.JFXTextField field = new com.jfoenix.controls.JFXTextField();
        field.setMinWidth(420);
        field.setText(org.jackhuang.hmcl.dsh.DshEnvironment.format(settings().globalEnvironment()));
        field.textProperty().addListener((observable, was, text) ->
                settings().globalEnvironmentProperty().set(org.jackhuang.hmcl.dsh.DshEnvironment.parse(text)));
        pane.setRight(field);

        ComponentList list = new ComponentList();
        list.getContent().add(pane);
        return list;
    }
}
