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

import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.instance.DshPortMode;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Agent Client Protocol client against a stub peer.
 */
class DshAcpClientTest {
	private static final Path TEMP = Path.of(System.getProperty("java.io.tmpdir"));

	private static DshInstance testInstance() {
		return new DshInstance("test",
		                       "0.0.0",
		                       "acp",
		                       TEMP.toString(),
		                       DshNodeRuntime.SYSTEM,
		                       DshHomeMode.ISOLATED,
		                       null,
		                       List.of(),
		                       Map.of(),
		                       DshInstanceIcon.DEFAULT.id(),
		                       null,
		                       DshPortMode.AUTO,
		                       0,
		                       0L);
	}

	private static List<String> stubCommand() {
		return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
		               "-cp",
		               System.getProperty("java.class.path"),
		               StubAcpServer.class.getName());
	}

	@Test
	void streamsAssistantTextAndReportsTheStopReason() throws Exception {
		List<String> text = new ArrayList<>();
		CountDownLatch finished = new CountDownLatch(1);
		List<String> stopReasons = new ArrayList<>();

		DshAcpClient.Listener listener = new DshAcpClient.Listener() {
			@Override
			public void onText(String chunk) {
				text.add(chunk);
			}

			@Override
			public void onPromptFinished(String stopReason) {
				stopReasons.add(stopReason);
				finished.countDown();
			}
		};

		try (DshAcpClient client = DshAcpClient.connect(testInstance(), stubCommand(), TEMP, Map.of(), listener)) {

			String sessionId = client.newSession();
			assertEquals(StubAcpServer.SESSION_ID, sessionId);

			client.prompt(sessionId, "say pong");

			assertTrue(finished.await(30, TimeUnit.SECONDS), "the prompt never settled");
		}

		assertEquals(List.of(StubAcpServer.CHUNKS), text, "the client must deliver every streamed chunk, in order");
		assertEquals(List.of(StubAcpServer.STOP_REASON), stopReasons);
	}

	@Test
	void closingTheConnectionStopsTheChild() throws Exception {
		DshAcpClient client = DshAcpClient.connect(testInstance(),
		                                           stubCommand(),
		                                           TEMP,
		                                           Map.of(),
		                                           new DshAcpClient.Listener() {
		                                           });

		assertEquals(StubAcpServer.SESSION_ID, client.newSession());
		assertTrue(client.isRunning());

		client.close();

		assertFalse(client.isRunning(), "closing stdin must let the peer exit");
	}
}
