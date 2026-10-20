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
 * Names the process holding a port.
 * <p>
 * The process is named as the platform found it; whether it may be stopped is not decided here.
 * <p>
 * Blocks: it reads the process table or runs a lookup command, so the caller must keep it off
 * the interface thread.
 */
@NotNullByDefault
public final class PortOccupant {
	/**
	 * How long a lookup command is given to answer. `lsof` is the one that can stop answering on a
	 * filesystem that has stopped.
	 */
	private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(2);

	/**
	 * How much of a command line is shown before it is shortened.
	 */
	private static final int MAX_DESCRIBED = 120;

	private PortOccupant() {
	}

	/**
	 * The process holding a port.
	 *
	 * @param pid         the process id
	 * @param name        the executable's name, or `null` when the platform would not say
	 * @param commandLine the command line it was started with, or `null` when the platform
	 *                    would not say
	 * @param executable  the program alone, or `null` when the platform would not say
	 */
	public record Occupant(long pid, @Nullable String name, @Nullable String commandLine,
	                       @Nullable String executable) {}

	/**
	 * Finds the process listening on a port.
	 * <p>
	 * When more than one socket shares the port, only the first the platform lists is named.
	 *
	 * @param port the port
	 * @return the process, or empty when nothing is listening or the platform would not say
	 */
	public static Optional<Occupant> find(int port) {
		return find(port, LOOKUP_TIMEOUT);
	}

	/**
	 * Finds the process listening on a port, within a bound.
	 *
	 * @param port    the port
	 * @param timeout how long the lookup is given
	 * @return the process, or empty when nothing is listening or the platform would not say
	 */
	static Optional<Occupant> find(int port, Duration timeout) {
		if (port <= 0 || port > 65535) {
			return Optional.empty();
		}
		OptionalLong pid = switch (OperatingSystem.CURRENT_OS) {
			case WINDOWS -> pidFromNetstat(port, timeout);
			case LINUX -> pidFromProc(port, timeout);
			case MACOS, FREEBSD -> pidFromLsof(port, timeout);
			default -> OptionalLong.empty();
		};
		return pid.isEmpty() ? Optional.empty() : Optional.of(occupantOf(pid.getAsLong()));
	}

	/**
	 * Describes a pid as far as the platform allows.
	 *
	 * @param pid the process
	 * @return what is known about it
	 */
	private static Occupant occupantOf(long pid) {
		ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
		String commandLine = null;
		String executable = null;
		if (handle != null) {
			commandLine = handle.info().commandLine().orElse(null);
			executable = handle.info().command().orElse(null);
		}
		// `ProcessHandle` reports nothing for a process of another user, and these do.
		if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX) {
			String fromProc = linuxCommandLine(pid);
			if (fromProc != null) {
				commandLine = fromProc;
			}
		}
		String name = nameOf(commandLine);
		if (name == null) {
			name = nameOf(executable);
		}
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
		if (commandLine == null) {
			return null;
		}
		String text = commandLine.trim();
		if (text.isEmpty()) {
			return null;
		}
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
	 * @return the image name, or `null` when no row names one
	 */
	static @Nullable String imageNameFromTasklist(List<String> lines) {
		for (String raw : lines) {
			String line = raw.trim();
			if (!line.startsWith("\"")) {
				continue;
			}
			int end = line.indexOf('"', 1);
			if (end > 1) {
				return line.substring(1, end);
			}
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
	 * Finds the listening pid on Windows with `netstat -ano`.
	 *
	 * @param port    the port
	 * @param timeout how long the command is given
	 * @return the pid, or empty when no row listens on it
	 */
	private static OptionalLong pidFromNetstat(int port, Duration timeout) {
		return pidListeningOn(run(List.of("netstat", "-ano"), timeout), port);
	}

	/**
	 * Reads the listening pid out of `netstat -ano` output.
	 *
	 * @param lines the output lines
	 * @param port  the port to find
	 * @return the pid, or empty when no row listens on it
	 */
	static OptionalLong pidListeningOn(List<String> lines, int port) {
		String wanted = Integer.toString(port);
		for (String raw : lines) {
			String line = raw.trim();
			if (line.isEmpty()) {
				continue;
			}
			String[] fields = line.split("\\s+");
			// The state column is translated by Windows, so it is not read.
			// A listening row is the one whose protocol is `TCP` and whose foreign port is `0`.
			if (fields.length < 5 || !"TCP".equalsIgnoreCase(fields[0])) {
				continue;
			}
			if (!portOf(fields[1]).equals(wanted) || !fields[2].endsWith(":0")) {
				continue;
			}
			try {
				long pid = Long.parseLong(fields[fields.length - 1]);
				if (pid > 0) {
					return OptionalLong.of(pid);
				}
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
	 * `/proc/net/tcp` and its IPv6 sibling hold, per row, the local port and the socket's inode;
	 * the pid is then the one whose descriptors include that inode.
	 *
	 * @param port    the port
	 * @param timeout how long the scan is given
	 * @return the pid, or empty when no row listens on it
	 */
	private static OptionalLong pidFromProc(int port, Duration timeout) {
		Set<String> inodes = listeningInodes(port);
		if (inodes.isEmpty()) {
			return OptionalLong.empty();
		}
		long deadline = System.nanoTime() + timeout.toNanos();
		try (DirectoryStream<Path> processes = Files.newDirectoryStream(Path.of("/proc"))) {
			for (Path process : processes) {
				if (System.nanoTime() > deadline) {
					LOG.info("Gave up scanning /proc for the process holding port " + port);
					return OptionalLong.empty();
				}
				String name = process.getFileName().toString();
				if (name.isEmpty() || !name.chars().allMatch(Character::isDigit)) {
					continue;
				}
				long pid;
				try {
					pid = Long.parseLong(name);
				} catch (NumberFormatException e) {
					continue;
				}
				if (holdsSocket(process.resolve("fd"), inodes)) {
					return OptionalLong.of(pid);
				}
			}
		} catch (IOException | SecurityException e) {
			LOG.info("Could not scan /proc for the process holding port " + port + ": " + e.getMessage());
		}
		return OptionalLong.empty();
	}

	/**
	 * Returns the socket inodes listening on a port, out of the two `/proc/net` tables.
	 *
	 * @param port the port
	 * @return the inodes of the listening sockets on it
	 */
	private static Set<String> listeningInodes(int port) {
		Set<String> inodes = new LinkedHashSet<>();
		for (String inodesOf : inodesFromProc(readLines(Path.of("/proc/net/tcp")), port)) {
			inodes.add(inodesOf);
		}
		// The IPv6 table is absent on a kernel built without IPv6.
		for (String inodesOf : inodesFromProc(readLines(Path.of("/proc/net/tcp6")), port)) {
			inodes.add(inodesOf);
		}
		return inodes;
	}

	/**
	 * Returns the listening inodes out of the lines of one `/proc/net` table.
	 *
	 * @param lines the table's lines
	 * @param port  the port to find
	 * @return the inodes of the listening sockets on it
	 */
	static Set<String> inodesFromProc(List<String> lines, int port) {
		Set<String> inodes = new LinkedHashSet<>();
		for (String raw : lines) {
			String[] fields = raw.trim().split("\\s+");
			// sl, local_address, rem_address, st, tx_queue:rx_queue, tr:tm->when, retrnsmt,
			// uid, timeout, inode, ...
			if (fields.length < 10 || !"0A".equalsIgnoreCase(fields[3])) {
				continue;
			}
			String local = fields[1];
			int colon = local.lastIndexOf(':');
			if (colon < 0) {
				continue;
			}
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
	 * Reports whether any descriptor in a process's `fd` directory is one of the sockets.
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
			// The descriptors of a process this user may not look into are not readable, so the
			// socket it holds cannot be attributed to it.
			return false;
		}
		return false;
	}

	/**
	 * Finds the listening pid on macOS and FreeBSD with `lsof`.
	 *
	 * @param port    the port
	 * @param timeout how long the command is given
	 * @return the pid, or empty when no row listens on it
	 */
	private static OptionalLong pidFromLsof(int port, Duration timeout) {
		Path lsof = Files.isExecutable(Path.of("/usr/sbin/lsof")) ? Path.of("/usr/sbin/lsof") : Path.of("lsof");
		for (String line : run(List.of(lsof.toString(), "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-t"), timeout)) {
			String text = line.trim();
			if (text.isEmpty()) {
				continue;
			}
			try {
				long pid = Long.parseLong(text);
				if (pid > 0) {
					return OptionalLong.of(pid);
				}
			} catch (NumberFormatException e) {
				// `lsof` prints only pids with `-t`; anything else is a diagnostic line.
			}
		}
		return OptionalLong.empty();
	}

	/**
	 * Reads a text file, returning no lines when it does not exist or cannot be read.
	 *
	 * @param file the file
	 * @return its lines
	 */
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
	 * @param command the program and its arguments
	 * @param timeout how long the command is given
	 * @return the output lines, or none when the command did not answer
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
	 * A short form of the process, for a message that has a line to itself.
	 * <p>
	 * The command line is preferred over the name and the name over the program alone, and
	 * anything past [#MAX_DESCRIBED] is replaced by an ellipsis.
	 *
	 * @param occupant the process
	 * @return the text to show, or `null` when the platform named nothing
	 */
	public static @Nullable String describe(@Nullable Occupant occupant) {
		if (occupant == null) {
			return null;
		}
		String text = occupant.commandLine() != null ? occupant.commandLine() : occupant.name();
		if (text == null) {
			text = occupant.executable();
		}
		if (text == null || text.isBlank()) {
			return null;
		}
		String trimmed = text.trim();
		if (trimmed.length() <= MAX_DESCRIBED) {
			return trimmed;
		}
		// Cut on a code point, so that a character outside the basic plane is not cut in half.
		int end = trimmed.offsetByCodePoints(0, MAX_DESCRIBED);
		return trimmed.substring(0, end) + "...";
	}
}
