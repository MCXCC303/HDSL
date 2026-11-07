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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Locale;
import java.util.Set;

/**
 * A DeepSeek Harness release published to the npm registry.
 *
 * @param version     the published version string
 * @param distTags    the npm dist-tags that point at this version, such as {@code latest} or {@code alpha}
 * @param publishedAt the ISO timestamp when the release was published, or {@code null}
 */
@NotNullByDefault
public record DshRelease(String version, @Unmodifiable Set<String> distTags, @Nullable String publishedAt) {

	/**
	 * Classifies a release for filtering.
	 *
	 * @return the release type
	 */
	public Type type() {
		String lower = version.toLowerCase(Locale.ROOT);
		if (lower.contains("-alpha")) {
			return Type.ALPHA;
		}
		if (lower.contains("-rc")) {
			return Type.RC;
		}
		if (lower.contains("-beta")) {
			return Type.BETA;
		}
		return Type.STABLE;
	}

	/**
	 * Reports whether this release is only reachable through a non-{@code latest} tag.
	 *
	 * @return whether this is a pre-release
	 */
	public boolean isPrerelease() {
		return version.contains("-") && !distTags.contains("latest");
	}

	/**
	 * Reports whether this release is the current {@code latest} tag.
	 *
	 * @return whether {@code latest} points at this version
	 */
	public boolean isLatest() {
		return distTags.contains("latest");
	}

	/**
	 * Returns the most specific tag for this release, for display.
	 *
	 * @return the tag name, or {@code null} when the version carries none
	 */
	public String primaryTag() {
		if (isLatest()) {
			return "latest";
		}
		return distTags.stream().sorted().findFirst().orElse(null);
	}

	/**
	 * The kinds of release the download page can filter by.
	 */
	public enum Type {
		STABLE("stable"),
		RC("rc"),
		BETA("beta"),
		ALPHA("alpha"),
		OTHER("other");

		private final String id;

		Type(String id) {
			this.id = id;
		}

		/**
		 * Returns the identifier used in settings and translation keys.
		 *
		 * @return the identifier
		 */
		public String id() {
			return id;
		}
	}
}
