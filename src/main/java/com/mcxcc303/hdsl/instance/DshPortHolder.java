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

import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Finds, and if the user asks, stops the process holding an instance's port.
///
/// DeepSeek Harness exits with status 1 when its port is taken instead of probing for another one,
/// so a launch whose port is held by something else cannot continue — and the thing holding it is
/// most often a harness that outlived the launcher which started it. The launcher therefore names
/// the process and offers to stop it, rather than only reporting the number and leaving the person
/// to find the program themselves.
///
/// Nothing here decides anything on its own: `find` and `kill` are called by the interface, which
/// asks first. The rules that keep that offer safe live in this class, so no caller can get them
/// wrong — the launcher's own process and its ancestors are never touched, and a pid that no longer
/// describes the process that was shown is not stopped either.
///
/// All of this blocks: the caller must run it off the JavaFX thread.
@NotNullByDefault
public final class DshPortHolder {
	/// How long a lookup command (`netstat`, `lsof`) is given to answer.
	///
	/// `lsof` is the one that can hang — a mounted filesystem that stopped answering holds its
	/// scan — so the answer is bounded rather than waited for indefinitely.
	private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);

	/// How long a stopped process is given to exit before it is killed outright.
	private static final Duration KILL_GRACE = Duration.ofSeconds(5);

	/// How long a forcibly killed process is given to disappear.
	private static final Duration FORCE_GRACE = Duration.ofSeconds(2);

	/// How far apart the recorded and the observed start time of a process may be and still be the
	/// same process. A pid is reused by the system, a start time is not.
	private static final Duration START_TIME_TOLERANCE = Duration.ofSeconds(2);

	/// How often the port is re-tested while waiting for a stopped process to let go of it.
	private static final long FREE_POLL_MILLIS = 100;

	private DshPortHolder() {
	}

	/// The process holding a port.
	///
	/// @param pid         the process id
	/// @param commandLine the command line it was started with, or `null` when the platform will
	///                    not say
	/// @param startedAt   when it began, or `null` when the platform will not say
	public record Holder(long pid, @Nullable String commandLine, @Nullable Instant startedAt) {
		/// Returns the executable's name, for a dialog that has one line to name the process in.
		///
		/// @return the name, or the empty string when no command line is known
		public String displayName() {
			if (commandLine == null) {
				return "";
			}
			String text = commandLine.trim();
			if (text.isEmpty()) {
				return "";
			}
			// Windows quotes an executable path that contains spaces, and splitting on whitespace
			// first would turn `"C:\Program Files\nodejs\node.exe"` into `Program`.
			String first;
			if (text.charAt(0) == '"') {
				int end = text.indexOf('"', 1);
				first = end > 0 ? text.substring(1, end) : text.substring(1);
			} else {
				first = text.split("\\s+", 2)[0];
			}
			int slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
			String name = slash >= 0 ? first.substring(slash + 1) : first;
			return name.isBlank() ? "" : name;
		}
	}

	/// What became of an attempt to stop a process.
	public enum KillResult {
		/// The process exited after it was asked to.
		KILLED,
		/// There was nothing to stop: the process was already gone, or the pid now names another.
		NOT_RUNNING,
		/// The process asked to be stopped is the launcher itself.
		REFUSED_SELF,
		/// The process asked to be stopped is one the launcher runs inside.
		REFUSED_ANCESTOR,
		/// The process could not be stopped, usually because it belongs to another user.
		FAILED
	}

	/// Finds the process listening on a port.
	///
	/// @param port the port
	/// @return the process, or empty when nothing is listening or the platform will not say
	public static Optional<Holder> find(int port) {
		if (port <= 0 || port > 65535) {
			return Optional.empty();
		}
		return switch (OperatingSystem.CURRENT_OS) {
			case WINDOWS -> findOnWindows(port);
			case LINUX -> findOnLinux(port);
			case MACOS, FREEBSD -> findWithLsof(port);
			default -> Optional.empty();
		};
	}

	/// Stops the process, and everything it started.
	///
	/// @param holder the process, as [find] reported it
	/// @return what became of the attempt
	public static KillResult kill(Holder holder) {
		long pid = holder.pid();
		if (pid <= 0) {
			return KillResult.FAILED;
		}

		ProcessHandle current = ProcessHandle.current();
		if (pid == current.pid()) {
			return KillResult.REFUSED_SELF;
		}
		// The launcher's own ancestors are what it runs inside — a terminal, an IDE, the desktop —
		// and stopping one of them would take the launcher down with it.
		for (ProcessHandle ancestor = current.parent().orElse(null);
		     ancestor != null;
		     ancestor = ancestor.parent().orElse(null)) {
			if (ancestor.pid() == pid) {
				return KillResult.REFUSED_ANCESTOR;
			}
		}

		ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
		if (handle == null || !handle.isAlive()) {
			return KillResult.NOT_RUNNING;
		}
		// The pid shown in the dialog may have been reused since it was read. A start time is not
		// reused, so a process that began elsewhere is not the one the person was asked about.
		if (holder.startedAt() != null) {
			Instant began = handle.info().startInstant().orElse(null);
			if (began != null &&
			    Duration.between(began, holder.startedAt()).abs().compareTo(START_TIME_TOLERANCE) > 0) {
				return KillResult.NOT_RUNNING;
			}
		}

		try {
			List<ProcessHandle> descendants = handle.descendants().toList();
			descendants.forEach(ProcessHandle::destroy);
			handle.destroy();
			if (waitForExit(handle, KILL_GRACE)) {
				return KillResult.KILLED;
			}
			descendants.forEach(ProcessHandle::destroyForcibly);
			handle.destroyForcibly();
			return waitForExit(handle, FORCE_GRACE) ? KillResult.KILLED : KillResult.FAILED;
		} catch (RuntimeException e) {
			LOG.warning("Could not stop the process holding a port (pid " + pid + ")", e);
			return KillResult.FAILED;
		}
	}

	/// Waits until a port can be bound again.
	///
	/// @param port    the port
	/// @param timeout how long to wait
	/// @return whether the port became free
	public static boolean awaitFree(int port, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!DshPorts.isFree(port)) {
			if (System.nanoTime() > deadline) {
				return false;
			}
			try {
				Thread.sleep(FREE_POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return true;
	}

	/// Finds the process listening on a port on Windows.
	///
	/// `netstat -ano` lists both address families in one answer, and the last column is the pid. The
	/// state column is not read: Windows translates it, so a German machine writes `ABHÖREN` where
	/// this one writes `LISTENING`. What a listening socket is, regardless of language, is a local
	/// address ending in the port and a foreign address ending in `:0`.
	private static Optional<Holder> findOnWindows(int port) {
		List<String> lines = Collections.synchronizedList(new ArrayList<>());
		run(List.of("netstat", "-ano"), lines);

		OptionalLong pid = pidFromNetstat(lines, port);
		if (pid.isEmpty()) {
			return Optional.empty();
		}

		Holder holder = holderOf(pid.getAsLong());
		if (holder.commandLine() == null) {
			String image = windowsImageName(pid.getAsLong());
			if (image != null) {
				holder = new Holder(holder.pid(), image, holder.startedAt());
			}
		}
		return Optional.of(holder);
	}

	/// Reads a pid out of `netstat -ano` output.
	///
	/// @param lines the output lines
	/// @param port  the port to find
	/// @return the pid, or empty when no row listens on it
	static OptionalLong pidFromNetstat(List<String> lines, int port) {
		String wanted = Integer.toString(port);
		for (String raw : List.copyOf(lines)) {
			String line = raw.trim();
			if (line.isEmpty()) {
				continue;
			}
			String[] fields = line.split("\\s+");
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
			} catch (NumberFormatException ignored) {
				// A header or a row this parser does not understand: not a listening socket.
			}
		}
		return OptionalLong.empty();
	}

	/// Returns the port out of `address:port`, in either the IPv4 or the bracketed IPv6 spelling.
	private static String portOf(String address) {
		int colon = address.lastIndexOf(':');
		return colon < 0 ? "" : address.substring(colon + 1);
	}

	/// Returns the image name `tasklist` reports for a pid, or `null`.
	private static @Nullable String windowsImageName(long pid) {
		List<String> lines = Collections.synchronizedList(new ArrayList<>());
		run(List.of("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH"), lines);
		for (String raw : List.copyOf(lines)) {
			String line = raw.trim();
			// A CSV row starts with a quoted image name; anything else is a notice, and notices are
			// translated, so they are skipped by shape rather than by their wording.
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

	/// Finds the process listening on a port on Linux.
	///
	/// `/proc/net/tcp` and its IPv6 sibling are world readable and hold, per row, the local port and
	/// the socket's inode. The pid is then found by scanning `/proc/<pid>/fd` for that inode. This
	/// asks nothing of `ss` or `lsof`, which a small distribution may not have installed.
	private static Optional<Holder> findOnLinux(int port) {
		Set<String> inodes = listeningInodes(port);
		if (inodes.isEmpty()) {
			return Optional.empty();
		}
		OptionalLong pid = pidHolding(inodes);
		return pid.isEmpty() ? Optional.empty() : Optional.of(holderOf(pid.getAsLong()));
	}

	/// Returns the socket inodes listening on a port, out of the two `/proc/net` tables.
	private static Set<String> listeningInodes(int port) {
		List<String> tcp = readLines(Path.of("/proc/net/tcp"));
		List<String> tcp6 = readLines(Path.of("/proc/net/tcp6"));
		return inodesFromProc(tcp, tcp6, port);
	}

	/// Reads the listening inodes out of `/proc/net/tcp` and `/proc/net/tcp6` lines.
	///
	/// @param tcp  the `/proc/net/tcp` lines
	/// @param tcp6 the `/proc/net/tcp6` lines
	/// @param port the port to find
	/// @return the inodes of the listening sockets on it
	static Set<String> inodesFromProc(List<String> tcp, List<String> tcp6, int port) {
		Set<String> inodes = new LinkedHashSet<>();
		for (List<String> table : List.of(tcp, tcp6)) {
			for (String raw : table) {
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
				} catch (NumberFormatException ignored) {
					// A header or a row this parser does not understand.
				}
			}
		}
		return inodes;
	}

	/// Returns the pid whose file descriptors include one of the sockets.
	private static OptionalLong pidHolding(Set<String> inodes) {
		try (DirectoryStream<Path> processes = Files.newDirectoryStream(Path.of("/proc"))) {
			for (Path process : processes) {
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
			LOG.info("Could not scan /proc for the process holding a port: " + e.getMessage());
		}
		return OptionalLong.empty();
	}

	/// Reports whether any descriptor in a process's `fd` directory is one of the sockets.
	private static boolean holdsSocket(Path descriptors, Set<String> inodes) {
		try (DirectoryStream<Path> fds = Files.newDirectoryStream(descriptors)) {
			for (Path fd : fds) {
				String target;
				try {
					target = Files.readSymbolicLink(fd).toString();
				} catch (IOException | SecurityException | UnsupportedOperationException e) {
					continue;
				}
				if (!target.startsWith("socket:[") || !target.endsWith("]")) {
					continue;
				}
				if (inodes.contains(target.substring("socket:[".length(), target.length() - 1))) {
					return true;
				}
			}
		} catch (IOException | SecurityException e) {
			// A process this user may not look into is a process this user cannot name.
			return false;
		}
		return false;
	}

	/// Finds the process listening on a port on macOS and FreeBSD, with `lsof`.
	private static Optional<Holder> findWithLsof(int port) {
		Path lsof = Files.isExecutable(Path.of("/usr/sbin/lsof")) ? Path.of("/usr/sbin/lsof") : Path.of("lsof");
		List<String> lines = Collections.synchronizedList(new ArrayList<>());
		run(List.of(lsof.toString(), "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-t"), lines);

		List<Long> pids = pidsFromLsof(lines);
		return pids.isEmpty() ? Optional.empty() : Optional.of(holderOf(pids.get(0)));
	}

	/// Reads the pids out of `lsof -t` output.
	///
	/// @param lines the output lines, one pid per line
	/// @return the pids, in the order they were printed
	static List<Long> pidsFromLsof(List<String> lines) {
		List<Long> pids = new ArrayList<>();
		for (String raw : List.copyOf(lines)) {
			String line = raw.trim();
			if (line.isEmpty()) {
				continue;
			}
			try {
				long pid = Long.parseLong(line);
				if (pid > 0) {
					pids.add(pid);
				}
			} catch (NumberFormatException ignored) {
				// `lsof` prints only pids with `-t`; anything else is a diagnostic line.
			}
		}
		return pids;
	}

	/// Describes a pid, as far as the platform allows.
	private static Holder holderOf(long pid) {
		ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
		String commandLine = null;
		Instant startedAt = null;
		if (handle != null) {
			commandLine = handle.info().commandLine().or(() -> handle.info().command()).orElse(null);
			startedAt = handle.info().startInstant().orElse(null);
		}
		if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX) {
			String fromProc = linuxCommandLine(pid);
			if (fromProc != null) {
				commandLine = fromProc;
			}
		}
		return new Holder(pid, commandLine, startedAt);
	}

	/// Returns a process's command line from `/proc/<pid>/cmdline`, or `null`.
	private static @Nullable String linuxCommandLine(long pid) {
		try {
			byte[] bytes = Files.readAllBytes(Path.of("/proc", Long.toString(pid), "cmdline"));
			String text = new String(bytes, OperatingSystem.NATIVE_CHARSET).replace('\0', ' ').trim();
			return text.isEmpty() ? null : text;
		} catch (IOException | SecurityException e) {
			return null;
		}
	}

	/// Reads a text file, returning no lines when it does not exist or cannot be read.
	private static List<String> readLines(Path file) {
		try {
			return Files.readAllLines(file);
		} catch (IOException | SecurityException e) {
			return List.of();
		}
	}

	/// Runs a lookup command with a bound, collecting its output.
	///
	/// @param command the program and its arguments
	/// @param lines   the list the output lines are added to
	/// @return the exit code, or `-1` when the command did not finish
	private static int run(List<String> command, List<String> lines) {
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(true);

		ManagedProcess process;
		try {
			process = new ManagedProcess(builder);
		} catch (IOException e) {
			LOG.info("Could not run " + command.get(0) + " to find the process holding a port: " + e.getMessage());
			return -1;
		}

		process.pumpInputStream(line -> {
			synchronized (lines) {
				lines.add(line);
			}
		});
		Process raw = process.getProcess();
		try {
			if (!raw.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				raw.destroyForcibly();
				raw.waitFor(1, TimeUnit.SECONDS);
				LOG.info(command.get(0) + " did not answer within " + COMMAND_TIMEOUT.toSeconds() + "s");
				return -1;
			}
			process.awaitRelatedThreads(Duration.ofSeconds(1));
			return raw.isAlive() ? -1 : raw.exitValue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return -1;
		} finally {
			process.destroyRelatedThreads();
		}
	}

	/// Waits for a process to disappear.
	private static boolean waitForExit(ProcessHandle handle, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (handle.isAlive()) {
			if (System.nanoTime() > deadline) {
				return false;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return true;
	}
}
