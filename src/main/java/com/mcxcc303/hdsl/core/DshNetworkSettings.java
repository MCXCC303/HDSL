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

import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Network environment configuration for child processes.
 */
@NotNullByDefault
public final class DshNetworkSettings {
	private static volatile Supplier<Map<String, String>> environmentSupplier =
			() -> computeEnvironment(DshProxyMode.SYSTEM, null, null, false, null, null, 64);

	private DshNetworkSettings() {
	}

	/**
	 * Configures the supplier of network environment variables.
	 *
	 * @param supplier the environment supplier
	 * @return the previously installed supplier
	 */
	public static Supplier<Map<String, String>> setSupplier(Supplier<Map<String, String>> supplier) {
		Supplier<Map<String, String>> previous = environmentSupplier;
		environmentSupplier = Objects.requireNonNull(supplier, "supplier");
		return previous;
	}

	/**
	 * Returns environment variables contributed by launcher network settings.
	 *
	 * @return the environment variables, empty when nothing is configured
	 */
	public static Map<String, String> environment() {
		try {
			return environmentSupplier.get();
		} catch (RuntimeException e) {
			Logger.LOG.warning("Could not read the network settings", e);
			return Map.of();
		}
	}

	/**
	 * Computes environment variables from raw network proxy configuration settings.
	 *
	 * @param mode          the proxy mode
	 * @param host          the proxy host or empty/null
	 * @param port          the proxy port or empty/null
	 * @param authenticated whether authentication is enabled
	 * @param user          the proxy username or empty/null
	 * @param password      the proxy password or empty/null
	 * @param concurrency   the maximum concurrent connections or null
	 * @return the computed environment variables
	 */
	public static Map<String, String> computeEnvironment(
			DshProxyMode mode,
			@Nullable String host,
			@Nullable String port,
			boolean authenticated,
			@Nullable String user,
			@Nullable String password,
			@Nullable Integer concurrency) {
		Map<String, String> environment = new LinkedHashMap<>();
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
				String address = address(mode, host, port, authenticated, user, password);
				if (address != null) {
					environment.put("HTTP_PROXY", address);
					environment.put("HTTPS_PROXY", address);
					// ALL_PROXY is read by curl and various tools, especially for SOCKS proxies.
					environment.put("ALL_PROXY", address);
				}
			}
		}

		if (concurrency != null && concurrency > 0) {
			// npm_config_maxsockets limits concurrent socket connections in npm.
			environment.put("npm_config_maxsockets", Integer.toString(concurrency));
		}
		return environment;
	}

	private static @Nullable String address(
			DshProxyMode mode,
			@Nullable String host,
			@Nullable String port,
			boolean authenticated,
			@Nullable String user,
			@Nullable String password) {
		if (host == null || host.isBlank()) {
			return null;
		}
		String credentials = "";
		if (authenticated && user != null && !user.isBlank()) {
			credentials = user + ":" + (password == null ? "" : password) + "@";
		}
		String scheme = mode == DshProxyMode.SOCKS ? "socks5" : "http";
		return scheme + "://" + credentials + host.trim()
		       + (port == null || port.isBlank() ? "" : ":" + port.trim());
	}
}
