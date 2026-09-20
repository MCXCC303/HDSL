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
import org.jackhuang.hmcl.setting.BackgroundType;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.theme.Theme;
import org.jackhuang.hmcl.theme.ThemePackManager;
import org.jackhuang.hmcl.theme.ThemeReference;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The "appearance" tab of the launcher settings page.
///
/// Drives the transplanted theme engine: theme pack selection, brightness mode,
/// background source, background opacity and window transparency.
@NotNullByDefault
public final class AppearanceSettingsPage extends ScrollPane {
    /// Brightness mode identifiers accepted by the theme engine.
    private static final List<String> BRIGHTNESS_MODES = List.of("auto", "light", "dark");

    /// Background opacity presets.
    private static final List<Double> OPACITY_PRESETS = List.of(0.25, 0.5, 0.75, 0.9, 1.0);

    /// Creates the appearance settings tab.
    public AppearanceSettingsPage() {
        setFitToWidth(true);

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        setContent(root);

        // Must run after the content is installed: smooth scrolling binds to
        // the content node and fails on a null content.
        FXUtils.smoothScrolling(this);

        root.getChildren().addAll(buildThemeList(), buildBackgroundList(), buildWindowList());
    }

    /// Builds the theme section: theme pack and brightness mode.
    ///
    /// @return the assembled component list
    private ComponentList buildThemeList() {
        LineSelectButton<ThemeReference> theme = new LineSelectButton<>();
        theme.setTitle(i18n("settings.launcher.theme"));
        theme.setItems(installedThemeReferences());
        theme.setNullSafeConverter(AppearanceSettingsPage::displayNameOf);
        theme.setValue(settings().getSelectedThemeOrDefault());
        theme.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !Objects.equals(oldValue, newValue)) {
                settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_COLOR);
                settings().selectedThemeProperty().set(newValue);
            }
        });

        LineSelectButton<String> brightness = new LineSelectButton<>();
        brightness.setTitle(i18n("dsh.settings.theme.brightness"));
        brightness.setItems(BRIGHTNESS_MODES);
        brightness.setNullSafeConverter(mode -> i18n("dsh.settings.theme.brightness." + mode));
        brightness.setValue(settings().themeBrightnessModeProperty().get());
        brightness.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE);
                settings().themeBrightnessModeProperty().set(newValue);
            }
        });

        ComponentList list = new ComponentList();
        list.getContent().addAll(theme, brightness);
        return list;
    }

    /// Enumerates every theme the installed theme packs expose.
    ///
    /// Falls back to the built-in default reference when the theme pack
    /// directory cannot be read, so the selector is never empty.
    ///
    /// @return the selectable theme references
    private static List<ThemeReference> installedThemeReferences() {
        List<ThemeReference> references = new ArrayList<>();
        try {
            for (ThemePackManager.InstalledThemePack pack : ThemePackManager.listInstalled()) {
                List<Theme> themes = pack.manifest().themes();
                if (themes.isEmpty()) {
                    references.add(new ThemeReference(pack.manifest().id(), null));
                } else {
                    for (Theme candidate : themes) {
                        references.add(new ThemeReference(pack.manifest().id(), candidate.id()));
                    }
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to enumerate installed theme packs", e);
        }
        if (references.isEmpty()) {
            references.add(LauncherSettings.DEFAULT_THEME_REFERENCE);
        }
        return references;
    }

    /// Renders a theme reference for the selector.
    ///
    /// @param reference the reference to render
    /// @return a human-readable label
    private static String displayNameOf(ThemeReference reference) {
        return reference.themeId() == null
                ? reference.packId()
                : reference.packId() + " / " + reference.themeId();
    }

    /// Builds the background section.
    ///
    /// @return the assembled component list
    private ComponentList buildBackgroundList() {
        LineSelectButton<BackgroundType> backgroundType = new LineSelectButton<>();
        backgroundType.setTitle(i18n("dsh.settings.background"));
        backgroundType.setItems(List.of(
                BackgroundType.DEFAULT,
                BackgroundType.BUILTIN,
                BackgroundType.CUSTOM,
                BackgroundType.NETWORK,
                BackgroundType.PAINT,
                BackgroundType.THEME_COLOR));
        backgroundType.setNullSafeConverter(type -> i18n("dsh.settings.background." + type.name().toLowerCase()));
        backgroundType.setValue(settings().backgroundTypeProperty().get());
        backgroundType.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BACKGROUND);
                settings().backgroundTypeProperty().set(newValue);
            }
        });

        LineSelectButton<Double> opacity = new LineSelectButton<>();
        opacity.setTitle(i18n("dsh.settings.background.opacity"));
        opacity.setItems(OPACITY_PRESETS);
        opacity.setNullSafeConverter(value -> "%d%%".formatted((int) Math.round(value * 100)));
        opacity.setValue(nearestPreset(settings().backgroundOpacityProperty().get()));
        opacity.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BACKGROUND_OPACITY);
                settings().backgroundOpacityProperty().set(newValue);
            }
        });

        ComponentList list = new ComponentList();
        list.getContent().addAll(backgroundType, opacity);
        return list;
    }

    /// Builds the window section.
    ///
    /// @return the assembled component list
    private ComponentList buildWindowList() {
        LineToggleButton transparentTitleBar = new LineToggleButton();
        transparentTitleBar.setTitle(i18n("dsh.settings.title_bar_transparent"));
        transparentTitleBar.setSelected(settings().titleBarTransparentProperty().get());
        transparentTitleBar.selectedProperty().addListener((observable, oldValue, newValue) -> {
            settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_TITLE_BAR_TRANSPARENT);
            settings().titleBarTransparentProperty().set(newValue);
        });

        ComponentList list = new ComponentList();
        list.getContent().add(transparentTitleBar);
        return list;
    }

    /// Snaps an arbitrary opacity to the nearest preset so the selector can show it.
    ///
    /// @param value the current opacity
    /// @return the closest preset
    private static double nearestPreset(double value) {
        double best = OPACITY_PRESETS.get(0);
        for (double preset : OPACITY_PRESETS) {
            if (Math.abs(preset - value) < Math.abs(best - value)) {
                best = preset;
            }
        }
        return best;
    }
}
