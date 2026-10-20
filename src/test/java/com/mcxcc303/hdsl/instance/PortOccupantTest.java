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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the process lookup behind the message that says a port is taken.
 *
 * The platform rows are written by hand; the lookup is tested against a socket this JVM holds.
 */
class PortOccupantTest {
	/**
	 * How long the tests give a lookup. Longer than the launcher's own, because on Windows and
	 * macOS the lookup runs an external command and a loaded CI runner answers slowly.
	 */
	private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(30);

	/**
	 * Reads a listening pid out of `netstat -ano` output without reading the state word.
	 *
	 * Windows translates that column, so a German machine writes `ABHÖREN` where an English one
	 * writes `LISTENING`. The row carrying the German word is the only listening row on its
	 * port, so a parser that began requiring `LISTENING` would answer differently here.
	 */
	@Test
	void readsTheListeningPidWithoutReadingTheStateWord() {
		List<String> german = List.of(
				"活动连接",
				"",
				"  协议  本地地址          外部地址        状态            PID",
				"  TCP    0.0.0.0:3081           0.0.0.0:0              ABHÖREN         4321");

		assertEquals(OptionalLong.of(4321), PortOccupant.pidListeningOn(german, 3081),
		             "the state column is translated, so only the shape of the row says it is listening");

		List<String> english = List.of(
				"  TCP    0.0.0.0:3081           0.0.0.0:0              LISTENING       4321",
				// An established row on the same port: its foreign address is not the wildcard.
				"  TCP    127.0.0.1:3081         127.0.0.1:52344        HERGESTELLT     9999",
				// A listening row on another port.
				"  TCP    0.0.0.0:3082           0.0.0.0:0              LISTENING       1111",
				// UDP carries neither the listening state nor a pid.
				"  UDP    0.0.0.0:3081           *:*                                    7777");

		assertEquals(OptionalLong.of(4321), PortOccupant.pidListeningOn(english, 3081));
		assertEquals(OptionalLong.of(1111), PortOccupant.pidListeningOn(english, 3082));
		assertTrue(PortOccupant.pidListeningOn(english, 3083).isEmpty());
		assertTrue(PortOccupant.pidListeningOn(List.of(), 3081).isEmpty());
	}

	/**
	 * Reads the socket inodes out of the lines of one `/proc/net` table.
	 *
	 * What is fixed here is the position of the state column and of the inode column.
	 */
	@Test
	void readsTheListeningInodesOutOfProcRows() {
		String header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt" +
		                "   uid  timeout inode";
		// 0x0C61 is 3169.
		String listening = "   0: 0100007F:0C61 00000000:0000 0A 00000000:00000000 00:00000000" +
		                   " 00000000  1000        0 123456 1 0000000000000000 100 0 0 10 0";
		String established = "   1: 0100007F:0C61 0100007F:1F90 01 00000000:00000000 00:00000000" +
		                     " 00000000  1000        0 654321 1 0000000000000000 20 4 30 10 -1";
		String otherPort = "   2: 0100007F:0C62 00000000:0000 0A 00000000:00000000 00:00000000" +
		                   " 00000000  1000        0 999999 1 0000000000000000 100 0 0 10 0";

		assertEquals(Set.of("123456"), PortOccupant.inodesFromProc(
				List.of(header, listening, established, otherPort), 3169),
		             "an established row does not own the listening socket");
		assertTrue(PortOccupant.inodesFromProc(List.of(header), 3169).isEmpty());
	}

	/**
	 * Names the process behind a port that is really being listened on, and names nothing once
	 * the socket is gone.
	 *
	 * Both halves are asserted on the same port, so that a lookup which always answered empty
	 * cannot pass.
	 */
	@Test
	void namesTheProcessListeningOnAPortAndNotOneThatIsFree() throws IOException {
		int port;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = socket.getLocalPort();

			var occupant = PortOccupant.find(port, LOOKUP_TIMEOUT);

			assertTrue(occupant.isPresent(), "the test's own listener should be found on port " + port);
			assertEquals(ProcessHandle.current().pid(), occupant.get().pid(),
			             "the process holding the port is this JVM");
			assertNotNull(occupant.get().name(),
			              "this JVM's own name is readable, so the fallback chain reaches it");
		}

		assertTrue(PortOccupant.find(port, LOOKUP_TIMEOUT).isEmpty(),
		           "the same port has no listener once the socket is closed");
	}

	/**
	 * Refuses a port that cannot be a port at all.
	 */
	@Test
	void refusesAPortThatIsNotOne() {
		assertTrue(PortOccupant.find(0).isEmpty());
		assertTrue(PortOccupant.find(-1).isEmpty());
		assertTrue(PortOccupant.find(65536).isEmpty());
	}

	/**
	 * Describes a process by its command line, falling back to its name and shortened at a
	 * code point boundary.
	 */
	@Test
	void describesAProcessByItsCommandLine() {
		var byLine = new PortOccupant.Occupant(42, "node", "node /opt/dsh/lib/bin.js --port 3081", "/usr/bin/node");
		var byName = new PortOccupant.Occupant(42, "node.exe", null, null);
		var byProgram = new PortOccupant.Occupant(42, null, null, "/usr/bin/node");
		var unknown = new PortOccupant.Occupant(42, null, null, null);
		var blank = new PortOccupant.Occupant(42, "  ", "   ", null);

		assertEquals("node /opt/dsh/lib/bin.js --port 3081", PortOccupant.describe(byLine),
		             "the command line names the process more precisely than the program does");
		assertEquals("node.exe", PortOccupant.describe(byName),
		             "a name read without a command line is still worth showing");
		assertEquals("/usr/bin/node", PortOccupant.describe(byProgram));
		assertNull(PortOccupant.describe(unknown), "a process nothing is known about is not described");
		assertNull(PortOccupant.describe(null));
		assertNull(PortOccupant.describe(blank));

		String atLimit = "x".repeat(120);
		assertEquals(atLimit, PortOccupant.describe(new PortOccupant.Occupant(42, null, atLimit, null)),
		             "a command line that fits is shown whole");

		String overLimit = "x".repeat(121);
		assertEquals(atLimit + "...", PortOccupant.describe(new PortOccupant.Occupant(42, null, overLimit, null)),
		             "one character over the limit loses that character and gains an ellipsis");

		// A character outside the basic plane is two code units, and a cut between them would
		// leave half of it on its own.
		String emoji = "y".repeat(119) + "😀" + "z".repeat(10);
		String described = PortOccupant.describe(new PortOccupant.Occupant(42, null, emoji, null));
		assertNotNull(described);
		assertEquals("y".repeat(119) + "😀...", described,
		             "the cut lands after the whole character rather than inside it");
	}

	/**
	 * Takes the executable's name out of a command line, quote-aware.
	 */
	@Test
	void namesTheProcessFromItsCommandLine() {
		assertEquals("node.exe",
		             PortOccupant.nameOf("\"C:\\Program Files\\nodejs\\node.exe\" -e x"),
		             "a quoted Windows path is one token, not the word before the first space");
		assertEquals("node", PortOccupant.nameOf("/usr/bin/node script.js"));
		assertEquals("python3", PortOccupant.nameOf("/usr/bin/python3"));
		assertNull(PortOccupant.nameOf(null));
		assertNull(PortOccupant.nameOf("   "));
	}

	/**
	 * Reads the image name out of `tasklist` CSV output, skipping the notices it prints
	 * instead of a row.
	 */
	@Test
	void readsTheImageNameOutOfTasklistRows() {
		assertEquals("node.exe",
		             PortOccupant.imageNameFromTasklist(List.of("\"node.exe\",\"18892\",\"Console\",\"1\",\"1.234 K\"")),
		             "the first quoted field is the image name");
		assertEquals("dsh",
		             PortOccupant.imageNameFromTasklist(List.of("信息: 没有运行的任务匹配指定标准。", "\"dsh\",\"42\"")),
		             "a notice is translated, so only the shape of the row says it is not one");
		assertNull(PortOccupant.imageNameFromTasklist(List.of("信息: 没有运行的任务匹配指定标准。")));
		assertNull(PortOccupant.imageNameFromTasklist(List.of()));
	}
}
