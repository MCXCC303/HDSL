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

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.plugin.CatalogClient;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Downloads and caches {@code .dspack} modpack archives with SHA-256 verification.
 */
@NotNullByDefault
public final class DspackDownload {
	private DspackDownload() {
	}

	/**
	 * Returns the local cache directory for downloaded packs.
	 *
	 * @return pack cache directory path
	 */
	public static Path cacheDirectory() {
		return CatalogClient.cacheDirectory().resolve("packs");
	}

	/**
	 * Resolves the cached archive file path for a market pack entry.
	 *
	 * @param entry pack entry
	 * @return archive file path
	 */
	public static Path archiveFile(DshPackMarket.Entry entry) {
		String safe = entry.id().replaceAll("[^A-Za-z0-9._-]", "-") + "-" +
		              entry.version().replaceAll("[^A-Za-z0-9._-]", "-");
		return cacheDirectory().resolve(safe + ".dspack");
	}

	/**
	 * Fetches a pack into the cache and verifies its size and SHA-256 digest.
	 *
	 * @param entry  the pack entry
	 * @param report receives progress lines, or null
	 * @return the verified archive path
	 * @throws DshException when the pack cannot be fetched or verification fails
	 */
	public static Path download(DshPackMarket.Entry entry, @Nullable Consumer<String> report) throws DshException {
		Path target = archiveFile(entry);
		if (isCached(entry)) {
			return target;
		}
		try {
			Files.createDirectories(target.getParent());
		} catch (IOException e) {
			throw new DshException("Could not create " + target.getParent(), e);
		}
		Path staging = target.resolveSibling(target.getFileName() + ".hdsl-downloading");

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(entry.downloadUrl()))
			                                 .timeout(Duration.ofMinutes(10))
			                                 .header("Accept", "application/octet-stream")
			                                 .GET()
			                                 .build();
			try (HttpClient client = HttpClient.newBuilder()
			                                   .connectTimeout(Duration.ofSeconds(20))
			                                   .followRedirects(HttpClient.Redirect.NORMAL)
			                                   .build()) {
				HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
				if (response.statusCode() < 200 || response.statusCode() >= 300) {
					throw new DshException("The market's address for this pack answered HTTP " + response.statusCode(),
					                       null);
				}
				MessageDigest digest = DigestUtils.getDigest("SHA-256");
				long written = 0;
				try (InputStream body = response.body();
				     OutputStream out = Files.newOutputStream(staging,
				                                              StandardOpenOption.CREATE,
				                                              StandardOpenOption.TRUNCATE_EXISTING)) {
					byte[] buffer = new byte[64 * 1024];
					int read;
					while ((read = body.read(buffer)) >= 0) {
						out.write(buffer, 0, read);
						digest.update(buffer, 0, read);
						written += read;
						if (report != null && entry.size() > 0 && written % (1024 * 1024) < buffer.length) {
							report.accept("Downloaded " + (written / 1024) + " KB of " + (entry.size() / 1024) + " KB");
						}
					}
				}
				String actual = hex(digest.digest());
				if (!entry.sha256().equalsIgnoreCase(actual)) {
					throw new DshException(
							"The pack does not match the market's SHA-256 (expected " + entry.sha256() + ", got " +
							actual + ")", null);
				}
			}
			Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
			verify(entry, target);
			return target;
		} catch (IOException e) {
			throw new DshException("Could not fetch the pack: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DshException("The download was interrupted", e);
		} finally {
			try {
				Files.deleteIfExists(staging);
			} catch (IOException e) {
				LOG.warning("Could not remove the partly downloaded " + staging, e);
			}
		}
	}

	/**
	 * Encodes bytes into a lowercase hexadecimal string.
	 *
	 * @param bytes input byte array
	 * @return hexadecimal string
	 */
	static String hex(byte[] bytes) {
		StringBuilder text = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			text.append(String.format("%02x", value));
		}
		return text.toString();
	}

	/**
	 * Reports whether a pack has already been fetched and verified.
	 *
	 * @param entry the pack entry
	 * @return true if the archive is cached and matches size and digest
	 */
	public static boolean isCached(DshPackMarket.Entry entry) {
		Path archive = archiveFile(entry);
		try {
			if (!Files.isRegularFile(archive) || Files.size(archive) != entry.size()) {
				return false;
			}
			return entry.sha256().equalsIgnoreCase(DigestUtils.digestToString("SHA-256", archive));
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * Verifies an archive against the size and digest specified in the entry.
	 *
	 * @param entry   the pack entry
	 * @param archive the archive file
	 * @throws DshException when size or digest does not match
	 */
	public static void verify(DshPackMarket.Entry entry, Path archive) throws DshException {
		long length;
		try {
			length = Files.size(archive);
		} catch (IOException e) {
			throw new DshException("Could not read " + archive, e);
		}
		if (entry.size() > 0 && length != entry.size()) {
			throw new DshException("The pack is " + length + " bytes and the market says it is " + entry.size() +
			                       " bytes, so it did not arrive whole", null);
		}
		String actual;
		try {
			actual = DigestUtils.digestToString("SHA-256", archive);
		} catch (IOException e) {
			throw new DshException("Could not read " + archive, e);
		}
		if (!entry.sha256().equalsIgnoreCase(actual)) {
			throw new DshException(
					"The pack does not match the market's SHA-256 (expected " + entry.sha256() + ", got " + actual +
					")", null);
		}
	}
}
