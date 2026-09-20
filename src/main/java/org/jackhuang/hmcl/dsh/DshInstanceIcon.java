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
/// The mechanism and the set are HMCL's: an instance carries an icon selection,
/// the list and the sidebar show it, and the chooser offers the same images the
/// original does. The mod-loader artwork is kept for that parity — a launcher
/// that offers fewer choices than the one it is modelled on reads as a
/// limitation rather than a decision.
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
    /// The April Fools artwork.
    APRIL_FOOLS("april_fools"),
    /// The OptiFine logo.
    OPTIFINE("optifine"),
    /// Crafting table.
    CRAFT_TABLE("craft_table"),
    /// The Fabric logo.
    FABRIC("fabric"),
    /// The Legacy Fabric logo.
    LEGACY_FABRIC("legacyfabric"),
    /// The Forge logo.
    FORGE("forge"),
    /// The Cleanroom logo.
    CLEANROOM("cleanroom"),
    /// The NeoForge logo.
    NEO_FORGE("neoforge"),
    /// Furnace.
    FURNACE("furnace"),
    /// The Quilt logo.
    QUILT("quilt");

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
