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
package org.jackhuang.hmcl.setting;

import java.util.Map;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import javafx.collections.ObservableSet;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import org.glavo.monetfx.ColorStyle;
import org.jackhuang.hmcl.dsh.NodeSource;
import org.jackhuang.hmcl.util.i18n.SupportedLocale;
import org.jackhuang.hmcl.theme.BackgroundLoadPolicy;
import org.jackhuang.hmcl.theme.BuiltinBackground;
import org.jackhuang.hmcl.theme.NetworkBackgroundImageCachePolicy;
import org.jackhuang.hmcl.theme.ThemeColor;
import org.jackhuang.hmcl.theme.ThemeReference;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Holds the appearance-related launcher settings consumed by the transplanted
/// theme engine.
///
/// This is the HMCL-DSH counterpart of HMCL's much larger `LauncherSettings`.
/// Only the fields the ported `theme` and window-chrome code actually reads are
/// kept; everything that described Java runtimes, memory limits, game
/// directories or download sources is gone.
@NotNullByDefault
public final class LauncherSettings {
    /// Theme-pack reference used when the user has not chosen a theme.
    public static final ThemeReference DEFAULT_THEME_REFERENCE = new ThemeReference("hmcl.default", null);

    /// Appearance-override key for the theme brightness mode.
    public static final String THEME_APPEARANCE_BRIGHTNESS_MODE = "themeBrightnessMode";

    /// Appearance-override key for the theme color.
    public static final String THEME_APPEARANCE_COLOR = "themeColor";

    /// Appearance-override key for the theme color style.
    public static final String THEME_APPEARANCE_COLOR_STYLE = "themeColorStyle";

    /// Appearance-override key for the transparent title bar.
    public static final String THEME_APPEARANCE_TITLE_BAR_TRANSPARENT = "titleBarTransparent";

    /// Appearance-override key for the transparent window background.
    public static final String THEME_APPEARANCE_WINDOW_TRANSPARENT = "windowTransparent";

    /// Appearance-override key for the background.
    public static final String THEME_APPEARANCE_BACKGROUND = "background";

    /// Appearance-override key for the background opacity.
    public static final String THEME_APPEARANCE_BACKGROUND_OPACITY = "backgroundOpacity";

    /// The theme pack and theme the user selected, or `null` to follow the default.
    private final ObjectProperty<@Nullable ThemeReference> selectedTheme =
            new SimpleObjectProperty<>(DEFAULT_THEME_REFERENCE);

    /// The set of appearance keys the user has explicitly overridden.
    private final ObservableSet<String> themeAppearanceOverrides = FXCollections.observableSet();

    /// The theme brightness mode: `auto`, `light` or `dark`.
    private final StringProperty themeBrightnessMode = new SimpleStringProperty("auto");

    /// The custom theme color used when [#themeColorTypeProperty] is `CUSTOM`.
    private final ObjectProperty<ThemeColor> customThemeColor = new SimpleObjectProperty<>(ThemeColor.DEFAULT);

    /// The source used to choose the Monet theme color seed.
    private final ObjectProperty<ThemeColorType> themeColorType = new SimpleObjectProperty<>(ThemeColorType.DEFAULT);

    /// The Monet color style.
    private final ObjectProperty<ColorStyle> themeColorStyle = new SimpleObjectProperty<>(ColorStyle.FIDELITY);

    /// Whether the window title bar is transparent.
    private final BooleanProperty titleBarTransparent = new SimpleBooleanProperty(false);

    /// Whether the window background is transparent.
    private final BooleanProperty windowTransparent = new SimpleBooleanProperty(false);

    /// What happens when a plugin wants to run an install script.
    ///
    /// An install script runs with the user's own rights and nobody has read it, which
    /// is why a package manager stops and asks. Answering is a decision, so the
    /// launcher asks by default and an instance can be told otherwise.
    private final ObjectProperty<org.jackhuang.hmcl.dsh.DshBuildScriptPolicy> buildScriptPolicy =
            new SimpleObjectProperty<>(org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.MANUAL);

    /// Whether the launcher writes debug lines to its log.
    private final BooleanProperty debugLog = new SimpleBooleanProperty(false);

    /// Returns whether debug lines are written.
    ///
    /// @return the property
    public BooleanProperty debugLogProperty() {
        return debugLog;
    }

    /// Where the plugin catalogue is read from, or empty for the built-in address.
    private final javafx.beans.property.StringProperty pluginCatalogUrl =
            new javafx.beans.property.SimpleStringProperty("");

    /// Returns the address the plugin catalogue is read from.
    ///
    /// @return the property, empty for the built-in address
    public javafx.beans.property.StringProperty pluginCatalogUrlProperty() {
        return pluginCatalogUrl;
    }

    /// Whether a new instance keeps its own home.
    private final ObjectProperty<org.jackhuang.hmcl.dsh.DshIsolationPolicy> isolationPolicy =
            new SimpleObjectProperty<>(org.jackhuang.hmcl.dsh.DshIsolationPolicy.WITH_PLUGINS);

    /// Returns whether a new instance keeps its own home.
    ///
    /// @return the property
    public ObjectProperty<org.jackhuang.hmcl.dsh.DshIsolationPolicy> isolationPolicyProperty() {
        return isolationPolicy;
    }

    /// Returns the policy a new instance is created under.
    ///
    /// @return the policy
    public org.jackhuang.hmcl.dsh.DshIsolationPolicy isolationPolicy() {
        return isolationPolicy.get() == null
                ? org.jackhuang.hmcl.dsh.DshIsolationPolicy.WITH_PLUGINS : isolationPolicy.get();
    }

    /// What the launcher does with itself once an instance is running.
    private final ObjectProperty<org.jackhuang.hmcl.dsh.DshLauncherVisibility> launcherVisibility =
            new SimpleObjectProperty<>(org.jackhuang.hmcl.dsh.DshLauncherVisibility.KEEP);

    /// Returns what the launcher does with itself once an instance is running.
    ///
    /// @return the property
    public ObjectProperty<org.jackhuang.hmcl.dsh.DshLauncherVisibility> launcherVisibilityProperty() {
        return launcherVisibility;
    }

    /// Returns what the launcher does with itself once an instance is running.
    ///
    /// The launcher's own answer, without consulting any instance. This is what is
    /// stored, so it must be the value the row shows rather than what an instance
    /// happens to resolve to.
    ///
    /// @return the choice, never `null`
    public org.jackhuang.hmcl.dsh.DshLauncherVisibility launcherVisibility() {
        org.jackhuang.hmcl.dsh.DshLauncherVisibility choice = launcherVisibility.get();
        return choice == null ? org.jackhuang.hmcl.dsh.DshLauncherVisibility.KEEP : choice;
    }

    /// The variables every instance runs with, unless it sets its own.
    ///
    /// Edited as lines of `NAME=VALUE`, because that is what people paste into a box.
    private final ObjectProperty<Map<String, String>> globalEnvironment =
            new SimpleObjectProperty<>(Map.of());

    /// Returns the variables every instance runs with.
    ///
    /// @return the property
    public ObjectProperty<Map<String, String>> globalEnvironmentProperty() {
        return globalEnvironment;
    }

    /// Returns the variables every instance runs with.
    ///
    /// @return the variables, never `null`
    public Map<String, String> globalEnvironment() {
        Map<String, String> variables = globalEnvironment.get();
        return variables == null ? Map.of() : variables;
    }

    /// Whether the cache folder was chosen rather than left where the launcher puts it.
    private final BooleanProperty cacheDirectoryCustom = new SimpleBooleanProperty(false);

    /// Returns whether the cache folder was chosen.
    ///
    /// @return the property
    public BooleanProperty cacheDirectoryCustomProperty() {
        return cacheDirectoryCustom;
    }

    /// Where the launcher keeps what it fetched, or empty for the default place.
    private final javafx.beans.property.StringProperty cacheDirectory =
            new javafx.beans.property.SimpleStringProperty("");

    /// Returns where the launcher keeps what it fetched.
    ///
    /// @return the property, empty for the default place
    public javafx.beans.property.StringProperty cacheDirectoryProperty() {
        return cacheDirectory;
    }

    /// How the launcher reaches the network.
    private final ObjectProperty<org.jackhuang.hmcl.dsh.DshProxyMode> proxyMode =
            new SimpleObjectProperty<>(org.jackhuang.hmcl.dsh.DshProxyMode.SYSTEM);

    /// The proxy's host.
    private final javafx.beans.property.StringProperty proxyHost =
            new javafx.beans.property.SimpleStringProperty("");

    /// The proxy's port.
    private final javafx.beans.property.StringProperty proxyPort =
            new javafx.beans.property.SimpleStringProperty("");

    /// Whether the proxy wants a name and a password.
    private final BooleanProperty proxyAuthenticated = new SimpleBooleanProperty(false);

    /// The name the proxy wants, when it wants one.
    private final javafx.beans.property.StringProperty proxyUser =
            new javafx.beans.property.SimpleStringProperty("");

    /// The password the proxy wants, when it wants one.
    private final javafx.beans.property.StringProperty proxyPassword =
            new javafx.beans.property.SimpleStringProperty("");

    /// Returns how the launcher reaches the network.
    ///
    /// @return the property
    public ObjectProperty<org.jackhuang.hmcl.dsh.DshProxyMode> proxyModeProperty() {
        return proxyMode;
    }

    /// Returns the proxy's host.
    ///
    /// @return the property
    public javafx.beans.property.StringProperty proxyHostProperty() {
        return proxyHost;
    }

    /// Returns the proxy's port.
    ///
    /// @return the property
    public javafx.beans.property.StringProperty proxyPortProperty() {
        return proxyPort;
    }

    /// Returns whether the proxy wants a name and a password.
    ///
    /// @return the property
    public BooleanProperty proxyAuthenticatedProperty() {
        return proxyAuthenticated;
    }

    /// Returns the name the proxy wants.
    ///
    /// @return the property
    public javafx.beans.property.StringProperty proxyUserProperty() {
        return proxyUser;
    }

    /// Returns the password the proxy wants.
    ///
    /// @return the property
    public javafx.beans.property.StringProperty proxyPasswordProperty() {
        return proxyPassword;
    }

    /// Whether the launcher chooses how many downloads happen at once.
    private final BooleanProperty autoDownloadThreads = new SimpleBooleanProperty(true);

    /// How many downloads may happen at once, when the launcher is not choosing.
    private final ObjectProperty<@Nullable Integer> downloadConcurrency = new SimpleObjectProperty<>(64);

    /// Returns whether the launcher chooses how many downloads happen at once.
    ///
    /// @return the property
    public BooleanProperty autoDownloadThreadsProperty() {
        return autoDownloadThreads;
    }

    /// Returns the proxy downloads go through.
    ///

    /// Returns the proxy secure downloads go through.
    ///

    /// Returns the hosts reached without a proxy.
    ///

    /// Returns how many downloads may happen at once.
    ///
    /// @return the property, `null` for the tool's own choice
    public ObjectProperty<@Nullable Integer> downloadConcurrencyProperty() {
        return downloadConcurrency;
    }

    /// A command to run before an instance starts, or an empty string for none.
    private final javafx.beans.property.StringProperty preLaunchCommand =
            new javafx.beans.property.SimpleStringProperty("");

    /// A command to run after an instance has ended, or an empty string for none.
    private final javafx.beans.property.StringProperty postExitCommand =
            new javafx.beans.property.SimpleStringProperty("");

    /// Returns the command to run before an instance starts.
    ///
    /// @return the property, empty for none
    public javafx.beans.property.StringProperty preLaunchCommandProperty() {
        return preLaunchCommand;
    }

    /// Returns the command to run after an instance has ended.
    ///
    /// @return the property, empty for none
    public javafx.beans.property.StringProperty postExitCommandProperty() {
        return postExitCommand;
    }

    /// The source used to render the launcher background.
    private final ObjectProperty<@Nullable String> launcherFontFamily =
            new SimpleObjectProperty<>(this, LAUNCHER_FONT_FAMILY, null);

    /// The Node runtime new instances are given.
    ///
    /// An instance may pin its own; this is what "follow the global setting"
    /// resolves to, and what a new instance starts with.
    private final ObjectProperty<String> defaultNodeRuntime =
            new SimpleObjectProperty<>(this, DEFAULT_NODE_RUNTIME, org.jackhuang.hmcl.dsh.DshNodeRuntime.SYSTEM);

    /// The DSH_HOME policy new instances are given.
    private final ObjectProperty<org.jackhuang.hmcl.dsh.DshHomeMode> defaultHomeMode =
            new SimpleObjectProperty<>(this, DEFAULT_HOME_MODE, org.jackhuang.hmcl.dsh.DshHomeMode.ISOLATED);

    /// The folders the launcher looks for instances in.
    private final ObjectProperty<java.util.List<GameDirectory>> gameDirectories =
            new SimpleObjectProperty<>(this, GAME_DIRECTORIES, java.util.List.of());

    /// The folder whose instances the list shows.
    private final ObjectProperty<@Nullable String> selectedGameDirectoryId =
            new SimpleObjectProperty<>(this, SELECTED_GAME_DIRECTORY, GameDirectory.DEFAULT_ID);

    private final ObjectProperty<BackgroundType> backgroundType = new SimpleObjectProperty<>(BackgroundType.DEFAULT);

    /// The id of the selected built-in wallpaper.
    private final StringProperty builtinBackgroundId = new SimpleStringProperty(BuiltinBackground.FALLBACK.id());

    /// The path of the user-selected background image.
    private final StringProperty customBackgroundImagePath = new SimpleStringProperty();

    /// The URL of the user-selected network background image.
    private final StringProperty networkBackgroundImageUrl = new SimpleStringProperty();

    /// The user-selected flat background paint.
    private final ObjectProperty<@Nullable Paint> customBackgroundPaint = new SimpleObjectProperty<>();

    /// The background opacity in the range `0..1`.
    private final DoubleProperty backgroundOpacity = new SimpleDoubleProperty(1.0);

    /// The cache policy for network background images.
    private final ObjectProperty<NetworkBackgroundImageCachePolicy> networkBackgroundImageCachePolicy =
            new SimpleObjectProperty<>(NetworkBackgroundImageCachePolicy.ENABLED);

    /// The background source used when the primary source cannot be resolved.
    private final ObjectProperty<BackgroundType> backgroundFallbackType =
            new SimpleObjectProperty<>(BackgroundType.BUILTIN);

    /// The paint used when the fallback background source is `PAINT`.
    private final ObjectProperty<Paint> backgroundFallbackPaint = new SimpleObjectProperty<>(Color.WHITE);

    /// When the background is loaded relative to the window becoming visible.
    private final ObjectProperty<BackgroundLoadPolicy> backgroundLoadPolicy =
            new SimpleObjectProperty<>(BackgroundLoadPolicy.WAIT_FOR_BACKGROUND);

    /// The key for the default Node runtime new instances are pinned to.
    public static final String DEFAULT_NODE_RUNTIME = "defaultNodeRuntime";

    /// The key for the default DSH_HOME policy new instances are given.
    public static final String DEFAULT_HOME_MODE = "defaultHomeMode";

    /// The key for the folders the launcher looks for instances in.
    public static final String GAME_DIRECTORIES = "gameDirectories";

    /// The key for the folder whose instances are shown.
    public static final String SELECTED_GAME_DIRECTORY = "selectedGameDirectoryId";

    /// The key for the instance each game directory has chosen.
    public static final String SELECTED_INSTANCE = "selectedInstance";

    /// The font family used by the interface, or `null` for the platform default.
    public static final String LAUNCHER_FONT_FAMILY = "launcherFontFamily";

    /// The font family used by the log view, or empty for the default.
    private final StringProperty logFontFamily = new SimpleStringProperty();

    /// The font size used by the log view.
    private final DoubleProperty logFontSize = new SimpleDoubleProperty(12);

    /// How the launcher draws text.
    ///
    /// Read before the toolkit starts rather than while it runs: the property it becomes
    /// is one the renderer reads as it initialises, so the choice takes effect at the next
    /// start — which is what the row says.
    private final ObjectProperty<FontAntiAliasing> fontAntiAliasing =
            new SimpleObjectProperty<>(FontAntiAliasing.AUTO);

    /// The arguments a new instance is launched with, as one line.
    ///
    /// A default rather than a setting the launcher itself obeys: what an instance runs is its own
    /// list, and this is what an instance that has stated nothing of its own shows and follows.
    private final StringProperty defaultLaunchArguments = new SimpleStringProperty("");

    /// Returns the arguments a new instance is launched with.
    ///
    /// @return the property
    public StringProperty defaultLaunchArgumentsProperty() {
        return defaultLaunchArguments;
    }

    /// Returns the arguments a new instance is launched with.
    ///
    /// @return the line, never `null`
    public String defaultLaunchArguments() {
        String value = defaultLaunchArguments.get();
        return value == null ? "" : value;
    }

    /// The accounts this machine has been given.
    ///
    /// A DeepSeek Harness account is a key and the vendor it belongs to; the original stores logins
    /// it performs on the user's behalf, and this stands in the same place. Kept in the launcher's
    /// own settings — which already holds this machine's proxy password — and never written into an
    /// instance, a profile or a pack.
    private final javafx.collections.ObservableList<org.jackhuang.hmcl.dsh.DshAccount> accounts =
            javafx.collections.FXCollections.observableArrayList();

    /// Returns the accounts.
    ///
    /// @return the list
    public javafx.collections.ObservableList<org.jackhuang.hmcl.dsh.DshAccount> getAccounts() {
        return accounts;
    }

    /// The suppliers this machine has been told about, beside the ones the launcher offers.
    ///
    /// A person pastes the address of a service that is not in the dropdown — an aggregator of their
    /// own, a gateway inside their network — and it joins the list of ways to add an account, so the
    /// next account on it is two fields rather than the same address typed again. Kept here rather
    /// than in a file of its own because there is no more to one than the four things the harness
    /// needs in order to route it.
    private final javafx.collections.ObservableList<org.jackhuang.hmcl.dsh.DshVendor> customVendors =
            javafx.collections.FXCollections.observableArrayList();

    /// Returns the suppliers this machine has been told about.
    ///
    /// @return the list
    public javafx.collections.ObservableList<org.jackhuang.hmcl.dsh.DshVendor> getCustomVendors() {
        return customVendors;
    }

    /// Returns every supplier the interface should offer: the launcher's own, then the added ones.
    ///
    /// The launcher's own lead, because they are the ones that need nothing typed. An added supplier
    /// whose id one of them already uses is left out: two rows for one supplier, one of which cannot
    /// be told from the other, is worse than ignoring the second.
    ///
    /// @return the list
    public java.util.List<org.jackhuang.hmcl.dsh.DshVendor> allVendors() {
        java.util.List<org.jackhuang.hmcl.dsh.DshVendor> all =
                new java.util.ArrayList<>(org.jackhuang.hmcl.dsh.DshVendor.offered());
        for (org.jackhuang.hmcl.dsh.DshVendor vendor : customVendors) {
            boolean known = all.stream().anyMatch(known0 -> known0.id().equalsIgnoreCase(vendor.id()));
            if (!known) {
                all.add(vendor);
            }
        }
        return java.util.List.copyOf(all);
    }

    /// Which account the launcher uses, by its key, or empty.
    ///
    /// A named choice rather than a position. The list's first entry used to *be* the answer, which
    /// meant choosing an account moved it: the list reordered itself under the pointer, and every
    /// reader had to remember that "first" meant "in force". A key can name an account that has moved,
    /// or been removed — in which case there is no choice, which is a state the launcher should be
    /// able to be in rather than one it has to hide by keeping a stale row.
    private final javafx.beans.property.StringProperty activeAccountKey =
            new javafx.beans.property.SimpleStringProperty("");

    /// Returns which account the launcher uses.
    ///
    /// @return the property
    public javafx.beans.property.StringProperty activeAccountKeyProperty() {
        return activeAccountKey;
    }

    /// Returns which account the launcher uses, or empty when none is chosen.
    ///
    /// @return the key
    public @Nullable String activeAccountKey() {
        String key = activeAccountKey.get();
        return key == null || key.isEmpty() ? null : key;
    }

    /// Returns the account the launcher uses.
    ///
    /// A chosen account that is gone falls back to the first one, because a launcher with exactly one
    /// account means it for everything and does not need to be told so.
    ///
    /// @return the account, or `null` when there are none
    public @Nullable org.jackhuang.hmcl.dsh.DshAccount activeAccount() {
        java.util.List<org.jackhuang.hmcl.dsh.DshAccount> all = getAccounts();
        if (all.isEmpty()) {
            return null;
        }
        String chosen = activeAccountKey();
        if (chosen != null) {
            for (org.jackhuang.hmcl.dsh.DshAccount account : all) {
                if (account.matchesKey(chosen)) {
                    return account;
                }
            }
        }
        return all.get(0);
    }

    /// Returns how the launcher draws text.
    ///
    /// @return the property
    public ObjectProperty<FontAntiAliasing> fontAntiAliasingProperty() {
        return fontAntiAliasing;
    }

    /// Returns how the launcher draws text.
    ///
    /// @return the choice, never `null`
    public FontAntiAliasing fontAntiAliasing() {
        FontAntiAliasing choice = fontAntiAliasing.get();
        return choice == null ? FontAntiAliasing.AUTO : choice;
    }

    /// The language the interface speaks.
    ///
    /// Stored here rather than only in the internationalisation helper because that helper
    /// keeps its choice in memory: without a setting to write it to, choosing a language
    /// lasted until the launcher was closed.
    private final ObjectProperty<SupportedLocale> language =
            new SimpleObjectProperty<>(SupportedLocale.DEFAULT);

    /// Returns the language the interface speaks.
    ///
    /// @return the property
    public ObjectProperty<SupportedLocale> languageProperty() {
        return language;
    }

    /// Whether animations are disabled; `null` follows the platform setting.
    private final ObjectProperty<@Nullable Boolean> animationDisabled = new SimpleObjectProperty<>();

    /// Where Node.js distributions are fetched from.
    ///
    /// The one download this launcher makes that does not come from npm, and
    /// therefore the one that cannot be redirected by the user's `.npmrc`: a
    /// network that reaches a mirror of npm but not `nodejs.org` can run
    /// DeepSeek Harness and install plugins, and still be unable to install a
    /// runtime to run it with.
    private final ObjectProperty<NodeSource> nodeSource = new SimpleObjectProperty<>(NodeSource.OFFICIAL);

    /// The instance the launch button targets, keyed by game directory id.
    ///
    /// The original keeps one selection per game directory rather than one for
    /// the whole launcher: switching folders is switching collections of
    /// instances, and a collection remembers what was chosen in it.
    private final ObservableMap<String, String> selectedInstance = FXCollections.observableHashMap();

    /// Whether the browser is opened automatically once an instance is ready.
    private final BooleanProperty openBrowserOnLaunch = new SimpleBooleanProperty(true);

    /// Returns the selected theme reference, falling back to the default when unset.
    ///
    /// @return the effective theme reference
    public ThemeReference getSelectedThemeOrDefault() {
        return Objects.requireNonNullElse(selectedTheme.get(), DEFAULT_THEME_REFERENCE);
    }

    /// Returns the property holding the selected theme reference.
    ///
    /// @return the selected-theme property
    public ObjectProperty<@Nullable ThemeReference> selectedThemeProperty() {
        return selectedTheme;
    }

    /// Returns the mutable set of appearance keys the user has overridden.
    ///
    /// @return the appearance-override set
    public ObservableSet<String> getThemeAppearanceOverrides() {
        return themeAppearanceOverrides;
    }

    /// Returns the default Node runtime property.
    ///
    /// @return the property
    public ObjectProperty<String> defaultNodeRuntimeProperty() {
        return defaultNodeRuntime;
    }

    /// Returns the default DSH_HOME policy property.
    ///
    /// @return the property
    public ObjectProperty<org.jackhuang.hmcl.dsh.DshHomeMode> defaultHomeModeProperty() {
        return defaultHomeMode;
    }

    /// Returns the game directory list property.
    ///
    /// @return the property
    public ObjectProperty<java.util.List<GameDirectory>> gameDirectoriesProperty() {
        return gameDirectories;
    }

    /// Returns the selected game directory property.
    ///
    /// @return the property
    public ObjectProperty<@Nullable String> selectedGameDirectoryIdProperty() {
        return selectedGameDirectoryId;
    }

    /// Returns the interface font family property.
    ///
    /// @return the font family property, whose value may be `null`
    public ObjectProperty<@Nullable String> launcherFontFamilyProperty() {
        return launcherFontFamily;
    }

    /// Returns the theme brightness mode property.
    ///
    /// @return the brightness-mode property
    public StringProperty themeBrightnessModeProperty() {
        return themeBrightnessMode;
    }

    /// Returns the custom theme color property.
    ///
    /// @return the custom theme color property
    public ObjectProperty<ThemeColor> customThemeColorProperty() {
        return customThemeColor;
    }

    /// Returns the theme color source property.
    ///
    /// @return the theme color type property
    public ObjectProperty<ThemeColorType> themeColorTypeProperty() {
        return themeColorType;
    }

    /// Returns the Monet color style property.
    ///
    /// @return the color style property
    public ObjectProperty<ColorStyle> themeColorStyleProperty() {
        return themeColorStyle;
    }

    /// Returns the transparent-title-bar property.
    ///
    /// @return the title-bar transparency property
    public BooleanProperty titleBarTransparentProperty() {
        return titleBarTransparent;
    }

    /// Returns the transparent-window property.
    ///
    /// @return the window transparency property
    public BooleanProperty windowTransparentProperty() {
        return windowTransparent;
    }

    /// Returns what happens when a plugin wants to run an install script.
    ///
    /// @return the launcher's policy, for instances that do not override it
    public ObjectProperty<org.jackhuang.hmcl.dsh.DshBuildScriptPolicy> buildScriptPolicyProperty() {
        return buildScriptPolicy;
    }

    /// Whether an instance's log window opens when it launches.
    private final BooleanProperty showLogs = new SimpleBooleanProperty(false);

    /// Returns the command that runs before an instance starts.
    ///
    /// The instance's own command wins; without one, the launcher's applies.
    ///
    /// @param instanceId the instance
    /// @return the command, or an empty string for none
    public String preLaunchCommandFor(String instanceId) {
        org.jackhuang.hmcl.dsh.DshInstance instance =
                org.jackhuang.hmcl.dsh.DshInstanceManager.find(instanceId);
        if (instance != null) {
            String own = org.jackhuang.hmcl.dsh.DshInstanceSettings.preLaunchCommand(instance);
            if (own != null) {
                return own;
            }
        }
        return preLaunchCommand.get() == null ? "" : preLaunchCommand.get();
    }

    /// Returns the command that runs after an instance has ended.
    ///
    /// @param instanceId the instance
    /// @return the command, or an empty string for none
    public String postExitCommandFor(String instanceId) {
        org.jackhuang.hmcl.dsh.DshInstance instance =
                org.jackhuang.hmcl.dsh.DshInstanceManager.find(instanceId);
        if (instance != null) {
            String own = org.jackhuang.hmcl.dsh.DshInstanceSettings.postExitCommand(instance);
            if (own != null) {
                return own;
            }
        }
        return postExitCommand.get() == null ? "" : postExitCommand.get();
    }

    /// Returns whether an instance's log window opens when it launches.
    ///
    /// @return the property
    public BooleanProperty showLogsProperty() {
        return showLogs;
    }

    /// Returns whether an instance's log window opens when it launches.
    ///
    /// The instance's own answer wins; without one, the launcher's applies.
    ///
    /// @param instanceId the instance
    /// @return whether the window opens
    public boolean showLogsFor(String instanceId) {
        org.jackhuang.hmcl.dsh.DshInstance instance =
                org.jackhuang.hmcl.dsh.DshInstanceManager.find(instanceId);
        if (instance != null) {
            Boolean own = org.jackhuang.hmcl.dsh.DshInstanceSettings.showLogs(instance);
            if (own != null) {
                return own;
            }
        }
        return showLogs.get();
    }

    /// Returns whether debug lines are written for an instance.
    ///
    /// The instance's own answer wins; without one, the launcher's applies. One method, so the
    /// interface and the launch cannot disagree about which is in force.
    ///
    /// @param instanceId the instance
    /// @return whether debug lines are written
    public boolean debugLogFor(String instanceId) {
        org.jackhuang.hmcl.dsh.DshInstance instance =
                org.jackhuang.hmcl.dsh.DshInstanceManager.find(instanceId);
        if (instance != null) {
            Boolean own = org.jackhuang.hmcl.dsh.DshInstanceSettings.debugLog(instance);
            if (own != null) {
                return own;
            }
        }
        return debugLog.get();
    }

    /// Returns what the launcher does with itself while an instance runs.
    ///
    /// The instance's own choice wins; without one, the launcher's applies. One method, so
    /// the interface and the launch cannot disagree about which is in force.
    ///
    /// @param instanceId the instance
    /// @return the choice
    public org.jackhuang.hmcl.dsh.DshLauncherVisibility launcherVisibilityFor(String instanceId) {
        org.jackhuang.hmcl.dsh.DshInstance instance =
                org.jackhuang.hmcl.dsh.DshInstanceManager.find(instanceId);
        if (instance != null) {
            String own = org.jackhuang.hmcl.dsh.DshInstanceSettings.launcherVisibility(instance);
            if (own != null) {
                return org.jackhuang.hmcl.dsh.DshLauncherVisibility.of(own);
            }
        }
        org.jackhuang.hmcl.dsh.DshLauncherVisibility launcher =
                launcherVisibility.get();
        return launcher == null ? org.jackhuang.hmcl.dsh.DshLauncherVisibility.KEEP : launcher;
    }

    /// Returns the launcher's policy.
    ///
    /// @return the policy
    public org.jackhuang.hmcl.dsh.DshBuildScriptPolicy buildScriptPolicy() {
        return buildScriptPolicy.get() == null
                ? org.jackhuang.hmcl.dsh.DshBuildScriptPolicy.MANUAL : buildScriptPolicy.get();
    }


    /// Returns the background source property.
    ///
    /// @return the background type property
    public ObjectProperty<BackgroundType> backgroundTypeProperty() {
        return backgroundType;
    }

    /// Returns the built-in wallpaper id property.
    ///
    /// @return the built-in background id property
    public StringProperty builtinBackgroundIdProperty() {
        return builtinBackgroundId;
    }

    /// Returns the custom background image path property.
    ///
    /// @return the custom background path property
    public StringProperty customBackgroundImagePathProperty() {
        return customBackgroundImagePath;
    }

    /// Returns the network background image URL property.
    ///
    /// @return the network background URL property
    public StringProperty networkBackgroundImageUrlProperty() {
        return networkBackgroundImageUrl;
    }

    /// Returns the custom background paint property.
    ///
    /// @return the custom background paint property
    public ObjectProperty<@Nullable Paint> customBackgroundPaintProperty() {
        return customBackgroundPaint;
    }

    /// Returns the background opacity property.
    ///
    /// @return the background opacity property
    public DoubleProperty backgroundOpacityProperty() {
        return backgroundOpacity;
    }

    /// Returns the network background cache-policy property.
    ///
    /// @return the cache policy property
    public ObjectProperty<NetworkBackgroundImageCachePolicy> networkBackgroundImageCachePolicyProperty() {
        return networkBackgroundImageCachePolicy;
    }

    /// Returns the fallback background source property.
    ///
    /// @return the fallback background type property
    public ObjectProperty<BackgroundType> backgroundFallbackTypeProperty() {
        return backgroundFallbackType;
    }

    /// Returns the fallback background paint property.
    ///
    /// @return the fallback background paint property
    public ObjectProperty<Paint> backgroundFallbackPaintProperty() {
        return backgroundFallbackPaint;
    }

    /// Returns the background load-policy property.
    ///
    /// @return the background load policy property
    public ObjectProperty<BackgroundLoadPolicy> backgroundLoadPolicyProperty() {
        return backgroundLoadPolicy;
    }

    /// Returns the log font family property.
    ///
    /// @return the log font family property
    public StringProperty logFontFamilyProperty() {
        return logFontFamily;
    }

    /// Returns the log font size property.
    ///
    /// @return the log font size property
    public DoubleProperty logFontSizeProperty() {
        return logFontSize;
    }

    /// Returns whether animations are disabled.
    ///
    /// When the user has not expressed a preference this reports `false`; the
    /// platform preference is applied by the window layer instead.
    ///
    /// @return whether animations are disabled
    public boolean isAnimationDisabled() {
        return Boolean.TRUE.equals(animationDisabled.get());
    }

    /// Returns the animation-disabled property.
    ///
    /// @return the animation-disabled property
    public ObjectProperty<@Nullable Boolean> animationDisabledProperty() {
        return animationDisabled;
    }

    /// Returns the property holding the Node.js download source.
    ///
    /// @return the source property
    public ObjectProperty<NodeSource> nodeSourceProperty() {
        return nodeSource;
    }

    /// Returns the selected instance of every game directory.
    ///
    /// Owned by [GameDirectoryManager]; code outside it should not write here.
    ///
    /// @return the observable map from game directory id to instance id
    public ObservableMap<String, String> getSelectedInstance() {
        return selectedInstance;
    }

    /// Returns the instance selected in a game directory.
    ///
    /// @param gameDirectoryId the game directory id, or `null`
    /// @return the instance id, or `null` when nothing is chosen there
    public @Nullable String getSelectedInstance(@Nullable String gameDirectoryId) {
        return gameDirectoryId == null ? null : selectedInstance.get(gameDirectoryId);
    }

    /// Records the instance selected in a game directory.
    ///
    /// A `null` instance clears the entry, which is what an empty directory
    /// leaves behind.
    ///
    /// @param gameDirectoryId the game directory id, or `null` to do nothing
    /// @param instanceId      the instance id, or `null` to clear
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

    /// Returns whether the browser should be opened when an instance becomes ready.
    ///
    /// @return the open-browser property
    public BooleanProperty openBrowserOnLaunchProperty() {
        return openBrowserOnLaunch;
    }
}
