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
import java.io.Serial;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Port selection for the browser surface.
///
/// DeepSeek Harness exits with status 1 when its port is taken rather than
/// probing for another one, so the launcher has to choose a free port itself.
/// It also has to keep choosing the *same* port for an instance: the browser
/// interface keys session state by origin, so reaching one history through two
/// ports lets two writers corrupt it.
///
/// The port is therefore settled once, when the instance is created, and written
/// into its manifest; every launch from then on asks for that same port. A port
/// that has since been taken is not worked around — moving the instance would
/// change the origin its state is keyed by — so the launch is refused and the
/// user is told which port to free or to change.
@NotNullByDefault
public final class DshPorts {
    private DshPorts() {
    }

    /// The lowest port the automatic allocator will hand out.
    private static final int AUTO_RANGE_START = 3081;

    /// The highest port the automatic allocator will hand out.
    private static final int AUTO_RANGE_END = 4081;

    /// How many random candidates are tried before the range is swept in order.
    private static final int RANDOM_ATTEMPTS = 64;

    /// The source of the candidates.
    ///
    /// Random rather than sequential so that two launchers, or two runs of the
    /// same one, do not both hand out the bottom of the range and collide the
    /// moment anything else on the machine takes a port.
    private static final java.util.Random RANDOM = new java.util.Random();

    /// Raised when the port an instance must bind is held by something else.
    ///
    /// Carries the port, because the interface names it when it explains what to
    /// do about it, and the domain layer is where the number is known.
    public static final class PortUnavailableException extends DshException {
        @Serial
        private static final long serialVersionUID = 1L;

        /// The instance that cannot have its port.
        private final String instanceId;

        /// The port that is taken.
        private final int port;

        /// Creates the exception.
        ///
        /// @param instanceId the instance
        /// @param port       the port it needs
        PortUnavailableException(String instanceId, int port) {
            super("Port " + port + " for instance " + instanceId + " is already in use");
            this.instanceId = instanceId;
            this.port = port;
        }

        /// Returns the instance that cannot be started.
        ///
        /// @return the instance id
        public String instanceId() {
            return instanceId;
        }

        /// Returns the port that is taken.
        ///
        /// @return the port
        public int port() {
            return port;
        }
    }

    /// Reserves a free port for an instance, avoiding the ones other instances hold.
    ///
    /// Called when an instance is created, so that the port it will use for its
    /// whole life is decided once and written down, rather than being discovered
    /// on the first launch — which is what left an instance without a port while
    /// it sat in the list, and let two instances be given the same one.
    ///
    /// @param instanceId the instance being created, excluded from the ports in use
    /// @return the reserved port
    /// @throws DshException when no free port can be found in the range
    public static int reserve(String instanceId) throws DshException {
        Set<Integer> claimed = claimedPorts(instanceId);
        int span = AUTO_RANGE_END - AUTO_RANGE_START + 1;

        for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
            int candidate = AUTO_RANGE_START + RANDOM.nextInt(span);
            if (!claimed.contains(candidate) && isFree(candidate)) {
                return candidate;
            }
        }

        for (int port = AUTO_RANGE_START; port <= AUTO_RANGE_END; port++) {
            if (!claimed.contains(port) && isFree(port)) {
                return port;
            }
        }
        throw new DshException("No free port was found between " + AUTO_RANGE_START
                + " and " + AUTO_RANGE_END);
    }

    /// Resolves the port to launch an instance on.
    ///
    /// The instance's own port is used as it stands, whether the launcher picked
    /// it or the user did. An instance that has no port yet — one made before
    /// ports were reserved — is given one now, and keeps it from then on.
    ///
    /// @param instance the instance being launched
    /// @return the port to pass to DeepSeek Harness
    /// @throws DshException when no free port can be found, or the instance's own
    ///                       port is held by something else
    public static int resolve(DshInstance instance) throws DshException {
        int port = instance.portOrDefault();
        if (port <= 0) {
            LOG.info("Instance " + instance.id() + " has no port yet; reserving one");
            return reserve(instance.id());
        }
        if (!isFree(port)) {
            // The port is part of what the instance is: the browser interface
            // keys its state by origin, so an instance that came up somewhere
            // else would be an instance whose history is somewhere else. The
            // launcher says so instead of quietly changing it.
            throw new PortUnavailableException(instance.id(), port);
        }
        return port;
    }

    /// Records the port an instance settled on.
    ///
    /// Only an instance that had none is written to: a port the launcher or the
    /// user already chose is the one it keeps, and rewriting it would be exactly
    /// the silent move this class exists to prevent.
    ///
    /// @param instance the instance
    /// @param port     the port DeepSeek Harness is bound to
    /// @throws DshException when the instance cannot be written back
    public static void remember(DshInstance instance, int port) throws DshException {
        if (port <= 0 || instance.portOrDefault() == port) {
            return;
        }
        if (instance.portModeOrDefault() != DshPortMode.AUTO) {
            return;
        }
        DshInstanceManager.update(instance.withPort(port));
    }

    /// Reports whether a port can be bound on the loopback interface.
    ///
    /// @param port the port to test
    /// @return whether it is free
    public static boolean isFree(int port) {
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

    /// Returns the ports other instances have been given.
    ///
    /// @param exceptInstanceId the instance to leave out, or `null`
    /// @return the ports in use on paper
    private static Set<Integer> claimedPorts(@Nullable String exceptInstanceId) {
        Set<Integer> claimed = new HashSet<>();
        for (DshInstance instance : List.copyOf(DshInstanceManager.list())) {
            if (instance.id().equals(exceptInstanceId)) {
                continue;
            }
            int port = instance.portOrDefault();
            if (port > 0) {
                claimed.add(port);
            }
        }
        return claimed;
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
