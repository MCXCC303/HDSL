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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Port selection for the browser surface.
///
/// DeepSeek Harness exits with status 1 when its port is taken rather than
/// probing for another one, so the launcher has to choose a free port itself.
/// It also has to keep choosing the *same* port for an instance: the browser
/// interface keys session state by origin, so reaching one history through two
/// ports lets two writers corrupt it.
@NotNullByDefault
public final class DshPorts {
    private DshPorts() {
    }

    /// The lowest port the automatic allocator will hand out.
    private static final int AUTO_RANGE_START = 3081;

    /// The highest port the automatic allocator will hand out.
    private static final int AUTO_RANGE_END = 4081;

    /// Resolves the port to launch an instance on.
    ///
    /// A fixed port is used as given. An automatic instance keeps the port it
    /// was first given; only when that port has since been taken by something
    /// else does the launcher move it, because a launch that cannot bind is
    /// worse than a changed port.
    ///
    /// @param instance the instance being launched
    /// @return the port to pass to DeepSeek Harness
    /// @throws DshException when no free port can be found in the range
    public static int resolve(DshInstance instance) throws DshException {
        if (instance.portMode() == DshPortMode.FIXED) {
            return instance.port();
        }

        int remembered = instance.port();
        if (remembered > 0) {
            if (isFree(remembered)) {
                return remembered;
            }
            LOG.warning("Port " + remembered + " for instance " + instance.id()
                    + " is taken; allocating another one");
        }

        return allocate();
    }

    /// Records the port an automatic instance settled on.
    ///
    /// @param instance the instance
    /// @param port     the port DeepSeek Harness is bound to
    /// @throws DshException when the instance cannot be written back
    public static void remember(DshInstance instance, int port) throws DshException {
        if (instance.portMode() == DshPortMode.AUTO && instance.port() != port) {
            DshInstanceManager.update(instance.withPort(port));
        }
    }

    /// Finds a free port in the allocator's range.
    ///
    /// @return the port
    /// @throws DshException when the range is exhausted
    private static int allocate() throws DshException {
        for (int port = AUTO_RANGE_START; port <= AUTO_RANGE_END; port++) {
            if (isFree(port)) {
                return port;
            }
        }
        throw new DshException("No free port was found between " + AUTO_RANGE_START
                + " and " + AUTO_RANGE_END);
    }

    /// Reports whether a port can be bound on the loopback interface.
    ///
    /// @param port the port to test
    /// @return whether it is free
    private static boolean isFree(int port) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            socket.setReuseAddress(false);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /// Validates a user-entered port.
    ///
    /// @param port the port, or `null`
    /// @return the complaint, or `null` when the port is usable
    public static @Nullable String validate(@Nullable Integer port) {
        if (port == null) {
            return "Enter a port number";
        }
        if (port < 1024 || port > 65535) {
            return "Use a port between 1024 and 65535";
        }
        return null;
    }
}
