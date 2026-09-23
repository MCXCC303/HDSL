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
import org.jackhuang.hmcl.dsh.DshAccount;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import java.util.List;
import java.util.Locale;
import java.nio.file.Path;
import java.util.ArrayList;
import org.jackhuang.hmcl.dsh.NodeRuntimeManager;
import org.jackhuang.hmcl.dsh.NodeRuntime;
import org.jackhuang.hmcl.dsh.DshNodeRuntime;
import org.jackhuang.hmcl.dsh.DshHomeMode;
import org.jackhuang.hmcl.dsh.DshInstanceSettings;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshPortMode;
import org.jackhuang.hmcl.dsh.DshPorts;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.dsh.VersionPickerPage;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.FXUtils;
import javafx.scene.image.Image;
import org.jackhuang.hmcl.dsh.DshInstanceIcons;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.dsh.InstanceIconDialog;
import org.jackhuang.hmcl.ui.construct.ImagePickerItem;
import org.jackhuang.hmcl.dsh.DshInstanceIcon;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineInheritableSelectButton;
import org.jackhuang.hmcl.ui.construct.LineInheritableTextField;
import org.jackhuang.hmcl.ui.construct.LineInheritableToggleButton;
import org.jackhuang.hmcl.ui.construct.LinePane;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineTextPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.construct.LineInheritableSelectButton;
import org.jackhuang.hmcl.ui.construct.LinePane;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.NumberValidator;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;


import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The settings tab of an instance's management page.
///
/// Only the settings that exist for a DeepSeek Harness instance are offered.
/// The port is the one with real consequences: the browser interface keys
/// session state by origin, so an instance must keep one port for its whole
/// life, and two instances sharing a history through different ports would let
/// two writers corrupt it.
@NotNullByDefault
public final class InstanceSettingsPage extends ScrollPane {
    /// The instance being edited, refreshed from storage after every write.
    private DshInstance instance;

    /// The icon row, kept so its image can follow a change.
    private final ImagePickerItem iconRow = new ImagePickerItem();

    /// The port row, kept so its hint can follow the mode.
    private final LineTextPane portRow = new LineTextPane();

    /// The port number field, kept so it can be offered or taken away with the mode.
    private final com.jfoenix.controls.JFXTextField portField = new com.jfoenix.controls.JFXTextField();

    /// Called after a change is written back.
    private final Runnable onChanged;

    /// Creates the settings tab.
    ///
    /// @param instance  the instance being edited
    /// @param onChanged run after a successful write
    public InstanceSettingsPage(DshInstance instance, Runnable onChanged) {
        this.instance = instance;
        this.onChanged = onChanged;

        setFitToWidth(true);
        setFitToHeight(true);

        ComponentList iconList = new ComponentList();
        iconList.getContent().add(buildIconRow());

        ComponentList environmentList = new ComponentList();
        environmentList.getContent().add(buildNodeRuntimeRow());
        environmentList.getContent().add(buildLaunchArgumentsRow());
        environmentList.getContent().add(buildAccountRow());
        environmentList.getContent().add(buildHomeModeRow());

        ComponentList portList = new ComponentList();
        portList.getContent().add(buildPortModeRow());
        portList.getContent().add(buildPortRow());

        // The original's card list: the padding and the spacing between sections
        // come from the class rather than from numbers chosen here, so a page
        // built from the same parts sits the same way.
        // What version of DeepSeek Harness the instance runs is not here: choosing
        // it installs a runtime, and the auto-install page is where the original
        // offers that — beside the boot library and the plugins, which are the
        // same kind of thing.
        VBox root = new VBox(
                ComponentList.createComponentListTitle(i18n("dsh.instance.icon")), iconList,
                ComponentList.createComponentListTitle(i18n("dsh.settings.environment")), environmentList,
                ComponentList.createComponentListTitle(i18n("dsh.instance.port")), portList,
                ComponentList.createComponentListTitle(i18n("dsh.settings.build_scripts")), buildScriptsList(),
                ComponentList.createComponentListTitle(i18n("dsh.settings.env_vars")), buildEnvironmentVariablesList(),
                ComponentList.createComponentListTitle(i18n("dsh.settings.debug")), buildDebugList(),
                ComponentList.createComponentListTitle(i18n("dsh.settings.commands")), buildCommandsList());
        root.getStyleClass().add("card-list");
        setContent(root);

        // Must run after the content is installed: smooth scrolling binds to the
        // content node and throws on a null content.
        FXUtils.smoothScrolling(this);
    }

    /// Builds the two commands that run around this instance.
    ///
    /// Each follows the launcher until it is taken over, which is the shape every per-instance
    /// setting here has: the globe beside the name is the way in, and the field shows the
    /// launcher's command until it is.
    ///
    /// @return the list
    private ComponentList buildCommandsList() {
        ComponentList list = new ComponentList();
        list.getContent().add(commandRow(i18n("dsh.settings.commands.pre"), true));
        list.getContent().add(commandRow(i18n("dsh.settings.commands.post"), false));
        return list;
    }

    /// Builds one command row for this instance.
    ///
    /// @param title  the row's name
    /// @param before whether it is the command that runs before the instance starts
    /// @return the row
    private LineInheritableTextField commandRow(String title, boolean before) {
        String own = before ? DshInstanceSettings.preLaunchCommand(instance)
                : DshInstanceSettings.postExitCommand(instance);
        String launcher = before ? settings().preLaunchCommandProperty().get()
                : settings().postExitCommandProperty().get();

        LineInheritableTextField row = new LineInheritableTextField(title);
        row.setOverridden(own != null);
        row.setText(own != null ? own : launcher);

        javafx.beans.value.ChangeListener<String> store = (observable, was, value) -> {
            try {
                if (before) {
                    DshInstanceSettings.setPreLaunchCommand(instance,
                            row.isOverridden() ? value : null);
                } else {
                    DshInstanceSettings.setPostExitCommand(instance,
                            row.isOverridden() ? value : null);
                }
            } catch (DshException e) {
                LOG.warning("Failed to store the command", e);
            }
        };
        row.textProperty().addListener(store);
        row.overriddenProperty().addListener((observable, was, overridden) -> {
            if (!overridden) {
                // Handing it back shows the launcher's command again, which is what the row now is.
                row.setText(settings().preLaunchCommandProperty().get() == null ? "" : launcher);
                store.changed(null, null, null);
            }
        });
        return row;
    }

    /// Builds the row about this instance's debug lines.
    ///
    /// It follows the launcher until it is told otherwise, which is the shape every
    /// per-instance setting here has: the globe beside its name is the way in.
    ///
    /// @return the list
    private ComponentList buildDebugList() {
        ComponentList list = new ComponentList();
        list.getContent().add(launcherVisibilityRow());
        list.getContent().add(debugLogRow());
        list.getContent().add(logRow());
        return list;
    }

    /// Builds the row about what the launcher does while this instance runs.
    ///
    /// The original offers this inside a game's own settings as well as globally, for the
    /// same reason it is offered in both places here: one instance may want the launcher to
    /// get out of the way while another wants it to stay.
    ///
    /// @return the row
    private LineInheritableSelectButton<org.jackhuang.hmcl.dsh.DshLauncherVisibility> launcherVisibilityRow() {
        LineInheritableSelectButton<org.jackhuang.hmcl.dsh.DshLauncherVisibility> row =
                new LineInheritableSelectButton<>();
        row.setTitle(i18n("dsh.settings.launcher.visibility"));
        row.setItems(java.util.List.of(org.jackhuang.hmcl.dsh.DshLauncherVisibility.values()));
        row.setNullSafeConverter(choice -> choice == null ? ""
                : i18n("dsh.settings.launcher.visibility." + choice.id()));

        String own = DshInstanceSettings.launcherVisibility(instance);
        row.setOverridden(own != null);
        row.setValue(own != null
                ? org.jackhuang.hmcl.dsh.DshLauncherVisibility.of(own)
                : settings().launcherVisibilityFor(instance.id()));

        javafx.beans.value.ChangeListener<Object> store = (observable, was, value) -> {
            try {
                DshInstanceSettings.setLauncherVisibility(instance,
                        row.overriddenProperty().get() && row.getValue() != null
                                ? row.getValue().id() : null);
            } catch (DshException e) {
                LOG.warning("Failed to store the launcher visibility", e);
            }
        };
        row.overriddenProperty().addListener(store);
        row.valueProperty().addListener(store);
        return row;
    }

    /// Builds the row about this instance's debug lines.
    ///
    /// @return the row
    private LineInheritableToggleButton debugLogRow() {
        LineInheritableToggleButton row = new LineInheritableToggleButton();
        row.setTitle(i18n("dsh.settings.debug.log"));

        Boolean own = DshInstanceSettings.debugLog(instance);
        row.overriddenProperty().set(own != null);
        row.rawValueProperty().set(own != null ? own : settings().debugLogProperty().get());

        javafx.beans.value.ChangeListener<Boolean> store = (observable, was, value) -> {
            try {
                DshInstanceSettings.setDebugLog(instance,
                        row.overriddenProperty().get() ? row.rawValueProperty().get() : null);
            } catch (DshException e) {
                LOG.warning("Failed to store the debug log setting", e);
            }
        };
        row.overriddenProperty().addListener(store);
        row.rawValueProperty().addListener(store);
        return row;
    }

    /// Builds the row about this instance's log window.
    ///
    /// @return the row
    private LineInheritableToggleButton logRow() {
        LineInheritableToggleButton row = new LineInheritableToggleButton();
        row.setTitle(i18n("dsh.settings.launcher.show_logs"));

        Boolean own = DshInstanceSettings.showLogs(instance);
        row.overriddenProperty().set(own != null);
        row.rawValueProperty().set(own != null ? own : settings().showLogsProperty().get());

        javafx.beans.value.ChangeListener<Boolean> store = (observable, was, value) -> {
            try {
                DshInstanceSettings.setShowLogs(instance,
                        row.overriddenProperty().get() ? row.rawValueProperty().get() : null);
            } catch (DshException e) {
                LOG.warning("Failed to store the log window setting", e);
            }
        };
        row.overriddenProperty().addListener(store);
        row.rawValueProperty().addListener(store);
        return row;
    }

    /// Builds the editor for the variables an instance runs with.
    ///
    /// This is how one instance is given one API key and the next another: the variables are
    /// passed to whatever the instance runs, so a key set here belongs to this instance and is
    /// never written into a profile, a plugin or a pack.
    ///
    /// The row follows the launcher until it is taken over, like every other row here: while it
    /// follows, the box shows the launcher's set and does not accept typing, and the globe beside
    /// the name is the way in. What an instance sets is laid on top of the launcher's set rather
    /// than replacing it, so taking the row over starts from nothing: an instance that lists only
    /// its own key keeps every launcher-wide one as well.
    ///
    /// The set is written the way the global tab writes it — `NAME=VALUE`, one per line — so the
    /// same text can be moved between the two boxes.
    ///
    /// @return the list
    private ComponentList buildEnvironmentVariablesList() {
        LineInheritableTextField row = new LineInheritableTextField(i18n("dsh.settings.env_vars"));

        // Whether the instance has a set of its own is the state, and it has to be read from the
        // file rather than from the map: the constructor turns an absent member into an empty map
        // for safety, so an instance that adds nothing and one that has never been asked both look
        // empty in memory. The file is what remembers which it is.
        boolean own = org.jackhuang.hmcl.dsh.DshInstanceSettings.environmentIsOwn(instance);
        row.setOverridden(own);
        row.setText(org.jackhuang.hmcl.dsh.DshEnvironment.format(
                own ? instance.environment() : settings().globalEnvironment()));

        // Typing is only this instance's to store while the row is the one deciding; what is
        // written is this instance's own set, which is what is laid over the launcher's.
        row.textProperty().addListener((observable, was, text) -> {
            if (row.isOverridden()) {
                write(instance.withEnvironment(org.jackhuang.hmcl.dsh.DshEnvironment.parse(text)));
                rememberEnvironment(instance, true);
            }
        });
        row.overriddenProperty().addListener((observable, was, overridden) -> {
            if (overridden) {
                // An instance's own set is laid on top of the launcher's, so it starts empty: a
                // copy of the launcher's set would say the opposite — that every launcher-wide
                // variable is now this instance's, which would freeze them here.
                write(instance.withEnvironment(java.util.Map.of()));
                rememberEnvironment(instance, true);
                row.setText("");
            } else {
                write(instance.withEnvironment(null));
                rememberEnvironment(instance, false);
                row.setText(org.jackhuang.hmcl.dsh.DshEnvironment.format(settings().globalEnvironment()));
            }
        });

        ComponentList list = new ComponentList();
        list.getContent().add(row);
        return list;
    }

    /// Records whether an instance has an environment of its own.
    ///
    /// The instance file cannot answer this: its `environment` member is read as an empty map
    /// whether the instance adds nothing or has never been asked, so the answer is kept beside the
    /// instance's other own-versus-launcher choices. A failure is logged rather than shown: the
    /// variables themselves are already stored, and the worst a lost flag does is show the box
    /// empty next time.
    ///
    /// @param instance the instance
    /// @param own      whether it has one
    private static void rememberEnvironment(DshInstance instance, boolean own) {
        try {
            DshInstanceSettings.setEnvironmentIsOwn(instance, own);
        } catch (DshException e) {
            LOG.warning("Failed to record whether the instance has its own environment", e);
        }
    }

    /// Builds the row about install scripts.
    ///
    /// The same three answers the launcher offers, plus following it, which is what
    /// an instance starts at: the launcher's answer is the default, and an instance
    /// states its own only when it has a reason to.
    ///
    /// @return the list
    private ComponentList buildScriptsList() {
        // The entries are the policies with a null in front, which is what
        // "follow the launcher" is.
        java.util.List<org.jackhuang.hmcl.dsh.DshBuildScriptPolicy> choices = new java.util.ArrayList<>();

        choices.addAll(java.util.List.of(org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.AUTO,
                org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.MANUAL,
                org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.NEVER));

        LineSelectButton<org.jackhuang.hmcl.dsh.DshBuildScriptPolicy> row = new LineSelectButton<>();
        row.setTitle(i18n("dsh.settings.build_scripts.approve"));
        row.setItems(choices);
        // Not the null-safe wrapper: for this row null is an answer — it is what "follow
        // the launcher" is stored as — and the wrapper would draw nothing for it.
        row.setConverter(policy -> i18n(policy == null
                ? "dsh.settings.build_scripts.follow"
                : "dsh.settings.build_scripts." + policy.id()));
        row.setValue(org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.of(
                DshInstanceSettings.buildScriptPolicy(instance)));
        row.valueProperty().addListener((observable, was, value) -> {
            try {
                DshInstanceSettings.setBuildScriptPolicy(instance,
                        value == null ? null : value.id());
            } catch (DshException e) {
                LOG.warning("Failed to store the build script policy", e);
            }
        });

        ComponentList list = new ComponentList();
        list.getContent().add(row);
        return list;
    }

    /// Builds the Node runtime row.
    ///
    /// A choice of this instance's own, with no mark beside it: the launcher's default runtime is
    /// what a *new* instance is given, not something an existing one keeps following, so there is
    /// nothing here to inherit. What the row shows is what the instance runs.
    ///
    /// @return the row
    private LineSelectButton<String> buildNodeRuntimeRow() {
        List<String> choices = new ArrayList<>();
        choices.add(DshNodeRuntime.SYSTEM);
        for (NodeRuntime runtime : NodeRuntimeManager.listInstalled()) {
            choices.add(runtime.version());
        }

        LineSelectButton<String> row = new LineSelectButton<>();
        row.setTitle(i18n("dsh.node.title"));
        row.setItems(choices);
        row.setNullSafeConverter(selection -> DshNodeRuntime.SYSTEM.equals(selection)
                ? i18n("dsh.install.node.system")
                : selection);
        // A runtime that was never chosen — or that was stored as "follow the launcher" before this
        // row lost its mark — reads as the launcher's default, which is what the instance runs.
        String chosen = instance.nodeRuntime();
        row.setValue(chosen == null || DshNodeRuntime.GLOBAL.equals(chosen)
                ? settings().defaultNodeRuntimeProperty().get() : chosen);
        row.valueProperty().addListener((observable, was, value) -> {
            if (value != null && !value.equals(was)) {
                write(instance.withNodeRuntime(value));
            }
        });
        return row;
    }

    /// Builds the row that chooses which account this instance launches with.
    ///
    /// An account is a key and the vendor it belongs to, and the launcher hands it to the harness
    /// as it starts — the harness asks to be configured before it will answer anything, and a
    /// launcher that holds a key already can spare the person that step. Which one is the
    /// instance's own choice, because two instances may be two different accounts.
    ///
    /// The row offers "no account" as well: a harness that has been configured by hand, or one
    /// whose key is meant to come from the environment, is a real arrangement and not an omission.
    ///
    /// @return the row
    private javafx.scene.Node buildAccountRow() {
        LineTextPane row = new LineTextPane();
        row.setTitle(i18n("dsh.account.title"));

        javafx.scene.control.ComboBox<String> picker = new javafx.scene.control.ComboBox<>();
        java.util.List<DshAccount> accounts = settings().getAccounts();
        java.util.List<String> choices = new java.util.ArrayList<>();
        choices.add(NONE_ACCOUNT);
        for (DshAccount account : accounts) {
            choices.add(account.key());
        }
        picker.getItems().setAll(choices);
        picker.setConverter(FXUtils.stringConverter(choice -> {
            if (choice == null || NONE_ACCOUNT.equals(choice)) {
                return i18n("dsh.account.none.short");
            }
            for (DshAccount account : settings().getAccounts()) {
                if (account.matchesKey(choice)) {
                    return account.displayName();
                }
            }
            return choice;
        }));
        String chosen = DshInstanceSettings.accountKey(instance);
        picker.setValue(chosen == null ? NONE_ACCOUNT : chosen);
        picker.setMinWidth(240);
        picker.valueProperty().addListener((observable, was, value) -> {
            if (value == null || value.equals(was)) {
                return;
            }
            try {
                DshInstanceSettings.setAccountKey(instance, NONE_ACCOUNT.equals(value) ? null : value);
            } catch (DshException e) {
                LOG.warning("Failed to store the account choice", e);
            }
        });

        com.jfoenix.controls.JFXButton manage = new com.jfoenix.controls.JFXButton(
                i18n("dsh.account.manage"));
        manage.getStyleClass().add("jfx-button-border");
        manage.setOnAction(event -> org.jackhuang.hmcl.ui.Controllers.dialog(
                new AccountSettingsDialog(null)));

        javafx.scene.layout.HBox controls = new javafx.scene.layout.HBox(8, picker, manage);
        controls.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        row.setRowTrailing(controls);

        // The row is not inheritable: an account is this machine's, and the launcher-wide answer is
        // "whichever one there is" rather than a value an instance copies.
        return row;
    }

    /// The choice meaning "let the harness use whatever it is configured with".
    private static final String NONE_ACCOUNT = "";

    /// Builds the row for the arguments this instance is launched with.
    ///
    /// In the environment section, beside the runtime and the home, because it is the third thing
    /// that decides what actually runs — and with a globe like its neighbours, since the launcher
    /// has a default line of its own and an instance may state its own instead.
    ///
    /// What somebody types here is passed to the harness as it stands, with two exceptions the
    /// launch reports rather than obeys: `--port` (an instance's port is part of its identity, and
    /// the browser keys its stored state by it) and `DSH_HOME` (which is how one instance's state
    /// is kept away from another's). Everything else, including which profile to boot and whether
    /// to open a browser, is theirs to decide — see [DshLaunchArguments] for the split.
    ///
    /// @return the row
    private LineInheritableTextField buildLaunchArgumentsRow() {
        LineInheritableTextField row = new LineInheritableTextField(i18n("dsh.settings.launch_args"));
        row.setSubtitle(i18n("dsh.settings.launch_args.hint"));

        List<String> own = instance.extraArguments();
        row.setOverridden(!own.isEmpty());
        row.setText(own.isEmpty()
                ? settings().defaultLaunchArguments() : String.join(" ", own));

        row.textProperty().addListener((observable, was, text) -> {
            if (row.isOverridden()) {
                // Shown as one line and stored as the arguments it names, so what runs is what was
                // typed rather than what a second parser made of it later.
                write(instance.withLaunchOptions(
                        org.jackhuang.hmcl.dsh.DshLaunchArguments.tokenize(text),
                        instance.environment()));
            }
        });
        row.overriddenProperty().addListener((observable, was, overridden) -> {
            if (overridden) {
                write(instance.withLaunchOptions(List.of(), instance.environment()));
                row.setText("");
            } else {
                write(instance.withLaunchOptions(List.of(), instance.environment()));
                row.setText(settings().defaultLaunchArguments());
            }
        });
        return row;
    }

    /// Builds the DSH_HOME policy row.
    ///
    /// A choice of this instance's own, with no mark beside it, for the same reason the runtime row
    /// has none: the launcher's default policy decides what a new instance is created with, and
    /// after that the instance has its own answer. Where that answer points is the next row.
    ///
    /// @return the row
    private LineSelectButton<DshHomeMode> buildHomeModeRow() {
        LineSelectButton<DshHomeMode> row = new LineSelectButton<>();
        row.setTitle(i18n("dsh.install.home"));
        row.setItems(DshHomeMode.ISOLATED, DshHomeMode.VERSION_SHARED, DshHomeMode.CUSTOM);
        row.setNullSafeConverter(mode -> i18n("dsh.instance.home."
                + mode.name().toLowerCase(Locale.ROOT)));
        // A policy stored as "follow the launcher" before this row lost its mark reads as the
        // launcher's default, which is the policy such an instance is actually running under.
        DshHomeMode stored = instance.homeMode();
        row.setValue(stored == DshHomeMode.GLOBAL
                ? settings().defaultHomeModeProperty().get() : stored);
        row.valueProperty().addListener((observable, was, mode) -> {
            if (mode == null || mode == was) {
                return;
            }
            if (mode == DshHomeMode.CUSTOM) {
                javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
                chooser.setTitle(i18n("dsh.install.home"));
                Path chosen = Controllers.showDialog(chooser);
                if (chosen == null) {
                    row.setValue(was);
                    return;
                }
                write(instance.withHome(DshHomeMode.CUSTOM, chosen));
            } else {
                write(instance.withHome(mode, null));
            }
        });
        return row;
    }

    /// Builds the instance icon row.
    ///
    /// HMCL's icon row is an `ImagePickerItem`: the image sits on the trailing
    /// edge with an edit button beside it and a reset button after that, rather
    /// than the whole row being clickable. The distinction matters because the
    /// row is a setting like any other — the controls say what can be done to
    /// it — and because resetting to the default has to be reachable without
    /// opening the chooser.
    ///
    /// @return the row
    private ImagePickerItem buildIconRow() {
        iconRow.setTitle(i18n("dsh.instance.icon"));
        iconRow.setImage(DshInstanceIcons.load(instance));
        iconRow.setOnSelectButtonClicked(event -> Controllers.dialog(
                new InstanceIconDialog(instance, this::reloadFromStorage)));
        iconRow.setOnDeleteButtonClicked(event -> resetIcon());
        return iconRow;
    }

    /// Re-reads the instance after a change made outside this pane.
    ///
    /// The icon chooser writes to storage itself, so the pane cannot rely on
    /// having produced the change and has to pick it up afterwards.
    private void reloadFromStorage() {
        DshInstance stored = DshInstanceManager.find(instance.id());
        if (stored != null) {
            instance = stored;
        }
        iconRow.setImage(DshInstanceIcons.load(instance));
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /// Restores the default icon.
    private void resetIcon() {
        write(instance.withIcon(DshInstanceIcon.DEFAULT).withNoIconFile());
    }

    /// Builds the automatic-versus-fixed choice.
    ///
    /// @return the row
    private LineSelectButton<DshPortMode> buildPortModeRow() {
        LineSelectButton<DshPortMode> row = new LineSelectButton<>();
        row.setTitle(i18n("dsh.instance.port.mode"));
        row.setItems(DshPortMode.AUTO, DshPortMode.FIXED);
        // A choice of this instance's own: there is no launcher-wide port policy for it to follow,
        // and a mark offering to follow one would be offering to follow nothing.
        row.setValue(instance.portModeOrDefault());
        // The control runs its converter the moment one is installed, before a
        // value need exist, so the null-safe form is required here.
        row.setNullSafeConverter(mode -> i18n("dsh.instance.port.mode." + mode.id()));
        row.valueProperty().addListener((observable, was, mode) -> {
            if (mode != null && mode != was) {
                write(withPortMode(mode));
            }
        });
        return row;
    }

    /// Returns the instance under a new port policy.
    ///
    /// An instance that has no port yet is given one here, so that choosing to
    /// name the port offers a number the instance can actually use instead of a
    /// zero. That is what makes the reserved port the starting point of the
    /// choice rather than something to look up elsewhere.
    ///
    /// @param mode the policy to record
    /// @return the instance to store
    private DshInstance withPortMode(DshPortMode mode) {
        if (instance.portOrDefault() > 0) {
            return instance.withPortPolicy(mode, instance.port());
        }
        try {
            return instance.withPortPolicy(mode, DshPorts.reserve(instance.id()));
        } catch (DshException e) {
            LOG.warning("Could not reserve a port for " + instance.id(), e);
            return instance.withPortPolicy(mode, instance.port());
        }
    }

    /// Builds the fixed-port entry.
    ///
    /// The field and the hint are drawn from the stored instance rather than
    /// decided once here, because the control is built before the user has
    /// chosen a mode and is never built again: deciding here is what left the
    /// field disabled after the mode had already been switched to a fixed port,
    /// with nothing to type the port into.
    ///
    /// @return the row
    private LineTextPane buildPortRow() {
        portRow.setTitle(i18n("dsh.instance.port.fixed"));

        portField.setPrefWidth(80);
        portField.setValidators(new NumberValidator(i18n("dsh.instance.port.invalid"), false));
        portField.setOnAction(event -> applyPort(portField.getText()));
        portField.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                applyPort(portField.getText());
            }
        });
        portRow.setRowTrailing(portField);

        syncPortRow();
        return portRow;
    }

    /// Draws the port row from the stored instance.
    ///
    /// Called after every write, so that switching the mode and naming a port
    /// take effect on the control the user is looking at.
    private void syncPortRow() {
        boolean fixed = instance.hasOwnPortMode() && instance.portMode() == DshPortMode.FIXED;

        // The port is always shown, whether or not it can be changed. It is settled when the
        // instance is created and stays the same for its whole life — a browser keys its stored
        // state by origin, so the port is part of the instance's identity — which makes it
        // something the person needs to be able to read at any time: to reach the interface
        // directly, to point another tool at it, or to see which of two instances is which.
        // Only its editability follows the mode.
        portField.setDisable(!fixed);
        portField.setText(instance.portOrDefault() > 0
                ? Integer.toString(instance.portOrDefault()) : "");
        portField.setPromptText(i18n("dsh.instance.port.auto.none"));
        portRow.setVisible(true);
        portRow.setManaged(true);
    }

    /// Writes a typed port back.
    ///
    /// @param text the field text
    private void applyPort(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Integer port;
        try {
            port = Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return;
        }
        String complaint = DshPorts.validate(port);
        if (complaint != null) {
            Controllers.dialog(complaint, i18n("dsh.instance.port.invalid"), MessageType.ERROR);
            return;
        }
        if (port == instance.portOrDefault()) {
            return;
        }
        write(instance.withPortPolicy(DshPortMode.FIXED, port));
    }

    /// Persists a modified instance and notifies the caller.
    ///
    /// @param updated the instance to store
    private void write(DshInstance updated) {
        try {
            DshInstanceManager.update(updated);
            // Re-read rather than trusting the copy passed in: the stored
            // instance is what the rest of the interface will show.
            DshInstance stored = DshInstanceManager.find(updated.id());
            if (stored != null) {
                instance = stored;
            }
            iconRow.setImage(DshInstanceIcons.load(instance));
            syncPortRow();
            if (onChanged != null) {
                onChanged.run();
            }
        } catch (DshException e) {
            LOG.warning("Failed to save instance settings", e);
            Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
        }
    }
}
