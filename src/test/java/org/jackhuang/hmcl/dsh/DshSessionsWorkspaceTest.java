/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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
package org.jackhuang.hmcl.dsh;

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that importing sessions brings their projects with them.
///
/// The harness groups its history by workspace and keeps that grouping in
/// `storages/workspace.json`, apart from the sessions themselves. Bringing the
/// sessions over without it leaves every conversation in the interface's
/// ungrouped bucket: the logs are all there and the titles are all there, and
/// nothing says which project each one was about — which reads as an import that
/// lost most of what it copied.
class DshSessionsWorkspaceTest {
    /// The instance the merges are made into.
    private static final String INSTANCE_ID = "workspace-merge";

    /// A folder for the source home's sessions.
    @TempDir
    Path sourceHome;

    /// Removes the instance the test made.
    @AfterEach
    void removeInstance() {
        try {
            DshInstanceManager.delete(INSTANCE_ID);
        } catch (DshException e) {
            // Nothing to clean up.
        }
    }

    /// Creates the instance a merge is made into.
    ///
    /// @return the instance
    private DshInstance makeInstance() throws Exception {
        return DshInstanceManager.create(INSTANCE_ID, "1.0.0", DshInstance.DEFAULT_PROFILE,
                sourceHome, DshHomeMode.ISOLATED, null, List.of(), Map.of());
    }

    /// Writes a workspace registry into a home.
    ///
    /// @param home   the home
    /// @param json   the registry
    private static void writeRegistry(Path home, String json) throws Exception {
        Path file = home.resolve("storages").resolve("workspace.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    /// Reads the workspace registry of a home.
    ///
    /// @param home the home
    /// @return the registry
    private static JsonObject readRegistry(Path home) throws Exception {
        return JsonUtils.fromJsonFile(home.resolve("storages").resolve("workspace.json"), JsonObject.class);
    }

    /// Returns the session ids a workspace holds.
    ///
    /// @param registry the registry
    /// @param id       the workspace id
    /// @return its session ids
    private static List<String> sessionsOf(JsonObject registry, String id) {
        return registry.getAsJsonObject("tables").getAsJsonObject("workspaces")
                .getAsJsonObject(id).getAsJsonArray("sessionIds").asList().stream()
                .map(element -> element.getAsString())
                .toList();
    }

    @Test
    void importedSessionsKeepTheProjectsTheyCameFrom() throws Exception {
        writeRegistry(sourceHome, """
                {
                  "unit": {"name": "workspace", "version": 2},
                  "global": {
                    "initialized": true,
                    "workspaceIds": ["project-one", "project-two", "project-empty"],
                    "archivedSessionIds": ["session-b"]
                  },
                  "tables": {"workspaces": {
                    "project-one": {"path": "/tmp/one", "title": "One",
                                    "sessionIds": ["session-a", "session-b"], "createdAt": "2026-01-01T00:00:00.000Z"},
                    "project-two": {"path": "/tmp/two", "title": "Two",
                                    "sessionIds": ["session-c"], "createdAt": "2026-01-02T00:00:00.000Z"},
                    "project-empty": {"path": "/tmp/three", "title": "Three",
                                      "sessionIds": ["session-never-imported"], "createdAt": "2026-01-03T00:00:00.000Z"}
                  }}
                }
                """);

        DshInstance instance = makeInstance();
        DshSessions.adoptWorkspaces(sourceHome, instance, Set.of("session-a", "session-b", "session-c"));

        JsonObject merged = readRegistry(instance.homeDirectory());

        // Both projects that the imported sessions belonged to are recorded, in
        // the order the source showed them, with only what was actually imported.
        assertEquals(List.of("project-one", "project-two"),
                merged.getAsJsonObject("global").getAsJsonArray("workspaceIds").asList().stream()
                        .map(element -> element.getAsString()).toList(),
                "the sidebar order is the source's, minus the projects nothing came from");
        assertEquals(List.of("session-a", "session-b"), sessionsOf(merged, "project-one"));
        assertEquals(List.of("session-c"), sessionsOf(merged, "project-two"));
        assertFalse(merged.getAsJsonObject("tables").getAsJsonObject("workspaces").has("project-empty"),
                "a project none of the imported sessions belonged to must not appear");

        // The record is carried over whole: a project keeps its name and its time.
        assertEquals("One", merged.getAsJsonObject("tables").getAsJsonObject("workspaces")
                .getAsJsonObject("project-one").get("title").getAsString());
        assertEquals("2026-01-01T00:00:00.000Z", merged.getAsJsonObject("tables").getAsJsonObject("workspaces")
                .getAsJsonObject("project-one").get("createdAt").getAsString());
        assertEquals("workspace", merged.getAsJsonObject("unit").get("name").getAsString(),
                "the schema marker the harness reads has to be there");

        // An imported session that was archived stays archived.
        assertTrue(merged.getAsJsonObject("global").getAsJsonArray("archivedSessionIds").asList().stream()
                        .anyMatch(element -> element.getAsString().equals("session-b")),
                "an archived conversation must not come back as one to resume");
    }

    @Test
    void aProjectTheInstanceAlreadyKnowsKeepsWhatItHad() throws Exception {
        writeRegistry(sourceHome, """
                {
                  "global": {"workspaceIds": ["shared"], "archivedSessionIds": []},
                  "tables": {"workspaces": {
                    "shared": {"path": "/tmp/shared", "title": "Shared", "sessionIds": ["session-new"]}
                  }}
                }
                """);

        DshInstance instance = makeInstance();
        writeRegistry(instance.homeDirectory(), """
                {
                  "unit": {"name": "workspace", "version": 2},
                  "global": {"initialized": true, "workspaceIds": ["shared"], "archivedSessionIds": []},
                  "tables": {"workspaces": {
                    "shared": {"path": "/tmp/shared", "title": "Shared", "sessionIds": ["session-mine"]}
                  }}
                }
                """);

        DshSessions.adoptWorkspaces(sourceHome, instance, Set.of("session-new"));

        JsonObject merged = readRegistry(instance.homeDirectory());
        assertEquals(List.of("session-mine", "session-new"), sessionsOf(merged, "shared"),
                "the instance's own session stays where it was and the imported one joins it");
        assertEquals(1, merged.getAsJsonObject("global").getAsJsonArray("workspaceIds").size(),
                "a project that was already listed must not be listed twice");
    }

    @Test
    void aSourceWithoutARegistryChangesNothing() throws Exception {
        DshInstance instance = makeInstance();

        DshSessions.adoptWorkspaces(sourceHome, instance, Set.of("session-a"));

        assertFalse(Files.exists(instance.homeDirectory().resolve("storages").resolve("workspace.json")),
                "a home that never grouped its sessions must not be given an empty grouping");
    }
}
