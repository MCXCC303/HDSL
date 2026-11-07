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
package com.mcxcc303.hdsl.instance;

import com.mcxcc303.hdsl.launch.DshSessions;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that sessions are grouped the way the harness stores them.
 */
class DshWorkspacesTest {
	@TempDir
	Path home;

	private void writeSession(String slug, String id, @Nullable String cwd, @Nullable String title, long modifiedAt)
	throws Exception {
		Path directory = home.resolve("sessions").resolve(slug).resolve(id);
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("session.v3.jsonl.zstd"), "log");

		if (cwd != null || title != null) {
			Path cache = home.resolve("storages")
			                 .resolve("session_projcache")
			                 .resolve("sessions")
			                 .resolve(id + ".json");
			Files.createDirectories(cache.getParent());
			String identity = cwd == null ? "{}" : "{\"cwd\":\"" + cwd + "\",\"createdAt\":" + modifiedAt + "}";
			String rows = title == null ? "{}" : "{\"title\":{\"val\":\"" + title + "\"}}";
			Files.writeString(cache, "{\"record\":{\"identity\":" + identity + ",\"rows\":" + rows + "}}");
		}
		Files.setLastModifiedTime(directory, FileTime.fromMillis(modifiedAt));
	}

	@Test
	void sessionsAreGroupedByTheirWorkspaceAndNewestFirst() throws Exception {
		writeSession("--tmp-alpha--", "session-1", "/tmp/alpha", "First", 1_000L);
		writeSession("--tmp-alpha--", "session-2", "/tmp/alpha", "Second", 2_000L);
		writeSession("--tmp-beta--", "session-3", "/tmp/beta", "Third", 3_000L);

		List<DshWorkspace> workspaces = DshSessions.workspaces(home);

		assertEquals(2, workspaces.size(), "one workspace per slug directory");
		assertEquals("beta", workspaces.get(0).title(), "the most recently used workspace comes first");
		assertEquals("alpha", workspaces.get(1).title());
		assertEquals(2, workspaces.get(1).sessions().size());
		assertEquals("/tmp/alpha", workspaces.get(1).path());
		assertEquals(3_000L, workspaces.get(0).modifiedAt());
	}

	@Test
	void aWorkspaceTakesThePathItsSessionsNamed() throws Exception {
		writeSession("--tmp-gamma--", "session-1", null, null, 1_000L);
		writeSession("--tmp-gamma--", "session-2", "/tmp/gamma", "Named", 2_000L);

		DshWorkspace workspace = DshSessions.workspaces(home).get(0);

		assertEquals("/tmp/gamma", workspace.path(), "the one session that named a path names the workspace");
		assertEquals("gamma", workspace.title());
	}

	@Test
	void aHomeWithNoSessionsHasNoWorkspaces() throws Exception {
		assertTrue(DshSessions.workspaces(home).isEmpty());
	}
}
