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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshPaths;
import com.mcxcc303.hdsl.plugin.Catalog.Plugin;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Reads the catalogue from configured sources, manages local caching, and resolves npm registries.
 */
@NotNullByDefault
public final class CatalogClient {
	private static volatile @Nullable Catalog lastCatalog;

	private CatalogClient() {
	}

	/**
	 * Looks a package up in the catalogue the launcher last read.
	 *
	 * @param packageName the npm package name
	 * @return the entry, or empty when the catalogue does not hold it
	 */
	public static Optional<Plugin> find(String packageName) {
		Catalog catalog = lastCatalog;
		if (catalog == null || packageName == null || packageName.isBlank()) {
			return Optional.empty();
		}
		for (Plugin plugin : catalog.plugins()) {
			if (packageName.equals(plugin.npm()) || packageName.equals(plugin.name())) {
				return Optional.of(plugin);
			}
		}
		return Optional.empty();
	}

	/**
	 * Reads the catalogue from every source that answers.
	 *
	 * @return the catalogue
	 * @throws DshException when no source could be read and no copy was kept
	 */
	public static Catalog fetch() throws DshException {
		return fetch(CatalogSources.sources());
	}

	/**
	 * Reads the catalogue from the given sources, in the given order.
	 *
	 * @param sources the sources to read
	 * @return the catalogue
	 * @throws DshException when no source could be read and no copy was kept
	 */
	public static Catalog fetch(List<String> sources) throws DshException {
		List<Catalog> answered = new ArrayList<>();
		List<String> unreadable = new ArrayList<>();
		List<String> reasons = new ArrayList<>();

		for (String source : sources) {
			Path cached = cachedFile(source);
			try {
				String body = readSource(source);
				keepCopy(cached, body);
				answered.add(CatalogParser.parse(body));
				continue;
			} catch (IOException | RuntimeException direct) {
				String kept = readCached(cached);
				if (kept != null) {
					LOG.info("The plugin catalogue at " + source + " could not be read (" + direct.getMessage() +
					         "); using the copy kept at " + cached);
					try {
						answered.add(CatalogParser.parse(kept));
						unreadable.add(source);
						continue;
					} catch (RuntimeException stale) {
						// Ignored if cached copy is invalid
					}
				}
				LOG.info("The plugin catalogue at " + source + " could not be read: " + direct.getMessage());
				unreadable.add(source);
				reasons.add(source + " (" + direct.getMessage() + ")");
			}
		}

		if (answered.isEmpty()) {
			// Fall back to npm rescue source if all configured sources failed
			String rescue = CatalogSources.fallbackSource();
			if (sources.stream().noneMatch(rescue::equalsIgnoreCase)) {
				Path cached = cachedFile(rescue);
				try {
					String body = readSource(rescue);
					keepCopy(cached, body);
					LOG.info("The plugin catalogue was read from the npm package " + rescue);
					Catalog catalog = CatalogParser.merge(List.of(CatalogParser.parse(body)), unreadable);
					lastCatalog = catalog;
					return catalog;
				} catch (IOException | RuntimeException viaNpm) {
					String kept = readCached(cached);
					if (kept != null) {
						try {
							Catalog catalog = CatalogParser.merge(List.of(CatalogParser.parse(kept)), unreadable);
							lastCatalog = catalog;
							return catalog;
						} catch (RuntimeException stale) {
							// Ignored if cached copy is invalid
						}
					}
					reasons.add(rescue + " (" + viaNpm.getMessage() + ")");
				}
			}
			throw new DshException("Failed to read the plugin catalogue from " +
			                       String.join(", ", reasons.isEmpty() ? sources : reasons));
		}

		Catalog catalog = CatalogParser.merge(answered, unreadable);
		lastCatalog = catalog;
		return catalog;
	}

	private static String readSource(String source) throws IOException {
		if (CatalogSources.isPackageSource(source)) {
			return fetchFromNpm(source);
		}
		return NetworkUtils.doGet(URI.create(source.trim()));
	}

	private static void keepCopy(Path cached, String body) {
		try {
			Files.createDirectories(cached.getParent());
			Files.writeString(cached, body);
		} catch (IOException e) {
			LOG.warning("Could not keep a copy of the plugin catalogue", e);
		}
	}

	private static String fetchFromNpm(String packageName) throws IOException {
		String registry = npmRegistry();
		String metadataUrl = registry + "/" + packageName + "/latest";
		String metadata = NetworkUtils.doGet(URI.create(metadataUrl));
		JsonObject object = JsonParser.parseString(metadata).getAsJsonObject();
		JsonElement tarball =
				object.getAsJsonObject("dist") == null ? null : object.getAsJsonObject("dist").get("tarball");
		if (tarball == null || !tarball.isJsonPrimitive()) {
			throw new IOException("the registry's answer for " + packageName + " names no archive");
		}

		byte[] archive = downloadBytes(URI.create(tarball.getAsString()));

		// Decompress gzip layer before reading tar entries
		byte[] tar;
		try (InputStream unzipped = new GZIPInputStream(new ByteArrayInputStream(archive))) {
			tar = unzipped.readAllBytes();
		}
		try (kala.compress.archivers.tar.TarArchiveReader reader = new kala.compress.archivers.tar.TarArchiveReader(tar)) {
			for (kala.compress.archivers.tar.TarArchiveEntry entry : reader.getEntries()) {
				if (entry.getName().endsWith("plugins.json")) {
					try (InputStream content = reader.getInputStream(entry)) {
						return new String(content.readAllBytes(), StandardCharsets.UTF_8);
					}
				}
			}
		}
		throw new IOException("the archive holds no plugins.json");
	}

	private static byte[] downloadBytes(URI uri) throws IOException {
		URLConnection connection = NetworkUtils.createConnection(uri);
		if (connection instanceof HttpURLConnection http) {
			connection = NetworkUtils.resolveConnection(http);
		}
		try (InputStream input = connection.getInputStream()) {
			return input.readAllBytes();
		}
	}

	/**
	 * Returns the registry the machine's npm is configured to use.
	 *
	 * @return the registry's base address, without a trailing slash
	 */
	public static String npmRegistry() {
		try {
			DshNodeRuntime runtime = DshNodeRuntime.detect().orElse(null);
			if (runtime != null && runtime.npm() != null) {
				DshCommand.Result result = DshCommand.run(List.of(runtime.npm().toString(),
				                                                  "config",
				                                                  "get",
				                                                  "registry"));
				if (result.isSuccess()) {
					String configured = result.text().strip();
					// Take the last valid URL line from npm output
					for (String line : configured.lines().toList().reversed()) {
						String candidate = line.strip();
						if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
							return candidate.endsWith("/") ? candidate.substring(0, candidate.length() - 1) : candidate;
						}
					}
				}
			}
		} catch (IOException | InterruptedException | RuntimeException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			LOG.info("Could not read npm's registry; using the public one", e);
		}
		return "https://registry.npmjs.org";
	}

	/**
	 * Returns the directory the launcher keeps fetched catalogues in.
	 *
	 * @return the directory, which may not exist
	 */
	public static Path cacheDirectory() {
		try {
			LauncherSettings settings = SettingsManager.settings();
			String configured = settings.cacheDirectoryProperty().get();
			if (settings.cacheDirectoryCustomProperty().get() && configured != null && !configured.isBlank()) {
				return Path.of(configured.trim());
			}
		} catch (RuntimeException e) {
			LOG.warning("Could not read the cache directory", e);
		}
		return DshPaths.CATALOG;
	}

	/**
	 * Returns the file one address's copy is kept in.
	 *
	 * @param url the address
	 * @return the file, which may not exist
	 */
	public static Path cachedFile(String url) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			StringBuilder name = new StringBuilder();
			for (byte value : digest.digest(url.getBytes(StandardCharsets.UTF_8))) {
				name.append(String.format("%02x", value));
			}
			return cacheDirectory().resolve(name.substring(0, 16) + ".json");
		} catch (NoSuchAlgorithmException e) {
			return cacheDirectory().resolve("catalog.json");
		}
	}

	private static @Nullable String readCached(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.readString(file) : null;
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Removes every kept copy.
	 *
	 * @return how many files were removed
	 */
	public static int clearCache() {
		Path directory = cacheDirectory();
		if (!Files.isDirectory(directory)) {
			return 0;
		}
		int removed = 0;
		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.toList()) {
				try {
					if (Files.isDirectory(file)) {
						continue;
					}
					Files.delete(file);
					removed++;
				} catch (IOException e) {
					LOG.warning("Could not remove " + file, e);
				}
			}
		} catch (IOException e) {
			LOG.warning("Could not list " + directory, e);
		}
		return removed;
	}
}

