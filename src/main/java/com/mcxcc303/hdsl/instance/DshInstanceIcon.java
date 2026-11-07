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
package com.mcxcc303.hdsl.instance;

import javafx.scene.image.Image;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Icons available for launcher instances.
 */
@NotNullByDefault
public enum DshInstanceIcon {
	DSH_APPLICATION("dsh_application"),
	DSH_BLACK("dsh_black"),
	DSH_WHITE("dsh_white"),
	DEFAULT("grass"),
	GRASS("grass"),
	CHEST("chest"),
	CHICKEN("chicken"),
	COMMAND("command"),
	APRIL_FOOLS("april_fools"),
	OPTIFINE("optifine"),
	CRAFT_TABLE("craft_table"),
	FABRIC("fabric"),
	LEGACY_FABRIC("legacyfabric"),
	FORGE("forge"),
	CLEANROOM("cleanroom"),
	NEO_FORGE("neoforge"),
	FURNACE("furnace"),
	QUILT("quilt");

	private final String asset;

	DshInstanceIcon(String asset) {
		this.asset = asset;
	}

	/**
	 * Resolves an icon from its stored identifier.
	 *
	 * @param value the stored identifier, possibly {@code null} or unknown
	 * @return the matching icon, or {@link #DEFAULT}
	 */
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

	/**
	 * Returns the identifier stored with an instance.
	 *
	 * @return the lower-case identifier
	 */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Returns the bundled image asset name without its extension.
	 *
	 * @return the asset name
	 */
	public String assetName() {
		return asset;
	}

	/**
	 * Loads the icon image from the bundled assets.
	 *
	 * @return the image, or {@code null} when the asset is missing
	 */
	public @Nullable Image load() {
		return load(1.0);
	}

	/**
	 * Loads the icon image at the requested display scale.
	 *
	 * @param scale the display scale, 1.0 for standard resolution
	 * @return the image, or {@code null} when the asset is missing
	 */
	public @Nullable Image load(double scale) {
		String suffix = scale > 1.5 ? "@2x" : "";
		var url = DshInstanceIcon.class.getResource("/assets/img/" + asset + suffix + ".png");
		if (url == null && !suffix.isEmpty()) {
			url = DshInstanceIcon.class.getResource("/assets/img/" + asset + ".png");
		}
		return url == null ? null : new Image(url.toExternalForm(), true);
	}
}
