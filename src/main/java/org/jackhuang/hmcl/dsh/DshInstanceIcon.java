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
package org.jackhuang.hmcl.dsh;

import javafx.scene.image.Image;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/// The icon shown for a launcher instance.
///
/// The mechanism is HMCL's: an instance carries an icon selection, and the list
/// and the sidebar show it. The images available are the ones HMCL ships, minus
/// the mod-loader and contributor artwork, which mean nothing for a DeepSeek
/// Harness instance.
@NotNullByDefault
public enum DshInstanceIcon {
    /// The default icon.
    DEFAULT("grass"),
    /// Grass block.
    GRASS("grass"),
    /// Chest.
    CHEST("chest"),
    /// Chicken.
    CHICKEN("chicken"),
    /// Command block.
    COMMAND("command"),
    /// Crafting table.
    CRAFT_TABLE("craft_table"),
    /// Furnace.
    FURNACE("furnace"),
    /// Burning TNT.
    BURNING_TNT("burningtnt"),
    /// Terracotta.
    TERRACOTTA("terracotta"),
    /// Unknown pack.
    UNKNOWN_PACK("unknown_pack"),
    /// Unknown server.
    UNKNOWN_SERVER("unknown_server");

    private final String asset;

    DshInstanceIcon(String asset) {
        this.asset = asset;
    }

    /// Returns the identifier stored with an instance.
    ///
    /// @return the lower-case identifier
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /// Resolves an icon from its stored identifier.
    ///
    /// @param value the stored identifier, possibly `null` or unknown
    /// @return the matching icon, or [DshInstanceIcon#DEFAULT]
    public static DshInstanceIcon of(@Nullable String value) {
        if (value != null) {
            for (DshInstanceIcon icon : values()) {
                if (icon.id().equalsIgnoreCase(value.trim())) {
                    return icon;
                }
            }
        }
        return DEFAULT;
    }

    /// Loads the icon image from the bundled assets.
    ///
    /// @return the image, or `null` when the asset is missing
    public @Nullable Image load() {
        var url = DshInstanceIcon.class.getResource("/assets/img/" + asset + ".png");
        return url == null ? null : new Image(url.toExternalForm(), true);
    }
}
