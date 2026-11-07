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

import com.google.gson.JsonObject;
import com.mcxcc303.hdsl.core.DshException;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Inspects and validates {@code .dspack} container archives and their markers.
 */
@NotNullByDefault
public final class DspackContainer {
	private static final String MARKER = "dspack.json";
	private static final String FORMAT = "dspack";
	private static final int CURRENT_CONTAINER = 3;
	private static final int OLD_CONTAINER = 2;

	private DspackContainer() {
	}

	/**
	 * Identifies and validates the container and manifest versions of a pack archive.
	 *
	 * @param archive the archive file
	 * @return container details and manifest
	 * @throws DshException when the archive is not a supported pack
	 */
	public static Container identify(Path archive) throws DshException {
		JsonObject marker;
		JsonObject manifest;
		int containerVersion;
		try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
			marker = readJson(zip, MARKER);
			if (marker == null) {
				throw new DshException(
						"That archive is not a DeepSeek Harness pack: it has no " + MARKER + " at its root", null);
			}
			if (!FORMAT.equals(text(marker, "format"))) {
				throw new DshException("That archive says it is a " + text(marker, "format") + " archive, not a pack",
				                       null);
			}
			Integer version = number(marker, "version");
			if (version == null || (version != CURRENT_CONTAINER && version != OLD_CONTAINER)) {
				throw new DshException(
						"That pack is container version " + version + ", and this launcher reads versions " +
						OLD_CONTAINER + " and " + CURRENT_CONTAINER, null);
			}
			containerVersion = version;
			manifest = readJson(zip, "manifest.json");
			if (manifest == null) {
				throw new DshException("That pack has no manifest.json", null);
			}
		} catch (IOException e) {
			throw new DshException("Could not read " + archive + ": " + e.getMessage(), e);
		}

		Integer manifestVersion = number(manifest, "manifestVersion");
		if (manifestVersion == null) {
			manifestVersion = 5;
		}
		int expectedContainer = containerFor(manifestVersion);
		if (expectedContainer != containerVersion) {
			throw new DshException(
					"That pack's container is version " + containerVersion + " and its manifest is version " +
					manifestVersion + ", which do not go together", null);
		}
		String type = text(manifest, "type");
		if ("collection".equalsIgnoreCase(type)) {
			throw new DshException("That pack is a collection, which this launcher cannot install", null);
		}
		return new Container(containerVersion, manifestVersion, manifest, "dshhome".equalsIgnoreCase(type));
	}

	/**
	 * Returns the expected container version for a given manifest version.
	 *
	 * @param manifestVersion the manifest version
	 * @return container version, or -1 when no container carries that manifest
	 */
	static int containerFor(int manifestVersion) {
		return switch (manifestVersion) {
			case 4 -> OLD_CONTAINER;
			case 5 -> CURRENT_CONTAINER;
			default -> -1;
		};
	}

	static @Nullable JsonObject readJson(ZipFile zip, String name) {
		ZipEntry entry = zip.getEntry(name);
		if (entry == null) {
			return null;
		}
		try (InputStream stream = zip.getInputStream(entry)) {
			return JsonUtils.fromJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8),
			                          JsonObject.class);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	static @Nullable Integer number(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isNumber()
		       ? object.get(key).getAsInt()
		       : null;
	}

	static @Nullable String text(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
	}

	/**
	 * Lists an archive's member paths in entry order.
	 *
	 * @param archive the archive file
	 * @return list of entry names
	 * @throws DshException when the archive cannot be read
	 */
	public static List<String> list(Path archive) throws DshException {
		List<String> names = new ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				names.add(entry.getName());
			}
		} catch (IOException e) {
			throw new DshException("Could not read " + archive, e);
		}
		return List.copyOf(names);
	}

	/**
	 * Recognized container metadata.
	 *
	 * @param containerVersion container version
	 * @param manifestVersion  manifest version
	 * @param manifest         raw manifest JSON object
	 * @param wholeHome        true if the pack replaces an entire home
	 */
	public record Container(int containerVersion, int manifestVersion, JsonObject manifest, boolean wholeHome) {

		public @Nullable String text(String key) {
			return manifest.has(key) && manifest.get(key).isJsonPrimitive() ? manifest.get(key).getAsString() : null;
		}

		public String name(String fallback) {
			String name = text("displayName");
			return name == null || name.isBlank() ? fallback : name;
		}

		public String profileName(String fallback) {
			String name = text("profileName");
			return name == null || name.isBlank() ? fallback : name;
		}
	}
}
