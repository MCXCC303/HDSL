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
package com.mcxcc303.hdsl.skin;

import javafx.scene.image.Image;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * Bundled default skins that ship with the launcher.
 */
@NotNullByDefault
public enum DefaultSkin {
	ALEX("alex"),
	ARI("ari"),
	EFE("efe"),
	KAI("kai"),
	MAKENA("makena"),
	NOOR("noor"),
	STEVE("steve"),
	SUNNY("sunny"),
	ZURI("zuri");

	private static final String DIRECTORY = "/assets/img/skin/";

	private final String baseName;
	private @Nullable Image wide;
	private @Nullable Image slim;

	DefaultSkin(String baseName) {
		this.baseName = baseName;
	}

	/**
	 * Returns the default skin chosen for an account UUID.
	 *
	 * @param uuid the account UUID
	 * @return the chosen skin and whether it uses the slim model
	 */
	public static Chosen forUuid(UUID uuid) {
		DefaultSkin[] skins = values();
		int index = Math.floorMod(uuid.hashCode(), skins.length * 2);
		if (index < skins.length) {
			return new Chosen(skins[index].image(true), true);
		}
		return new Chosen(skins[index - skins.length].image(false), false);
	}

	/**
	 * Returns the display name of this skin.
	 *
	 * @return the display name
	 */
	public String displayName() {
		return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
	}

	/**
	 * Loads the image for the specified model arm width.
	 *
	 * @param slim whether to load the slim-arm variant
	 * @return the image, or {@code null} if unreadable
	 */
	public @Nullable Image image(boolean slim) {
		if (slim ? this.slim != null : wide != null) {
			return slim ? this.slim : wide;
		}

		String path = DIRECTORY + (slim ? "slim/" : "wide/") + baseName + ".png";
		try (InputStream stream = DefaultSkin.class.getResourceAsStream(path)) {
			if (stream == null) {
				Logger.LOG.warning("No bundled skin at " + path);
				return null;
			}
			Image image = new Image(stream);
			if (image.isError() || image.getWidth() <= 0) {
				Logger.LOG.warning("The bundled skin " + path + " could not be read");
				return null;
			}
			if (slim) {
				this.slim = image;
			} else {
				wide = image;
			}
			return image;
		} catch (IOException | RuntimeException e) {
			Logger.LOG.warning("The bundled skin " + path + " could not be read", e);
			return null;
		}
	}

	/**
	 * A chosen default skin and whether it uses the slim model.
	 *
	 * @param image the skin image, or {@code null} if unreadable
	 * @param slim  whether it is drawn on the slim body
	 */
	public record Chosen(@Nullable Image image, boolean slim) {
	}
}
