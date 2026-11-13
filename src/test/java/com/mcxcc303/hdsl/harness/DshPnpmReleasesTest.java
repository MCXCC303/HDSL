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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcxcc303.hdsl.runtime.DshNpmChannel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DshPnpmReleasesTest {
	private static final String DOCUMENT = """
			{
			  "dist-tags": { "latest": "12.18.0", "latest-12": "12.18.0", "next-12": "12.20.0" },
			  "time": {
			    "12.18.0": "2026-11-05T00:56:16.339Z",
			    "12.20.0": "2026-11-12T00:23:01.043Z",
			    "11.28.11": "2026-11-05T00:56:08.898Z",
			    "10.0.0-alpha.1": "2025-01-02T03:04:05.000Z"
			  },
			  "versions": {
			    "12.18.0": {
			      "version": "12.18.0",
			      "engines": { "node": ">=18" }
			    },
			    "12.20.0": {
			      "version": "12.20.0",
			      "engines": { "node": ">=18" }
			    },
			    "11.28.11": {
			      "version": "11.28.11",
			      "engines": { "node": ">=22.13" },
			      "deprecated": "This is a broken version. Please install pnpm v11.28.12 or newer"
			    },
			    "10.0.0-alpha.1": {
			      "version": "10.0.0-alpha.1"
			    }
			  }
			}
			""";

	private static JsonObject document() {
		return JsonParser.parseString(DOCUMENT).getAsJsonObject();
	}

	private static DshPnpmReleases.Release release(List<DshPnpmReleases.Release> releases, String version) {
		return releases.stream()
		               .filter(release -> release.version().equals(version))
		               .findFirst()
		               .orElseThrow(() -> new AssertionError("no release for " + version));
	}

	@Test
	void aReleaseSaysWhenItWasPublishedAndWhichChannelItBelongsTo() {
		List<DshPnpmReleases.Release> releases = DshPnpmReleases.read(document());

		DshPnpmReleases.Release alpha = release(releases, "10.0.0-alpha.1");
		assertEquals(DshNpmChannel.ALPHA, alpha.channel(), "the suffix in the name says what it is");
		assertEquals(Instant.parse("2025-01-02T03:04:05Z"), alpha.published());
		assertNull(alpha.deprecated(), "a version the author did not deprecate says nothing about it");
		assertNull(alpha.nodeEngine(), "a version that declares no Node.js says nothing about it");

		DshPnpmReleases.Release deprecated = release(releases, "11.28.11");
		assertEquals("This is a broken version. Please install pnpm v11.28.12 or newer",
		             deprecated.deprecated(),
		             "why a version was deprecated is what the author wants a reader to know");
		assertEquals(">=22.13", deprecated.nodeEngine(), "the Node.js a version needs decides whether it can run");
	}

	@Test
	void aReleaseCarriesTheTagsThatPointAtIt() {
		List<DshPnpmReleases.Release> releases = DshPnpmReleases.read(document());

		assertEquals(List.of("latest", "latest-12"),
		             release(releases, "12.18.0").tags(),
		             "the tag a bare install takes comes first, and the release line's own tag follows");
		assertEquals(List.of("next-12"), release(releases, "12.20.0").tags());
		assertTrue(release(releases, "11.28.11").tags().isEmpty(), "a version no tag points at carries none");
	}

	@Test
	void theNewestReleaseComesFirst() {
		List<DshPnpmReleases.Release> releases = DshPnpmReleases.read(document());

		assertEquals(List.of("12.20.0", "12.18.0", "11.28.11", "10.0.0-alpha.1"),
		             releases.stream().map(DshPnpmReleases.Release::version).toList(),
		             "the releases are ordered by when they were published, newest first");
	}

	@Test
	void aDocumentThatDescribesNoVersionsDescribesNoReleases() {
		assertTrue(DshPnpmReleases.read(new JsonObject()).isEmpty(), "a document without versions has nothing to draw");
	}
}
