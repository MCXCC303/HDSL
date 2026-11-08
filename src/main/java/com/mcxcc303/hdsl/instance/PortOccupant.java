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

import com.mcxcc303.hdsl.core.DshCommand;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.jackhuang.hmcl.util.platform.NativeUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Identifies the process listening on a port.
 * <p>
 * The fields are filled from what the platform reports.
 * <p>
 * Blocks while it reads the process table or runs a lookup command, so call it off the interface thread.
 */
@NotNullByDefault
public final class PortOccupant {
	// How long one lookup step is given. `lsof` can hang on an unresponsive filesystem, so PowerShell is slow to start.
	private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);
	// How much of a description is shown before it is shortened.
	private static final int MAX_DESCRIBED = 120;
	private static final int AF_INET = 2;
	private static final int AF_INET6 = 23;
	private static final int TCP_TABLE_OWNER_PID_LISTENER = 3;

	private PortOccupant() {
	}

	/**
	 * Finds the process listening on a port.
	 * <p>
	 * When several sockets listen on the port, the first one the platform reports is returned.
	 *
	 * @param port the port
	 * @return the process, or empty when nothing is listening, the port is invalid, or the platform
	 * cannot report the process
	 */
	public static Optional<Occupant> find(int port) {
		return find(port, LOOKUP_TIMEOUT);
	}

	/**
	 * Finds the process listening on a port, bounded by a timeout.
	 *
	 * @param port    the port
	 * @param timeout how long each lookup step is given
	 * @return the process, or empty when nothing is listening, the port is invalid, or the platform
	 * cannot report the process
	 */
	static Optional<Occupant> find(int port, Duration timeout) {
		if (port <= 0 || port > 65535) return Optional.empty();
		OptionalLong pid = switch (OperatingSystem.CURRENT_OS) {
			case WINDOWS -> pidOnWindows(port, timeout);
			case LINUX -> pidFromProc(port, timeout);
			case MACOS, FREEBSD -> pidFromLsof(port, timeout);
			default -> OptionalLong.empty();
		};
		return pid.isEmpty() ? Optional.empty() : Optional.of(occupantOf(pid.getAsLong()));
	}

	private static Occupant occupantOf(long pid) {
		ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
		String commandLine = null;
		String executable = null;
		if (handle != null) {
			commandLine = handle.info().commandLine().orElse(null);
			executable = handle.info().command().orElse(null);
		}
		// `ProcessHandle` can report no command line; `/proc/<pid>/cmdline` supplies one on Linux.
		if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX) {
			String fromProc = linuxCommandLine(pid);
			if (fromProc != null) commandLine = fromProc;
		}
		String name = nameOf(commandLine);
		if (name == null) name = nameOf(executable);

		if (name == null && OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			name = imageNameFromTasklist(run(List.of("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH"),
			                                 LOOKUP_TIMEOUT));
		}
		return new Occupant(pid, name, commandLine, executable);
	}

	/**
	 * Returns the name of the executable a command line or an executable path starts with.
	 *
	 * @param commandLine the command line, an executable path, or `null`
	 * @return the executable's name, or `null` when none can be read
	 */
	static @Nullable String nameOf(@Nullable String commandLine) {
		if (commandLine == null) return null;

		String text = commandLine.trim();
		if (text.isEmpty()) return null;

		String first;
		if (text.charAt(0) == '"') {
			int end = text.indexOf('"', 1);
			first = end > 0 ? text.substring(1, end) : text.substring(1);
		} else {
			first = text.split("\\s+", 2)[0];
		}
		int slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
		String name = (slash >= 0 ? first.substring(slash + 1) : first).trim();
		return name.isEmpty() ? null : name;
	}

	/**
	 * Returns the image name out of `tasklist` CSV output.
	 *
	 * @param lines the output lines
	 * @return the image name, or `null` when no row carries one
	 */
	static @Nullable String imageNameFromTasklist(List<String> lines) {
		for (String raw : lines) {
			String line = raw.trim();
			if (!line.startsWith("\"")) continue;

			int end = line.indexOf('"', 1);
			if (end > 1) return line.substring(1, end);
		}
		return null;
	}

	/**
	 * Returns a process's command line out of `/proc/<pid>/cmdline`, or `null`.
	 *
	 * @param pid the process
	 * @return the command line, or `null` when it cannot be read
	 */
	private static @Nullable String linuxCommandLine(long pid) {
		try {
			byte[] bytes = Files.readAllBytes(Path.of("/proc", Long.toString(pid), "cmdline"));
			String text = new String(bytes, OperatingSystem.NATIVE_CHARSET).replace('\0', ' ').trim();
			return text.isEmpty() ? null : text;
		} catch (IOException | SecurityException e) {
			return null;
		}
	}

	/**
	 * Finds the listening pid on Windows.
	 * <p>
	 * Tries `netstat -ano`, then the kernel listener table, then `Get-NetTCPConnection`.
	 *
	 * @param port    the port
	 * @param timeout how long each command is given
	 * @return the pid, or empty when none of the sources reports a listener
	 */
	private static OptionalLong pidOnWindows(int port, Duration timeout) {
		OptionalLong pid = pidFromNetstat(port, timeout);
		if (pid.isPresent()) return pid;

		OptionalLong nativePid = pidFromNative(port);
		if (nativePid.isPresent()) return nativePid;

		return pidFromPowershell(port, timeout);
	}

	/**
	 * Queries the Windows kernel TCP listener table via `iphlpapi.dll`.
	 *
	 * @param port the port to find
	 * @return the listening pid, or empty when no listener is found or the query fails
	 */
	private static OptionalLong pidFromNative(int port) {
		if (Iphlpapi.INSTANCE == null) return OptionalLong.empty();

		try {
			OptionalLong ipv4 = queryTcpListeners(AF_INET, port, 24, 8, 20);
			if (ipv4.isPresent()) return ipv4;
			return queryTcpListeners(AF_INET6, port, 56, 20, 52);
		} catch (Throwable e) {
			LOG.warning("Failed to query TCP listener table via iphlpapi: " + e.getMessage());
			return OptionalLong.empty();
		}
	}

	/**
	 * Reads the listening pid out of one TCP table.
	 * <p>
	 * The table starts with a row count, followed by fixed-size rows.
	 * An IPv4 row is 24 bytes, with the port at offset 8 and the pid at offset 20.
	 * An IPv6 row is 56 bytes, with the port at offset 20 and the pid at offset 52.
	 * The port is in network byte order.
	 * The size is queried first.
	 * If rows were added in between, the query is retried with the larger size.
	 *
	 * @param af         the address family the table holds
	 * @param port       the port to find
	 * @param rowSize    the size of one row
	 * @param portOffset the offset of the port in a row
	 * @param pidOffset  the offset of the pid in a row
	 * @return the pid, or empty when no row matches the port
	 */
	private static OptionalLong queryTcpListeners(int af, int port, int rowSize, int portOffset, int pidOffset) {
		IntByReference size = new IntByReference(0);
		int res = Iphlpapi.INSTANCE.GetExtendedTcpTable(Pointer.NULL, size, false, af, TCP_TABLE_OWNER_PID_LISTENER, 0);
		for (int attempt = 0; attempt < 3 && size.getValue() > 0; attempt++) {
			Memory memory = new Memory(size.getValue());
			res = Iphlpapi.INSTANCE.GetExtendedTcpTable(memory, size, false, af, TCP_TABLE_OWNER_PID_LISTENER, 0);
			if (res == 0) {
				int count = memory.getInt(0);
				for (int i = 0; i < count; i++) {
					long row = 4 + (long) i * rowSize;
					int portRaw = memory.getInt(row + portOffset);
					int localPort = ((portRaw & 0xFF) << 8) | ((portRaw >> 8) & 0xFF);
					if (localPort != port) continue;

					long pid = memory.getInt(row + pidOffset) & 0xFFFFFFFFL;
					if (pid > 0) return OptionalLong.of(pid);
				}
				return OptionalLong.empty();
			} else if (res != 122) { // 122 = ERROR_INSUFFICIENT_BUFFER
				break;
			}
		}
		return OptionalLong.empty();
	}

	private static OptionalLong pidFromNetstat(int port, Duration timeout) {
		return pidListeningOn(run(List.of("netstat", "-ano"), timeout), port);
	}

	/**
	 * Finds the listening pid on Windows with `Get-NetTCPConnection`.
	 *
	 * @return the pid, or empty when the command reports no listener or cannot run
	 */
	private static OptionalLong pidFromPowershell(int port, Duration timeout) {
		List<String> lines = run(List.of("powershell",
		                                 "-NoProfile",
		                                 "-NonInteractive",
		                                 "-ExecutionPolicy",
		                                 "Bypass",
		                                 "-Command",
		                                 "Get-NetTCPConnection -LocalPort " + port +
		                                 " -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess"),
		                         timeout);
		return pidFromPowershellOutput(lines);
	}

	/**
	 * Reads the pid out of `Get-NetTCPConnection` output lines.
	 *
	 * @return the pid, or empty when no line is a positive pid
	 */
	static OptionalLong pidFromPowershellOutput(List<String> lines) {
		for (String line : lines) {
			String text = line.trim();
			if (text.isEmpty()) continue;

			try {
				long fallbackPid = Long.parseLong(text);
				if (fallbackPid > 0) return OptionalLong.of(fallbackPid);
			} catch (NumberFormatException ignored) {
				// The command's diagnostics arrive on the same stream as its answer.
			}
		}
		return OptionalLong.empty();
	}

	/**
	 * Reads the listening pid out of `netstat -ano` output.
	 *
	 * @return the pid, or empty when no row reports a listener on the port
	 */
	static OptionalLong pidListeningOn(List<String> lines, int port) {
		String wanted = Integer.toString(port);
		for (String raw : lines) {
			String line = raw.trim();
			if (line.isEmpty()) continue;

			String[] fields = line.split("\\s+");
			// The state column is localized, so it is not parsed.
			// A listening row has protocol `TCP` and foreign port `0`.
			if (fields.length < 5 || !"TCP".equalsIgnoreCase(fields[0])) continue;
			if (!portOf(fields[1]).equals(wanted) || !fields[2].endsWith(":0")) continue;

			try {
				long pid = Long.parseLong(fields[fields.length - 1]);
				if (pid > 0) return OptionalLong.of(pid);

			} catch (NumberFormatException e) {
				// A header, or a row this parser does not know.
			}
		}
		return OptionalLong.empty();
	}

	/**
	 * Returns the port out of `address:port`, in either the IPv4 or the bracketed IPv6 spelling.
	 *
	 * @param address the address
	 * @return the port, or an empty string when there is none
	 */
	private static String portOf(String address) {
		int colon = address.lastIndexOf(':');
		return colon < 0 ? "" : address.substring(colon + 1);
	}

	/**
	 * Finds the listening pid on Linux out of `/proc`.
	 * <p>
	 * `/proc/net/tcp` and `/proc/net/tcp6` map the listening port to a socket inode.
	 * The pid is the process whose `fd` entries point to that inode.
	 *
	 * @param port    the port
	 * @param timeout how long the scan is given
	 * @return the pid, or empty when no row reports a listener on the port
	 */
	private static OptionalLong pidFromProc(int port, Duration timeout) {
		Set<String> inodes = listeningInodes(port);
		if (inodes.isEmpty()) return OptionalLong.empty();

		long deadline = System.nanoTime() + timeout.toNanos();
		try (DirectoryStream<Path> processes = Files.newDirectoryStream(Path.of("/proc"))) {
			for (Path process : processes) {
				if (System.nanoTime() > deadline) {
					LOG.info("Gave up scanning /proc for the process holding port " + port);
					return OptionalLong.empty();
				}
				String name = process.getFileName().toString();
				if (name.isEmpty() || !name.chars().allMatch(Character::isDigit)) continue;

				long pid;
				try {
					pid = Long.parseLong(name);
				} catch (NumberFormatException e) {
					continue;
				}
				if (holdsSocket(process.resolve("fd"), inodes)) return OptionalLong.of(pid);
			}
		} catch (IOException | SecurityException e) {
			LOG.info("Could not scan /proc for the process holding port " + port + ": " + e.getMessage());
		}
		return OptionalLong.empty();
	}

	private static Set<String> listeningInodes(int port) {
		Set<String> inodes = new LinkedHashSet<>(inodesFromProc(readLines(Path.of("/proc/net/tcp")), port));
		// The IPv6 table is absent on a kernel built without IPv6.
		inodes.addAll(inodesFromProc(readLines(Path.of("/proc/net/tcp6")), port));
		return inodes;
	}

	static Set<String> inodesFromProc(List<String> lines, int port) {
		Set<String> inodes = new LinkedHashSet<>();
		for (String raw : lines) {
			String[] fields = raw.trim().split("\\s+");
			// `fields[3]` is the state (`0A` = TCP_LISTEN); `fields[9]` is the inode.
			if (fields.length < 10 || !"0A".equalsIgnoreCase(fields[3])) continue;

			String local = fields[1];
			int colon = local.lastIndexOf(':');
			if (colon < 0) continue;

			try {
				if (Integer.parseInt(local.substring(colon + 1), 16) == port) {
					inodes.add(fields[9]);
				}
			} catch (NumberFormatException e) {
				// A header, or a row this parser does not know.
			}
		}
		return inodes;
	}

	/**
	 * Reports whether any descriptor in a process's `fd` directory points to one of the socket inodes.
	 *
	 * @param descriptors the process's `fd` directory
	 * @param inodes      the socket inodes to look for
	 * @return whether the process holds one of them
	 */
	private static boolean holdsSocket(Path descriptors, Set<String> inodes) {
		try (DirectoryStream<Path> fds = Files.newDirectoryStream(descriptors)) {
			for (Path fd : fds) {
				String target;
				try {
					target = Files.readSymbolicLink(fd).toString();
				} catch (IOException | SecurityException | UnsupportedOperationException e) {
					continue;
				}
				if (target.startsWith("socket:[") && target.endsWith("]") &&
				    inodes.contains(target.substring(8, target.length() - 1))) {
					return true;
				}
			}
		} catch (IOException | SecurityException e) {
			// The `fd` directory is unreadable for another user's process, or the process has exited.
			// Either way the socket cannot be attributed to the process.
			return false;
		}
		return false;
	}

	/**
	 * Finds the listening pid on macOS and FreeBSD with `lsof`.
	 *
	 * @return the pid, or empty when `lsof` reports no listening pid
	 */
	private static OptionalLong pidFromLsof(int port, Duration timeout) {
		Path lsof = Files.isExecutable(Path.of("/usr/sbin/lsof")) ? Path.of("/usr/sbin/lsof") : Path.of("lsof");
		for (String line : run(List.of(lsof.toString(), "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-t"), timeout)) {
			String text = line.trim();
			if (text.isEmpty()) continue;

			try {
				long pid = Long.parseLong(text);
				if (pid > 0) return OptionalLong.of(pid);
			} catch (NumberFormatException e) {
				// `lsof` prints only pids with `-t`; anything else is a diagnostic line.
			}
		}
		return OptionalLong.empty();
	}

	private static List<String> readLines(Path file) {
		try {
			return Files.readAllLines(file, OperatingSystem.NATIVE_CHARSET);
		} catch (IOException | SecurityException e) {
			return List.of();
		}
	}

	/**
	 * Runs a lookup command, collecting its output.
	 *
	 * @return the output lines, or an empty list when the command: could not run, timed out, or was interrupted
	 */
	private static List<String> run(List<String> command, Duration timeout) {
		try {
			DshCommand.Result result = DshCommand.run(command, timeout, null, Map.of(), null);
			if (result.timedOut()) {
				LOG.info(command.get(0) + " did not answer within " + timeout.toSeconds() + "s");
				return List.of();
			}
			return result.output();
		} catch (IOException e) {
			LOG.info("Could not run " + command.get(0) + " to find the process holding a port: " + e.getMessage());
			return List.of();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return List.of();
		}
	}

	/**
	 * Returns a short description of the process.
	 * <p>
	 * Uses the command line if set, then the name, then the executable path.
	 * Returns `null` when the chosen text is blank.
	 * Text past `{@link #MAX_DESCRIBED}` code points is truncated and an ellipsis is appended.
	 *
	 * @param occupant the process
	 * @return the description, or `null` when no description is available
	 */
	public static @Nullable String describe(@Nullable Occupant occupant) {
		if (occupant == null) return null;

		String text = occupant.commandLine() != null ? occupant.commandLine() : occupant.name();
		if (text == null) text = occupant.executable();
		if (text == null || text.isBlank()) return null;

		String trimmed = text.trim();
		if (trimmed.length() <= MAX_DESCRIBED) return trimmed;

		// Cut on a code point, so that a character outside the basic plane is not cut in half.
		int end = trimmed.offsetByCodePoints(0, MAX_DESCRIBED);
		return trimmed.substring(0, end) + "...";
	}

	/**
	 * The kernel's TCP table query.
	 * <p>
	 * `GetExtendedTcpTable` is stdcall: on 32-bit Windows, a cdecl binding would unbalance the stack.
	 */
	private interface Iphlpapi extends StdCallLibrary {
		@Nullable Iphlpapi INSTANCE =
				NativeUtils.USE_JNA && OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? NativeUtils.load(
						"iphlpapi",
						Iphlpapi.class) : null;

		int GetExtendedTcpTable(Pointer pTcpTable,
		                        IntByReference pdwSize,
		                        boolean bOrder,
		                        int ulAf,
		                        int tableClass,
		                        int reserved);
	}

	/**
	 * The process listening on a port.
	 *
	 * @param pid         the process id
	 * @param name        the executable's name, or `null` when unknown
	 * @param commandLine the command line it was started with, or `null` when unknown
	 * @param executable  the executable path, or `null` when unknown
	 */
	public record Occupant(long pid, @Nullable String name, @Nullable String commandLine,
	                       @Nullable String executable) {}
}
