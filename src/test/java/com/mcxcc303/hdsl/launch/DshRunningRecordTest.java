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

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.DshSurface;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.instance.DshPortMode;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What one launcher writes down about an instance, and what another launcher reads.
 */
class DshRunningRecordTest {

	private static final URI ADDRESS = URI.create("http://127.0.0.1:3567/?token=s3cret");

	@Test
	void whatOneLauncherWritesIsWhatAnotherReads(@TempDir Path directory) {
		DshInstance instance = instanceIn(directory);
		// The process is this one: it is alive, and it began when the record says it did. No child
		// has to be started to have something to point at, which is what makes this a test rather
		// than a launch.
		DshRunningRecord.write(instance, ProcessHandle.current().pid(), 3567, ADDRESS, DshSurface.WEB,
		                       began());

		DshRunningRecord record = DshRunningRecord.read(instance);
		assertNotNull(record, "the record describes a process that is running");
		assertEquals(ProcessHandle.current().pid(), record.pid());
		assertEquals(3567, record.port());
		assertEquals(Optional.of(ADDRESS), record.webUrl());
		assertEquals(DshSurface.WEB.name(), record.surface());
		assertTrue(record.uptime().toSeconds() >= 0);
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void whatIsWrittenAboutARunningProcessIsReadableOnlyByItsOwner(@TempDir Path directory) throws Exception {
		DshInstance instance = instanceIn(directory);
		DshRunningRecord.write(instance, ProcessHandle.current().pid(), 3567, ADDRESS, DshSurface.WEB,
		                       began());

		Path file = directory.resolve("running.json");
		assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
		             Files.getPosixFilePermissions(file),
		             "the address the record carries holds the token");
		assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
		                        PosixFilePermission.OWNER_EXECUTE),
		             Files.getPosixFilePermissions(directory),
		             "the directory is its owner's alone");
	}

	@Test
	void theManagerSeesAnInstanceAnotherLauncherStarted(@TempDir Path directory) {
		DshInstance instance = instanceIn(directory);
		assertTrue(DshProcessManager.findRunning(instance).isEmpty(),
		           "nothing has been started by this JVM, and no record says otherwise");

		DshRunningRecord.write(instance, ProcessHandle.current().pid(), 3567, ADDRESS, DshSurface.WEB,
		                       began());

		DshProcessManager.Running running = DshProcessManager.findRunning(instance).orElse(null);
		assertNotNull(running, "the record is what says an instance another launcher started is up");
		assertEquals(instance.id(), running.instanceId());
		assertEquals(ADDRESS, running.webUrl().orElse(null));
		assertEquals(DshSurface.WEB.name(), running.surface());
	}

	@Test
	void aRecordWhoseProcessIsGoneIsNotARecordOfAnything(@TempDir Path directory) {
		DshInstance instance = instanceIn(directory);
		DshRunningRecord.write(instance, Long.MAX_VALUE, 4999, null, DshSurface.WEB, System.currentTimeMillis());

		assertNull(DshRunningRecord.read(instance), "a pid nothing holds is not a running instance");
		assertFalse(Files.exists(directory.resolve("running.json")),
		            "and the reader that found it out deletes it, so the next one does not have to");
	}

	@Test
	void aRecordThatOutlivedItsProcessIsStale(@TempDir Path directory) {
		// The pid is alive — it is this one — but the record says the process began an hour ago. A
		// pid is reused, so a process that began long after the record was written is not the one it
		// describes.
		DshInstance instance = instanceIn(directory);
		DshRunningRecord.write(instance, ProcessHandle.current().pid(), 4999, null, DshSurface.WEB,
		                       System.currentTimeMillis() - Duration.ofHours(1).toMillis());

		assertNull(DshRunningRecord.read(instance));
	}

	@Test
	void aRecordCanBeTakenBack(@TempDir Path directory) {
		DshInstance instance = instanceIn(directory);
		DshRunningRecord.write(instance, ProcessHandle.current().pid(), 4999, null, DshSurface.WEB, began());
		assertNotNull(DshRunningRecord.read(instance));

		DshRunningRecord.delete(instance);
		assertNull(DshRunningRecord.read(instance), "an instance that stopped is not running");
	}

	@Test
	void aSecondLauncherDoesNotStartWhatTheFirstOneIsRunning(@TempDir Path directory) {
		// The launcher keeps one process per instance because a second harness on one home races the
		// profile files. What it could not see until the record existed was a process another
		// launcher had started, so this is that check reaching across processes.
		DshInstance instance = instanceIn(directory);
		DshRunningRecord.write(instance, ProcessHandle.current().pid(), 3567, ADDRESS, DshSurface.WEB,
		                       began());

		DshException refused = assertThrows(DshException.class, () -> DshProcessManager.launch(instance));
		assertTrue(refused.getMessage().contains("already running"), refused.getMessage());
		assertTrue(refused.getMessage().contains("another launcher"), refused.getMessage());
	}

	private static long began() {
		return ProcessHandle.current().info().startInstant().orElseGet(Instant::now).toEpochMilli();
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
