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
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.*;
import com.mcxcc303.hdsl.launch.DshEnvironment;
import com.mcxcc303.hdsl.launch.DshLaunchArguments;
import com.mcxcc303.hdsl.launch.DshLauncherVisibility;
import com.mcxcc303.hdsl.plugin.DshBuildScriptPolicy;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import com.mcxcc303.hdsl.runtime.NodeRuntime;
import com.mcxcc303.hdsl.runtime.NodeRuntimeManager;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.dsh.InstanceIconDialog;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Settings tab of an instance's management page.
 */
@NotNullByDefault
public final class InstanceSettingsPage extends ScrollPane {

	private final ImagePickerItem iconRow = new ImagePickerItem();
	private final LineTextPane portRow = new LineTextPane();
	private final JFXTextField portField = new JFXTextField();
	private final Runnable onChanged;
	private DshInstance instance;

	/**
	 * Creates the settings tab.
	 *
	 * @param instance  the instance being edited
	 * @param onChanged run after a successful write
	 */
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
		environmentList.getContent().add(buildHomeModeRow());

		ComponentList portList = new ComponentList();
		portList.getContent().add(buildPortModeRow());
		portList.getContent().add(buildPortRow());

		VBox root = new VBox(ComponentList.createComponentListTitle(i18n("dsh.instance.icon")),
		                     iconList,
		                     ComponentList.createComponentListTitle(i18n("dsh.settings.environment")),
		                     environmentList,
		                     ComponentList.createComponentListTitle(i18n("dsh.instance.port")),
		                     portList,
		                     ComponentList.createComponentListTitle(i18n("dsh.settings.build_scripts")),
		                     buildScriptsList(),
		                     ComponentList.createComponentListTitle(i18n("dsh.settings.env_vars")),
		                     buildEnvironmentVariablesList(),
		                     ComponentList.createComponentListTitle(i18n("dsh.settings.debug")),
		                     buildDebugList(),
		                     ComponentList.createComponentListTitle(i18n("dsh.settings.commands")),
		                     buildCommandsList());
		root.getStyleClass().add("card-list");
		setContent(root);

		FXUtils.smoothScrolling(this);
	}

	private static void rememberEnvironment(DshInstance instance, boolean own) {
		try {
			DshInstanceSettings.setEnvironmentIsOwn(instance, own);
		} catch (DshException e) {
			LOG.warning("Failed to record whether the instance has its own environment", e);
		}
	}

	private ComponentList buildCommandsList() {
		ComponentList list = new ComponentList();
		list.getContent().add(commandRow(i18n("dsh.settings.commands.pre"), true));
		list.getContent().add(commandRow(i18n("dsh.settings.commands.post"), false));
		return list;
	}

	private LineInheritableTextField commandRow(String title, boolean before) {
		String own = before
		             ? DshInstanceSettings.preLaunchCommand(instance)
		             : DshInstanceSettings.postExitCommand(instance);
		String launcher = before
		                  ? settings().preLaunchCommandProperty().get()
		                  : settings().postExitCommandProperty().get();

		LineInheritableTextField row = new LineInheritableTextField(title);
		row.setOverridden(own != null);
		row.setText(own != null ? own : launcher);

		ChangeListener<String> store = (observable, was, value) -> {
			try {
				if (before) {
					DshInstanceSettings.setPreLaunchCommand(instance, row.isOverridden() ? value : null);
				} else {
					DshInstanceSettings.setPostExitCommand(instance, row.isOverridden() ? value : null);
				}
			} catch (DshException e) {
				LOG.warning("Failed to store the command", e);
			}
		};
		row.textProperty().addListener(store);
		row.overriddenProperty().addListener((observable, was, overridden) -> {
			if (!overridden) {
				row.setText(settings().preLaunchCommandProperty().get() == null ? "" : launcher);
				store.changed(null, null, null);
			}
		});
		return row;
	}

	private ComponentList buildDebugList() {
		ComponentList list = new ComponentList();
		list.getContent().add(launcherVisibilityRow());
		list.getContent().add(debugLogRow());
		list.getContent().add(logRow());
		return list;
	}

	private LineInheritableSelectButton<DshLauncherVisibility> launcherVisibilityRow() {
		LineInheritableSelectButton<DshLauncherVisibility> row = new LineInheritableSelectButton<>();
		row.setTitle(i18n("dsh.settings.launcher.visibility"));
		row.setItems(List.of(DshLauncherVisibility.values()));
		row.setNullSafeConverter(choice -> choice == null
		                                   ? ""
		                                   : i18n("dsh.settings.launcher.visibility." + choice.id()));

		String own = DshInstanceSettings.launcherVisibility(instance);
		row.setOverridden(own != null);
		row.setValue(own != null ? DshLauncherVisibility.of(own) : settings().launcherVisibilityFor(instance.id()));

		ChangeListener<Object> store = (observable, was, value) -> {
			try {
				DshInstanceSettings.setLauncherVisibility(instance,
				                                          row.overriddenProperty().get() && row.getValue() != null
				                                          ? row.getValue().id()
				                                          : null);
			} catch (DshException e) {
				LOG.warning("Failed to store the launcher visibility", e);
			}
		};
		row.overriddenProperty().addListener(store);
		row.valueProperty().addListener(store);
		return row;
	}

	private LineInheritableSwitch debugLogRow() {
		LineInheritableSwitch row = new LineInheritableSwitch();
		row.setTitle(i18n("dsh.settings.debug.log"));

		Boolean own = DshInstanceSettings.debugLog(instance);
		row.overriddenProperty().set(own != null);
		row.rawValueProperty().set(own != null ? own : settings().debugLogProperty().get());

		ChangeListener<Boolean> store = (observable, was, value) -> {
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

	private LineInheritableSwitch logRow() {
		LineInheritableSwitch row = new LineInheritableSwitch();
		row.setTitle(i18n("dsh.settings.launcher.show_logs"));

		Boolean own = DshInstanceSettings.showLogs(instance);
		row.overriddenProperty().set(own != null);
		row.rawValueProperty().set(own != null ? own : settings().showLogsProperty().get());

		ChangeListener<Boolean> store = (observable, was, value) -> {
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

	private ComponentList buildEnvironmentVariablesList() {
		LineInheritableTextField row = new LineInheritableTextField(i18n("dsh.settings.env_vars"));

		boolean own = DshInstanceSettings.environmentIsOwn(instance);
		row.setOverridden(own);
		row.setText(DshEnvironment.format(own ? instance.environment() : settings().globalEnvironment()));

		row.textProperty().addListener((observable, was, text) -> {
			if (row.isOverridden()) {
				write(instance.withEnvironment(DshEnvironment.parse(text)));
				rememberEnvironment(instance, true);
			}
		});
		row.overriddenProperty().addListener((observable, was, overridden) -> {
			if (overridden) {
				write(instance.withEnvironment(Map.of()));
				rememberEnvironment(instance, true);
				row.setText("");
			} else {
				write(instance.withEnvironment(null));
				rememberEnvironment(instance, false);
				row.setText(DshEnvironment.format(settings().globalEnvironment()));
			}
		});

		ComponentList list = new ComponentList();
		list.getContent().add(row);
		return list;
	}

	private ComponentList buildScriptsList() {
		List<DshBuildScriptPolicy> choices = new ArrayList<>();
		choices.addAll(List.of(DshBuildScriptPolicy.AUTO, DshBuildScriptPolicy.MANUAL, DshBuildScriptPolicy.NEVER));

		LineInheritableSelectButton<DshBuildScriptPolicy> row = new LineInheritableSelectButton<>();
		row.setTitle(i18n("dsh.settings.build_scripts.approve"));
		row.setItems(choices);
		row.setNullSafeConverter(policy -> i18n("dsh.settings.build_scripts." + policy.id()));

		String own = DshInstanceSettings.buildScriptPolicy(instance);
		row.setOverridden(own != null);
		row.setValue(own != null ? DshBuildScriptPolicy.of(own) : settings().buildScriptPolicy());

		Consumer<DshBuildScriptPolicy> store = policy -> {
			try {
				DshInstanceSettings.setBuildScriptPolicy(instance, policy == null ? null : policy.id());
			} catch (DshException e) {
				LOG.warning("Failed to store the build script policy", e);
			}
		};
		row.valueProperty().addListener((observable, was, value) -> {
			if (row.isOverridden()) {
				store.accept(value);
			}
		});
		row.overriddenProperty()
		   .addListener((observable, was, isOverridden) -> store.accept(isOverridden ? row.getValue() : null));

		ComponentList list = new ComponentList();
		list.getContent().add(row);
		return list;
	}

	private LineInheritableSelectButton<String> buildNodeRuntimeRow() {
		List<String> choices = new ArrayList<>();
		choices.add(DshNodeRuntime.SYSTEM);
		for (NodeRuntime runtime : NodeRuntimeManager.listInstalled()) {
			choices.add(runtime.version());
		}

		LineInheritableSelectButton<String> row = new LineInheritableSelectButton<>();
		row.setTitle(i18n("dsh.node.title"));
		row.setItems(choices);
		row.setNullSafeConverter(selection -> DshNodeRuntime.SYSTEM.equals(selection)
		                                      ? i18n("dsh.install.node.system")
		                                      : selection);

		String own = instance.nodeRuntime();
		boolean overridden = own != null && !DshNodeRuntime.GLOBAL.equals(own);
		row.setOverridden(overridden);
		row.setValue(overridden ? own : settings().defaultNodeRuntimeProperty().get());

		row.valueProperty().addListener((observable, was, value) -> {
			if (value != null && !value.equals(was) && row.isOverridden()) {
				write(instance.withNodeRuntime(value));
			}
		});
		row.overriddenProperty()
		   .addListener((observable, was, isOverridden) -> write(instance.withNodeRuntime(isOverridden
		                                                                                  ? row.getValue()
		                                                                                  : DshNodeRuntime.GLOBAL)));
		return row;
	}

	private LineInheritableTextField buildLaunchArgumentsRow() {
		LineInheritableTextField row = new LineInheritableTextField(i18n("dsh.settings.launch_args"));

		List<String> own = instance.extraArguments();
		row.setOverridden(!own.isEmpty());
		row.setText(own.isEmpty() ? settings().defaultLaunchArguments() : String.join(" ", own));

		row.textProperty().addListener((observable, was, text) -> {
			if (row.isOverridden()) {
				write(instance.withLaunchOptions(DshLaunchArguments.tokenize(text), instance.environment()));
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

	private LineInheritableSelectButton<DshHomeMode> buildHomeModeRow() {
		LineInheritableSelectButton<DshHomeMode> row = new LineInheritableSelectButton<>();
		row.setTitle(i18n("dsh.install.home"));
		row.setItems(DshHomeMode.ISOLATED, DshHomeMode.VERSION_SHARED, DshHomeMode.CUSTOM);
		row.setNullSafeConverter(mode -> i18n("dsh.instance.home." + mode.name().toLowerCase(Locale.ROOT)));
		DshHomeMode stored = instance.homeMode();
		boolean overridden = stored != null && stored != DshHomeMode.GLOBAL;
		row.setOverridden(overridden);
		row.setValue(overridden ? stored : settings().defaultHomeModeProperty().get());
		row.overriddenProperty().addListener((observable, was, isOverridden) -> {
			if (!isOverridden) {
				write(instance.withHome(DshHomeMode.GLOBAL, null));
			}
		});
		row.valueProperty().addListener((observable, was, mode) -> {
			if (mode == null || mode == was || !row.isOverridden()) {
				return;
			}
			if (mode == DshHomeMode.CUSTOM) {
				DirectoryChooser chooser = new DirectoryChooser();
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

	private ImagePickerItem buildIconRow() {
		iconRow.setTitle(i18n("dsh.instance.icon"));
		iconRow.setImage(DshInstanceIcons.load(instance));
		iconRow.setOnSelectButtonClicked(event -> Controllers.dialog(new InstanceIconDialog(instance,
		                                                                                    this::reloadFromStorage)));
		iconRow.setOnDeleteButtonClicked(event -> resetIcon());
		return iconRow;
	}

	private void reloadFromStorage() {
		DshInstance stored = InstanceStore.find(instance.id());
		if (stored != null) {
			instance = stored;
		}
		iconRow.setImage(DshInstanceIcons.load(instance));
		if (onChanged != null) {
			onChanged.run();
		}
	}

	private void resetIcon() {
		write(instance.withIcon(DshInstanceIcon.DEFAULT).withNoIconFile());
	}

	private LineSelectButton<DshPortMode> buildPortModeRow() {
		LineSelectButton<DshPortMode> row = new LineSelectButton<>();
		row.setTitle(i18n("dsh.instance.port.mode"));
		row.setItems(DshPortMode.AUTO, DshPortMode.FIXED);
		row.setValue(instance.portModeOrDefault());
		row.setNullSafeConverter(mode -> i18n("dsh.instance.port.mode." + mode.id()));
		row.valueProperty().addListener((observable, was, mode) -> {
			if (mode != null && mode != was) {
				write(withPortMode(mode));
			}
		});
		return row;
	}

	private DshInstance withPortMode(DshPortMode mode) {
		try {
			return DshPorts.withMode(instance, mode);
		} catch (DshException e) {
			LOG.warning("Could not choose a port for " + instance.id(), e);
			return instance.withPortPolicy(mode, instance.port());
		}
	}

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

	private void syncPortRow() {
		boolean fixed = instance.hasOwnPortMode() && instance.portMode() == DshPortMode.FIXED;
		portField.setDisable(!fixed);
		portField.setText(instance.portOrDefault() > 0 ? Integer.toString(instance.portOrDefault()) : "");
		portField.setPromptText(i18n("dsh.instance.port.auto.none"));
		portRow.setVisible(true);
		portRow.setManaged(true);
	}

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

	private void write(DshInstance updated) {
		try {
			DshInstanceManager.update(updated);
			DshInstance stored = InstanceStore.find(updated.id());
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
