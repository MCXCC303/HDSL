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

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Scene;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Applies the launcher's font choice to the interface.
///
/// The bundled `font.css` sets `-fx-font-family: -fx-base-font-family`, so a
/// chosen family only has to be published on the scene root as an inline style:
/// an inline declaration outranks the stylesheet, which is what makes the choice
/// take effect immediately rather than only on the next start.
@NotNullByDefault
public final class FontManager {
    private FontManager() {
    }

    /// The family currently applied, or `null` when the platform default is used.
    private static final ReadOnlyObjectWrapper<@Nullable String> font =
            new ReadOnlyObjectWrapper<>(null);

    /// The scene the font is applied to, once the interface exists.
    private static @Nullable Scene scene;

    /// Returns the family currently applied.
    ///
    /// @return the family, or `null` for the platform default
    public static @Nullable String getFontFamily() {
        return font.get();
    }

    /// Returns the applied-family property.
    ///
    /// @return the property
    public static ReadOnlyObjectProperty<@Nullable String> fontProperty() {
        return font.getReadOnlyProperty();
    }

    /// Remembers the scene whose font this manager controls.
    ///
    /// @param newScene the scene
    public static void attach(Scene newScene) {
        scene = newScene;
        apply();
    }

    /// Chooses the font family.
    ///
    /// An empty or `null` family restores the platform default.
    ///
    /// @param family the family, or `null` for the default
    public static void setFontFamily(@Nullable String family) {
        String normalized = family == null || family.isBlank() ? null : family.trim();
        settings().launcherFontFamilyProperty().set(normalized);
        apply();
    }

    /// Resolves the stored choice and pushes it to the scene.
    ///
    /// The order is the user's choice, then a start-up override, then the
    /// platform default, so a packaged build can be forced to a font without
    /// changing the stored settings.
    private static void apply() {
        @Nullable String family = settings().launcherFontFamilyProperty().get();
        if (family == null) {
            family = System.getProperty("hdsl.font.override");
        }
        if (family == null) {
            family = System.getenv("HDSL_FONT");
        }

        font.set(family);

        Scene target = scene;
        if (target == null) {
            return;
        }
        if (family == null) {
            target.getRoot().setStyle("");
        } else {
            target.getRoot().setStyle("-fx-font-family: \"" + family.replace("\"", "") + "\";");
        }
        LOG.info("Font family: " + (family == null ? "system default" : family));
    }

    /// Lists the font families available on this system.
    ///
    /// @return the family names, sorted
    public static java.util.List<String> availableFamilies() {
        return javafx.scene.text.Font.getFamilies().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
