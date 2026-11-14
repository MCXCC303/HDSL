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
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.runtime.DshNpmDocument;
import com.mcxcc303.hdsl.runtime.DshReleaseChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.time.Instant;
import java.util.*;

/**
 * Published pnpm releases, read from the registry document for the
 * {@value PACKAGE} package.
 * <p>
 * A release records its publishing time, channel, dist-tags, deprecation, and
 * required Node.js version.
 */
@NotNullByDefault
public final class DshPnpmReleases {
	public static final String PACKAGE = "pnpm";

	private DshPnpmReleases() {
	}

	/**
	 * Reads the published pnpm releases.
	 *
	 * @param refresh whether to fetch the registry document instead of using the
	 *                cache
	 * @return the releases, newest first
	 * @throws DshException when the registry document cannot be read
	 */
	public static List<Release> read(boolean refresh) throws DshException {
		JsonObject document = DshPackageRegistry.packument(PACKAGE, refresh);
		if (document == null) {
			throw new DshException("The registry's document for " + PACKAGE + " could not be read");
		}
		return read(document);
	}

	/**
	 * Reads the releases described by a registry document.
	 *
	 * @param document the registry document
	 * @return the releases, newest first
	 */
	public static List<Release> read(JsonObject document) {
		JsonObject versions = DshNpmDocument.object(document, "versions");
		if (versions == null)
			return List.of();

		JsonObject times = DshNpmDocument.object(document, "time");
		JsonObject tags = DshNpmDocument.object(document, "dist-tags");
		Map<String, List<String>> tagged = tagsOf(tags);

		List<Release> releases = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : versions.entrySet()) {
			if (!entry.getValue().isJsonObject())
				continue;

			JsonObject manifest = entry.getValue().getAsJsonObject();
			String version = DshNpmDocument.text(manifest, "version");
			if (version == null)
				version = entry.getKey();
			if (version.isBlank())
				continue;

			releases.add(new Release(version,
					DshNpmDocument.channelOf(version, tags),
					DshNpmDocument.moment(times, version),
					List.copyOf(tagged.getOrDefault(version, List.of())),
					DshNpmDocument.text(manifest, "deprecated"),
					nodeEngine(manifest)));
		}

		releases.sort(Comparator.comparing(Release::published, Comparator.nullsLast(Comparator.reverseOrder())));
		return List.copyOf(releases);
	}

	/**
	 * Maps each version to the dist-tags pointing at it.
	 * <p>
	 * The document maps tags to versions; this inverts that. {@code latest} sorts
	 * first, the rest
	 * keep document order.
	 *
	 * @param tags the document's dist-tags, or null
	 * @return tag names keyed by version
	 */
	private static Map<String, List<String>> tagsOf(@Nullable JsonObject tags) {
		Map<String, List<String>> tagged = new LinkedHashMap<>();
		if (tags == null)
			return tagged;

		for (Map.Entry<String, JsonElement> tag : tags.entrySet()) {
			if (!tag.getValue().isJsonPrimitive())
				continue;
			tagged.computeIfAbsent(tag.getValue().getAsString(), version -> new ArrayList<>()).add(tag.getKey());
		}
		for (List<String> names : tagged.values()) {
			names.sort(Comparator.comparingInt(DshPnpmReleases::rank).thenComparing(Comparator.naturalOrder()));
		}
		return tagged;
	}

	private static int rank(String name) {
		return "latest".equals(name) ? 0 : 1;
	}

	/**
	 * Returns the Node.js range a version requires.
	 *
	 * @param manifest the version's manifest
	 * @return the declared range, or null when none is declared
	 */
	private static @Nullable String nodeEngine(JsonObject manifest) {
		return DshNpmDocument.text(DshNpmDocument.object(manifest, "engines"), "node");
	}

	/**
	 * One published pnpm release.
	 *
	 * @param version    the version
	 * @param channel    the release channel
	 * @param published  publish time, or null
	 * @param tags       dist-tags pointing at this version
	 * @param deprecated deprecation message, or null
	 * @param nodeEngine required Node.js range, or null
	 */
	public record Release(String version, DshReleaseChannel channel, @Nullable Instant published,
			@Unmodifiable List<String> tags, @Nullable String deprecated, @Nullable String nodeEngine) {
	}
}