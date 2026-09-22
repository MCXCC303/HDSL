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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Reads package metadata from the npm registry.
///
/// Plugins are ordinary npm packages, so the registry is the only catalogue
/// there is: upstream ships no plugin store and no version feed. The ordinary
/// `npm view` command is used rather than talking HTTP directly, so a user's
/// registry mirror or proxy configuration is honoured automatically.
@NotNullByDefault
public final class DshPackageRegistry {
    private DshPackageRegistry() {
    }

    /// Fetches the published versions of a package, newest first.
    ///
    /// @param packageName the package name
    /// @return the published versions, newest first
    /// @throws DshException when npm is unavailable or the query fails
    public static List<String> fetchVersions(String packageName) throws DshException {
        DshNodeRuntime runtime = DshNodeRuntime.detect()
                .orElseThrow(() -> new DshException("Node.js was not found on PATH; "
                        + DshNodeRuntime.requirement()));
        if (!runtime.canInstall()) {
            throw new DshException("npm was not found on PATH; listing plugin versions requires it");
        }

        List<String> command = List.of(runtime.npm().toString(), "view", packageName, "versions", "--json");
        DshCommand.Result result;
        try {
            result = DshCommand.run(command);
        } catch (IOException e) {
            throw new DshException("Failed to run npm", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DshException("npm view was interrupted", e);
        }

        if (!result.isSuccess()) {
            throw new DshException("`npm view " + packageName + " versions` exited with code "
                    + result.exitCode() + ": " + tail(result.output()));
        }

        List<String> versions = parseVersions(result.text());
        versions.sort(Comparator.comparing((String version) -> version,
                DshVersionManager::compareVersions).reversed());
        return versions;
    }

    /// Parses the version list npm printed.
    ///
    /// @param text the JSON text, which may be a lone string for a package with one release
    /// @return the versions
    /// @throws DshException when the output is not usable
    private static List<String> parseVersions(String text) throws DshException {
        if (text.isBlank()) {
            throw new DshException("npm returned no versions for this package");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            LOG.warning("Failed to parse npm output", e);
            throw new DshException("npm returned output that could not be parsed");
        }

        List<String> versions = new ArrayList<>();
        if (parsed.isJsonArray()) {
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    versions.add(element.getAsString());
                }
            }
        } else if (parsed.isJsonPrimitive()) {
            versions.add(parsed.getAsString());
        }
        if (versions.isEmpty()) {
            throw new DshException("npm returned no versions for this package");
        }
        return versions;
    }

    /// Returns the last few output lines, for an error message.
    ///
    /// @param lines the captured output
    /// @return the trailing lines joined by newlines
    private static String tail(List<String> lines) {
        int from = Math.max(0, lines.size() - 6);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    /// Splits a package spec into its name and optional version range.
    ///
    /// @param spec the spec, for example `dshmarket` or `dshmarket@1.48.0`
    /// @return the package name
    /// Reads what a published package depends on.
    ///
    /// @param packageName the package
    /// @param version     the version, or `null` for the latest
    /// @return the dependencies, empty when there are none or they cannot be read
    public static JsonObject dependencies(String packageName, @Nullable String version) {
        String spec = version == null || version.isBlank() ? packageName : packageName + "@" + version;
        JsonObject cached = DEPENDENCIES.get(spec);
        if (cached != null) {
            return cached;
        }
        JsonObject dependencies = viewJson(spec, "dependencies");
        DEPENDENCIES.put(spec, dependencies);
        return dependencies;
    }

    /// The dependencies read so far, by specification.
    private static final Map<String, JsonObject> DEPENDENCIES = new java.util.concurrent.ConcurrentHashMap<>();

    /// Reads one object field of a published package.
    ///
    /// @param spec  the package specification
    /// @param field the field
    /// @return the object, or an empty one when it is absent or cannot be read
    private static JsonObject viewJson(String spec, String field) {
        try {
            DshNodeRuntime runtime = DshNodeRuntime.detect().orElse(null);
            if (runtime == null || runtime.npm() == null) {
                return new JsonObject();
            }
            DshCommand.Result result = DshCommand.run(
                    List.of(runtime.npm().toString(), "view", spec, field, "--json"), null, null);
            String body = String.join("\n", result.output()).trim();
            if (result.exitCode() == 0 && body.startsWith("{")) {
                JsonElement parsed = com.google.gson.JsonParser.parseString(body);
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warning("Could not read " + field + " of " + spec, e);
        }
        return new JsonObject();
    }

    /// Reads the peer requirements a published package declares.
    ///
    /// Asked of the package rather than of the catalogue, because the catalogue does not
    /// carry them. The answer is remembered: a page asks about the same packages again
    /// whenever it is searched, and a registry is not to be asked twice for the same
    /// thing.
    ///
    /// @param packageName the package
    /// @param version     the version, or `null` for the latest
    /// @return the peer requirements, empty when there are none or they cannot be read
    public static JsonObject peerDependencies(String packageName, @Nullable String version) {
        String spec = version == null || version.isBlank() ? packageName : packageName + "@" + version;
        JsonObject cached = PEERS.get(spec);
        if (cached != null) {
            return cached;
        }

        JsonObject peers = new JsonObject();
        try {
            DshNodeRuntime runtime = DshNodeRuntime.detect().orElse(null);
            if (runtime == null || runtime.npm() == null) {
                return peers;
            }
            DshCommand.Result result = DshCommand.run(
                    List.of(runtime.npm().toString(), "view", spec, "peerDependencies", "--json"),
                    null, null);
            String body = String.join("\n", result.output()).trim();
            if (result.exitCode() == 0 && body.startsWith("{")) {
                JsonElement parsed = com.google.gson.JsonParser.parseString(body);
                if (parsed.isJsonObject()) {
                    peers = parsed.getAsJsonObject();
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warning("Could not read the peer requirements of " + spec, e);
        }

        PEERS.put(spec, peers);
        return peers;
    }

    /// The peer requirements read so far, by specification.
    private static final Map<String, JsonObject> PEERS = new java.util.concurrent.ConcurrentHashMap<>();

    public static String packageNameOf(String spec) {
        String trimmed = spec.trim();
        if (trimmed.startsWith("@")) {
            int slash = trimmed.indexOf('/');
            if (slash < 0) {
                return trimmed;
            }
            int at = trimmed.indexOf('@', slash);
            return at < 0 ? trimmed : trimmed.substring(0, at);
        }
        int at = trimmed.indexOf('@');
        return at < 0 ? trimmed : trimmed.substring(0, at);
    }

    /// Returns the version part of a package spec.
    ///
    /// @param spec the spec
    /// @return the version range, or `null` when the spec carries none
    public static @Nullable String versionOf(String spec) {
        String trimmed = spec.trim();
        int at = trimmed.startsWith("@") ? trimmed.indexOf('@', trimmed.indexOf('/') + 1) : trimmed.indexOf('@');
        return at < 0 || at + 1 >= trimmed.length() ? null : trimmed.substring(at + 1);
    }
}
