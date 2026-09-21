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

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the port policy that keeps an instance on one origin.
class DshPortsTest {
    /// Builds an instance with the given port policy.
    ///
    /// @param mode the policy
    /// @param port the remembered or fixed port
    /// @return the instance
    private static DshInstance instance(DshPortMode mode, int port) {
        return new DshInstance("test", "0.0.0", "web", "/tmp",
                DshNodeRuntime.SYSTEM, DshHomeMode.ISOLATED, null,
                List.of(), Map.of(), DshInstanceIcon.DEFAULT.id(), null, mode, port, 0L);
    }

    @Test
    void aFixedPortIsUsedAsGiven() throws Exception {
        assertEquals(34567, DshPorts.resolve(instance(DshPortMode.FIXED, 34567)));
    }

    @Test
    void anAutomaticInstanceKeepsThePortItWasGiven() throws Exception {
        // The probe socket must be released first: while it is bound, the port
        // is by definition not free and the allocator would rightly move on.
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        assertEquals(port, DshPorts.resolve(instance(DshPortMode.AUTO, port)),
                "a remembered port that is still free must be reused");
    }

    @Test
    void aTakenPortRefusesTheLaunchRatherThanMovingTheInstance() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = taken.getLocalPort();
            DshPorts.PortUnavailableException refused = assertThrows(
                    DshPorts.PortUnavailableException.class,
                    () -> DshPorts.resolve(instance(DshPortMode.AUTO, port)),
                    "the browser interface keys its state by origin, so an instance must not be moved");
            assertEquals(port, refused.port(), "the message has to name the port to free");
            assertEquals("test", refused.instanceId());
        }
    }

    @Test
    void aTakenFixedPortIsRefusedTheSameWay() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = taken.getLocalPort();
            assertThrows(DshPorts.PortUnavailableException.class,
                    () -> DshPorts.resolve(instance(DshPortMode.FIXED, port)));
        }
    }

    @Test
    void aReservedPortIsFreeAndInTheLaunchersOwnRange() throws Exception {
        int port = DshPorts.reserve("a-new-instance");
        assertTrue(port >= 3081 && port <= 4081, "reserved ports stay in the launcher's band, got " + port);
        assertTrue(DshPorts.isFree(port));
    }

    @Test
    void aReservationAvoidsThePortsOtherInstancesHold() throws Exception {
        // Another instance already holds a port; a new one must not be given it,
        // or the second instance to start would find its own port taken.
        DshInstance existing = DshInstanceManager.create("holds-a-port", "1.0.0",
                DshInstance.DEFAULT_PROFILE, java.nio.file.Path.of("/tmp"),
                DshHomeMode.ISOLATED, null, List.of(), Map.of());
        try {
            assertTrue(existing.portOrDefault() > 0, "creating an instance settles its port");
            assertNotEquals(existing.portOrDefault(), DshPorts.reserve("another-instance"),
                    "two instances must not be promised the same port");
        } finally {
            DshInstanceManager.delete(existing.id());
        }
    }

    @Test
    void anAutomaticInstanceWithNoPortGetsAFreeOne() throws Exception {
        int port = DshPorts.resolve(instance(DshPortMode.AUTO, 0));
        assertTrue(port > 0);
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            assertTrue(probe.isBound());
        }
    }

    @Test
    void validationRejectsPrivilegedAndOutOfRangePorts() {
        assertNull(DshPorts.validate(25565));
        assertFalse(DshPorts.validate(80) == null, "ports below 1024 need root");
        assertFalse(DshPorts.validate(70000) == null);
        assertFalse(DshPorts.validate(null) == null);
    }

    @Test
    void theSurfacePassesTheResolvedPortThrough() {
        assertTrue(DshSurface.WEB.arguments(39000).contains("39000"));
        assertFalse(DshSurface.WEB.arguments(39000).contains("0"));
        // Only the browser surface serves HTTP, so only it takes a port.
        assertEquals(List.of(), DshSurface.ACP.arguments(39000));
    }
}
