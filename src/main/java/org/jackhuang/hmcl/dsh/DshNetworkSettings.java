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

import org.jetbrains.annotations.NotNullByDefault;

import java.util.LinkedHashMap;
import java.util.Map;

/// The network settings every child process is given.
///
/// Downloads happen in whatever the package manager runs — reading a version list, resolving a
/// profile, installing a plugin, fetching a package — and a package manager is configured through
/// the environment it inherits. So the settings live here and are merged into every child this
/// launcher starts, in one place rather than at each call site: a proxy that applied to some
/// downloads and not others would be worse than no setting at all, because nothing would say which
/// ones went where.
///
/// Empty settings contribute nothing, so a launcher nobody configured starts its children with
/// exactly the environment it always did.
@NotNullByDefault
public final class DshNetworkSettings {
    private DshNetworkSettings() {
    }

    /// Returns the variables the launcher's network settings contribute.
    ///
    /// @return the variables, empty when nothing is configured
    public static Map<String, String> environment() {
        Map<String, String> environment = new LinkedHashMap<>();
        try {
            org.jackhuang.hmcl.setting.LauncherSettings settings =
                    org.jackhuang.hmcl.setting.SettingsManager.settings();
            DshProxyMode mode = settings.proxyModeProperty().get() == null
                    ? DshProxyMode.SYSTEM : settings.proxyModeProperty().get();
            switch (mode) {
                case SYSTEM -> {
                    // Nothing: whatever the system uses is what a child inherits anyway.
                }
                case NONE -> {
                    // Said out loud, because a child that inherits a proxy from the system would
                    // otherwise keep using one after being told not to.
                    environment.put("HTTP_PROXY", "");
                    environment.put("HTTPS_PROXY", "");
                    environment.put("NO_PROXY", "*");
                }
                case HTTP, SOCKS -> {
                    String address = address(settings, mode);
                    if (address != null) {
                        environment.put("HTTP_PROXY", address);
                        environment.put("HTTPS_PROXY", address);
                        // Some tools read only this one, and a SOCKS proxy is usually named here.
                        environment.put("ALL_PROXY", address);
                    }
                }
            }

            Integer concurrency = settings.downloadConcurrencyProperty().get();
            if (concurrency != null && concurrency > 0) {
                // npm's configuration is read from the environment by npm and pnpm both, and
                // this is the one that decides how many downloads happen at once.
                environment.put("npm_config_network_concurrency", Integer.toString(concurrency));
            }
        } catch (RuntimeException e) {
            // A launcher whose settings cannot be read is not a reason to fail every command: it
            // simply has no proxy, which is what an unconfigured one has too.
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Could not read the network settings", e);
        }
        return environment;
    }

    /// Builds the address of the proxy the settings describe.
    ///
    /// @param settings the settings
    /// @param mode     the chosen mode
    /// @return the address, or `null` when there is no host to reach
    private static @org.jetbrains.annotations.Nullable String address(
            org.jackhuang.hmcl.setting.LauncherSettings settings, DshProxyMode mode) {
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

    /// Adds a variable when it holds something.
    ///
    /// @param environment the variables
    /// @param name        the variable's name
    /// @param value       its value, or `null` or blank for none
    private static void add(Map<String, String> environment, String name, String value) {
        if (value != null && !value.isBlank()) {
            environment.put(name, value.trim());
        }
    }
}
