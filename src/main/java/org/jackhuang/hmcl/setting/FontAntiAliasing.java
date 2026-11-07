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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Font anti-aliasing rendering mode options for JavaFX text rendering.
 */
@NotNullByDefault
public enum FontAntiAliasing {
	AUTO("auto"),
	GRAY("gray"),
	LCD("lcd");

	private final String id;

	FontAntiAliasing(String id) {
		this.id = id;
	}

	/**
	 * Returns the stored identifier string.
	 *
	 * @return the identifier
	 */
	public String id() {
		return id;
	}

	/**
	 * Parses a stored anti-aliasing identifier, defaulting to {@link #AUTO}.
	 *
	 * @param value the stored string, or {@code null}
	 * @return the matching anti-aliasing option
	 */
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

	/**
	 * Returns the value for the JavaFX {@code prism.lcdtext} system property.
	 *
	 * @return {@code "true"}, {@code "false"}, or {@code null} for auto
	 */
	public @Nullable String lcdTextProperty() {
		return switch (this) {
			case LCD -> "true";
			case GRAY -> "false";
			case AUTO -> null;
		};
	}

	/**
	 * Returns the translation key for this option.
	 *
	 * @return the i18n translation key
	 */
	public String i18nKey() {
		return "settings.launcher.font.anti_aliasing." + name().toLowerCase(Locale.ROOT);
	}
}
