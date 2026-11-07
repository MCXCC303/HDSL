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
package com.mcxcc303.hdsl.core;

import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Network environment configuration for child processes.
 */
@NotNullByDefault
public final class DshNetworkSettings {
	private DshNetworkSettings() {
	}

	/**
	 * Returns environment variables contributed by launcher network settings.
	 *
	 * @return the environment variables, empty when nothing is configured
	 */
	public static Map<String, String> environment() {
		Map<String, String> environment = new LinkedHashMap<>();
		try {
			LauncherSettings settings = SettingsManager.settings();
			DshProxyMode mode = settings.proxyModeProperty().get() == null
			                    ? DshProxyMode.SYSTEM
			                    : settings.proxyModeProperty().get();
			switch (mode) {
				case SYSTEM -> {
					// Inherit default system proxy configuration.
				}
				case NONE -> {
					// Explicitly clear proxy variables so system defaults are not inherited.
					environment.put("HTTP_PROXY", "");
					environment.put("HTTPS_PROXY", "");
					environment.put("NO_PROXY", "*");
				}
				case HTTP, SOCKS -> {
					String address = address(settings, mode);
					if (address != null) {
						environment.put("HTTP_PROXY", address);
						environment.put("HTTPS_PROXY", address);
						// ALL_PROXY is read by curl and various tools, especially for SOCKS proxies.
						environment.put("ALL_PROXY", address);
					}
				}
			}

			Integer concurrency = settings.downloadConcurrencyProperty().get();
			if (concurrency != null && concurrency > 0) {
				// npm_config_maxsockets limits concurrent socket connections in npm.
				environment.put("npm_config_maxsockets", Integer.toString(concurrency));
			}
		} catch (RuntimeException e) {
			Logger.LOG.warning("Could not read the network settings", e);
		}
		return environment;
	}

	private static @Nullable String address(LauncherSettings settings, DshProxyMode mode) {
		String host = settings.proxyHostProperty().get();
		if (host == null || host.isBlank()) {
			return null;
		}
		String port = settings.proxyPortProperty().get();
		String credentials = "";
		if (settings.proxyAuthenticatedProperty().get()) {
			String user = settings.proxyUserProperty().get();
			String password = settings.proxyPasswordProperty().get();
			if (user != null && !user.isBlank()) {
				credentials = user + ":" + (password == null ? "" : password) + "@";
			}
		}
		String scheme = mode == DshProxyMode.SOCKS ? "socks5" : "http";
		return scheme + "://" + credentials + host.trim()
		       + (port == null || port.isBlank() ? "" : ":" + port.trim());
	}
}
