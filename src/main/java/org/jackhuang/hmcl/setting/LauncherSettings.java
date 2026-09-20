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

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import org.glavo.monetfx.ColorStyle;
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

    /// The source used to render the launcher background.
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

    /// The font family used by the log view, or empty for the default.
    private final StringProperty logFontFamily = new SimpleStringProperty();

    /// The font size used by the log view.
    private final DoubleProperty logFontSize = new SimpleDoubleProperty(12);

    /// The number of log lines retained, or `null` for unlimited.
    private final ObjectProperty<@Nullable Integer> logLines = new SimpleObjectProperty<>();

    /// Whether animations are disabled; `null` follows the platform setting.
    private final ObjectProperty<@Nullable Boolean> animationDisabled = new SimpleObjectProperty<>();

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

    /// Returns the retained log line count property.
    ///
    /// @return the log line count property
    public ObjectProperty<@Nullable Integer> logLinesProperty() {
        return logLines;
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
}
