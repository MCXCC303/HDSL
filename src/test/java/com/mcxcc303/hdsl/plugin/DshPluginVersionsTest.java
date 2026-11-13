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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DshPluginVersionsTest {
	private static final String HARNESS = "0.1.7-rc.1";
	private static final Map<String, String> CORE = Map.of("@deepseek-ai/dsh-settings", "0.1.7-rc.1");

	private static final String DOCUMENT = """
			{
			  "dist-tags": { "latest": "0.24.1", "beta": "0.22.0", "dev": "0.21.0-dev.1" },
			  "time": {
			    "0.24.1": "2026-09-28T15:37:38.360Z",
			    "0.22.0": "2026-09-27T14:40:46.276Z",
			    "0.21.0-dev.1": "2026-09-25T04:00:00.000Z",
			    "0.20.0-alpha.1": "2026-09-20T04:00:00.000Z",
			    "0.12.2": "2026-08-15T13:45:44.628Z"
			  },
			  "versions": {
			    "0.24.1": {
			      "version": "0.24.1",
			      "peerDependencies": {
			        "@deepseek-ai/cordis": "^4.0.4",
			        "@deepseek-ai/dsh-settings": "^0.2.0-rc.1"
			      }
			    },
			    "0.22.0": {
			      "version": "0.22.0",
			      "peerDependencies": {
			        "@deepseek-ai/dsh-settings": "^0.1.7-rc.1 || ^0.2.0-rc.1"
			      }
			    },
			    "0.21.0-dev.1": {
			      "version": "0.21.0-dev.1",
			      "peerDependencies": {
			        "@deepseek-ai/dsh-settings": "^0.1.5 || ^0.1.7-rc.1"
			      }
			    },
			    "0.20.0-alpha.1": {
			      "version": "0.20.0-alpha.1",
			      "peerDependencies": { "@deepseek-ai/cordis": "^4.0.4" }
			    },
			    "0.12.2": {
			      "version": "0.12.2",
			      "peerDependencies": {
			        "@deepseek-ai/dsh-settings": "^0.1.0 || ^0.1.1"
			      }
			    }
			  }
			}
			""";

	private static List<String> versionsOf(DshPluginVersions.Group group) {
		return group.versions().stream().map(DshPluginVersions.Published::version).toList();
	}

	private static JsonObject document() {
		return JsonParser.parseString(DOCUMENT).getAsJsonObject();
	}

	@Test
	void readReturnsVersionsNewestFirst() {
		List<DshPluginVersions.Published> versions = DshPluginVersions.read(document());

		assertEquals(List.of("0.24.1", "0.22.0", "0.21.0-dev.1", "0.20.0-alpha.1", "0.12.2"),
		             versions.stream().map(DshPluginVersions.Published::version).toList());
		assertEquals("2026-09-28T15:37:38.360Z",
		             versions.getFirst().published().toString(),
		             "published must come from the registry time field");
	}

	@Test
	void readKeepsOnlyHarnessPeers() {
		List<DshPluginVersions.Published> versions = DshPluginVersions.read(document());

		assertEquals(1,
		             versions.get(0).harnessPeers().size(),
		             "@deepseek-ai/cordis must be excluded");
		assertTrue(versions.get(0).harnessPeers().has("@deepseek-ai/dsh-settings"));
		assertTrue(versions.get(3).harnessPeers().isEmpty(),
		           "a manifest with only @deepseek-ai/cordis must have no harness peers");
		assertEquals(List.of("0.2.0-rc.1", "0.1.7-rc.1"),
		             versions.get(1).claimed(),
		             "claimed must list range versions newest first");
	}

	@Test
	void groupCreatesOneListPerNamedHarnessVersion() {
		List<DshPluginVersions.Group> groups = DshPluginVersions.group(DshPluginVersions.read(document()), HARNESS);

		assertEquals(List.of(HARNESS, "0.2.0-rc.1", "0.1.5", "0.1.1", "0.1.0"),
		             groups.subList(0, 5).stream().map(DshPluginVersions.Group::harnessVersion).toList(),
		             "current harness must be first, then named versions newest first");
		assertTrue(groups.getFirst().recommended(), "the current harness group must be recommended");
		assertTrue(groups.subList(1, 5).stream().noneMatch(DshPluginVersions.Group::recommended));

		assertEquals(List.of("0.22.0", "0.21.0-dev.1", "0.12.2"),
		             versionsOf(groups.get(0)),
		             "group for " + HARNESS + " must contain 0.22.0, 0.21.0-dev.1 and 0.12.2");
		assertEquals(List.of("0.24.1", "0.22.0"),
		             versionsOf(groups.get(1)),
		             "group for 0.2.0-rc.1 must contain 0.24.1 and 0.22.0");
		assertEquals(List.of("0.21.0-dev.1", "0.12.2"),
		             versionsOf(groups.get(2)),
		             "group for 0.1.5 must contain 0.21.0-dev.1 and 0.12.2");
		assertEquals(List.of("0.12.2"), versionsOf(groups.get(3)));
		assertEquals(List.of("0.12.2"),
		             versionsOf(groups.get(4)),
		             "a version admitting two harness versions must appear in both groups");
	}

	@Test
	void groupPutsUndeclaredVersionsInFinalGroup() {
		List<DshPluginVersions.Group> groups = DshPluginVersions.group(DshPluginVersions.read(document()), HARNESS);

		DshPluginVersions.Group last = groups.getLast();
		assertNull(last.harnessVersion());
		assertEquals(List.of("0.20.0-alpha.1"), versionsOf(last));
	}

	@Test
	void fitOfReturnsUnclaimedAndGroupKeepsUndeclaredVersion() {
		List<DshPluginVersions.Published> versions = DshPluginVersions.read(document());
		DshPluginVersions.Published silent = versions.stream()
		                                             .filter(version -> version.version().equals("0.20.0-alpha.1"))
		                                             .findFirst()
		                                             .orElseThrow();

		assertEquals(DshPluginVersions.Fit.UNCLAIMED, DshPluginVersions.fitOf(silent, HARNESS, CORE));

		List<DshPluginVersions.Group> groups = DshPluginVersions.group(versions, HARNESS);
		assertTrue(groups.stream().flatMap(group -> group.versions().stream()).anyMatch(silent::equals),
		           "an undeclared version must still appear in some group");
	}

	@Test
	void fitOfFallsBackToHarnessVersionWhenCoreMissing() {
		List<DshPluginVersions.Published> versions = DshPluginVersions.read(document());
		DshPluginVersions.Published later = versions.get(0);
		DshPluginVersions.Published alternatives = versions.get(1);

		assertEquals(DshPluginVersions.Fit.NOT_FITS, DshPluginVersions.fitOf(later, HARNESS, null));
		assertEquals(DshPluginVersions.Fit.FITS,
		             DshPluginVersions.fitOf(alternatives, HARNESS, null),
		             "without harnessCore the range must be checked against harnessVersion");
	}

	@Test
	void groupRecommendsNothingWithoutInstance() {
		List<DshPluginVersions.Group> groups = DshPluginVersions.group(DshPluginVersions.read(document()), null);

		assertTrue(groups.stream().noneMatch(DshPluginVersions.Group::recommended),
		           "no group must be recommended when harnessVersion is null");
		assertEquals(List.of("0.2.0-rc.1", "0.1.7-rc.1", "0.1.5", "0.1.1", "0.1.0"),
		             groups.subList(0, 5).stream().map(DshPluginVersions.Group::harnessVersion).toList());
		assertNull(groups.get(5).harnessVersion(), "the undeclared group must be last with a null harnessVersion");
	}

	@Test
	void groupExcludesVersionRequiringLaterPrerelease() {
		JsonObject document = JsonParser.parseString("""
				                                             {
				                                               "versions": {
				                                                 "0.4.3": { "version": "0.4.3",
				                                                            "peerDependencies": { "@deepseek-ai/dsh": ">=0.1.7-rc.2" } },
				                                                 "0.4.1": { "version": "0.4.1",
				                                                            "peerDependencies": { "@deepseek-ai/dsh": ">=0.1.7-rc.1" } }
				                                               }
				                                             }
				                                             """).getAsJsonObject();

		List<DshPluginVersions.Group> groups = DshPluginVersions.group(DshPluginVersions.read(document), HARNESS);

		assertEquals(HARNESS, groups.getFirst().harnessVersion());
		assertEquals(List.of("0.4.1"),
		             versionsOf(groups.getFirst()),
		             "0.4.3 requires >=0.1.7-rc.2 and must not appear in the 0.1.7-rc.1 group");
		assertTrue(groups.stream()
		                 .anyMatch(group -> "0.1.7-rc.2".equals(group.harnessVersion()) &&
		                                    versionsOf(group).contains("0.4.3")),
		           "0.4.3 must still appear under 0.1.7-rc.2");
	}
}