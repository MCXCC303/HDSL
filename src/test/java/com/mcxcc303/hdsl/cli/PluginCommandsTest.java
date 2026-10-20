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
package com.mcxcc303.hdsl.cli;

import com.mcxcc303.hdsl.harness.DshSurface;
import com.mcxcc303.hdsl.instance.*;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plugin commands, run against instances left on disk
 */
class PluginCommandsTest {
	private static final String ID = "plugin-commands-test";

	// The list tests use an instance of their own, so they leave the running one alone.
	private static final String LIST_ID = "plugin-list-test";

	// A runtime nothing installs, so the work stops where the test can name it.
	private static final String MISSING_RUNTIME = "test-runtime-that-is-not-installed";

	@TempDir
	Path folder;

	private Supplier<List<Path>> previousFolders;

	@BeforeEach
	void pointAtTheTestFolder() {
		previousFolders = InstanceFolders.setFolders(() -> List.of(folder));
	}

	@AfterEach
	void restoreTheFolders() {
		InstanceFolders.setFolders(previousFolders);
	}

	@Test
	void aPluginInstallStartsWhileTheInstanceIsRunning() {
		leaveARunningInstance();

		Run run = run("plugin", "install", "--id", ID, "--spec", "dsh-anything");

		assertEquals(1, run.code());
		assertTrue(run.out().contains("Installing dsh-anything into " + ID), "the work starts: " + run.out());
		assertTrue(run.err().contains(MISSING_RUNTIME), "and stops at the runtime the test left out: " + run.err());
	}

	@Test
	void aPluginRemovalStartsWhileTheInstanceIsRunning() {
		leaveARunningInstance();

		Run run = run("plugin", "remove", "--id", ID, "--spec", "dsh-anything");

		assertEquals(1, run.code());
		assertTrue(run.out().contains("Removing dsh-anything from " + ID), "the work starts: " + run.out());
		assertTrue(run.err().contains(MISSING_RUNTIME), "and stops at the runtime the test left out: " + run.err());
	}

	@Test
	void aBuildScriptAnswerIsWrittenWhileTheInstanceIsRunning() throws Exception {
		leaveARunningInstance();

		Run run = run("plugin", "build-scripts", "--id", ID, "--allow");

		assertEquals(0, run.code(), run.err());
		assertTrue(run.out().contains("Allowed install scripts for 1 package(s)"), run.out());
		assertTrue(Files.readString(workspaceFile()).contains("node-pty: true"),
		           "the answer is written where the package manager reads it");
	}

	@Test
	void thePluginsOfAnInstanceAreListedAsATable() throws Exception {
		leaveAProfileWithAPlugin(LIST_ID);

		Run run = run("plugin", "list", "--id", LIST_ID);

		assertEquals(0, run.code(), run.err());
		assertTrue(run.out().contains("PACKAGE"), run.out());
		assertTrue(run.out().contains("dsh-thing"), run.out());
		assertTrue(run.out().contains("1.2.3"), run.out());
		assertTrue(run.out().contains("active"), run.out());
	}

	@Test
	void theLoaderRowsAreListedOnlyWhenTheyAreAskedFor() throws Exception {
		leaveAProfileWithAPlugin(LIST_ID);

		Run plain = run("plugin", "list", "--id", LIST_ID);
		Run withRows = run("plugin", "list", "--id", LIST_ID, "--show-bundles");

		assertFalse(plain.out().contains("BUNDLES"), plain.out());
		assertTrue(withRows.out().contains("BUNDLES"), withRows.out());
		assertTrue(withRows.out().contains("dsh-thing-row"), withRows.out());
	}

	/**
	 * Leaves an instance and a record that says it is running
	 * <p>
	 * The record names this process, so a reader finds it alive
	 */
	private void leaveARunningInstance() {
		Path directory = folder.resolve(ID);
		try {
			Files.createDirectories(directory.resolve("home").resolve("profiles").resolve("web"));
			writeTheInstance(ID);
			// A profile with one build waiting for an answer.
			Files.writeString(workspaceFile(), """
					packages:
					  - .
					
					allowBuilds:
					  node-pty: set this to true or false
					""");
			long began = ProcessHandle.current().info().startInstant().orElseGet(Instant::now).toEpochMilli();
			Files.writeString(directory.resolve("running.json"),
			                  "{\"pid\":" + ProcessHandle.current().pid() + ",\"port\":3567,\"url\":null," +
			                  "\"surface\":\"" + DshSurface.WEB.name() + "\",\"startedAt\":" + began + "}");
		} catch (Exception e) {
			throw new AssertionError("Could not leave an instance for the command line to find", e);
		}
	}

	private void writeTheInstance(String id) throws Exception {
		Path directory = folder.resolve(id);
		JsonUtils.writeToJsonFile(directory.resolve("instance.json"),
		                          new DshInstance(id,
		                                          "0.0.0",
		                                          "web",
		                                          folder.toString(),
		                                          MISSING_RUNTIME,
		                                          DshHomeMode.ISOLATED,
		                                          null,
		                                          List.of(),
		                                          Map.of(),
		                                          DshInstanceIcon.DEFAULT.id(),
		                                          null,
		                                          DshPortMode.AUTO,
		                                          0,
		                                          0L,
		                                          directory.toString()));
	}

	private void leaveAProfileWithAPlugin(String id) throws Exception {
		Path directory = folder.resolve(id);
		Path profile = directory.resolve("home").resolve("profiles").resolve("web");
		Files.createDirectories(profile);
		writeTheInstance(id);

		Files.writeString(profile.resolve("package.json"), """
			{
			  "name": "dsh-profile-web",
			  "private": true,
			  "dependencies": {
			    "dsh-thing": "1.2.3"
			  },
			  "dsh": {
			    "profile": {
			      "bundles": [
			        "dsh-thing"
			      ]
			    }
			  }
			}
			""");

		Path packageDirectory = profile.resolve("node_modules").resolve("dsh-thing");
		Files.createDirectories(packageDirectory);
		Files.writeString(packageDirectory.resolve("package.json"), """
			{
			  "name": "dsh-thing",
			  "version": "1.2.3",
			  "peerDependencies": {
			    "@deepseek-ai/dsh": ">=0.0.0"
			  }
			}
			""");
		Files.writeString(packageDirectory.resolve("cordis.patch.yml"), """
			- insert:
			    - id: dsh-thing-row
			""");

		Path harness = directory.resolve("dsh").resolve("node_modules").resolve("@deepseek-ai").resolve("dsh");
		Files.createDirectories(harness);
		Files.writeString(harness.resolve("package.json"), """
			{
			  "name": "@deepseek-ai/dsh",
			  "version": "0.0.0"
			}
			""");
	}

	private Path workspaceFile() {
		return folder.resolve(ID).resolve("home").resolve("profiles").resolve("web").resolve("pnpm-workspace.yaml");
	}

	private static Run run(String... words) {
		Cli.Invocation invocation = Cli.parse(List.of(words));
		assertNotNull(invocation);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		int code = Cli.run(invocation,
		                   new PrintStream(out, true, StandardCharsets.UTF_8),
		                   new PrintStream(err, true, StandardCharsets.UTF_8));
		return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
	}

	private record Run(int code, String out, String err) {}
}
