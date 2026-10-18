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

import java.net.InetAddress;
import java.net.ServerSocket;

/// Binds a port in a process of its own, so a test has something real to find and stop.
///
/// A child rather than a socket in the test's own process: stopping the launcher's own process is
/// exactly what [DshPortHolder#kill] refuses to do, so the only way to test stopping something is
/// to be looking at something else.
public final class PortBinder {
	private PortBinder() {
	}

	/// Binds the port and waits to be stopped.
	///
	/// @param args the port to bind
	public static void main(String[] args) throws Exception {
		int port = Integer.parseInt(args[0]);
		try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
			System.out.println("bound " + socket.getLocalPort());
			System.out.flush();
			Thread.sleep(60_000);
		}
	}
}
