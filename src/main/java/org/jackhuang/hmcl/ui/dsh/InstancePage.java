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
import com.mcxcc303.hdsl.core.DshFileManager;
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
 * Shows one instance and manages what is installed into its profile.
 */
@NotNullByDefault
public final class InstancePage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {
	private final DshInstance instance;

	// The page state published to the window decorator.
	private final ReadOnlyObjectWrapper<State> state;

	private final TabHeader tab;
	private final TabHeader.Tab<InstanceSettingsPage> settingsTab = new TabHeader.Tab<>("dshInstanceSettings");
	private final TabHeader.Tab<InstanceInstallersPage> installersTab = new TabHeader.Tab<>("dshInstanceInstallers");
	private final TabHeader.Tab<WorkspaceListPage> sessionsTab = new TabHeader.Tab<>("dshInstanceSessions");
	private final TabHeader.Tab<PluginListPage> pluginsTab = new TabHeader.Tab<>("dshInstancePlugins");
	private final TabHeader.Tab<SkillListPage> skillsTab = new TabHeader.Tab<>("dshInstanceSkills");

	// The animated content pane shared by the tabs.
	private final TransitionPane transitionPane = new TransitionPane();

	// 'Test Launch' item (with stop/stopping status)
	private final AdvancedListItem launchItem = new AdvancedListItem();

	// Loading animation
	private final Timeline ticker;

	// rebuilt on every refresh
	private final ComponentList pluginList = new ComponentList();
	private final Label pluginStatus = new Label();

	public InstancePage(DshInstance instance) {
		this(instance, null);
	}

	/**
	 * Creates the page for an instance.
	 *
	 * @param instance   the instance to show
	 * @param initialTab the tab to open: `settings`, `installers`, `plugins`,
	 *                   `skills`, `sessions` or `details`, or `null` for the first
	 */
	public InstancePage(DshInstance instance, @Nullable String initialTab) {
		this.instance = instance;
		this.state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("instance.manage.manage.title", instance.id())));

		settingsTab.setNodeSupplier(() -> new InstanceSettingsPage(instance, this::refresh));
		installersTab.setNodeSupplier(() -> new InstanceInstallersPage(instance));
		sessionsTab.setNodeSupplier(() -> new WorkspaceListPage(instance));
		pluginsTab.setNodeSupplier(() -> new PluginListPage(instance));
		skillsTab.setNodeSupplier(() -> new SkillListPage(instance));

		// keep this tab
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
		launchItem.setOnAction(event -> testLaunch());

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

	/// Names the action this page offers on the instance.
	///
	/// A stopped instance is started from here as a test, which is what the
	/// original's own instance page calls it; a running one is stopped, and that
	/// is the only thing the entry may be called then.
	///
	/// @param state the instance's state
	/// @return the label
	private static String actionLabel(LaunchState state) {
		return state == LaunchState.STOPPED ? i18n("dsh.instance.test_launch") : DshLaunchService.actionLabel(state);
	}

	/// Returns the key the instance's harness is asking for, when it is up and printed one.
	///
	/// @param instance the instance
	/// @return the key, or empty when the instance is not running or its harness printed none
	private static Optional<String> launchToken(DshInstance instance) {
		return DshProcessManager.findRunning(instance).flatMap(DshProcessManager.Running::webUrl)
		                        .flatMap(DshPorts::launchToken);
	}

	/// Draws the sidebar entry from the instance's state.
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

	@Override
	public void refresh() {
		refreshPlugins();
		refreshLaunchItem();
	}

	/// Shows the folders this instance owns.
	///
	/// The original puts these in a popup on the sidebar rather than on a tab:
	/// they are places to go, not a view to read, and a popup says so.
	///
	/// @param anchor the sidebar item the popup is anchored to
	private void showBrowsePopup(AdvancedListItem anchor) {
		PopupMenu menu = new PopupMenu();
		JFXPopup popup = new JFXPopup(menu);

		List<Node> entries = new ArrayList<>();
		entries.add(new IconedMenuItem(SVG.STADIA_CONTROLLER,
		                               i18n("dsh.instance.open_home"),
		                               () -> openDirectoryQuietly(instanceDirectoryOrNull()),
		                               popup));
		entries.add(new IconedMenuItem(SVG.FOLDER,
		                               i18n("dsh.instance.open_home.dsh"),
		                               () -> openDirectoryQuietly(homeChild("")),
		                               popup));
		entries.add(new IconedMenuItem(SVG.FOLDER_OPEN,
		                               i18n("dsh.instance.open_workspace"),
		                               () -> openDirectoryQuietly(instance.workspacePath()),
		                               popup));
		entries.add(new IconedMenuItem(SVG.FOLDER_COPY,
		                               i18n("dsh.instance.open.sessions"),
		                               () -> openDirectoryQuietly(homeChild("sessions")),
		                               popup));
		entries.add(new IconedMenuItem(SVG.EXTENSION,
		                               i18n("dsh.instance.open.profiles"),
		                               () -> openDirectoryQuietly(homeChild("profiles")),
		                               popup));
		entries.add(new IconedMenuItem(SVG.ARCHIVE,
		                               i18n("dsh.instance.open.storages"),
		                               () -> openDirectoryQuietly(homeChild("storages")),
		                               popup));
		menu.getContent().setAll(entries);

		popup.show(anchor, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.LEFT, anchor.getWidth(), 0);
	}

	/// Writes this instance into a pack, through the original's wizard.
	///
	/// The wizard asks what kind of pack, what it says about itself and what of the
	/// instance goes into it, which is the order the original asks in — and writing
	/// it is what the last step does.
	private void exportModpack() {
		Controllers.getDecorator().startWizard(new ModpackExportWizardProvider(instance), i18n("modpack.wizard"));
	}

	/// Shows the operations available on this instance.
	///
	/// @param anchor the sidebar item the popup is anchored to
	private void showManagePopup(AdvancedListItem anchor) {
		PopupMenu menu = new PopupMenu();
		JFXPopup popup = new JFXPopup(menu);

		List<Node> entries = new ArrayList<>();
		LaunchState state = DshLaunchService.state(instance.id());
		entries.add(new IconedMenuItem(state == LaunchState.STOPPED ? SVG.ROCKET_LAUNCH : SVG.CANCEL,
		                               actionLabel(state),
		                               this::testLaunch,
		                               popup));
		// Right below the launch entry, because it is what a person reaches for next: the instance
		// is up and the page it serves is where the work happens. Offered whether it is running or
		// not, the same as the instance list's menu — see [InstanceBrowser].
		entries.add(new IconedMenuItem(SVG.PUBLIC,
		                               i18n("dsh.instance.open_browser"),
		                               () -> InstanceBrowser.open(instance),
		                               popup));
		// Right under the browser entry, because it is the other half of the same thing: the address
		// the harness serves is behind a key, and reaching it from another window means carrying the
		// key over. An instance that is not running has none, and a harness from before the key
		// existed printed none either — the entry is disabled rather than gone, so that what is
		// missing is visible instead of being a menu that changes shape.
		IconedMenuItem copyToken = new IconedMenuItem(SVG.CONTENT_COPY,
		                                              i18n("dsh.instance.copy_token"),
		                                              this::copyLaunchToken,
		                                              popup);
		copyToken.setDisable(launchToken(instance).isEmpty());
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
			return instance.instanceDirectory();
		} catch (DshException e) {
			return null;
		}
	}

	private @Nullable Path homeChild(String name) {
		try {
			Path home = instance.homeDirectory();
			return name.isEmpty() ? home : home.resolve(name);
		} catch (DshException e) {
			return null;
		}
	}

	/// Opens a directory, reporting rather than throwing when it is missing.
	///
	/// @param directory the directory to open, or `null`
	private void openDirectoryQuietly(@Nullable Path directory) {
		if (directory == null || !Files.isDirectory(directory)) {
			Controllers.dialog(i18n("dsh.instance.open.unavailable"), i18n("message.error"), MessageType.ERROR);
			return;
		}
		DshFileManager.openDirectory(directory);
	}

	/// Opens the running instance's live output, or its diagnostics folder when it is stopped.
	///
	/// A running instance has a process this launcher holds, and that process has the output
	/// the log window is for. When it is not running there is no live feed; the harness writes
	/// a report under its own home when a start-up audit fails, which is the thing worth reading
	/// when an instance will not run, so that is what is opened then.
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
		DshFileManager.openDirectory(logs);
	}

	/// Renames this instance.
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

	/// Copies this instance into a new one.
	///
	/// The copy is filled from a pack the launcher writes and reads back — version, profile, plugin
	/// list, patch layer, local plugin files, plugin settings, skills — and the work runs behind a
	/// progress dialog, because installing a harness and resolving its plugins takes minutes rather
	/// than a click. That dialog is also what asks about install scripts, and the copy it made is
	/// kept for the answer: see [DshInstanceManager#duplicate]. Saying that it finished is the
	/// dialog's own toast, the same one every other installation ends with.
	private void duplicateInstance() {
		String newId = DshInstanceManager.nextId(instance.id());
		PluginInstalls.runCreating(i18n("dsh.instance.duplicating", instance.id()),
		                           () -> InstanceStore.find(newId),
		                           report -> DshInstanceManager.duplicate(instance.id(), newId, report::accept),
		                           null);
	}

	/// Starts or stops this instance from its own page.
	/// Opens a terminal on this instance, set up the way a launch sets it up.
	///
	/// The way out of a launch that will not start: the same home, the same environment and
	/// the instance's own toolchain, in a shell where the harness can be run by hand.
	private void openTerminal() {
		try {
			DshInstanceTerminal.open(instance, DshAccount.forInstance(instance));
		} catch (DshException e) {
			LOG.warning("Could not open a terminal on " + instance.id(), e);
			Controllers.dialog(e.getMessage(), i18n("message.error"), MessageType.ERROR);
		}
	}

	private void testLaunch() {
		// Test launch shows the output: the point of launching from here is to
		// see what the program does, which is what the original's test game does
		// too. Stopping it is the same action everywhere and shows nothing.
		DshLaunchService.toggle(instance, this::refresh, true);
	}

	/// Puts the key the running harness asks for on the clipboard.
	///
	/// The entry that calls this is disabled unless there is one, so an empty key here means the
	/// instance stopped between the menu being drawn and the entry being pressed. Said rather than
	/// swallowed: a copy that copied nothing looks exactly like one that worked.
	private void copyLaunchToken() {
		Optional<String> token = launchToken(instance);
		if (token.isEmpty()) {
			Controllers.showToast(i18n("dsh.instance.token_missing"));
			return;
		}
		FXUtils.copyText(token.get(), i18n("dsh.instance.token_copied"));
	}

	/// Deletes this instance after confirmation, leaving the page afterwards.
	///
	/// The question is the launcher's own, and so is what follows it: the progress dialog every
	/// other operation of this size is shown in, which runs the removal and stays until it has
	/// finished. The removal waits twice — an instance that is running is stopped first and the
	/// stop waits for the child to drain, and the tree is then taken away with retries because a
	/// handle that is closing takes a moment to close — so it is work with a beginning and an end,
	/// and what is shown for it is a progress dialog rather than a question. Run on the interface
	/// thread instead, the window stops repainting for as long as the child takes to die: Windows
	/// paints it white and offers to end it, and what the person reports is a launcher that has
	/// crashed.
	///
	/// The page is left only once the instance is gone. A failure keeps it where it is, beside the
	/// error, because there is still an instance there to try again on.
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

	// Rebuilds the plugin list from the profile's manifest on disk.
	private void refreshPlugins() {
		pluginList.getContent().clear();

		List<DshInstalledPlugins.Installed> plugins;
		try {
			plugins = DshInstalledPlugins.of(instance);
		} catch (DshException e) {
			pluginStatus.setText(e.getMessage());
			return;
		}

		// Only the packages the user added; the in-box bundles every profile
		// starts with are not the user's to manage.
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

	/// Removes an installed plugin after confirmation.
	///
	/// @param name the package name
	private void removePlugin(String name) {
		Controllers.confirm(i18n("dsh.instance.plugins.remove.confirm", name),
		                    i18n("dsh.instance.plugins.remove"),
		                    () -> CompletableFuture.runAsync(() -> {
			                    try {
				                    DshPluginInstaller.remove(instance, name, null);
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
