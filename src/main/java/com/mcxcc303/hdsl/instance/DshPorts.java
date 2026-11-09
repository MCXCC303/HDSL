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

import com.mcxcc303.hdsl.core.DshException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Serial;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Chooses and holds the port an instance serves on.
 * <p>
 * The browser surface keys session state by origin, so an instance keeps one port for its life.
 * A port that has since been taken is not worked around, the launch is refused instead.
 */
@NotNullByDefault
public final class DshPorts {
	private static final int AUTO_RANGE_START = 3081;
	private static final int AUTO_RANGE_END = 4081;
	private static final int RANDOM_ATTEMPTS = 64;
	// two launchers must not both start at the bottom of the range.
	private static final Random RANDOM = new Random();

	private DshPorts() {}

	/**
	 * Reserves a free port for an instance, excluding ports other instances hold.
	 * <p>
	 * Called when an instance is created, so its port is fixed for its life.
	 *
	 * @param instanceId the instance being created
	 * @return the reserved port
	 * @throws DshException when no free port can be found in the range
	 */
	public static int reserve(String instanceId) throws DshException {
		return pick(claimedPorts(instanceId));
	}

	/**
	 * Gives an instance under the automatic policy another port and records it.
	 * <p>
	 * A named port is the user's, not the launcher's; an instance that names one keeps it.
	 *
	 * @param instance the instance
	 * @return the port the instance now has
	 * @throws DshException when the instance names its own port, or no free port is found
	 */
	public static int reassign(DshInstance instance) throws DshException {
		if (instance.portModeOrDefault() != DshPortMode.AUTO) {
			throw new DshException("Instance " + instance.id() + " names its own port");
		}
		Set<Integer> claimed = claimedPorts(instance.id());
		// Exclude both numbers the instance holds now
		// It must move, and its old port may lie outside the band
		claimed.add(instance.portOrDefault());
		claimed.add(autoPort(instance));

		int port = pick(claimed);
		// the record can be read between the two writes, and must never name a port the other file disagrees with.
		DshInstanceSettings.setAutoPort(instance, port);
		DshInstanceManager.update(instance.withPort(port));
		return port;
	}

	/**
	 * Resolves the port to launch an instance on.
	 * <p>
	 * An instance with no port yet (made before ports were reserved) will get one.
	 *
	 * @param instance the instance being launched
	 * @return the port to pass to DeepSeek Harness
	 * @throws DshException when no free port is found, or the instance's port is taken
	 */
	public static int resolve(DshInstance instance) throws DshException {
		int port = instance.portOrDefault();
		if (port <= 0) {
			LOG.info("Instance " + instance.id() + " has no port yet; reserving one");
			return reserve(instance.id());
		}
		if (!isFree(port)) {
			// another port is another origin, so the launch fails instead of moving.
			throw new PortUnavailableException(instance.id(), port);
		}
		return port;
	}

	/**
	 * Returns the key the harness asks for, read from the address it printed.
	 * <p>
	 * A harness from before the key existed printed none; an address with no key returns
	 * empty, the same as an empty key.
	 *
	 * @param printed the address the harness printed, or {@code null}
	 * @return the key, or empty when the address carries none
	 */
	public static Optional<String> launchToken(@Nullable URI printed) {
		String query = printed == null ? null : printed.getQuery();
		if (query == null || query.isBlank()) return Optional.empty();

		for (String pair : query.split("&")) {
			int equals = pair.indexOf('=');
			if (!"token".equals(equals < 0 ? pair : pair.substring(0, equals))) continue;

			String value = equals < 0 ? "" : pair.substring(equals + 1);
			return value.isBlank() ? Optional.empty() : Optional.of(value);
		}
		return Optional.empty();
	}

	public static int observedPort(@Nullable URI reported, int planned) {
		if (reported == null) return planned;
		int port = reported.getPort();
		return port > 0 ? port : planned;
	}

	/**
	 * Returns the address a browser is sent to for an instance.
	 * <p>
	 * The printed address is used while its port matches the recorded one, since it carries the token.
	 * Otherwise, the instance's own address is returned, deliberately without a token:
	 * the other port would be a different origin.
	 *
	 * @param instance the instance
	 * @param reported the address the harness printed, or {@code null}
	 * @return the address to open
	 */
	public static URI openAddress(DshInstance instance, @Nullable URI reported) {
		int port = instance.portOrDefault();
		boolean servingWhereItIsRecorded = reported != null && (port <= 0 || observedPort(reported, port) == port);
		return servingWhereItIsRecorded ? reported : URI.create("http://127.0.0.1:" + Math.max(port, 0) + "/");
	}

	/**
	 * Records the port an instance settled on.
	 * <p>
	 * Only an instance that had none port will be written.
	 */
	public static void remember(DshInstance instance, int port) throws DshException {
		if (port <= 0 || instance.portOrDefault() == port) return;
		if (instance.portModeOrDefault() != DshPortMode.AUTO) return;

		DshInstanceManager.update(instance.withPort(port));
		if (DshInstanceSettings.autoPort(instance) <= 0) {
			DshInstanceSettings.setAutoPort(instance, port);
		}
	}

	/**
	 * Returns the port the launcher gave an instance.
	 * <p>
	 * An instance made before this was recorded falls back to its own port when automatic.
	 *
	 * @param instance the instance
	 * @return the port, or {@code 0} when the launcher never gave it one
	 */
	public static int autoPort(DshInstance instance) {
		int recorded = DshInstanceSettings.autoPort(instance);
		if (recorded > 0) return recorded;
		return instance.portModeOrDefault() == DshPortMode.AUTO ? instance.portOrDefault() : 0;
	}

	/**
	 * Writes down the launcher's port before a named one takes over.
	 *
	 * @param instance the instance
	 * @throws DshException when the choice cannot be written
	 */
	public static void keepAutoPort(DshInstance instance) throws DshException {
		if (DshInstanceSettings.autoPort(instance) > 0 || instance.portModeOrDefault() != DshPortMode.AUTO) {
			return;
		}
		int port = instance.portOrDefault();
		if (port > 0) DshInstanceSettings.setAutoPort(instance, port);
	}

	/**
	 * Returns an instance under a different port policy.
	 * <p>
	 * The launcher's port is saved before it takes over and restored when the user stops naming one.
	 *
	 * @param instance the instance
	 * @param mode     the policy to record
	 * @return the instance to store
	 * @throws DshException when a port has to be reserved and none can be found
	 */
	public static DshInstance withMode(DshInstance instance, DshPortMode mode) throws DshException {
		if (mode == DshPortMode.FIXED) {
			keepAutoPort(instance);
			return instance.withPortPolicy(mode, instance.portOrDefault());
		}
		int automatic = autoPort(instance);
		if (automatic <= 0) {
			automatic = reserve(instance.id());
			DshInstanceSettings.setAutoPort(instance, automatic);
		}
		return instance.withPortPolicy(mode, automatic);
	}

	/**
	 * Reports whether a port can be bound on the loopback interface.
	 */
	public static boolean isFree(int port) {
		if (port <= 0 || port > 65535) return false;

		try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
			socket.setReuseAddress(false);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Returns the ports other instances have been given.
	 *
	 * @param exceptInstanceId the instance to leave out, or {@code null}
	 * @return the ports in use on paper
	 */
	static Set<Integer> claimedPorts(@Nullable String exceptInstanceId) {
		Set<Integer> claimed = new HashSet<>();
		for (DshInstance instance : List.copyOf(InstanceListing.list())) {
			if (instance.id().equals(exceptInstanceId)) continue;

			for (int port : new int[]{instance.portOrDefault(), autoPort(instance)}) {
				if (port > 0) claimed.add(port);
			}
		}
		return claimed;
	}

	/**
	 * Picks a free port in the launcher's band. Random candidates first, then a sequential sweep.
	 */
	private static int pick(Set<Integer> claimed) throws DshException {
		int span = AUTO_RANGE_END - AUTO_RANGE_START + 1;

		for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
			int candidate = AUTO_RANGE_START + RANDOM.nextInt(span);
			if (!claimed.contains(candidate) && isFree(candidate)) return candidate;
		}

		for (int port = AUTO_RANGE_START; port <= AUTO_RANGE_END; port++) {
			if (!claimed.contains(port) && isFree(port)) return port;
		}
		throw new DshException("No free port was found between " + AUTO_RANGE_START + " and " + AUTO_RANGE_END);
	}

	/**
	 * Validates a user-entered port.
	 *
	 * @param port the port, may be {@code null}
	 * @return the complaint, or {@code null} when the port is usable
	 */
	public static @Nullable String validate(@Nullable Integer port) {
		if (port == null) return "Enter a port number";
		if (port < 1024 || port > 65535) return "Use a port between 1024 and 65535";
		return null;
	}

	/**
	 * Raised when an instance's port is taken, and the launch must be refused.
	 *
	 * @see DshPorts#resolve(DshInstance)
	 */
	public static final class PortUnavailableException extends DshException {
		@Serial
		private static final long serialVersionUID = 1L;
		private final String instanceId;
		private final int port;

		PortUnavailableException(String instanceId, int port) {
			super("Port " + port + " for instance " + instanceId + " is already in use");
			this.instanceId = instanceId;
			this.port = port;
		}

		public String instanceId() {
			return instanceId;
		}

		public int port() {
			return port;
		}
	}
}
