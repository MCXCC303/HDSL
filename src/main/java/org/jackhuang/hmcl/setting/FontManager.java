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
package org.jackhuang.hmcl.setting;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Scene;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Manages and applies user-selected font families to the JavaFX scene graph.
 */
@NotNullByDefault
public final class FontManager {
	private FontManager() {
	}

	private static final ReadOnlyObjectWrapper<@Nullable String> font =
			new ReadOnlyObjectWrapper<>(null);

	private static @Nullable Scene scene;

	/**
	 * Returns the currently applied font family.
	 *
	 * @return the font family, or {@code null} for the platform default
	 */
	public static @Nullable String getFontFamily() {
		return font.get();
	}

	/**
	 * Returns the read-only property for the applied font family.
	 *
	 * @return the font family property
	 */
	public static ReadOnlyObjectProperty<@Nullable String> fontProperty() {
		return font.getReadOnlyProperty();
	}

	/**
	 * Attaches a scene to receive font family styling updates.
	 *
	 * @param newScene the scene to manage
	 */
	public static void attach(Scene newScene) {
		scene = newScene;
		apply();
	}

	/**
	 * Sets the preferred font family, or {@code null} to restore the default.
	 *
	 * @param family the font family name, or {@code null}
	 */
	public static void setFontFamily(@Nullable String family) {
		String normalized = family == null || family.isBlank() ? null : family.trim();
		settings().launcherFontFamilyProperty().set(normalized);
		apply();
	}

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

	/**
	 * Returns all font families available on the system, sorted alphabetically.
	 *
	 * @return sorted list of available font family names
	 */
	public static java.util.List<String> availableFamilies() {
		return javafx.scene.text.Font.getFamilies().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
	}
}
