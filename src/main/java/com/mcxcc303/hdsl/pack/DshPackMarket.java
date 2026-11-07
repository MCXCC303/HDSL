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
package com.mcxcc303.hdsl.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.plugin.CatalogClient;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Client for fetching and parsing the community modpack market index and manifests.
 */
@NotNullByDefault
public final class DshPackMarket {
	public static final String INDEX_URL = "https://dsh-packforge.github.io/dsh-pack-market/index.json";

	private static final int SUPPORTED_SCHEMA = 2;

	private DshPackMarket() {
	}

	/**
	 * Reads the modpack index, falling back to a cached copy if remote fetch fails.
	 *
	 * @return the modpack index
	 * @throws DshException when the index cannot be fetched or read
	 */
	public static Index fetch() throws DshException {
		String url = indexUrl();
		Path cached = cachedFile(url);
		String body;
		try {
			body = NetworkUtils.doGet(URI.create(url));
			keepCopy(cached, body);
		} catch (IOException | RuntimeException direct) {
			String kept = readCached(cached);
			if (kept == null) {
				throw new DshException(
						"Failed to read the modpack index from " + url + " (" + direct.getMessage() + ")", direct);
			}
			LOG.info("The modpack index could not be fetched; using the copy kept at " + cached);
			body = kept;
		}
		return parse(body);
	}

	/**
	 * Parses the modpack index JSON content.
	 *
	 * @param body the JSON string
	 * @return the parsed index
	 * @throws DshException when the JSON is invalid or schema is unsupported
	 */
	public static Index parse(String body) throws DshException {
		JsonObject root;
		try {
			root = JsonUtils.fromJson(body, JsonObject.class);
		} catch (RuntimeException e) {
			throw new DshException("The modpack index is not JSON", e);
		}
		if (root == null) {
			throw new DshException("The modpack index is empty", null);
		}

		int schema =
				root.has("schemaVersion") && root.get("schemaVersion").isJsonPrimitive() ? root.get("schemaVersion")
				                                                                               .getAsInt() : -1;
		if (schema != SUPPORTED_SCHEMA) {
			throw new DshException(
					"The modpack index is version " + schema + ", and this launcher reads version " + SUPPORTED_SCHEMA,
					null);
		}

		JsonElement packs = root.get("modpacks");
		if (packs == null || !packs.isJsonArray()) {
			throw new DshException("The modpack index has no list of packs", null);
		}

		List<Entry> entries = new ArrayList<>();
		for (JsonElement element : packs.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			Entry entry = readEntry(element.getAsJsonObject());
			if (entry != null) {
				entries.add(entry);
			}
		}
		String generated =
				root.has("generatedAt") && root.get("generatedAt").isJsonPrimitive() ? root.get("generatedAt")
				                                                                           .getAsString() : null;
		return new Index(generated, List.copyOf(entries));
	}

	private static @Nullable Entry readEntry(JsonObject object) {
		String id = string(object, "id");
		String downloadUrl = string(object, "downloadUrl");
		String sha256 = string(object, "sha256");
		JsonElement size = object.get("size");
		if (id == null || downloadUrl == null || sha256 == null || size == null || !size.isJsonPrimitive()) {
			LOG.warning("An entry in the modpack index has no id, download address, digest or size; skipped");
			return null;
		}
		return new Entry(id,
		                 stringOr(object, "name", id),
		                 stringOr(object, "version", ""),
		                 localized(object, "displayName", id),
		                 localized(object, "description", null),
		                 downloadUrl,
		                 sha256,
		                 size.getAsLong(),
		                 stringOr(object, "owner", ""),
		                 stringOr(object, "repo", ""),
		                 integer(object, "manifestVersion"),
		                 string(object, "type"),
		                 string(object, "author"),
		                 string(object, "category"),
		                 string(object, "dshVersion"),
		                 string(object, "profileName"),
		                 string(object, "updatedAt"),
		                 integer(object, "bundleCount"),
		                 integer(object, "depCount"));
	}

	private static @Nullable String localized(JsonObject object, String key, @Nullable String fallback) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return fallback;
		}
		if (element.isJsonPrimitive()) {
			String text = element.getAsString();
			return text.isBlank() ? fallback : text;
		}
		if (!element.isJsonObject()) {
			return fallback;
		}
		Map<String, JsonElement> byLanguage = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> pair : element.getAsJsonObject().entrySet()) {
			byLanguage.put(pair.getKey(), pair.getValue());
		}
		Locale display = I18n.getLocale().getDisplayLocale();
		for (String candidate : List.of(display.toString(),
		                                display.toString().replace('_', '-'),
		                                display.getLanguage(),
		                                "en")) {
			JsonElement value = byLanguage.get(candidate);
			if (value != null && value.isJsonPrimitive() && !value.getAsString().isBlank()) {
				return value.getAsString();
			}
		}
		for (JsonElement value : byLanguage.values()) {
			if (value.isJsonPrimitive() && !value.getAsString().isBlank()) {
				return value.getAsString();
			}
		}
		return fallback;
	}

	private static @Nullable String string(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return null;
		}
		String text = element.getAsString();
		return text.isBlank() ? null : text;
	}

	private static String stringOr(JsonObject object, String key, String fallback) {
		String text = string(object, key);
		return text == null ? fallback : text;
	}

	private static @Nullable Integer integer(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		return element.getAsInt();
	}

	/**
	 * Returns the address of the modpack index.
	 *
	 * @return the resolved index URL
	 */
	public static String indexUrl() {
		String property = System.getProperty("hdsl.packMarket");
		if (property != null && !property.isBlank()) {
			return property.trim();
		}
		try {
			String setting = SettingsManager.settings().packMarketUrlProperty().get();
			if (setting != null && !setting.isBlank()) {
				return setting.trim();
			}
		} catch (RuntimeException e) {
			LOG.warning("Could not read the market address from the settings", e);
		}
		return INDEX_URL;
	}

	/**
	 * Returns the manifest URL for a pack entry.
	 *
	 * @param entry the pack entry
	 * @return manifest URL
	 */
	public static String manifestUrl(Entry entry) {
		return lazyUrl(entry, "manifest.json");
	}

	/**
	 * Returns the README URL for a pack entry.
	 *
	 * @param entry the pack entry
	 * @return README URL
	 */
	public static String readmeUrl(Entry entry) {
		return lazyUrl(entry, "README.md");
	}

	private static String lazyUrl(Entry entry, String file) {
		return URI.create(indexUrl()).resolve("packs/" + entry.id() + "/" + file).toString();
	}

	/**
	 * Reads the content of a remote document.
	 *
	 * @param url document URL
	 * @return document text, or null if fetch fails or status is not 2xx
	 */
	public static @Nullable String document(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			                                 .timeout(Duration.ofSeconds(20))
			                                 .header("Accept", "text/plain, application/json, text/markdown, */*")
			                                 .GET()
			                                 .build();
			try (HttpClient client = HttpClient.newBuilder()
			                                   .connectTimeout(Duration.ofSeconds(15))
			                                   .followRedirects(HttpClient.Redirect.NORMAL)
			                                   .build()) {
				HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() < 200 || response.statusCode() >= 300) {
					return null;
				}
				return response.body();
			}
		} catch (IOException | InterruptedException | RuntimeException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			LOG.info("Could not read " + url + ": " + e.getMessage());
			return null;
		}
	}

	/**
	 * Returns the path where the index is cached on disk.
	 *
	 * @param url the index URL
	 * @return cache file path
	 */
	static Path cachedFile(String url) {
		return CatalogClient.cacheDirectory().resolve("market-" + hashedName(url) + ".json");
	}

	private static String hashedName(String url) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 8; i++) {
				hex.append(String.format("%02x", digest[i]));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			return "index-" + Integer.toHexString(url.hashCode());
		}
	}

	private static void keepCopy(Path cached, String body) {
		try {
			Files.createDirectories(cached.getParent());
			Files.writeString(cached, body);
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not keep a copy of the modpack index at " + cached, e);
		}
	}

	private static @Nullable String readCached(Path cached) {
		try {
			return Files.isRegularFile(cached) ? Files.readString(cached) : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * One pack version described in the market index.
	 *
	 * @param id              {@code <owner>.<repo>}, identifying the pack and directory holding its manifest
	 * @param name            the slug chosen by the author
	 * @param version         the pack semantic version
	 * @param displayName     resolved display name to present in UI
	 * @param description     one-line summary description, or {@code null}
	 * @param downloadUrl     direct URL of the pack archive
	 * @param sha256          SHA-256 digest of the archive in lowercase hex
	 * @param size            length of the archive in bytes
	 * @param owner           GitHub owner/account name
	 * @param repo            GitHub repository name
	 * @param manifestVersion manifest contract version, or {@code null} if unstated
	 * @param type            pack type ({@code "profile"} or {@code "dshhome"}), or {@code null} if unstated
	 * @param author          author display name, or {@code null}
	 * @param category        market category classification, or {@code null}
	 * @param dshVersion      compatible DeepSeek Harness version, or {@code null}
	 * @param profileName     target profile name when installed, or {@code null}
	 * @param updatedAt       last update timestamp string, or {@code null}
	 * @param bundleCount     number of bundled layers, or {@code null}
	 * @param depCount        number of package dependencies, or {@code null}
	 */
	public record Entry(String id, String name, String version, String displayName, @Nullable String description,
	                    String downloadUrl, String sha256, long size, String owner, String repo,
	                    @Nullable Integer manifestVersion, @Nullable String type, @Nullable String author,
	                    @Nullable String category, @Nullable String dshVersion, @Nullable String profileName,
	                    @Nullable String updatedAt, @Nullable Integer bundleCount, @Nullable Integer depCount) {

		/**
		 * Returns the display title for the pack.
		 *
		 * @return display name or ID
		 */
		public String title() {
			return displayName == null || displayName.isBlank() ? id : displayName;
		}

		/**
		 * Reports whether this pack replaces an entire home rather than one profile.
		 *
		 * @return true if pack type is dshhome
		 */
		public boolean isWholeHome() {
			return "dshhome".equalsIgnoreCase(type);
		}

		/**
		 * Returns the URL of the pack author's avatar, if available.
		 *
		 * @return avatar URL or null
		 */
		public @Nullable String iconUrl() {
			if (owner == null || owner.isBlank()) {
				return null;
			}
			return "https://github.com/" + owner.trim() + ".png?size=64";
		}

		/**
		 * Returns formatted subtitle text showing author, version, and harness version.
		 *
		 * @return subtitle string
		 */
		public String subtitle() {
			StringBuilder line = new StringBuilder();
			if (author != null && !author.isBlank()) {
				line.append(author).append(" · ");
			}
			line.append(version);
			if (dshVersion != null && !dshVersion.isBlank()) {
				line.append(" · dsh ").append(dshVersion);
			}
			return line.toString();
		}
	}

	/**
	 * Market index content.
	 *
	 * @param generatedAt timestamp when index was generated, or null
	 * @param entries     list of pack entries
	 */
	public record Index(@Nullable String generatedAt, List<Entry> entries) {
	}
}
