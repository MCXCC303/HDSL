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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the Agent Client Protocol client against a stub peer.
///
/// The streaming path is the part that cannot be exercised against the real
/// server without a model credential, so it is pinned here instead: framing,
/// request correlation, notification dispatch and shutdown.
class DshAcpClientTest {
    /// Builds a throwaway instance descriptor; the transport is supplied explicitly.
    ///
    /// @return the instance
    private static DshInstance testInstance() {
        return new DshInstance("test", "0.0.0", "acp", "/tmp",
                DshNodeRuntime.SYSTEM, DshHomeMode.ISOLATED, null,
                List.of(), Map.of(), 0L);
    }

    /// Builds the command that runs the stub on the current test classpath.
    ///
    /// @return the command
    private static List<String> stubCommand() {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
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

        try (DshAcpClient client = DshAcpClient.connect(
                testInstance(), stubCommand(), Path.of("/tmp"), Map.of(), listener)) {

            String sessionId = client.newSession();
            assertEquals(StubAcpServer.SESSION_ID, sessionId);

            client.prompt(sessionId, "say pong");

            assertTrue(finished.await(30, TimeUnit.SECONDS), "the prompt never settled");
        }

        assertEquals(List.of(StubAcpServer.CHUNKS), text,
                "the client must deliver every streamed chunk, in order");
        assertEquals(List.of(StubAcpServer.STOP_REASON), stopReasons);
    }

    @Test
    void closingTheConnectionStopsTheChild() throws Exception {
        DshAcpClient client = DshAcpClient.connect(
                testInstance(), stubCommand(), Path.of("/tmp"), Map.of(), new DshAcpClient.Listener() {
                });

        assertEquals(StubAcpServer.SESSION_ID, client.newSession());
        assertTrue(client.isRunning());

        client.close();

        assertFalse(client.isRunning(), "closing stdin must let the peer exit");
    }
}
