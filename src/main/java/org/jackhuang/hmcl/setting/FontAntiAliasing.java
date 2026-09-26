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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/// How the launcher draws text.
///
/// The original offers the choice because the answer depends on the display rather than
/// on taste: sub-pixel rendering is sharper on a screen whose pixels line up with the
/// grid, and produces coloured fringes where it does not — which is every scaled or
/// rotated display, and most high-density ones.
@NotNullByDefault
public enum FontAntiAliasing {
    /// Whatever the toolkit decides, which is what the platform usually gets right.
    AUTO("auto"),

    /// Grey-scale smoothing: no coloured fringes, slightly softer.
    GRAY("gray"),

    /// Sub-pixel smoothing: sharper, and coloured at the edges.
    LCD("lcd");

    /// The value the setting is stored as.
    private final String id;

    /// Creates a choice.
    ///
    /// @param id the stored value
    FontAntiAliasing(String id) {
        this.id = id;
    }

    /// Returns the value the setting is stored as.
    ///
    /// @return the id
    public String id() {
        return id;
    }

    /// Reads a stored value.
    ///
    /// @param value the stored value, or `null`
    /// @return the choice, or [FontAntiAliasing#AUTO] when it is not one
    public static FontAntiAliasing of(@Nullable String value) {
        if (value != null) {
            for (FontAntiAliasing choice : values()) {
                if (choice.id.equalsIgnoreCase(value.trim())) {
                    return choice;
                }
            }
        }
        return AUTO;
    }

    /// Returns the value the toolkit's `prism.lcdtext` property takes for this choice.
    ///
    /// The toolkit's property is a boolean, so "let the toolkit decide" is the absence of
    /// an answer rather than a third value.
    ///
    /// @return `"true"` or `"false"`, or `null` when the toolkit decides
    public @Nullable String lcdTextProperty() {
        return switch (this) {
            case LCD -> "true";
            case GRAY -> "false";
            case AUTO -> null;
        };
    }

    /// Returns the translation key for this choice.
    ///
    /// @return the key suffix
    public String i18nKey() {
        return "settings.launcher.font.anti_aliasing." + name().toLowerCase(Locale.ROOT);
    }
}
