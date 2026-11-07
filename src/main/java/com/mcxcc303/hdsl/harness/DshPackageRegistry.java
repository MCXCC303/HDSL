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
package com.mcxcc303.hdsl.harness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.plugin.CatalogClient;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Reads package metadata from the npm registry.
 */
@NotNullByDefault
public final class DshPackageRegistry {
	private static final Map<String, JsonObject> DEPENDENCIES = new ConcurrentHashMap<>();
	private static final Map<String, JsonObject> PACKUMENTS = new ConcurrentHashMap<>();
	private static final Map<String, JsonObject> PEERS = new ConcurrentHashMap<>();

	private DshPackageRegistry() {
	}

	/**
	 * Fetches the published versions of a package, newest first.
	 *
	 * @param packageName the package name
	 * @return the published versions, newest first
	 * @throws DshException when npm is unavailable or the query fails
	 */
	public static List<String> fetchVersions(String packageName) throws DshException {
		DshNodeRuntime runtime = DshNodeRuntime.detect()
		                                       .orElseThrow(() -> new DshException("Node.js was not found on PATH; " +
		                                                                           DshNodeRuntime.requirement()));
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
			throw new DshException(
					"`npm view " + packageName + " versions` exited with code " + result.exitCode() + ": " +
					tail(result.output()));
		}

		List<String> versions = parseVersions(result.text());
		versions.sort(Comparator.comparing((String version) -> version, VersionOrder::compareVersions).reversed());
		return versions;
	}

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

	private static String tail(List<String> lines) {
		int from = Math.max(0, lines.size() - 6);
		return String.join("\n", lines.subList(from, lines.size()));
	}

	/**
	 * Reads what a published package depends on.
	 *
	 * @param packageName the package name
	 * @param version     the version, or {@code null} for the latest
	 * @return the dependencies, empty when there are none or they cannot be read
	 */
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

	private static JsonObject viewJson(String spec, String field) {
		try {
			DshNodeRuntime runtime = DshNodeRuntime.detect().orElse(null);
			if (runtime == null || runtime.npm() == null) {
				return new JsonObject();
			}
			DshCommand.Result result = DshCommand.run(List.of(runtime.npm().toString(), "view", spec, field, "--json"),
			                                          null,
			                                          null);
			String body = String.join("\n", result.output()).trim();
			if (result.exitCode() == 0 && body.startsWith("{")) {
				JsonElement parsed = JsonParser.parseString(body);
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

	/**
	 * Reads the registry's packument document for a package.
	 *
	 * @param packageName the package name
	 * @param refresh     whether to fetch again rather than answer from memory
	 * @return the document, or {@code null} when it could not be read
	 */
	public static @Nullable JsonObject packument(String packageName, boolean refresh) {
		if (!refresh) {
			JsonObject known = PACKUMENTS.get(packageName);
			if (known != null) {
				return known;
			}
		}

		String address = CatalogClient.npmRegistry() + "/" + packageName.replace("/", "%2f");
		Path kept = CatalogClient.cachedFile(address);
		String body = null;
		try {
			body = NetworkUtils.doGet(URI.create(address));
			keep(kept, body);
		} catch (IOException | RuntimeException e) {
			LOG.info("Could not read " + address + " (" + e.getMessage() + "); using the kept copy");
			body = read(kept);
		}
		if (body == null) {
			return null;
		}

		try {
			JsonElement parsed = JsonParser.parseString(body);
			if (!parsed.isJsonObject()) {
				return null;
			}
			JsonObject document = parsed.getAsJsonObject();
			PACKUMENTS.put(packageName, document);
			return document;
		} catch (RuntimeException e) {
			LOG.warning("The registry's document for " + packageName + " could not be read", e);
			return null;
		}
	}

	private static void keep(Path file, String body) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, body);
		} catch (IOException e) {
			LOG.warning("Could not keep a copy at " + file, e);
		}
	}

	private static @Nullable String read(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.readString(file) : null;
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Checks whether a package is published at a specified version.
	 *
	 * @param packageName the package name
	 * @param version     the version
	 * @return what the registry says
	 */
	public static Availability availability(String packageName, String version) {
		DshNodeRuntime runtime = DshNodeRuntime.detect().orElse(null);
		if (runtime == null || runtime.npm() == null) {
			return Availability.UNKNOWN;
		}
		try {
			DshCommand.Result result = DshCommand.run(List.of(runtime.npm().toString(),
			                                                  "view",
			                                                  packageName + "@" + version,
			                                                  "version",
			                                                  "--json"), null, null);
			return availabilityOf(result.exitCode(), result.text());
		} catch (IOException e) {
			LOG.warning("Could not ask the registry about " + packageName + "@" + version, e);
			return Availability.UNKNOWN;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Availability.UNKNOWN;
		}
	}

	/**
	 * Reads what a registry answer means.
	 *
	 * @param exitCode the command's exit code
	 * @param output   what it printed
	 * @return what the registry says
	 */
	static Availability availabilityOf(int exitCode, String output) {
		if (exitCode == 0) {
			return Availability.PUBLISHED;
		}
		String text = output == null ? "" : output;
		return text.contains("E404") || text.contains("404 Not Found") || text.contains("is not in this registry")
		       ? Availability.ABSENT
		       : Availability.UNKNOWN;
	}

	/**
	 * Reads the peer requirements a published package declares.
	 *
	 * @param packageName the package
	 * @param version     the version, or {@code null} for the latest
	 * @return the peer requirements, empty when there are none or they cannot be read
	 */
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
			DshCommand.Result result = DshCommand.run(List.of(runtime.npm().toString(),
			                                                  "view",
			                                                  spec,
			                                                  "peerDependencies",
			                                                  "--json"), null, null);
			String body = String.join("\n", result.output()).trim();
			if (result.exitCode() == 0 && body.startsWith("{")) {
				JsonElement parsed = JsonParser.parseString(body);
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

	/**
	 * Extracts the package name from a package specification.
	 *
	 * @param spec the package specification
	 * @return the package name
	 */
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

	/**
	 * Returns the version part of a package specification.
	 *
	 * @param spec the package specification
	 * @return the version range, or {@code null} when the spec carries none
	 */
	public static @Nullable String versionOf(String spec) {
		String trimmed = spec.trim();
		int at = trimmed.startsWith("@") ? trimmed.indexOf('@', trimmed.indexOf('/') + 1) : trimmed.indexOf('@');
		return at < 0 || at + 1 >= trimmed.length() ? null : trimmed.substring(at + 1);
	}

	/**
	 * What the registry says about a package at a version.
	 */
	public enum Availability {
		PUBLISHED,
		ABSENT,
		UNKNOWN
	}
}
