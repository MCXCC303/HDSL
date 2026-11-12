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
package org.jackhuang.hmcl.ui.dsh.settings;

import com.jfoenix.controls.JFXTextField;
import com.mcxcc303.hdsl.core.DshFileManager;
import com.mcxcc303.hdsl.core.DshPaths;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshIsolationPolicy;
import com.mcxcc303.hdsl.launch.DshEnvironment;
import com.mcxcc303.hdsl.launch.DshLauncherVisibility;
import com.mcxcc303.hdsl.plugin.DshBuildScriptPolicy;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

@NotNullByDefault
public final class InstanceDefaultsPage extends ScrollPane {
	public InstanceDefaultsPage() {
		setFitToWidth(true);
		setFitToHeight(true);

		VBox root = new VBox(ComponentList.createComponentListTitle(i18n("dsh.settings.environment")),
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

		// Smooth scrolling binds to the content node; run this after installing the content.
		FXUtils.smoothScrolling(this);
	}

	private static Path logFolder() {
		Path logs = DshPaths.LOGS;
		try {
			Files.createDirectories(logs);
		} catch (IOException e) {
			Logger.LOG.warning("Could not create the log folder " + logs, e);
		}
		return logs;
	}

	private ComponentList buildEnvironmentList() {
		ComponentList list = new ComponentList();

		LineSelectButton<DshHomeMode> home = new LineSelectButton<>();
		home.setTitle(i18n("dsh.settings.default_home"));
		LineSelectButton<DshIsolationPolicy> policy = new LineSelectButton<>();
		policy.setTitle(i18n("dsh.settings.isolation"));
		policy.setItems(DshIsolationPolicy.values());
		policy.setConverter(choice -> choice == null ? "" : i18n("dsh.settings.isolation." + choice.id()));
		policy.setValue(settings().isolationPolicy());
		policy.valueProperty().addListener((observable, was, value) -> {
			if (value != null) settings().isolationPolicyProperty().set(value);
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

		LinePane arguments = new LinePane();
		arguments.setTitle(i18n("dsh.settings.default_launch_args"));

		JFXTextField field = new JFXTextField();
		field.setMinWidth(420);
		field.setText(settings().defaultLaunchArguments());
		field.textProperty()
		     .addListener((observable, was, text) -> settings().defaultLaunchArgumentsProperty()
		                                                       .set(text == null ? "" : text));
		arguments.setRight(field);
		list.getContent().add(arguments);

		return list;
	}

	private ComponentList buildBuildScriptsList() {
		LineSelectButton<DshBuildScriptPolicy> approve = new LineSelectButton<>();
		approve.setTitle(i18n("dsh.settings.build_scripts.approve"));
		approve.setItems(List.of(DshBuildScriptPolicy.AUTO, DshBuildScriptPolicy.MANUAL, DshBuildScriptPolicy.NEVER));
		approve.setConverter(policy -> policy == null ? "" : i18n("dsh.settings.build_scripts." + policy.id()));
		approve.setValue(settings().buildScriptPolicy());
		approve.valueProperty().addListener((observable, was, value) -> {
			if (value != null) settings().buildScriptPolicyProperty().set(value);
		});

		ComponentList list = new ComponentList();
		list.getContent().add(approve);
		return list;
	}

	private ComponentList buildLauncherList() {
		LineSelectButton<DshLauncherVisibility> visibility = new LineSelectButton<>();
		visibility.setTitle(i18n("dsh.settings.launcher.visibility"));
		visibility.setItems(DshLauncherVisibility.values());
		visibility.setConverter(choice -> choice == null
		                                  ? ""
		                                  : i18n("dsh.settings.launcher.visibility." + choice.id()));
		visibility.setValue(settings().launcherVisibilityProperty().get());
		visibility.valueProperty().addListener((observable, was, value) -> {
			if (value != null) settings().launcherVisibilityProperty().set(value);
		});

		LineSwitch showLogs = new LineSwitch();
		showLogs.setTitle(i18n("dsh.settings.launcher.show_logs"));
		showLogs.selectedProperty().bindBidirectional(settings().showLogsProperty());

		ComponentList list = new ComponentList();
		list.getContent().add(visibility);
		list.getContent().add(showLogs);
		return list;
	}

	private ComponentList buildCommandsList() {
		ComponentList list = new ComponentList();
		list.getContent().add(commandRow(i18n("dsh.settings.commands.pre"), settings().preLaunchCommandProperty()));
		list.getContent().add(commandRow(i18n("dsh.settings.commands.post"), settings().postExitCommandProperty()));
		return list;
	}

	private Node commandRow(String title, StringProperty property) {
		LinePane pane = new LinePane();
		pane.setTitle(title);

		JFXTextField field = new JFXTextField();
		field.setMinWidth(420);
		field.textProperty().bindBidirectional(property);
		pane.setRight(field);
		return pane;
	}

	private ComponentList buildDebugList() {
		LineSwitch debug = new LineSwitch();
		debug.setTitle(i18n("dsh.settings.debug.log"));
		debug.setSubtitle(i18n("dsh.settings.debug.log.hint"));
		debug.selectedProperty().bindBidirectional(settings().debugLogProperty());
		debug.selectedProperty()
		     .addListener((observable, was, value) -> Logger.setDebugEnabled(Boolean.TRUE.equals(value)));

		LineButton folder = new LineButton();
		folder.setTitle(i18n("settings.launcher.launcher_log.reveal"));
		folder.setSubtitle(DshPaths.LOGS.toString());
		folder.setLeading(SVG.FOLDER_OPEN, 16);
		folder.setOnAction(event -> DshFileManager.openDirectory(logFolder()));

		ComponentList list = new ComponentList();
		list.getContent().add(debug);
		list.getContent().add(folder);
		return list;
	}

	private ComponentList buildEnvironmentVariablesList() {
		LinePane pane = new LinePane();
		pane.setTitle(i18n("dsh.settings.env_vars"));

		JFXTextField field = new JFXTextField();
		field.setMinWidth(420);
		field.setText(DshEnvironment.format(settings().globalEnvironment()));
		field.textProperty()
		     .addListener((observable, was, text) -> settings().globalEnvironmentProperty()
		                                                       .set(DshEnvironment.parse(text)));
		pane.setRight(field);

		ComponentList list = new ComponentList();
		list.getContent().add(pane);
		return list;
	}
}
