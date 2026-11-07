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
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.instance.DshPortMode;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies checks for already-running instances before CLI commands proceed.
 */
class RunningInstancesTest {

	@Test
	void anInstanceAnotherLauncherIsRunningIsRefused(@TempDir Path directory) throws IOException {
		DshInstance instance = instanceIn(directory);
		leaveARunningRecord(directory);

		ByteArrayOutputStream err = new ByteArrayOutputStream();
		boolean up = RunningInstances.isUp(instance, new PrintStream(err, true, StandardCharsets.UTF_8));

		assertTrue(up, "the record says the instance is up, so the command must not go on");
		String message = err.toString(StandardCharsets.UTF_8);
		assertTrue(message.contains("instance test-running is running"), message);
		assertTrue(message.contains("hdsl instance stop --id test-running"),
		           "a refusal that does not say what to do about it is half an answer: " + message);
	}

	@Test
	void anInstanceNothingIsRunningIsNotRefused(@TempDir Path directory) {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		assertFalse(RunningInstances.isUp(instanceIn(directory), new PrintStream(err, true, StandardCharsets.UTF_8)));
		assertEquals("", err.toString(StandardCharsets.UTF_8), "and nothing is said when there is nothing to say");
	}

	private static void leaveARunningRecord(Path directory) throws IOException {
		long began = ProcessHandle.current().info().startInstant().orElseGet(Instant::now).toEpochMilli();
		Files.writeString(directory.resolve("running.json"),
		                  "{\"pid\":" + ProcessHandle.current().pid() + ",\"port\":3567,\"url\":null," +
		                  "\"surface\":\"" + DshSurface.WEB.name() + "\",\"startedAt\":" + began + "}");
	}

	private static DshInstance instanceIn(Path directory) {
		return new DshInstance("test-running",
		                       "0.0.0",
		                       "web",
		                       directory.toString(),
		                       DshNodeRuntime.SYSTEM,
		                       DshHomeMode.ISOLATED,
		                       null,
		                       List.of(),
		                       Map.of(),
		                       DshInstanceIcon.DEFAULT.id(),
		                       null,
		                       DshPortMode.AUTO,
		                       0,
		                       0L,
		                       directory.toString());
	}
}
