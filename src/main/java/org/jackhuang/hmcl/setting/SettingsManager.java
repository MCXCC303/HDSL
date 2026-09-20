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

import com.google.gson.annotations.SerializedName;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Owns the single [LauncherSettings] instance and persists it to disk.
///
/// Persistence is deliberately explicit rather than reflective: the settings
/// object holds JavaFX properties, which serialise poorly, so a plain
/// serialisable snapshot is written instead. This keeps the on-disk format
/// stable and easy to migrate as HMCL-DSH grows.
@NotNullByDefault
public final class SettingsManager {
    private SettingsManager() {
    }

    /// The settings file inside the per-user HMCL-DSH home.
    private static final Path SETTINGS_PATH = Metadata.HMCL_USER_HOME.resolve("launcher-settings.json");

    /// The window state file inside the per-user HMCL-DSH home.
    private static final Path STATE_PATH = Metadata.HMCL_USER_HOME.resolve("launcher-state.json");

    /// The lazily created settings singleton.
    private static @Nullable LauncherSettings launcherSettings;

    /// The lazily created window state singleton.
    private static @Nullable LauncherState launcherState;

    /// Returns the process-wide window state, loading it on first use.
    ///
    /// @return the launcher window state singleton
    public static synchronized LauncherState state() {
        LauncherState state = launcherState;
        if (state == null) {
            state = new LauncherState();
            launcherState = state;
            loadState(state);
        }
        return state;
    }

    /// Reads persisted window geometry into `state`, ignoring a missing file.
    ///
    /// @param state the state to populate
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

    /// Persists the current window geometry.
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

    /// Returns the process-wide launcher settings, loading them on first use.
    ///
    /// @return the launcher settings singleton
    public static LauncherSettings settings() {
        LauncherSettings settings = launcherSettings;
        if (settings == null) {
            settings = load();
            launcherSettings = settings;
        }
        return settings;
    }

    /// Loads settings from disk, returning defaults when the file is absent or unreadable.
    ///
    /// @return the loaded settings
    private static LauncherSettings load() {
        LauncherSettings settings = new LauncherSettings();
        if (!Files.isRegularFile(SETTINGS_PATH)) {
            return settings;
        }
        try {
            Snapshot snapshot = JsonUtils.fromJsonFile(SETTINGS_PATH, Snapshot.class);
            if (snapshot != null) {
                snapshot.applyTo(settings);
            }
        } catch (Exception e) {
            LOG.warning("Failed to load launcher settings from " + SETTINGS_PATH, e);
        }
        return settings;
    }

    /// Writes the current launcher settings to disk.
    ///
    /// Failures are logged rather than propagated so that a read-only home
    /// directory cannot make the launcher unusable.
    public static void save() {
        try {
            Path parent = SETTINGS_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JsonUtils.writeToJsonFile(SETTINGS_PATH, Snapshot.of(settings()));
        } catch (IOException e) {
            LOG.warning("Failed to save launcher settings to " + SETTINGS_PATH, e);
        }
    }

    /// The serialisable view of [LauncherSettings].
    ///
    /// Every field is nullable so that a partially written or older file still
    /// loads; missing values keep the defaults already present in
    /// [LauncherSettings].
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

        @SerializedName("backgroundLoadPolicy")
        private @Nullable String backgroundLoadPolicy;

        @SerializedName("logFontFamily")
        private @Nullable String logFontFamily;

        @SerializedName("logFontSize")
        private @Nullable Double logFontSize;

        @SerializedName("logLines")
        private @Nullable Integer logLines;

        @SerializedName("animationDisabled")
        private @Nullable Boolean animationDisabled;

        @SerializedName("selectedInstanceId")
        private @Nullable String selectedInstanceId;

        @SerializedName("openBrowserOnLaunch")
        private @Nullable Boolean openBrowserOnLaunch;

        /// Captures the current settings into a serialisable snapshot.
        ///
        /// @param settings the settings to capture
        /// @return the snapshot
        static Snapshot of(LauncherSettings settings) {
            Snapshot snapshot = new Snapshot();
            ThemeReference theme = settings.getSelectedThemeOrDefault();
            snapshot.selectedThemePackId = theme.packId();
            snapshot.selectedThemeId = theme.themeId();
            snapshot.themeAppearanceOverrides = new ArrayList<>(settings.getThemeAppearanceOverrides());
            snapshot.themeBrightnessMode = settings.themeBrightnessModeProperty().get();
            snapshot.themeColor = settings.customThemeColorProperty().get().name();
            snapshot.themeColorType = settings.themeColorTypeProperty().get().name();
            snapshot.themeColorStyle = settings.themeColorStyleProperty().get().name();
            snapshot.titleBarTransparent = settings.titleBarTransparentProperty().get();
            snapshot.windowTransparent = settings.windowTransparentProperty().get();
            snapshot.backgroundType = settings.backgroundTypeProperty().get().name();
            snapshot.builtinBackgroundId = settings.builtinBackgroundIdProperty().get();
            snapshot.customBackgroundImagePath = settings.customBackgroundImagePathProperty().get();
            snapshot.networkBackgroundImageUrl = settings.networkBackgroundImageUrlProperty().get();
            snapshot.backgroundOpacity = settings.backgroundOpacityProperty().get();
            snapshot.networkBackgroundImageCachePolicy = settings.networkBackgroundImageCachePolicyProperty().get().name();
            snapshot.backgroundFallbackType = settings.backgroundFallbackTypeProperty().get().name();
            snapshot.backgroundLoadPolicy = settings.backgroundLoadPolicyProperty().get().name();
            snapshot.logFontFamily = settings.logFontFamilyProperty().get();
            snapshot.logFontSize = settings.logFontSizeProperty().get();
            snapshot.logLines = settings.logLinesProperty().get();
            snapshot.animationDisabled = settings.animationDisabledProperty().get();
            snapshot.selectedInstanceId = settings.selectedInstanceIdProperty().get();
            snapshot.openBrowserOnLaunch = settings.openBrowserOnLaunchProperty().get();
            return snapshot;
        }

        /// Applies this snapshot onto a settings object, ignoring unset fields.
        ///
        /// @param settings the settings to update
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
                settings.customThemeColorProperty().set(new ThemeColor(themeColor, ThemeColor.DEFAULT.color()));
            }
            if (themeColorType != null) {
                settings.themeColorTypeProperty().set(ThemeColorType.valueOf(themeColorType));
            }
            if (themeColorStyle != null) {
                settings.themeColorStyleProperty().set(ColorStyle.valueOf(themeColorStyle));
            }
            if (titleBarTransparent != null) {
                settings.titleBarTransparentProperty().set(titleBarTransparent);
            }
            if (windowTransparent != null) {
                settings.windowTransparentProperty().set(windowTransparent);
            }
            if (backgroundType != null) {
                settings.backgroundTypeProperty().set(BackgroundType.valueOf(backgroundType));
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
                        .set(NetworkBackgroundImageCachePolicy.valueOf(networkBackgroundImageCachePolicy));
            }
            if (backgroundFallbackType != null) {
                settings.backgroundFallbackTypeProperty().set(BackgroundType.valueOf(backgroundFallbackType));
            }
            if (backgroundLoadPolicy != null) {
                settings.backgroundLoadPolicyProperty().set(BackgroundLoadPolicy.valueOf(backgroundLoadPolicy));
            }
            if (logFontFamily != null) {
                settings.logFontFamilyProperty().set(logFontFamily);
            }
            if (logFontSize != null) {
                settings.logFontSizeProperty().set(logFontSize);
            }
            if (logLines != null) {
                settings.logLinesProperty().set(logLines);
            }
            if (animationDisabled != null) {
                settings.animationDisabledProperty().set(animationDisabled);
            }
            if (selectedInstanceId != null) {
                settings.selectedInstanceIdProperty().set(selectedInstanceId);
            }
            if (openBrowserOnLaunch != null) {
                settings.openBrowserOnLaunchProperty().set(openBrowserOnLaunch);
            }
        }
    }

    /// The serialisable view of [LauncherState].
    private static final class StateSnapshot {
        @SerializedName("x")
        private @Nullable Double x;

        @SerializedName("y")
        private @Nullable Double y;

        @SerializedName("width")
        private @Nullable Double width;

        @SerializedName("height")
        private @Nullable Double height;

        /// Captures window geometry into a serialisable snapshot.
        ///
        /// @param state the state to capture
        /// @return the snapshot
        static StateSnapshot of(LauncherState state) {
            StateSnapshot snapshot = new StateSnapshot();
            snapshot.x = state.getX();
            snapshot.y = state.getY();
            snapshot.width = state.getWidth();
            snapshot.height = state.getHeight();
            return snapshot;
        }

        /// Applies this snapshot onto a state object, ignoring unset fields.
        ///
        /// @param state the state to update
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
