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

import com.google.gson.annotations.SerializedName;
import com.mcxcc303.hdsl.account.DshAccount;
import com.mcxcc303.hdsl.account.DshVendor;
import com.mcxcc303.hdsl.core.DshPermissions;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshIsolationPolicy;
import com.mcxcc303.hdsl.launch.DshLauncherVisibility;
import com.mcxcc303.hdsl.launch.DshProxyMode;
import com.mcxcc303.hdsl.plugin.DshBuildScriptPolicy;
import com.mcxcc303.hdsl.plugin.DshDependencyPolicy;
import com.mcxcc303.hdsl.runtime.node.NodeSource;
import com.mcxcc303.hdsl.skin.DshSkinChoice;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import org.glavo.monetfx.ColorStyle;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.theme.BackgroundLoadPolicy;
import org.jackhuang.hmcl.theme.NetworkBackgroundImageCachePolicy;
import org.jackhuang.hmcl.theme.ThemeColor;
import org.jackhuang.hmcl.theme.ThemeReference;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Owns the single {@link LauncherSettings} instance and persists the settings to disk.
 * <p>
 * The settings object holds JavaFX properties. The file holds a plain serializable snapshot
 * instead.
 */
@NotNullByDefault
public final class SettingsManager {
	private static final Path SETTINGS_PATH = Metadata.HDSL_USER_HOME.resolve("launcher-settings.json");
	private static final Path STATE_PATH = Metadata.HDSL_USER_HOME.resolve("launcher-state.json");
	// The lazily created settings singleton.
	private static @Nullable LauncherSettings launcherSettings;
	// The lazily created window state singleton.
	private static @Nullable LauncherState launcherState;

	private SettingsManager() {}

	/**
	 * Returns the process-wide window state, loaded on first use.
	 *
	 * @return the launcher window state singleton
	 */
	public static synchronized LauncherState state() {
		LauncherState state = launcherState;
		if (state == null) {
			state = new LauncherState();
			launcherState = state;
			loadState(state);
		}
		return state;
	}

	/**
	 * Reads persisted window geometry into {@code state}, ignoring a missing file.
	 *
	 * @param state the state to populate
	 */
	private static void loadState(LauncherState state) {
		Path path = STATE_PATH;
		if (!Files.isRegularFile(path)) {
			return;
		}
		try {
			StateSnapshot snapshot = JsonUtils.fromJsonFile(path, StateSnapshot.class);
			if (snapshot != null) {
				snapshot.applyTo(state);
			}
		} catch (Exception e) {
			LOG.warning("Failed to load launcher state from " + path, e);
		}
	}

	// Persists the current window geometry.
	public static void saveState() {
		LauncherState state = launcherState;
		if (state == null) {
			return;
		}
		try {
			Path parent = STATE_PATH.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			JsonUtils.writeToJsonFile(STATE_PATH, StateSnapshot.of(state));
		} catch (IOException e) {
			LOG.warning("Failed to save launcher state to " + STATE_PATH, e);
		}
	}

	/**
	 * Returns the process-wide launcher settings, loading them on first use.
	 *
	 * @return the launcher settings singleton
	 */
	public static LauncherSettings settings() {
		LauncherSettings settings = launcherSettings;
		if (settings == null) {
			settings = load();
			launcherSettings = settings;
		}
		return settings;
	}

	/**
	 * Loads settings from disk. Falls back to defaults if the file is absent or unreadable.
	 * <p>
	 * This is package-private so a test can {@link save()} settings and load them back.
	 *
	 * @return the loaded settings
	 */
	static LauncherSettings load() {
		LauncherSettings settings = new LauncherSettings();
		readSnapshot().applyTo(settings);
		return settings;
	}

	/**
	 * Resolves a stored theme color.
	 * <p>
	 * The stored value can be a standard color name or a hex value such as {@code #RRGGBB}.
	 * Standard names are matched case-insensitively. Older settings files store standard names.
	 *
	 * @param stored the stored name or hex value
	 * @return the resolved color, or {@link ThemeColor#DEFAULT} if the value cannot be resolved
	 */
	private static ThemeColor resolveThemeColor(String stored) {
		String value = stored.trim();
		for (ThemeColor standard : ThemeColor.STANDARD_COLORS) {
			if (standard.name().equalsIgnoreCase(value)) {
				return standard;
			}
		}
		try {
			return new ThemeColor(value, Color.web(value));
		} catch (IllegalArgumentException e) {
			LOG.warning("Unknown theme colour in settings: " + stored);
			return ThemeColor.DEFAULT;
		}
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			LOG.warning("Unknown " + type.getSimpleName() + " value in settings: " + value);
			return fallback;
		}
	}

	/**
	 * Renders a background color for the settings file.
	 * <p>
	 * This stores a flat color only.
	 *
	 * @param paint the color, or {@code null}
	 * @return the hex form, or {@code null} when there is nothing to store
	 */
	private static @Nullable String paintToString(@Nullable Paint paint) {
		if (!(paint instanceof Color color)) return null;
		return String.format("#%02X%02X%02X",
		                     Math.round(color.getRed() * 255),
		                     Math.round(color.getGreen() * 255),
		                     Math.round(color.getBlue() * 255));
	}

	/**
	 * Reads a background color written by {@link paintToString}.
	 *
	 * @param value the stored hex string
	 * @return the color, or {@code null} when the value is unreadable
	 */
	private static javafx.scene.paint.@Nullable Paint paintOf(String value) {
		try {
			return Color.web(value.trim());
		} catch (IllegalArgumentException e) {
			LOG.warning("Unknown background colour in settings: " + value);
			return null;
		}
	}

	/**
	 * Writes the current launcher settings to disk.
	 */
	public static void save() {
		writeSnapshot(Snapshot.of(settings()));
	}

	/**
	 * Adds a folder to the settings file, and selects the folder, in one read-modify-write.
	 * <p>
	 * The change is written to the file on disk.
	 *
	 * @param path the folder to add
	 * @return the folder entry, new or already there
	 */
	public static GameDirectory addGameDirectory(Path path) {
		Snapshot snapshot = readSnapshot();
		List<GameDirectory> directories = new ArrayList<>(
				snapshot.gameDirectories == null ? List.of() : snapshot.gameDirectories);
		GameDirectory added = GameDirectoryManager.entryFor(directories, path, null);

		snapshot.gameDirectories = List.copyOf(directories);
		snapshot.selectedGameDirectoryId = added.id();
		writeSnapshot(snapshot);
		return added;
	}

	/**
	 * Reads the settings file from disk.
	 *
	 * @return the snapshot, empty when there is no readable file
	 */
	private static Snapshot readSnapshot() {
		if (Files.isRegularFile(SETTINGS_PATH)) {
			try {
				Snapshot snapshot = JsonUtils.fromJsonFile(SETTINGS_PATH, Snapshot.class);
				if (snapshot != null) {
					return snapshot;
				}
			} catch (Exception e) {
				LOG.warning("Failed to read launcher settings from " + SETTINGS_PATH, e);
			}
		}
		return new Snapshot();
	}

	/**
	 * Writes a snapshot to the settings file.
	 * <p>
	 * Writes to a file beside the settings, then moves that file to replace the old one.
	 *
	 * @param snapshot the snapshot to write
	 */
	private static void writeSnapshot(Snapshot snapshot) {
		try {
			Path directory = SETTINGS_PATH.getParent();
			if (directory == null) {
				// A path with no parent is the root of a filesystem. The settings file never is.
				throw new IOException("The settings file has no directory: " + SETTINGS_PATH);
			}
			Files.createDirectories(directory);

			Path written = Files.createTempFile(directory, "launcher-settings", ".json");
			try {
				JsonUtils.writeToJsonFile(written, snapshot);
				restrictPermissions(written);
				move(written, SETTINGS_PATH);
			} finally {
				Files.deleteIfExists(written);
			}
		} catch (IOException e) {
			LOG.warning("Failed to save launcher settings to " + SETTINGS_PATH, e);
		}
	}

	/**
	 * Puts a written file in place of the settings file.
	 *
	 * @param written the file to move
	 * @param target  where the file goes
	 * @throws IOException when the file cannot be moved
	 */
	private static void move(Path written, Path target) throws IOException {
		try {
			Files.move(written, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			// A filesystem without atomic moves (such as a network mount) still gets the replacement.
			// The reader opens one file or the other, never a half-written one.
			LOG.info("Moving " + written + " into place atomically is not supported here", e);
			Files.move(written, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Makes the settings file readable only by the owner.
	 * <p>
	 * The file holds the accounts' API keys.
	 *
	 * @param file the file to restrict
	 */
	private static void restrictPermissions(Path file) {
		DshPermissions.restrict(file, DshPermissions.SECRET_FILE);
	}

	/**
	 * Applies the selection written before selections were kept per folder.
	 * <p>
	 * The selection goes to the folder owned by the launcher. If a newer selection already exists,
	 * the launcher does not overwrite it.
	 *
	 * @param settings the settings to update
	 * @param legacyId the instance id an older launcher stored, or {@code null}
	 */
	static void applyLegacySelection(LauncherSettings settings, @Nullable String legacyId) {
		if (legacyId == null || settings.getSelectedInstance(GameDirectory.DEFAULT_ID) != null) {
			return;
		}
		settings.setSelectedInstance(GameDirectory.DEFAULT_ID, legacyId);
	}

	/**
	 * The serializable view of {@link LauncherSettings}.
	 * <p>
	 * Every field is nullable. missing values will keep the defaults already present.
	 */
	private static final class Snapshot {
		@SerializedName("selectedThemePackId")
		private @Nullable String selectedThemePackId;

		@SerializedName("selectedThemeId")
		private @Nullable String selectedThemeId;

		@SerializedName("themeAppearanceOverrides")
		private @Nullable List<String> themeAppearanceOverrides;

		@SerializedName("themeBrightnessMode")
		private @Nullable String themeBrightnessMode;

		@SerializedName("themeColor")
		private @Nullable String themeColor;

		@SerializedName("themeColorType")
		private @Nullable String themeColorType;

		@SerializedName("themeColorStyle")
		private @Nullable String themeColorStyle;

		@SerializedName("titleBarTransparent")
		private @Nullable Boolean titleBarTransparent;

		@SerializedName("launcherFontFamily")
		private @Nullable String launcherFontFamily;

		@SerializedName("defaultNodeRuntime")
		private @Nullable String defaultNodeRuntime;

		@SerializedName("defaultPnpm")
		private @Nullable String defaultPnpm;

		@SerializedName("defaultHomeMode")
		private @Nullable String defaultHomeMode;

		@SerializedName("gameDirectories")
		private @Nullable List<GameDirectory> gameDirectories;

		@SerializedName("selectedGameDirectoryId")
		private @Nullable String selectedGameDirectoryId;

		@SerializedName("windowTransparent")
		private @Nullable Boolean windowTransparent;

		@SerializedName("backgroundType")
		private @Nullable String backgroundType;

		@SerializedName("builtinBackgroundId")
		private @Nullable String builtinBackgroundId;

		@SerializedName("customBackgroundImagePath")
		private @Nullable String customBackgroundImagePath;

		@SerializedName("networkBackgroundImageUrl")
		private @Nullable String networkBackgroundImageUrl;

		@SerializedName("backgroundOpacity")
		private @Nullable Double backgroundOpacity;

		@SerializedName("networkBackgroundImageCachePolicy")
		private @Nullable String networkBackgroundImageCachePolicy;

		@SerializedName("backgroundFallbackType")
		private @Nullable String backgroundFallbackType;

		@SerializedName("customBackgroundPaint")
		private @Nullable String customBackgroundPaint;

		@SerializedName("backgroundFallbackPaint")
		private @Nullable String backgroundFallbackPaint;

		@SerializedName("backgroundLoadPolicy")
		private @Nullable String backgroundLoadPolicy;

		@SerializedName("logFontFamily")
		private @Nullable String logFontFamily;

		@SerializedName("logFontSize")
		private @Nullable Double logFontSize;

		@SerializedName("fontAntiAliasing")
		private @Nullable String fontAntiAliasing;

		@SerializedName("defaultLaunchArguments")
		private @Nullable String defaultLaunchArguments;

		@SerializedName("accounts")
		private @Nullable List<AccountSnapshot> accounts;

		// The suppliers added by address, in the order they were added.
		private @Nullable List<VendorSnapshot> vendors;

		@SerializedName("activeAccountKey")
		private @Nullable String activeAccountKey;

		@SerializedName("language")
		private @Nullable String language;

		@SerializedName("animationDisabled")
		private @Nullable Boolean animationDisabled;

		@SerializedName("nodeSource")
		private @Nullable String nodeSource;

		@SerializedName("selectedInstance")
		private @Nullable Map<String, String> selectedInstance;

		@SerializedName("selectedInstanceId")
		private @Nullable String legacySelectedInstanceId;

		@SerializedName("openBrowserOnLaunch")
		private @Nullable Boolean openBrowserOnLaunch;

		@SerializedName("buildScriptPolicy")
		private @Nullable String buildScriptPolicy;

		@SerializedName("launcherVisibility")
		private @Nullable String launcherVisibility;

		@SerializedName("isolationPolicy")
		private @Nullable String isolationPolicy;

		@SerializedName("newInstanceIsolationPolicy")
		private @Nullable String newInstanceIsolationPolicy;

		@SerializedName("showLogs")
		private @Nullable Boolean showLogs;

		@SerializedName("debugLog")
		private @Nullable Boolean debugLog;

		@SerializedName("preLaunchCommand")
		private @Nullable String preLaunchCommand;

		@SerializedName("postExitCommand")
		private @Nullable String postExitCommand;

		@SerializedName("globalEnvironment")
		private @Nullable Map<String, String> globalEnvironment;

		@SerializedName("marketSource")
		private @Nullable String marketSource;

		// The skill catalogue, by id, or absent for the community's own index.
		@SerializedName("skillSource")
		private @Nullable String skillSource;

		// Where the modpack market's index is read from, or absent for the published one.
		@SerializedName("packMarketUrl")
		private @Nullable String packMarketUrl;

		// How much of a new instance's dependency tree is held, by the enum's own name.
		@SerializedName("dependencyPolicy")
		private @Nullable String dependencyPolicy;

		@SerializedName("cacheDirectory")
		private @Nullable String cacheDirectory;

		@SerializedName("cacheDirectoryCustom")
		private @Nullable Boolean cacheDirectoryCustom;

		@SerializedName("autoDownloadThreads")
		private @Nullable Boolean autoDownloadThreads;

		@SerializedName("downloadConcurrency")
		private @Nullable Integer downloadConcurrency;

		@SerializedName("proxyMode")
		private @Nullable String proxyMode;

		@SerializedName("proxyHost")
		private @Nullable String proxyHost;

		@SerializedName("proxyPort")
		private @Nullable String proxyPort;

		@SerializedName("proxyAuthenticated")
		private @Nullable Boolean proxyAuthenticated;

		@SerializedName("proxyUser")
		private @Nullable String proxyUser;

		@SerializedName("proxyPassword")
		private @Nullable String proxyPassword;

		/**
		 * Captures the current settings into a serializable snapshot.
		 *
		 * @param settings the settings to capture
		 * @return the snapshot
		 */
		static Snapshot of(LauncherSettings settings) {
			Snapshot snapshot = new Snapshot();
			ThemeReference theme = settings.getSelectedThemeOrDefault();
			snapshot.selectedThemePackId = theme.packId();
			snapshot.selectedThemeId = theme.themeId();
			snapshot.themeAppearanceOverrides = new ArrayList<>(settings.getThemeAppearanceOverrides());
			snapshot.themeBrightnessMode = settings.themeBrightnessModeProperty().get();
			snapshot.themeColor = ThemeColor.getColorDisplayName(settings.customThemeColorProperty().get().color());
			snapshot.themeColorType = settings.themeColorTypeProperty().get().name();
			snapshot.themeColorStyle = settings.themeColorStyleProperty().get().name();
			snapshot.titleBarTransparent = settings.titleBarTransparentProperty().get();
			snapshot.launcherFontFamily = settings.launcherFontFamilyProperty().get();
			snapshot.defaultNodeRuntime = settings.defaultNodeRuntimeProperty().get();
			snapshot.defaultPnpm = settings.defaultPnpmProperty().get();
			snapshot.defaultHomeMode = settings.defaultHomeModeProperty().get().name();
			snapshot.gameDirectories = List.copyOf(settings.gameDirectoriesProperty().get());
			snapshot.selectedGameDirectoryId = settings.selectedGameDirectoryIdProperty().get();
			snapshot.windowTransparent = settings.windowTransparentProperty().get();
			snapshot.backgroundType = settings.backgroundTypeProperty().get().name();
			snapshot.builtinBackgroundId = settings.builtinBackgroundIdProperty().get();
			snapshot.customBackgroundImagePath = settings.customBackgroundImagePathProperty().get();
			snapshot.networkBackgroundImageUrl = settings.networkBackgroundImageUrlProperty().get();
			snapshot.backgroundOpacity = settings.backgroundOpacityProperty().get();
			snapshot.networkBackgroundImageCachePolicy = settings.networkBackgroundImageCachePolicyProperty()
			                                                     .get()
			                                                     .name();
			snapshot.backgroundFallbackType = settings.backgroundFallbackTypeProperty().get().name();
			snapshot.customBackgroundPaint = paintToString(settings.customBackgroundPaintProperty().get());
			snapshot.backgroundFallbackPaint = paintToString(settings.backgroundFallbackPaintProperty().get());
			snapshot.backgroundLoadPolicy = settings.backgroundLoadPolicyProperty().get().name();
			snapshot.logFontFamily = settings.logFontFamilyProperty().get();
			snapshot.logFontSize = settings.logFontSizeProperty().get();
			snapshot.fontAntiAliasing = settings.fontAntiAliasing().id();
			snapshot.defaultLaunchArguments = settings.defaultLaunchArgumentsProperty().get();
			snapshot.activeAccountKey = settings.activeAccountKey();
			snapshot.accounts = settings.getAccounts()
			                            .stream()
			                            .map(account -> new AccountSnapshot(account.kind(),
			                                                                account.vendorId(),
			                                                                account.apiKey(),
			                                                                account.baseUrl(),
			                                                                account.label(),
			                                                                account.model(),
			                                                                account.skinOrDefault().type().name(),
			                                                                account.skinOrDefault().model().modelName,
			                                                                account.skinOrDefault().localSkinPath(),
			                                                                account.skinOrDefault().localCapePath()))
			                            .toList();
			snapshot.vendors = settings.getCustomVendors()
			                           .stream()
			                           .map(vendor -> new VendorSnapshot(vendor.id(),
			                                                             vendor.displayName(),
			                                                             vendor.apiKeyEnv(),
			                                                             vendor.api(),
			                                                             vendor.baseUrl()))
			                           .toList();
			settings.languageProperty().get();
			snapshot.language = settings.languageProperty().get().getName();
			snapshot.animationDisabled = settings.animationDisabledProperty().get();
			snapshot.nodeSource = settings.nodeSourceProperty().get().id();
			snapshot.selectedInstance = new LinkedHashMap<>(settings.getSelectedInstance());
			snapshot.openBrowserOnLaunch = settings.openBrowserOnLaunchProperty().get();
			snapshot.buildScriptPolicy = settings.buildScriptPolicy().id();
			snapshot.launcherVisibility = settings.launcherVisibility().id();
			snapshot.isolationPolicy = settings.isolationPolicy().id();
			snapshot.newInstanceIsolationPolicy = settings.isolationPolicyProperty().get() == null
			                                      ? DshIsolationPolicy.ALWAYS.id()
			                                      : settings.isolationPolicyProperty().get().id();
			snapshot.showLogs = settings.showLogsProperty().get();
			snapshot.debugLog = settings.debugLogProperty().get();
			snapshot.preLaunchCommand = settings.preLaunchCommandProperty().get();
			snapshot.postExitCommand = settings.postExitCommandProperty().get();
			snapshot.globalEnvironment = new LinkedHashMap<>(settings.globalEnvironment());
			snapshot.marketSource = settings.marketSourceProperty().get();
			snapshot.skillSource = settings.skillSourceProperty().get();
			snapshot.packMarketUrl = settings.packMarketUrlProperty().get();
			snapshot.dependencyPolicy = settings.dependencyPolicy().name();
			snapshot.cacheDirectory = settings.cacheDirectoryProperty().get();
			snapshot.cacheDirectoryCustom = settings.cacheDirectoryCustomProperty().get();
			snapshot.autoDownloadThreads = settings.autoDownloadThreadsProperty().get();
			snapshot.downloadConcurrency = settings.downloadConcurrencyProperty().get();
			settings.proxyModeProperty().get();
			snapshot.proxyMode = settings.proxyModeProperty().get().id();
			snapshot.proxyHost = settings.proxyHostProperty().get();
			snapshot.proxyPort = settings.proxyPortProperty().get();
			snapshot.proxyAuthenticated = settings.proxyAuthenticatedProperty().get();
			snapshot.proxyUser = settings.proxyUserProperty().get();
			snapshot.proxyPassword = settings.proxyPasswordProperty().get();
			return snapshot;
		}

		/**
		 * Applies this snapshot onto a settings object, ignoring unset fields.
		 *
		 * @param settings the settings to update
		 */
		void applyTo(LauncherSettings settings) {
			if (selectedThemePackId != null) {
				settings.selectedThemeProperty().set(new ThemeReference(selectedThemePackId, selectedThemeId));
			}
			if (themeAppearanceOverrides != null) {
				settings.getThemeAppearanceOverrides().clear();
				settings.getThemeAppearanceOverrides().addAll(themeAppearanceOverrides);
			}
			if (themeBrightnessMode != null) {
				settings.themeBrightnessModeProperty().set(themeBrightnessMode);
			}
			if (themeColor != null) {
				settings.customThemeColorProperty().set(resolveThemeColor(themeColor));
			}
			if (themeColorType != null) {
				settings.themeColorTypeProperty()
				        .set(parseEnum(ThemeColorType.class, themeColorType, ThemeColorType.DEFAULT));
			}
			if (themeColorStyle != null) {
				settings.themeColorStyleProperty()
				        .set(parseEnum(ColorStyle.class, themeColorStyle, ColorStyle.FIDELITY));
			}
			if (defaultNodeRuntime != null) {
				settings.defaultNodeRuntimeProperty().set(defaultNodeRuntime);
			}
			if (defaultPnpm != null) {
				settings.defaultPnpmProperty().set(defaultPnpm);
			}
			if (defaultHomeMode != null) {
				settings.defaultHomeModeProperty()
				        .set(parseEnum(DshHomeMode.class, defaultHomeMode, DshHomeMode.ISOLATED));
			}
			if (gameDirectories != null) {
				settings.gameDirectoriesProperty().set(List.copyOf(gameDirectories));
			}
			if (selectedGameDirectoryId != null) {
				settings.selectedGameDirectoryIdProperty().set(selectedGameDirectoryId);
			}
			if (launcherFontFamily != null) {
				settings.launcherFontFamilyProperty().set(launcherFontFamily);
			}
			if (titleBarTransparent != null) {
				settings.titleBarTransparentProperty().set(titleBarTransparent);
			}
			if (windowTransparent != null) {
				settings.windowTransparentProperty().set(windowTransparent);
			}
			if (backgroundType != null) {
				settings.backgroundTypeProperty()
				        .set(parseEnum(BackgroundType.class, backgroundType, BackgroundType.DEFAULT));
			}
			if (builtinBackgroundId != null) {
				settings.builtinBackgroundIdProperty().set(builtinBackgroundId);
			}
			if (customBackgroundImagePath != null) {
				settings.customBackgroundImagePathProperty().set(customBackgroundImagePath);
			}
			if (networkBackgroundImageUrl != null) {
				settings.networkBackgroundImageUrlProperty().set(networkBackgroundImageUrl);
			}
			if (backgroundOpacity != null) {
				settings.backgroundOpacityProperty().set(backgroundOpacity);
			}
			if (networkBackgroundImageCachePolicy != null) {
				settings.networkBackgroundImageCachePolicyProperty()
				        .set(parseEnum(NetworkBackgroundImageCachePolicy.class,
				                       networkBackgroundImageCachePolicy,
				                       NetworkBackgroundImageCachePolicy.ENABLED));
			}
			if (backgroundFallbackType != null) {
				settings.backgroundFallbackTypeProperty()
				        .set(parseEnum(BackgroundType.class, backgroundFallbackType, BackgroundType.BUILTIN));
			}
			if (customBackgroundPaint != null) {
				Paint paint = paintOf(customBackgroundPaint);
				if (paint != null) {
					settings.customBackgroundPaintProperty().set(paint);
				}
			}
			if (backgroundFallbackPaint != null) {
				Paint paint = paintOf(backgroundFallbackPaint);
				if (paint != null) {
					settings.backgroundFallbackPaintProperty().set(paint);
				}
			}
			if (backgroundLoadPolicy != null) {
				settings.backgroundLoadPolicyProperty()
				        .set(parseEnum(BackgroundLoadPolicy.class,
				                       backgroundLoadPolicy,
				                       BackgroundLoadPolicy.WAIT_FOR_BACKGROUND));
			}
			if (logFontFamily != null) {
				settings.logFontFamilyProperty().set(logFontFamily);
			}
			if (logFontSize != null) {
				settings.logFontSizeProperty().set(logFontSize);
			}
			if (fontAntiAliasing != null) {
				settings.fontAntiAliasingProperty()
				        .set(org.jackhuang.hmcl.setting.FontAntiAliasing.of(fontAntiAliasing));
			}
			if (activeAccountKey != null) {
				settings.activeAccountKeyProperty().set(activeAccountKey);
			}
			if (accounts != null) {
				for (AccountSnapshot account : accounts) {
					settings.getAccounts()
					        .add(new DshAccount(
							        account.kind() == null ? DshAccount.AccountKind.THIRD_PARTY : account.kind(),
							        account.vendorId(),
							        account.apiKey(),
							        account.baseUrl(),
							        account.label(),
							        account.model(),
							        new DshSkinChoice(
									        // an unknown name from a later version or a hand-edited file must not stop the settings file from loading.
									        Objects.requireNonNullElse(DshSkinChoice.Type.fromStorage(account.skinType()),
									                                   DshSkinChoice.Type.DEFAULT),
									        DshSkinChoice.TextureModel.fromStorage(account.skinModel()),
									        account.skinPath(),
									        account.skinCapePath())));
				}
			}
			if (vendors != null) {
				for (VendorSnapshot vendor : vendors) {
					if (vendor.baseUrl() == null || vendor.baseUrl().isBlank()) {
						continue;
					}
					settings.getCustomVendors()
					        .add(new DshVendor(vendor.id(),
					                           vendor.displayName() == null ? vendor.id() : vendor.displayName(),
					                           vendor.apiKeyEnv() == null ? "" : vendor.apiKeyEnv(),
					                           vendor.api() == null ? DshVendor.APIS.get(0) : vendor.api(),
					                           vendor.baseUrl(),
					                           false));
				}
			}
			if (defaultLaunchArguments != null) {
				settings.defaultLaunchArgumentsProperty().set(defaultLaunchArguments);
			}
			if (language != null) {
				settings.languageProperty().set(org.jackhuang.hmcl.util.i18n.SupportedLocale.getLocaleByName(language));
			}
			if (animationDisabled != null) {
				settings.animationDisabledProperty().set(animationDisabled);
			}
			if (nodeSource != null) {
				settings.nodeSourceProperty().set(NodeSource.of(nodeSource));
			}
			if (selectedInstance != null) {
				settings.getSelectedInstance().clear();
				settings.getSelectedInstance().putAll(selectedInstance);
			} else {
				applyLegacySelection(settings, legacySelectedInstanceId);
			}
			if (openBrowserOnLaunch != null) {
				settings.openBrowserOnLaunchProperty().set(openBrowserOnLaunch);
			}
			if (buildScriptPolicy != null) {
				settings.buildScriptPolicyProperty().set(DshBuildScriptPolicy.of(buildScriptPolicy));
			}
			if (launcherVisibility != null) {
				settings.launcherVisibilityProperty().set(DshLauncherVisibility.of(launcherVisibility));
			}
			if (newInstanceIsolationPolicy != null) {
				settings.isolationPolicyProperty().set(DshIsolationPolicy.of(newInstanceIsolationPolicy));
			} else if (isolationPolicy != null) {
				DshIsolationPolicy legacyPolicy = DshIsolationPolicy.of(isolationPolicy);
				// Only the creation default changes. Existing instances still resolve their
				// recorded homeMode, including GLOBAL's unchanged defaultHomeMode setting.
				settings.isolationPolicyProperty()
				        .set(legacyPolicy == DshIsolationPolicy.WITH_PLUGINS
				             ? DshIsolationPolicy.ALWAYS
				             : legacyPolicy);
			}
			if (showLogs != null) {
				settings.showLogsProperty().set(showLogs);
			}
			if (debugLog != null) {
				settings.debugLogProperty().set(debugLog);
				// The logger keeps its own flag. Without this call the flag keeps the value
				// the interface set before, which is off after a restart.
				org.jackhuang.hmcl.util.logging.Logger.setDebugEnabled(debugLog);
			}
			if (preLaunchCommand != null) {
				settings.preLaunchCommandProperty().set(preLaunchCommand);
			}
			if (postExitCommand != null) {
				settings.postExitCommandProperty().set(postExitCommand);
			}
			if (globalEnvironment != null) {
				settings.globalEnvironmentProperty().set(new LinkedHashMap<>(globalEnvironment));
			}
			if (packMarketUrl != null) {
				settings.packMarketUrlProperty().set(packMarketUrl);
			}
			if (dependencyPolicy != null) {
				// The launcher keeps the default value when the name is unknown.
				try {
					settings.dependencyPolicyProperty().set(DshDependencyPolicy.valueOf(dependencyPolicy));
				} catch (IllegalArgumentException e) {
					LOG.warning("Unknown dependency policy in the settings: " + dependencyPolicy);
				}
			}
			if (marketSource != null) {
				settings.marketSourceProperty().set(marketSource);
			}
			if (skillSource != null) {
				settings.skillSourceProperty().set(skillSource);
			}
			if (cacheDirectory != null) {
				settings.cacheDirectoryProperty().set(cacheDirectory);
			}
			if (cacheDirectoryCustom != null) {
				settings.cacheDirectoryCustomProperty().set(cacheDirectoryCustom);
			}
			if (autoDownloadThreads != null) {
				settings.autoDownloadThreadsProperty().set(autoDownloadThreads);
			}
			if (downloadConcurrency != null) {
				settings.downloadConcurrencyProperty().set(downloadConcurrency);
			}
			if (proxyMode != null) {
				settings.proxyModeProperty().set(DshProxyMode.of(proxyMode));
			}
			if (proxyHost != null) {
				settings.proxyHostProperty().set(proxyHost);
			}
			if (proxyPort != null) {
				settings.proxyPortProperty().set(proxyPort);
			}
			if (proxyAuthenticated != null) {
				settings.proxyAuthenticatedProperty().set(proxyAuthenticated);
			}
			if (proxyUser != null) {
				settings.proxyUserProperty().set(proxyUser);
			}
			if (proxyPassword != null) {
				settings.proxyPasswordProperty().set(proxyPassword);
			}
		}

		/**
		 * One stored account.
		 *
		 * @param vendorId the vendor's id
		 * @param apiKey   the key
		 * @param baseUrl  the endpoint, for a vendor whose address is per account
		 * @param label    the name a person gives the account
		 */
		private record AccountSnapshot(@Nullable DshAccount.AccountKind kind, String vendorId, String apiKey,
		                               @Nullable String baseUrl, @Nullable String label, @Nullable String model,
		                               @Nullable String skinType, @Nullable String skinModel, @Nullable String skinPath,
		                               @Nullable String skinCapePath) {}

		/**
		 * A supplier added by address.
		 * <p>
		 * The harness needs all five fields to route a request.
		 */
		private record VendorSnapshot(String id, @Nullable String displayName, @Nullable String apiKeyEnv,
		                              @Nullable String api, @Nullable String baseUrl) {}
	}

	// The serializable view of [LauncherState].
	private static final class StateSnapshot {
		@SerializedName("x")
		private @Nullable Double x;

		@SerializedName("y")
		private @Nullable Double y;

		@SerializedName("width")
		private @Nullable Double width;

		@SerializedName("height")
		private @Nullable Double height;

		/**
		 * Captures window geometry into a serialisable snapshot.
		 *
		 * @param state the state to capture
		 * @return the snapshot
		 */
		static StateSnapshot of(LauncherState state) {
			StateSnapshot snapshot = new StateSnapshot();
			snapshot.x = state.getX();
			snapshot.y = state.getY();
			snapshot.width = state.getWidth();
			snapshot.height = state.getHeight();
			return snapshot;
		}

		/**
		 * Applies this snapshot onto a state object, ignoring unset fields.
		 *
		 * @param state the state to update
		 */
		void applyTo(LauncherState state) {
			if (x != null) {
				state.setX(x);
			}
			if (y != null) {
				state.setY(y);
			}
			if (width != null) {
				state.setWidth(width);
			}
			if (height != null) {
				state.setHeight(height);
			}
		}
	}
}
