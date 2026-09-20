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

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import org.glavo.monetfx.Brightness;
import org.glavo.monetfx.ColorRole;
import org.glavo.monetfx.ColorScheme;
import org.jackhuang.hmcl.theme.ResolvedTheme;
import org.jackhuang.hmcl.theme.ThemeColor;
import org.jackhuang.hmcl.theme.Themes;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/// Builds and owns the stylesheet list applied to the launcher scene.
///
/// The list is rebuilt in place whenever the theme color scheme changes, which
/// keeps every scene that bound to it up to date without re-applying CSS.
@NotNullByDefault
public final class StyleSheets {
    private StyleSheets() {
    }

    /// Index of the font stylesheet in [#stylesheets].
    private static final int FONT_STYLE_SHEET_INDEX = 0;

    /// Index of the generated theme stylesheet in [#stylesheets].
    private static final int THEME_STYLE_SHEET_INDEX = 1;

    /// Index of the brightness stylesheet in [#stylesheets].
    private static final int BRIGHTNESS_SHEET_INDEX = 2;

    /// The live stylesheet list shared by every scene the launcher creates.
    private static final ObservableList<String> stylesheets;

    static {
        stylesheets = FXCollections.observableList(Arrays.asList(
                "/assets/css/font.css",
                getThemeStyleSheet(),
                getBrightnessStyleSheet(),
                "/assets/css/root.css"));

        Themes.colorSchemeProperty().addListener(o -> {
            stylesheets.set(THEME_STYLE_SHEET_INDEX, getThemeStyleSheet());
            stylesheets.set(BRIGHTNESS_SHEET_INDEX, getBrightnessStyleSheet());
        });
    }

    /// Encodes a CSS document as a `data:` URI that JavaFX accepts as a stylesheet.
    ///
    /// @param styleSheet the CSS source
    /// @return the data URI
    private static String toStyleSheetUri(String styleSheet) {
        return "data:text/css;charset=UTF-8;base64,"
                + Base64.getEncoder().encodeToString(styleSheet.getBytes(StandardCharsets.UTF_8));
    }

    /// Selects the stylesheet matching the current color scheme brightness.
    ///
    /// @return the brightness stylesheet path
    private static String getBrightnessStyleSheet() {
        return Themes.getColorScheme().getBrightness() == Brightness.LIGHT
                ? "/assets/css/brightness-light.css"
                : "/assets/css/brightness-dark.css";
    }

    /// Appends one CSS custom-property declaration.
    ///
    /// @param builder the target buffer
    /// @param name    the custom-property name
    /// @param color   the color value
    private static void addColor(StringBuilder builder, String name, Color color) {
        builder.append("  ").append(name)
                .append(": ").append(ThemeColor.getColorDisplayName(color)).append(";\n");
    }

    /// Appends one translucent CSS custom-property declaration.
    ///
    /// @param builder the target buffer
    /// @param name    the custom-property name
    /// @param color   the color value
    /// @param opacity the alpha multiplier
    private static void addColor(StringBuilder builder, String name, Color color, double opacity) {
        builder.append("  ").append(name)
                .append(": ").append(ThemeColor.getColorDisplayNameWithOpacity(color, opacity)).append(";\n");
    }

    /// Appends one translucent color-role custom property, named after its alpha percentage.
    ///
    /// @param builder the target buffer
    /// @param scheme  the active color scheme
    /// @param role    the color role
    /// @param opacity the alpha multiplier
    private static void addColor(StringBuilder builder, ColorScheme scheme, ColorRole role, double opacity) {
        builder.append("  ").append(role.getVariableName())
                .append("-transparent-%02d".formatted((int) (100 * opacity)))
                .append(": ").append(ThemeColor.getColorDisplayNameWithOpacity(scheme.getColor(role), opacity))
                .append(";\n");
    }

    /// Generates the theme stylesheet for the active theme.
    ///
    /// The built-in default theme needs no generated variables and returns the
    /// hand-written blue stylesheet instead.
    ///
    /// @return a stylesheet path or data URI
    private static String getThemeStyleSheet() {
        final String blueCss = "/assets/css/blue.css";

        if (ResolvedTheme.DEFAULT.equals(Themes.getTheme()))
            return blueCss;

        ColorScheme scheme = Themes.getColorScheme();

        StringBuilder builder = new StringBuilder();
        builder.append("* {\n");
        for (ColorRole colorRole : ColorRole.ALL) {
            addColor(builder, colorRole.getVariableName(), scheme.getColor(colorRole));
        }

        addColor(builder, "-monet-primary-seed", scheme.getPrimaryColorSeed());

        addColor(builder, scheme, ColorRole.PRIMARY, 0.5);
        addColor(builder, scheme, ColorRole.PRIMARY, 0.8);
        addColor(builder, scheme, ColorRole.SECONDARY_CONTAINER, 0.5);
        addColor(builder, scheme, ColorRole.SURFACE, 0.5);
        addColor(builder, scheme, ColorRole.SURFACE, 0.8);
        addColor(builder, scheme, ColorRole.ON_SURFACE_VARIANT, 0.38);
        addColor(builder, scheme, ColorRole.SURFACE_CONTAINER_LOW, 0.8);
        addColor(builder, scheme, ColorRole.SECONDARY_CONTAINER, 0.8);
        addColor(builder, scheme, ColorRole.INVERSE_SURFACE, 0.8);

        builder.append("}\n");
        return toStyleSheetUri(builder.toString());
    }

    /// Binds the launcher stylesheets to a scene.
    ///
    /// The binding is content-based, so later rebuilds of the theme stylesheet
    /// reach this scene without calling this method again.
    ///
    /// @param scene the scene to style
    public static void init(Scene scene) {
        Bindings.bindContent(scene.getStylesheets(), stylesheets);
    }
}
