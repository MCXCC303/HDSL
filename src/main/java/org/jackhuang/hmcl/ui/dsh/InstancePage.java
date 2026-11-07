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
package org.jackhuang.hmcl.ui.dsh;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXPopup;
import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.instance.DshPorts;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.launch.DshInstanceTerminal;
import com.mcxcc303.hdsl.launch.DshProcess;
import com.mcxcc303.hdsl.launch.DshProcessManager;
import com.mcxcc303.hdsl.launch.DshProcessManager.LaunchState;
import com.mcxcc303.hdsl.plugin.DshInstalledPlugins;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.dsh.settings.InstanceSettingsPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Management page for an individual instance, hosting settings, installers, plugins, skills, and sessions.
 */
@NotNullByDefault
public final class InstancePage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {
	private DshInstance instance;
	private final ReadOnlyObjectWrapper<State> state;

	private final TabHeader tab;
	private final TabHeader.Tab<InstanceSettingsPage> settingsTab = new TabHeader.Tab<>("dshInstanceSettings");
	private final TabHeader.Tab<InstanceInstallersPage> installersTab = new TabHeader.Tab<>("dshInstanceInstallers");
	private final TabHeader.Tab<WorkspaceListPage> sessionsTab = new TabHeader.Tab<>("dshInstanceSessions");
	private final TabHeader.Tab<PluginListPage> pluginsTab = new TabHeader.Tab<>("dshInstancePlugins");
	private final TabHeader.Tab<SkillListPage> skillsTab = new TabHeader.Tab<>("dshInstanceSkills");

	private final TransitionPane transitionPane = new TransitionPane();
	private final AdvancedListItem launchItem = new AdvancedListItem();
	private final Timeline ticker;
	private final ComponentList pluginList = new ComponentList();
	private final Label pluginStatus = new Label();

	public InstancePage(DshInstance instance) {
		this(instance, null);
	}

	/**
	 * Creates the page for an instance.
	 *
	 * @param instance   the instance to show
	 * @param initialTab initial tab identifier, or {@code null} for settings
	 */
	public InstancePage(DshInstance instance, @Nullable String initialTab) {
		this.instance = instance;
		this.state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("instance.manage.manage.title", instance.id())));

		settingsTab.setNodeSupplier(() -> new InstanceSettingsPage(currentInstance(), this::refresh));
		installersTab.setNodeSupplier(() -> new InstanceInstallersPage(currentInstance()));
		sessionsTab.setNodeSupplier(() -> new WorkspaceListPage(currentInstance()));
		pluginsTab.setNodeSupplier(() -> new PluginListPage(currentInstance()));
		skillsTab.setNodeSupplier(() -> new SkillListPage(currentInstance()));

		tab = new TabHeader(transitionPane, settingsTab, installersTab, pluginsTab, skillsTab, sessionsTab);
		TabHeader.Tab<?> initial = switch (initialTab == null ? "" : initialTab.trim().toLowerCase(Locale.ROOT)) {
			case "installers" -> installersTab;
			case "plugins" -> pluginsTab;
			case "skills" -> skillsTab;
			case "sessions" -> sessionsTab;
			default -> settingsTab;
		};
		tab.select(initial, false);

		AdvancedListBox sideBar = new AdvancedListBox()
				.addNavigationDrawerTab(tab,
				                        settingsTab,
				                        i18n("instance.manage.manage"),
				                        SVG.SETTINGS,
				                        SVG.SETTINGS_FILL)
				.addNavigationDrawerTab(tab,
				                        installersTab,
				                        i18n("settings.tabs.installers"),
				                        SVG.DEPLOYED_CODE,
				                        SVG.DEPLOYED_CODE_FILL)
				.addNavigationDrawerTab(tab,
				                        pluginsTab,
				                        i18n("dsh.instance.plugins"),
				                        SVG.EXTENSION,
				                        SVG.EXTENSION_FILL)
				.addNavigationDrawerTab(tab,
				                        skillsTab,
				                        i18n("dsh.instance.skills"),
				                        SVG.TEXTURE)
				.addNavigationDrawerTab(tab,
				                        sessionsTab,
				                        i18n("dsh.instance.sessions"),
				                        SVG.PACKAGE2,
				                        SVG.PACKAGE2_FILL);

		launchItem.getStyleClass().add("navigation-drawer-item");
		launchItem.setOnAction(event -> {
			launchItem.requestFocus();
			testLaunch();
		});

		AdvancedListBox actions = new AdvancedListBox()
				.add(launchItem)
				.addNavigationDrawerItem(i18n("settings.game.exploration"),
				                         SVG.FOLDER_OPEN,
				                         null,
				                         item -> item.setOnAction(
						                         event -> showBrowsePopup(item))
				                        )
				.addNavigationDrawerItem(i18n("settings.game.management"),
				                         SVG.MENU,
				                         null,
				                         item -> item.setOnAction(
						                         event -> showManagePopup(item))
				                        );
		actions.getStyleClass().add("advanced-list-box-clear-padding");

		FXUtils.setLimitWidth(sideBar, 200);
		FXUtils.setLimitHeight(actions, 40 * 3 + 12 * 2);

		sideBar.setMaxHeight(Double.MAX_VALUE);
		VBox.setVgrow(sideBar, Priority.ALWAYS);

		setLeft(sideBar, actions);
		setCenter(transitionPane);

		ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshLaunchItem()));
		ticker.setCycleCount(Animation.INDEFINITE);
		ticker.play();

		refreshLaunchItem();
	}

	private static String actionLabel(LaunchState state) {
		return state == LaunchState.STOPPED ? i18n("dsh.instance.test_launch") : DshLaunchService.actionLabel(state);
	}

	private static Optional<String> launchToken(DshInstance instance) {
		return DshProcessManager.findRunning(instance).flatMap(DshProcessManager.Running::webUrl)
		                        .flatMap(DshPorts::launchToken);
	}

	private void refreshLaunchItem() {
		LaunchState state = DshLaunchService.state(instance.id());
		launchItem.setTitle(actionLabel(state));
		launchItem.setLeftIcon(state == LaunchState.STOPPED ? SVG.ROCKET_LAUNCH : SVG.CANCEL);
		FXUtils.installFastTooltip(launchItem, DshLaunchService.actionHint(state));
	}

	@Override
	public ReadOnlyObjectProperty<State> stateProperty() {
		return state.getReadOnlyProperty();
	}

	@Override
	public void onPageShown() {
		tab.onPageShown();
		refresh();
	}

	@Override
	public void onPageHidden() {
		tab.onPageHidden();
	}

	private DshInstance currentInstance() {
		DshInstance fresh = InstanceStore.find(instance.id());
		if (fresh != null) {
			this.instance = fresh;
		}
		return this.instance;
	}

	@Override
	public void refresh() {
		currentInstance();
		refreshPlugins();
		refreshLaunchItem();
	}

	private void showBrowsePopup(AdvancedListItem anchor) {
		PopupMenu menu = new PopupMenu();
		JFXPopup popup = new JFXPopup(menu);

		List<Node> entries = new ArrayList<>();
		entries.add(new IconedMenuItem(SVG.STADIA_CONTROLLER,
		                               i18n("dsh.instance.open_home"),
		                               () -> revealQuietly(instanceDirectoryOrNull()),
		                               popup));
		entries.add(new IconedMenuItem(SVG.FOLDER,
		                               i18n("dsh.instance.open_home.dsh"),
		                               () -> revealQuietly(homeChild("")),
		                               popup));
		entries.add(new IconedMenuItem(SVG.FOLDER_OPEN,
		                               i18n("dsh.instance.open_workspace"),
		                               () -> revealQuietly(currentInstance().workspacePath()),
		                               popup));
		entries.add(new IconedMenuItem(SVG.FOLDER_COPY,
		                               i18n("dsh.instance.open.sessions"),
		                               () -> revealQuietly(homeChild("sessions")),
		                               popup));
		entries.add(new IconedMenuItem(SVG.EXTENSION,
		                               i18n("dsh.instance.open.profiles"),
		                               () -> revealQuietly(homeChild("profiles")),
		                               popup));
		entries.add(new IconedMenuItem(SVG.ARCHIVE,
		                               i18n("dsh.instance.open.storages"),
		                               () -> revealQuietly(homeChild("storages")),
		                               popup));
		menu.getContent().setAll(entries);

		popup.show(anchor, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.LEFT, anchor.getWidth(), 0);
	}

	private void exportModpack() {
		Controllers.getDecorator().startWizard(new ModpackExportWizardProvider(currentInstance()), i18n("modpack.wizard"));
	}

	private void showManagePopup(AdvancedListItem anchor) {
		PopupMenu menu = new PopupMenu();
		JFXPopup popup = new JFXPopup(menu);

		List<Node> entries = new ArrayList<>();
		LaunchState state = DshLaunchService.state(instance.id());
		entries.add(new IconedMenuItem(state == LaunchState.STOPPED ? SVG.ROCKET_LAUNCH : SVG.CANCEL,
		                               actionLabel(state),
		                               this::testLaunch,
		                               popup));
		entries.add(new IconedMenuItem(SVG.PUBLIC,
		                               i18n("dsh.instance.open_browser"),
		                               () -> InstanceBrowser.open(currentInstance()),
		                               popup));
		IconedMenuItem copyToken = new IconedMenuItem(SVG.CONTENT_COPY,
		                                              i18n("dsh.instance.copy_token"),
		                                              this::copyLaunchToken,
		                                              popup);
		copyToken.setDisable(launchToken(currentInstance()).isEmpty());
		entries.add(copyToken);
		entries.add(new IconedMenuItem(SVG.SCRIPT, i18n("dsh.instance.open_logs"), this::openLogs, popup));
		entries.add(new IconedMenuItem(SVG.OUTPUT, i18n("dsh.instance.terminal"), this::openTerminal, popup));
		entries.add(new MenuSeparator());
		entries.add(new IconedMenuItem(SVG.PACKAGE2, i18n("modpack.export"), this::exportModpack, popup));
		entries.add(new IconedMenuItem(SVG.EDIT, i18n("instance.manage.rename"), this::renameInstance, popup));
		entries.add(new IconedMenuItem(SVG.FOLDER_COPY,
		                               i18n("instance.manage.duplicate"),
		                               this::duplicateInstance,
		                               popup));
		entries.add(new IconedMenuItem(SVG.DELETE, i18n("instance.manage.remove"), this::removeInstance, popup));
		menu.getContent().setAll(entries);

		popup.show(anchor, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.LEFT, anchor.getWidth(), 0);
	}

	private @Nullable Path instanceDirectoryOrNull() {
		try {
			return currentInstance().instanceDirectory();
		} catch (DshException e) {
			return null;
		}
	}

	private @Nullable Path homeChild(String name) {
		try {
			Path home = currentInstance().homeDirectory();
			return name.isEmpty() ? home : home.resolve(name);
		} catch (DshException e) {
			return null;
		}
	}

	private void revealQuietly(@Nullable Path directory) {
		if (directory == null || !Files.isDirectory(directory)) {
			Controllers.dialog(i18n("dsh.instance.open.unavailable"), i18n("message.error"), MessageType.ERROR);
			return;
		}
		FXUtils.showFileInExplorer(directory);
	}

	private void openLogs() {
		DshProcess process = DshProcessManager.find(instance.id()).orElse(null);
		if (process != null) {
			DshLaunchService.openLogWindow(process);
			return;
		}
		Path logs = homeChild("logs");
		if (logs == null || !Files.isDirectory(logs)) {
			Controllers.dialog(i18n("dsh.instance.logs.none"), i18n("dsh.instance.open_logs"), MessageType.WARNING);
			return;
		}
		FXUtils.showFileInExplorer(logs);
	}

	private void renameInstance() {
		Controllers.dialog(new InputDialogPane(i18n("instance.manage.rename"), instance.id(), (newId, handler) -> {
			try {
				DshInstanceManager.rename(instance.id(), newId);
				handler.resolve();
				Controllers.navigate(Controllers.getInstancesPage());
			} catch (DshException e) {
				handler.reject(e.getMessage());
			}
		}));
	}

	/**
	 * Copies this instance into a newly created instance.
	 * <p>
	 * The duplication serializes and re-imports the instance via pack composition
	 * (version, profile, plugin list, patch layer, local plugins, settings, and skills).
	 * Operations run asynchronously inside a {@link ProgressDialog} to keep the UI responsive.
	 */
	private void duplicateInstance() {
		String newId = DshInstanceManager.nextId(instance.id());
		PluginInstalls.runCreating(i18n("dsh.instance.duplicating", instance.id()),
		                           () -> InstanceStore.find(newId),
		                           report -> DshInstanceManager.duplicate(instance.id(), newId, report::accept),
		                           null);
	}

	/**
	 * Opens a native terminal on this instance configured with its execution environment and toolchains.
	 * Provides a fallback diagnostic channel for harnesses that fail to start from the UI.
	 */
	private void openTerminal() {
		try {
			DshInstance target = currentInstance();
			DshInstanceTerminal.open(target, DshAccount.forInstance(target));
		} catch (DshException e) {
			LOG.warning("Could not open a terminal on " + instance.id(), e);
			Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
		}
	}

	/**
	 * Starts or stops this instance in test mode.
	 * Unlike normal background launch, test launch surfaces the log window so output is visible immediately.
	 */
	private void testLaunch() {
		DshLaunchService.toggle(currentInstance(), this::refresh, true);
	}

	/**
	 * Copies the running harness's web authentication token to the system clipboard.
	 * Surfaces a warning if the process stopped before the token could be retrieved.
	 */
	private void copyLaunchToken() {
		Optional<String> token = launchToken(currentInstance());
		if (token.isEmpty()) {
			Controllers.showToast(i18n("dsh.instance.token_missing"));
			return;
		}
		FXUtils.copyText(token.get(), i18n("dsh.instance.token_copied"));
	}

	/**
	 * Deletes this instance after confirmation, navigating away upon completion.
	 * <p>
	 * Removal runs in a background {@link ProgressDialog}: active child processes
	 * must be terminated and drained, and directory trees are deleted with retry loops.
	 * Running this synchronously on the JavaFX Application Thread would freeze UI repainting
	 * and trigger OS "Not Responding" alerts.
	 */
	private void removeInstance() {
		String id = instance.id();
		Controllers.confirm(i18n("dsh.instance.remove.confirm", id), i18n("dsh.instance.remove"), () -> {
			AtomicBoolean failed = new AtomicBoolean();
			ProgressDialog.run(i18n("dsh.instance.remove"), progress -> {
				progress.accept(i18n("dsh.instance.removing", id));
				DshInstanceManager.delete(id);
			}, () -> {
				if (!failed.get()) {
					Controllers.navigate(Controllers.getInstancesPage());
				}
			}, failure -> {
				failed.set(true);
				return false;
			});
		}, null);
	}

	/**
	 * Rebuilds the plugin list from the profile manifest on disk.
	 * Displays user-installed packages, excluding internal core bundle packages.
	 */
	private void refreshPlugins() {
		pluginList.getContent().clear();

		List<DshInstalledPlugins.Installed> plugins;
		try {
			plugins = DshInstalledPlugins.of(currentInstance());
		} catch (DshException e) {
			pluginStatus.setText(e.getMessage());
			return;
		}

		int shown = 0;
		for (DshInstalledPlugins.Installed plugin : plugins) {
			pluginList.getContent().add(buildPluginRow(plugin));
			shown++;
		}
		if (shown == 0) {
			LineTextPane note = new LineTextPane();
			note.setText(i18n("dsh.instance.plugins.empty"));
			pluginList.getContent().add(note);
		}

		pluginStatus.setText(i18n("dsh.instance.plugins.count", shown));
	}

	private LineButton buildPluginRow(DshInstalledPlugins.Installed plugin) {
		boolean bundle = plugin.state() == DshInstalledPlugins.State.ACTIVE ||
		                 plugin.state() == DshInstalledPlugins.State.DISABLED;

		JFXButton remove = FXUtils.newToggleButton4(SVG.DELETE, 18);
		remove.setOnAction(event -> removePlugin(plugin.name()));

		LineButton row = new LineButton();
		row.setTitle(plugin.displayName());
		row.setSubtitle(bundle
		                ? i18n("dsh.instance.plugins.active", plugin.declared())
		                : i18n("dsh.instance.plugins.inactive", plugin.declared()));
		row.setRowTrailing(remove);
		return row;
	}

	private void removePlugin(String name) {
		Controllers.confirm(i18n("dsh.instance.plugins.remove.confirm", name),
		                    i18n("dsh.instance.plugins.remove"),
		                    () -> CompletableFuture.runAsync(() -> {
			                    try {
				                    DshPluginInstaller.remove(currentInstance(), name, null);
			                    } catch (DshException e) {
				                    throw new CompletionException(e);
			                    }
		                    }, Schedulers.io()).whenComplete((ignored, throwable) -> runInFX(() -> {
			                    if (throwable != null) {
				                    Throwable cause =
						                    throwable instanceof CompletionException && throwable.getCause() != null
						                    ? throwable.getCause()
						                    : throwable;
				                    LOG.warning("Failed to remove " + name, cause);
				                    Controllers.dialog(cause.getMessage(),
				                                       i18n("dsh.instance.plugins.remove_failed"),
				                                       MessageType.ERROR);
			                    } else {
				                    Controllers.showToast(i18n("dsh.instance.plugins.removed", name));
			                    }
			                    refreshPlugins();
		                    })),
		                    null);
	}
}
