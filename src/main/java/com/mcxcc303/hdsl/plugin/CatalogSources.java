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
package com.mcxcc303.hdsl.plugin;

import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Configures the source URLs and npm packages for loading the plugin catalogue.
 */
@NotNullByDefault
public final class CatalogSources {
	/** Pattern matching valid npm package names. */
	static final Pattern NPM_NAME = Pattern.compile("^(@[a-z0-9-~][a-z0-9-._~]*/)?[a-z0-9-~][a-z0-9-._~]*$");

	/**
	 * Returns every place the catalogue is read from, in the order they are tried.
	 *
	 * @return the sources, in the order they are read
	 */
	public static List<String> sources() {
		String property = System.getProperty("hdsl.pluginCatalog");
		if (property != null && !property.isBlank()) {
			return List.of(property.trim());
		}
		return marketSource().addresses();
	}

	/**
	 * Returns the source read when every configured source has failed.
	 *
	 * @return the source to fall back on, or {@code null}
	 */
	public static @Nullable String fallbackSource() {
		return marketSource().fallback();
	}

	/**
	 * Returns the configured plugin market source.
	 *
	 * @return the market source
	 */
	public static MarketSource marketSource() {
		try {
			return MarketSource.of(SettingsManager.settings().marketSourceProperty().get());
		} catch (RuntimeException e) {
			LOG.warning("Could not read the plugin market from the settings", e);
			return MarketSource.AWESOME_DSH_PLUGIN;
		}
	}

	/**
	 * Configures the market source plugins are read from.
	 *
	 * @param source the market source
	 */
	public static void setMarketSource(MarketSource source) {
		try {
			LauncherSettings settings = SettingsManager.settings();
			settings.marketSourceProperty().set(source == null ? MarketSource.AWESOME_DSH_PLUGIN.id() : source.id());
			SettingsManager.save();
		} catch (RuntimeException e) {
			LOG.warning("Could not write the plugin market down", e);
		}
	}

	/**
	 * Reports whether a source names an npm package rather than a URL address.
	 *
	 * @param source the source
	 * @return whether it is a package name
	 */
	public static boolean isPackageSource(String source) {
		String trimmed = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
		return !trimmed.startsWith("http://") && !trimmed.startsWith("https://")
		       && !trimmed.startsWith("file:");
	}

	/**
	 * Reports whether a source string is valid.
	 *
	 * @param source the source to validate
	 * @return whether it is usable
	 */
	public static boolean isUsableSource(@Nullable String source) {
		if (source == null || source.isBlank()) {
			return false;
		}
		String trimmed = source.trim();
		if (isPackageSource(trimmed)) {
			return CatalogSources.NPM_NAME.matcher(trimmed).matches();
		}
		try {
			URI uri = URI.create(trimmed);
			return uri.getScheme() != null && uri.getHost() != null;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}

