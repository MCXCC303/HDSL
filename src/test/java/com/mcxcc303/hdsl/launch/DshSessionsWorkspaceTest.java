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
package com.mcxcc303.hdsl.launch;

import com.google.gson.JsonObject;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that importing sessions brings their projects with them.
 */
class DshSessionsWorkspaceTest {
	private static final String INSTANCE_ID = "workspace-merge";

	@TempDir
	Path sourceHome;

	private static void writeRegistry(Path home, String json) throws Exception {
		Path file = home.resolve("storages").resolve("workspace.json");
		Files.createDirectories(file.getParent());
		Files.writeString(file, json);
	}

	private static JsonObject readRegistry(Path home) throws Exception {
		return JsonUtils.fromJsonFile(home.resolve("storages").resolve("workspace.json"), JsonObject.class);
	}

	private static List<String> sessionsOf(JsonObject registry, String id) {
		return registry.getAsJsonObject("tables")
		               .getAsJsonObject("workspaces")
		               .getAsJsonObject(id)
		               .getAsJsonArray("sessionIds")
		               .asList()
		               .stream()
		               .map(element -> element.getAsString())
		               .toList();
	}

	@AfterEach
	void removeInstance() {
		try {
			DshInstanceManager.delete(INSTANCE_ID);
		} catch (DshException e) {
			// Nothing to clean up.
		}
	}

	private DshInstance makeInstance() throws Exception {
		return DshInstanceManager.create(INSTANCE_ID,
		                                 "1.0.0",
		                                 DshInstance.DEFAULT_PROFILE,
		                                 sourceHome,
		                                 DshHomeMode.ISOLATED,
		                                 null,
		                                 List.of(),
		                                 Map.of());
	}

	@Test
	void anImportAsksForTheGroupingToBeWorkedOutAgain() throws Exception {
		DshInstance instance = makeInstance();
		Path registry = instance.homeDirectory().resolve("storages").resolve("workspace.json");
		Files.createDirectories(registry.getParent());
		Files.writeString(registry, """
				{
				  "unit": {"name": "workspace", "version": 2},
				  "global": {"initialized": true, "workspaceIds": ["project-one"], "archivedSessionIds": ["session-b"]},
				  "tables": {"workspaces": {
				    "project-one": {"path": "/tmp/one", "title": "One", "sessionIds": ["session-a"],
				                    "createdAt": "2026-01-01T00:00:00.000Z"}
				  }}
				}
				""");

		DshSessions.regroup(instance.homeDirectory());

		JsonObject written = JsonUtils.fromJsonFile(registry, JsonObject.class);
		assertFalse(written.getAsJsonObject("global").get("initialized").getAsBoolean(),
		            "the marker is what makes the harness derive the grouping from the sessions");
		// Everything else is left as it was: the file is the harness's, and its
		// schema is not ours to reproduce — a registry missing one required field
		// refuses the whole plugin tree, and the instance then cannot start.
		assertEquals("workspace", written.getAsJsonObject("unit").get("name").getAsString());
		assertEquals(1, written.getAsJsonObject("global").getAsJsonArray("workspaceIds").size());
		assertEquals("session-b",
		             written.getAsJsonObject("global").getAsJsonArray("archivedSessionIds").get(0).getAsString());
		assertEquals("One",
		             written.getAsJsonObject("tables")
		                    .getAsJsonObject("workspaces")
		                    .getAsJsonObject("project-one")
		                    .get("title")
		                    .getAsString());
	}

	@Test
	void aHomeThatHasNeverGroupedAnythingIsLeftAlone() throws Exception {
		DshInstance instance = makeInstance();

		DshSessions.regroup(instance.homeDirectory());

		assertFalse(Files.exists(instance.homeDirectory().resolve("storages").resolve("workspace.json")),
		            "a home with no registry needs none: the harness writes one on its first run");
	}

	@Test
	void aPluginBackupIsNotMistakenForTheSession() throws Exception {
		DshInstance instance = makeInstance();
		Path slug = instance.homeDirectory().resolve("sessions").resolve("--tmp-project--");
		Path session = slug.resolve("11111111-2222-3333-4444-555555555555");
		Files.createDirectories(session);

		// The names a session directory can hold: the log itself, a copy a plugin
		// left behind, and a stale uncompressed spelling of an older generation.
		Files.writeString(session.resolve("session.v3.jsonl.zstd"), "log");
		Files.writeString(session.resolve("session.v3.jsonl.zstd.cost-meter-backup-1789322395742-1fd8c443"), "backup");
		Files.writeString(session.resolve("session.v2.jsonl"), "older");

		List<DshSession> sessions = DshSessions.list(instance.homeDirectory());

		assertEquals(1, sessions.size(), "one directory holds one session");
		assertEquals(3,
		             sessions.get(0).formatVersion(),
		             "the highest generation is the one the harness reads, not the backup and not the older one");
	}

	@Test
	void aDirectoryHoldingOnlyAPluginBackupIsNotASession() throws Exception {
		DshInstance instance = makeInstance();
		Path session = instance.homeDirectory()
		                       .resolve("sessions")
		                       .resolve("--tmp-project--")
		                       .resolve("11111111-2222-3333-4444-555555555555");
		Files.createDirectories(session);
		Files.writeString(session.resolve("session.v3.jsonl.zstd.cost-meter-backup-1789322395742-1fd8c443"), "backup");

		assertTrue(DshSessions.list(instance.homeDirectory()).isEmpty(),
		           "a directory with nothing but a plugin's copy has no log to list");
	}

	@Test
	void aRegistryThatIsAlreadyUninitializedIsLeftAlone() throws Exception {
		DshInstance instance = makeInstance();
		Path registry = instance.homeDirectory().resolve("storages").resolve("workspace.json");
		Files.createDirectories(registry.getParent());
		Files.writeString(registry, """
				{"global": {"initialized": false, "workspaceIds": [], "archivedSessionIds": []},
				 "tables": {"workspaces": {}}}
				""");
		FileTime before = Files.getLastModifiedTime(registry);

		DshSessions.regroup(instance.homeDirectory());

		assertEquals(before,
		             Files.getLastModifiedTime(registry),
		             "nothing to ask for when the grouping is already due to be worked out");
	}
}
