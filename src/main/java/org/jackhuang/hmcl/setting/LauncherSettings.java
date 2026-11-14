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
package org.jackhuang.hmcl.setting;

import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.account.DshVendor;
import com.mcxcc303.hdsl.instance.*;
import com.mcxcc303.hdsl.launch.DshLauncherVisibility;
import com.mcxcc303.hdsl.launch.DshProxyMode;
import com.mcxcc303.hdsl.plugin.DshBuildScriptPolicy;
import com.mcxcc303.hdsl.plugin.DshDependencyPolicy;
import com.mcxcc303.hdsl.runtime.node.DshNodeRuntime;
import com.mcxcc303.hdsl.runtime.DshToolchain;
import com.mcxcc303.hdsl.runtime.node.NodeSource;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.collections.ObservableSet;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import org.glavo.monetfx.ColorStyle;
import org.jackhuang.hmcl.theme.*;
import org.jackhuang.hmcl.util.i18n.SupportedLocale;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The launcher settings, one property each.
 */
@NotNullByDefault
public final class LauncherSettings {
	// Theme-pack reference used when the user has not chosen a theme.
	public static final ThemeReference DEFAULT_THEME_REFERENCE = new ThemeReference("hmcl.default", null);

	// Appearance-override keys, named as a theme pack names them.
	public static final String THEME_APPEARANCE_BRIGHTNESS_MODE = "themeBrightnessMode";
	public static final String THEME_APPEARANCE_COLOR = "themeColor";
	public static final String THEME_APPEARANCE_COLOR_STYLE = "themeColorStyle";
	public static final String THEME_APPEARANCE_TITLE_BAR_TRANSPARENT = "titleBarTransparent";
	public static final String THEME_APPEARANCE_WINDOW_TRANSPARENT = "windowTransparent";
	public static final String THEME_APPEARANCE_BACKGROUND = "background";
	public static final String THEME_APPEARANCE_BACKGROUND_OPACITY = "backgroundOpacity";

	// Preference keys, as written into the settings file.
	public static final String DEFAULT_NODE_RUNTIME = "defaultNodeRuntime";
	public static final String DEFAULT_PNPM = "defaultPnpm";
	public static final String DEFAULT_HOME_MODE = "defaultHomeMode";
	public static final String GAME_DIRECTORIES = "gameDirectories";
	public static final String SELECTED_GAME_DIRECTORY = "selectedGameDirectoryId";
	public static final String SELECTED_INSTANCE = "selectedInstance";
	public static final String LAUNCHER_FONT_FAMILY = "launcherFontFamily";

	// Themes.
	private final ObjectProperty<@Nullable ThemeReference> selectedTheme = new SimpleObjectProperty<>(
			DEFAULT_THEME_REFERENCE);
	private final ObservableSet<String> themeAppearanceOverrides = FXCollections.observableSet();
	// `auto`, `light` or `dark`.
	private final StringProperty themeBrightnessMode = new SimpleStringProperty("auto");
	private final ObjectProperty<ThemeColor> customThemeColor = new SimpleObjectProperty<>(ThemeColor.DEFAULT);
	private final ObjectProperty<ThemeColorType> themeColorType = new SimpleObjectProperty<>(ThemeColorType.DEFAULT);
	private final ObjectProperty<ColorStyle> themeColorStyle = new SimpleObjectProperty<>(ColorStyle.FIDELITY);
	private final BooleanProperty titleBarTransparent = new SimpleBooleanProperty(false);
	private final BooleanProperty windowTransparent = new SimpleBooleanProperty(false);

	// What happens when a plugin runs an installation script.
	private final ObjectProperty<DshBuildScriptPolicy> buildScriptPolicy = new SimpleObjectProperty<>(
			DshBuildScriptPolicy.MANUAL);

	// Whether the launcher writes debug lines to the log file.
	private final BooleanProperty debugLog = new SimpleBooleanProperty(false);

	// Sources of the plugin market
	private final StringProperty marketSource = new SimpleStringProperty("");
	private final StringProperty skillSource = new SimpleStringProperty("");
	private final StringProperty packMarketUrl = new SimpleStringProperty("");

	/**
	 * How much of a new instance's dependency tree is held to the harness versions.
	 * <p>
	 * Read when a harness is installed, so a change applies to the next install.
	 * <p>
	 * Defaults to {@link DshDependencyPolicy#CORE_PINNED}.
	 */
	private final ObjectProperty<DshDependencyPolicy> dependencyPolicy = new SimpleObjectProperty<>(DshDependencyPolicy.CORE_PINNED);
	// The isolation policy of new instances.
	private final ObjectProperty<DshIsolationPolicy> isolationPolicy = new SimpleObjectProperty<>(DshIsolationPolicy.ALWAYS);
	// What happens to the launcher once an instance is running.
	private final ObjectProperty<DshLauncherVisibility> launcherVisibility = new SimpleObjectProperty<>(
			DshLauncherVisibility.KEEP);

	/**
	 * The variables every instance runs with, overridable per instance.
	 * <p>
	 * Edited as lines of {@code NAME=VALUE}.
	 */
	private final ObjectProperty<Map<String, String>> globalEnvironment = new SimpleObjectProperty<>(Map.of());

	// The download cache: whether a folder was chosen, and which one.
	private final BooleanProperty cacheDirectoryCustom = new SimpleBooleanProperty(false);
	private final StringProperty cacheDirectory = new SimpleStringProperty("");

	// The proxy, used when proxyMode selects one.
	private final ObjectProperty<DshProxyMode> proxyMode = new SimpleObjectProperty<>(DshProxyMode.SYSTEM);
	private final StringProperty proxyHost = new SimpleStringProperty("");
	private final StringProperty proxyPort = new SimpleStringProperty("");
	private final BooleanProperty proxyAuthenticated = new SimpleBooleanProperty(false);
	private final StringProperty proxyUser = new SimpleStringProperty("");
	private final StringProperty proxyPassword = new SimpleStringProperty("");

	// Download concurrency: whether the launcher chooses, and the count otherwise.
	private final BooleanProperty autoDownloadThreads = new SimpleBooleanProperty(true);
	private final ObjectProperty<@Nullable Integer> downloadConcurrency = new SimpleObjectProperty<>(64);

	// Commands around a launch, empty for none.
	private final StringProperty preLaunchCommand = new SimpleStringProperty("");
	private final StringProperty postExitCommand = new SimpleStringProperty("");

	// The font family of the interface, or `null` for the platform default.
	private final ObjectProperty<@Nullable String> launcherFontFamily = new SimpleObjectProperty<>(this,
	                                                                                               LAUNCHER_FONT_FAMILY,
	                                                                                               null);
	// The Node runtime new instances are given; an instance may pin another.
	private final ObjectProperty<String> defaultNodeRuntime = new SimpleObjectProperty<>(this,
	                                                                                     DEFAULT_NODE_RUNTIME,
	                                                                                     DshNodeRuntime.SYSTEM);
	// The pnpm of the launcher, chosen on the pnpm page.
	private final ObjectProperty<String> defaultPnpm = new SimpleObjectProperty<>(this,
	                                                                              DEFAULT_PNPM,
	                                                                              DshToolchain.SYSTEM);
	// The DSH_HOME policy for new instances.
	private final ObjectProperty<DshHomeMode> defaultHomeMode = new SimpleObjectProperty<>(this,
	                                                                                       DEFAULT_HOME_MODE,
	                                                                                       DshHomeMode.ISOLATED);
	// Instance folders: which ones are read, and which is on display.
	private final ObjectProperty<List<GameDirectory>> gameDirectories = new SimpleObjectProperty<>(this,
	                                                                                               GAME_DIRECTORIES,
	                                                                                               List.of());
	private final ObjectProperty<@Nullable String> selectedGameDirectoryId = new SimpleObjectProperty<>(this,
	                                                                                                    SELECTED_GAME_DIRECTORY,
	                                                                                                    GameDirectory.DEFAULT_ID);

	// The launcher background: the source, the fallback when that source fails, and the load timing.
	private final ObjectProperty<BackgroundType> backgroundType = new SimpleObjectProperty<>(BackgroundType.DEFAULT);
	private final StringProperty builtinBackgroundId = new SimpleStringProperty(BuiltinBackground.FALLBACK.id());
	private final StringProperty customBackgroundImagePath = new SimpleStringProperty();
	private final StringProperty networkBackgroundImageUrl = new SimpleStringProperty();
	private final ObjectProperty<@Nullable Paint> customBackgroundPaint = new SimpleObjectProperty<>();

	// In the range `0..1`.
	private final DoubleProperty backgroundOpacity = new SimpleDoubleProperty(1.0);
	private final ObjectProperty<NetworkBackgroundImageCachePolicy> networkBackgroundImageCachePolicy = new SimpleObjectProperty<>(
			NetworkBackgroundImageCachePolicy.ENABLED);
	private final ObjectProperty<BackgroundType> backgroundFallbackType = new SimpleObjectProperty<>(BackgroundType.BUILTIN);
	private final ObjectProperty<Paint> backgroundFallbackPaint = new SimpleObjectProperty<>(Color.WHITE);
	private final ObjectProperty<BackgroundLoadPolicy> backgroundLoadPolicy = new SimpleObjectProperty<>(
			BackgroundLoadPolicy.WAIT_FOR_BACKGROUND);

	// The font family and size of the log view.
	private final StringProperty logFontFamily = new SimpleStringProperty();
	private final DoubleProperty logFontSize = new SimpleDoubleProperty(12);

	/**
	 * How the launcher draws text.
	 * <p>
	 * The choice takes effect at the next start.
	 */
	private final ObjectProperty<FontAntiAliasing> fontAntiAliasing = new SimpleObjectProperty<>(FontAntiAliasing.AUTO);
	// The arguments a new instance is launched with, as one line.
	private final StringProperty defaultLaunchArguments = new SimpleStringProperty("");
	// The accounts added on this machine, kept out of any instance, profile or pack.
	private final ObservableList<DshAccount> accounts = FXCollections.observableArrayList();
	// Suppliers added on this machine, alongside the built-in ones.
	private final ObservableList<DshVendor> customVendors = FXCollections.observableArrayList();
	/**
	 * Which account the launcher uses, by key, or empty.
	 * <p>
	 * A key rather than a position, so a moved or removed account leaves no choice behind.
	 */
	private final StringProperty activeAccountKey = new SimpleStringProperty("");
	// The language the interface speaks.
	private final ObjectProperty<SupportedLocale> language = new SimpleObjectProperty<>(SupportedLocale.DEFAULT);
	// Whether animations are disabled; `null` follows the platform setting.
	private final ObjectProperty<@Nullable Boolean> animationDisabled = new SimpleObjectProperty<>();
	/**
	 * Where Node.js distributions are fetched from.
	 * <p>
	 * The one download that does not come from npm, and so cannot be redirected by the user's
	 * {@code .npmrc}.
	 */
	private final ObjectProperty<NodeSource> nodeSource = new SimpleObjectProperty<>(NodeSource.OFFICIAL);
	/**
	 * The instance the launch button targets, keyed by game directory id.
	 * <p>
	 * One selection per game directory.
	 */
	private final ObservableMap<String, String> selectedInstance = FXCollections.observableHashMap();
	// Whether the browser is opened automatically once an instance is ready.
	private final BooleanProperty openBrowserOnLaunch = new SimpleBooleanProperty(true);
	// Whether an instance's log window opens at launch.
	private final BooleanProperty showLogs = new SimpleBooleanProperty(false);

	public BooleanProperty debugLogProperty() {
		return debugLog;
	}

	/**
	 * Returns the plugin market, chosen by id.
	 */
	public StringProperty marketSourceProperty() {
		return marketSource;
	}

	/**
	 * Returns the skill catalogue, chosen by id.
	 */
	public StringProperty skillSourceProperty() {
		return skillSource;
	}

	public StringProperty packMarketUrlProperty() {
		return packMarketUrl;
	}

	public ObjectProperty<DshDependencyPolicy> dependencyPolicyProperty() {
		return dependencyPolicy;
	}

	public DshDependencyPolicy dependencyPolicy() {
		var value = dependencyPolicy.get();
		return value == null ? DshDependencyPolicy.CORE_PINNED : value;
	}

	public ObjectProperty<DshIsolationPolicy> isolationPolicyProperty() {
		return isolationPolicy;
	}

	public DshIsolationPolicy isolationPolicy() {
		return isolationPolicy.get() == null ? DshIsolationPolicy.ALWAYS : isolationPolicy.get();
	}

	public ObjectProperty<DshLauncherVisibility> launcherVisibilityProperty() {
		return launcherVisibility;
	}

	/**
	 * Returns what happens to the launcher once an instance is running.
	 * <p>
	 * The stored value, without consulting any instance.
	 *
	 * @return the choice, never {@code null}
	 */
	public DshLauncherVisibility launcherVisibility() {
		DshLauncherVisibility choice = launcherVisibility.get();
		return choice == null ? DshLauncherVisibility.KEEP : choice;
	}

	public ObjectProperty<Map<String, String>> globalEnvironmentProperty() {
		return globalEnvironment;
	}

	/**
	 * Returns the variables every instance runs with.
	 *
	 * @return the variables, never {@code null}
	 */
	public Map<String, String> globalEnvironment() {
		Map<String, String> variables = globalEnvironment.get();
		return variables == null ? Map.of() : variables;
	}

	public BooleanProperty cacheDirectoryCustomProperty() {
		return cacheDirectoryCustom;
	}

	/**
	 * Returns where the launcher keeps downloaded files.
	 *
	 * @return the property, empty for the default place
	 */
	public StringProperty cacheDirectoryProperty() {
		return cacheDirectory;
	}

	public ObjectProperty<DshProxyMode> proxyModeProperty() {
		return proxyMode;
	}

	public StringProperty proxyHostProperty() {
		return proxyHost;
	}

	public StringProperty proxyPortProperty() {
		return proxyPort;
	}

	public BooleanProperty proxyAuthenticatedProperty() {
		return proxyAuthenticated;
	}

	public StringProperty proxyUserProperty() {
		return proxyUser;
	}

	public StringProperty proxyPasswordProperty() {
		return proxyPassword;
	}

	public BooleanProperty autoDownloadThreadsProperty() {
		return autoDownloadThreads;
	}

	/**
	 * Returns how many downloads may happen at once.
	 *
	 * @return the property, {@code null} for the tool's own choice
	 */
	public ObjectProperty<@Nullable Integer> downloadConcurrencyProperty() {
		return downloadConcurrency;
	}

	/**
	 * Returns the command to run before an instance starts.
	 *
	 * @return the property, empty for none
	 */
	public StringProperty preLaunchCommandProperty() {
		return preLaunchCommand;
	}

	/**
	 * Returns the command to run after an instance has ended.
	 *
	 * @return the property, empty for none
	 */
	public StringProperty postExitCommandProperty() {
		return postExitCommand;
	}

	public StringProperty defaultLaunchArgumentsProperty() {
		return defaultLaunchArguments;
	}

	/**
	 * Returns the arguments a new instance is launched with.
	 *
	 * @return the line, never {@code null}
	 */
	public String defaultLaunchArguments() {
		String value = defaultLaunchArguments.get();
		return value == null ? "" : value;
	}

	public ObservableList<DshAccount> getAccounts() {
		return accounts;
	}

	public ObservableList<DshVendor> getCustomVendors() {
		return customVendors;
	}

	/**
	 * Returns every supplier the interface should offer: built-in suppliers first, then those added
	 * on this machine.
	 * <p>
	 * An added supplier is left out when the id matches a built-in supplier.
	 *
	 * @return the list
	 */
	public List<DshVendor> allVendors() {
		List<DshVendor> all = new ArrayList<>(DshVendor.offered());
		for (DshVendor vendor : customVendors) {
			boolean known = all.stream().anyMatch(known0 -> known0.id().equalsIgnoreCase(vendor.id()));
			if (!known) {
				all.add(vendor);
			}
		}
		return List.copyOf(all);
	}

	public StringProperty activeAccountKeyProperty() {
		return activeAccountKey;
	}

	/**
	 * Returns which account the launcher uses, or empty when none is chosen.
	 */
	public @Nullable String activeAccountKey() {
		String key = activeAccountKey.get();
		return key == null || key.isEmpty() ? null : key;
	}

	/**
	 * Returns the active account.
	 * <p>
	 * If the chosen account no longer exists, the first account is used.
	 *
	 * @return the account, or {@code null} when there are none
	 */
	public @Nullable DshAccount activeAccount() {
		List<DshAccount> all = getAccounts();
		if (all.isEmpty()) {
			return null;
		}
		String chosen = activeAccountKey();
		if (chosen != null) {
			for (DshAccount account : all) {
				if (account.matchesKey(chosen)) {
					return account;
				}
			}
		}
		return all.get(0);
	}

	public ObjectProperty<FontAntiAliasing> fontAntiAliasingProperty() {
		return fontAntiAliasing;
	}

	/**
	 * Returns how the launcher draws text.
	 *
	 * @return the choice, never {@code null}
	 */
	public FontAntiAliasing fontAntiAliasing() {
		FontAntiAliasing choice = fontAntiAliasing.get();
		return choice == null ? FontAntiAliasing.AUTO : choice;
	}

	public ObjectProperty<SupportedLocale> languageProperty() {
		return language;
	}

	/**
	 * Returns the selected theme reference, falling back to the default when unset.
	 */
	public ThemeReference getSelectedThemeOrDefault() {
		return Objects.requireNonNullElse(selectedTheme.get(), DEFAULT_THEME_REFERENCE);
	}

	public ObjectProperty<@Nullable ThemeReference> selectedThemeProperty() {
		return selectedTheme;
	}

	public ObservableSet<String> getThemeAppearanceOverrides() {
		return themeAppearanceOverrides;
	}

	public ObjectProperty<String> defaultNodeRuntimeProperty() {
		return defaultNodeRuntime;
	}

	public ObjectProperty<String> defaultPnpmProperty() {
		return defaultPnpm;
	}

	public ObjectProperty<DshHomeMode> defaultHomeModeProperty() {
		return defaultHomeMode;
	}

	public ObjectProperty<List<GameDirectory>> gameDirectoriesProperty() {
		return gameDirectories;
	}

	public ObjectProperty<@Nullable String> selectedGameDirectoryIdProperty() {
		return selectedGameDirectoryId;
	}

	public ObjectProperty<@Nullable String> launcherFontFamilyProperty() {
		return launcherFontFamily;
	}

	public StringProperty themeBrightnessModeProperty() {
		return themeBrightnessMode;
	}

	public ObjectProperty<ThemeColor> customThemeColorProperty() {
		return customThemeColor;
	}

	public ObjectProperty<ThemeColorType> themeColorTypeProperty() {
		return themeColorType;
	}

	public ObjectProperty<ColorStyle> themeColorStyleProperty() {
		return themeColorStyle;
	}

	public BooleanProperty titleBarTransparentProperty() {
		return titleBarTransparent;
	}

	public BooleanProperty windowTransparentProperty() {
		return windowTransparent;
	}

	/**
	 * Returns what happens when a plugin wants to run an install script.
	 *
	 * @return the policy
	 */
	public ObjectProperty<DshBuildScriptPolicy> buildScriptPolicyProperty() {
		return buildScriptPolicy;
	}

	/**
	 * Returns the command that runs before an instance starts.
	 * <p>
	 * The instance's own command wins; without one, the launcher's applies.
	 *
	 * @param instanceId the instance
	 * @return the command, or an empty string for none
	 */
	public String preLaunchCommandFor(String instanceId) {
		DshInstance instance = InstanceStore.find(instanceId);
		if (instance != null) {
			String own = DshInstanceSettings.preLaunchCommand(instance);
			if (own != null) {
				return own;
			}
		}
		return preLaunchCommand.get() == null ? "" : preLaunchCommand.get();
	}

	/**
	 * Returns the command that runs after an instance has ended.
	 *
	 * @param instanceId the instance
	 * @return the command, or an empty string for none
	 */
	public String postExitCommandFor(String instanceId) {
		DshInstance instance = InstanceStore.find(instanceId);
		if (instance != null) {
			String own = DshInstanceSettings.postExitCommand(instance);
			if (own != null) {
				return own;
			}
		}
		return postExitCommand.get() == null ? "" : postExitCommand.get();
	}

	/**
	 * Returns whether an instance's log window opens at launch.
	 */
	public BooleanProperty showLogsProperty() {
		return showLogs;
	}

	/**
	 * Returns whether an instance's log window opens at launch.
	 * <p>
	 * If the instance has a value, that value wins; otherwise the launcher's value applies.
	 *
	 * @param instanceId the instance
	 * @return whether the window opens
	 */
	public boolean showLogsFor(String instanceId) {
		DshInstance instance = InstanceStore.find(instanceId);
		if (instance != null) {
			Boolean own = DshInstanceSettings.showLogs(instance);
			if (own != null) {
				return own;
			}
		}
		return showLogs.get();
	}

	/**
	 * Returns whether debug lines are written for an instance.
	 * <p>
	 * If the instance has a value, that value wins; otherwise the launcher's value applies.
	 *
	 * @param instanceId the instance
	 * @return whether debug lines are written
	 */
	public boolean debugLogFor(String instanceId) {
		DshInstance instance = InstanceStore.find(instanceId);
		if (instance != null) {
			Boolean own = DshInstanceSettings.debugLog(instance);
			if (own != null) {
				return own;
			}
		}
		return debugLog.get();
	}

	/**
	 * Returns what happens to the launcher while an instance runs.
	 * <p>
	 * The instance's own choice wins; without one, the launcher's applies.
	 *
	 * @param instanceId the instance
	 * @return the choice
	 */
	public DshLauncherVisibility launcherVisibilityFor(String instanceId) {
		DshInstance instance = InstanceStore.find(instanceId);
		if (instance != null) {
			String own = DshInstanceSettings.launcherVisibility(instance);
			if (own != null) {
				return DshLauncherVisibility.of(own);
			}
		}
		DshLauncherVisibility launcher = launcherVisibility.get();
		return launcher == null ? DshLauncherVisibility.KEEP : launcher;
	}

	public DshBuildScriptPolicy buildScriptPolicy() {
		return buildScriptPolicy.get() == null ? DshBuildScriptPolicy.MANUAL : buildScriptPolicy.get();
	}

	public ObjectProperty<BackgroundType> backgroundTypeProperty() {
		return backgroundType;
	}

	public StringProperty builtinBackgroundIdProperty() {
		return builtinBackgroundId;
	}

	public StringProperty customBackgroundImagePathProperty() {
		return customBackgroundImagePath;
	}

	public StringProperty networkBackgroundImageUrlProperty() {
		return networkBackgroundImageUrl;
	}

	public ObjectProperty<@Nullable Paint> customBackgroundPaintProperty() {
		return customBackgroundPaint;
	}

	public DoubleProperty backgroundOpacityProperty() {
		return backgroundOpacity;
	}

	public ObjectProperty<NetworkBackgroundImageCachePolicy> networkBackgroundImageCachePolicyProperty() {
		return networkBackgroundImageCachePolicy;
	}

	public ObjectProperty<BackgroundType> backgroundFallbackTypeProperty() {
		return backgroundFallbackType;
	}

	public ObjectProperty<Paint> backgroundFallbackPaintProperty() {
		return backgroundFallbackPaint;
	}

	public ObjectProperty<BackgroundLoadPolicy> backgroundLoadPolicyProperty() {
		return backgroundLoadPolicy;
	}

	public StringProperty logFontFamilyProperty() {
		return logFontFamily;
	}

	public DoubleProperty logFontSizeProperty() {
		return logFontSize;
	}

	/**
	 * Returns whether animations are disabled.
	 * <p>
	 * When the user has not expressed a preference this reports {@code false}, and the platform
	 * preference is applied by the window layer instead.
	 *
	 * @return whether animations are disabled
	 */
	public boolean isAnimationDisabled() {
		return Boolean.TRUE.equals(animationDisabled.get());
	}

	public ObjectProperty<@Nullable Boolean> animationDisabledProperty() {
		return animationDisabled;
	}

	public ObjectProperty<NodeSource> nodeSourceProperty() {
		return nodeSource;
	}

	/**
	 * Returns the selected instance of every game directory.
	 * <p>
	 * Owned by {@link GameDirectoryManager}; code outside that class should not write here.
	 *
	 * @return the observable map from game directory id to instance id
	 */
	public ObservableMap<String, String> getSelectedInstance() {
		return selectedInstance;
	}

	/**
	 * Returns the instance selected in a game directory.
	 *
	 * @param gameDirectoryId the game directory id, or {@code null}
	 * @return the instance id, or {@code null} when nothing is chosen there
	 */
	public @Nullable String getSelectedInstance(@Nullable String gameDirectoryId) {
		return gameDirectoryId == null ? null : selectedInstance.get(gameDirectoryId);
	}

	/**
	 * Records the instance selected in a game directory.
	 * <p>
	 * A {@code null} instance clears the entry.
	 *
	 * @param gameDirectoryId the game directory id, or {@code null} to do nothing
	 * @param instanceId      the instance id, or {@code null} to clear
	 */
	public void setSelectedInstance(@Nullable String gameDirectoryId, @Nullable String instanceId) {
		if (gameDirectoryId == null) {
			return;
		}
		if (instanceId != null) {
			selectedInstance.put(gameDirectoryId, instanceId);
		} else {
			selectedInstance.remove(gameDirectoryId);
		}
	}

	public BooleanProperty openBrowserOnLaunchProperty() {
		return openBrowserOnLaunch;
	}
}
