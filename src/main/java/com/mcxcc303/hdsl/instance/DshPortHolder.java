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
import java.util.Locale;
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

	/// How long the command-line fallback is given. A shell that has to start first takes longer
	/// than a native tool, and it is only asked when the platform would not answer directly.
	private static final Duration FALLBACK_TIMEOUT = Duration.ofSeconds(4);

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

	/// What kind of program was found holding the port.
	public enum Origin {
		/// This instance's own DeepSeek Harness, left running by an earlier launch.
		THIS_INSTANCE,
		/// A known instance's DeepSeek Harness, left running by an earlier launch.
		OTHER_INSTANCE,
		/// A DeepSeek Harness this launcher does not manage — started by hand, or by another tool.
		DSH_UNMANAGED,
		/// A program that is not DeepSeek Harness.
		NOT_DSH,
		/// Nothing was learned, usually because the platform will not print the command line.
		UNKNOWN
	}

	/// What an occupied port was found to belong to.
	///
	/// @param origin     the kind of program
	/// @param instanceId the instance it belongs to, for the two instance kinds
	public record Identification(Origin origin, @Nullable String instanceId) {
	}

	/// Identifies the program holding a port against the instances this launcher knows.
	///
	/// @param commandLine the command line [find] reported, or `null`
	/// @param target      the instance about to be launched
	/// @return the identification, never `null`
	public static Identification identify(@Nullable String commandLine, DshInstance target) {
		if (commandLine == null || commandLine.isBlank()) {
			return new Identification(Origin.UNKNOWN, null);
		}
		try {
			return identify(commandLine, target, InstanceListing.list());
		} catch (RuntimeException e) {
			// The listing reads manifests from disk, and a broken one is not a reason to lose the
			// prompt: the target itself is still enough to recognise a leftover of its own.
			LOG.warning("Could not list the instances while identifying a port holder", e);
			return identify(commandLine, target, List.of());
		}
	}

	/// Identifies the program holding a port against a known set of instances.
	///
	/// The command line is the evidence, in order of strength: the target's own entry point means
	/// this instance left a harness behind; another instance's entry means that one did; the
	/// package name alone means a harness this launcher does not manage. A command line that names
	/// none of them is a different program.
	///
	/// @param commandLine the command line [find] reported, or `null`
	/// @param target      the instance about to be launched
	/// @param known       every instance the caller considered
	/// @return the identification, never `null`
	static Identification identify(@Nullable String commandLine,
	                               DshInstance target,
	                               List<DshInstance> known) {
		if (commandLine == null || commandLine.isBlank()) {
			return new Identification(Origin.UNKNOWN, null);
		}
		if (mentionsInstance(commandLine, target)) {
			return new Identification(Origin.THIS_INSTANCE, target.id());
		}
		for (DshInstance instance : known) {
			if (instance.id().equals(target.id())) {
				continue;
			}
			if (mentionsInstance(commandLine, instance)) {
				return new Identification(Origin.OTHER_INSTANCE, instance.id());
			}
		}
		return looksLikeDsh(commandLine)
		       ? new Identification(Origin.DSH_UNMANAGED, null)
		       : new Identification(hasArguments(commandLine) ? Origin.NOT_DSH : Origin.UNKNOWN, null);
	}

	/// Reports whether a command line carries arguments after the program.
	///
	/// A platform that will not print a command line sometimes still prints the executable, and an
	/// executable alone says nothing about what was run: `node` is every Node.js program. Such a
	/// line is not evidence of "not DeepSeek Harness", so it is reported as unknown instead.
	///
	/// @param commandLine the command line
	/// @return whether anything follows the program
	private static boolean hasArguments(String commandLine) {
		String text = commandLine.trim();
		if (text.isEmpty()) {
			return false;
		}
		int afterProgram;
		if (text.charAt(0) == '"') {
			int end = text.indexOf('"', 1);
			if (end < 0) {
				return false;
			}
			afterProgram = end + 1;
		} else {
			int space = text.indexOf(' ');
			afterProgram = space < 0 ? text.length() : space;
		}
		return !text.substring(afterProgram).trim().isEmpty();
	}

	/// Reports whether a command line names a file or directory of an instance.
	///
	/// @param commandLine the command line
	/// @param instance    the instance
	/// @return whether it appears
	static boolean mentionsInstance(String commandLine, DshInstance instance) {
		try {
			return mentionsPath(commandLine, instance.dshEntryPoint())
			       || mentionsPath(commandLine, instance.dshDirectory());
		} catch (DshException | RuntimeException e) {
			return false;
		}
	}

	/// Reports whether a command line names a path, as a whole path.
	///
	/// Separators are normalised and, on Windows, so is case: the same directory is spelled with
	/// backslashes in one place and forward slashes in another, and a command line quotes a path
	/// that contains spaces rather than passing it bare. A match has to end at a path boundary, so
	/// that an instance directory does not match the longer name of another one starting the same.
	///
	/// @param commandLine the command line, or `null`
	/// @param path        the path to look for
	/// @return whether it appears
	static boolean mentionsPath(@Nullable String commandLine, Path path) {
		if (commandLine == null || commandLine.isBlank()) {
			return false;
		}
		String haystack = normalizePath(commandLine);
		String needle = normalizePath(path.toAbsolutePath().normalize().toString());
		if (needle.isEmpty()) {
			return false;
		}
		int from = 0;
		while (true) {
			int at = haystack.indexOf(needle, from);
			if (at < 0) {
				return false;
			}
			int end = at + needle.length();
			boolean startsAPath = at == 0 || !isPathCharacter(haystack.charAt(at - 1));
			boolean endsAPath = end == haystack.length()
			                    || haystack.charAt(end) == '/'
			                    || !isPathCharacter(haystack.charAt(end));
			if (startsAPath && endsAPath) {
				return true;
			}
			from = end;
		}
	}

	/// The npm package DeepSeek Harness ships as. A command line that runs a file inside it is
	/// DeepSeek Harness; the process name is not a signal, because `node` runs everything.
	static final String DSH_PACKAGE = "@deepseek-ai/dsh";

	/// Reports whether a command line runs DeepSeek Harness.
	///
	/// The launcher itself starts `node <package>/lib/bin.js`, and a harness started by hand or by
	/// another tool is the same package run the same way — so the package name in the command line
	/// is the signal. The process name is not: `node` runs everything, and a path that merely
	/// contains the word `dsh` is not this package.
	///
	/// @param commandLine the command line, or `null`
	/// @return whether it looks like DeepSeek Harness
	static boolean looksLikeDsh(@Nullable String commandLine) {
		return commandLine != null && normalizePath(commandLine).contains(DSH_PACKAGE);
	}

	/// Returns the profile a DeepSeek Harness command line was told to boot.
	///
	/// The launch command states it with `--profile <name>`. A harness started by hand usually does
	/// not, in which case there is nothing to report.
	///
	/// @param commandLine the command line, or `null`
	/// @return the profile, or `null` when none was named
	public static @Nullable String profileOf(@Nullable String commandLine) {
		if (commandLine == null) {
			return null;
		}
		String[] tokens = commandLine.split("\\s+");
		for (int i = 0; i + 1 < tokens.length; i++) {
			if ("--profile".equals(tokens[i])) {
				String value = tokens[i + 1].replace("\"", "").trim();
				return value.isEmpty() ? null : value;
			}
		}
		return null;
	}

	/// Normalises a command line or a path for comparison.
	private static String normalizePath(String text) {
		String normalized = text.replace('\\', '/');
		return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
		       ? normalized.toLowerCase(Locale.ROOT)
		       : normalized;
	}

	/// Reports whether a character belongs to a path, so a match can be bounded by one.
	private static boolean isPathCharacter(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '~';
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
		String executable = null;
		Instant startedAt = null;
		if (handle != null) {
			commandLine = handle.info().commandLine().orElse(null);
			executable = handle.info().command().orElse(null);
			startedAt = handle.info().startInstant().orElse(null);
		}
		if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX) {
			String fromProc = linuxCommandLine(pid);
			if (fromProc != null) {
				commandLine = fromProc;
			}
		}
		if (commandLine == null) {
			commandLine = fallbackCommandLine(pid);
		}
		// An executable alone is still worth showing, but it says nothing about the arguments, and
		// the identification reads a line without arguments as "unknown" rather than "not DSH".
		if (commandLine == null) {
			commandLine = executable;
		}
		return new Holder(pid, commandLine, startedAt);
	}

	/// Asks the platform for a process's command line when it would not hand one over directly.
	///
	/// `ProcessHandle` reports nothing for a process this user may not inspect, which is exactly
	/// the leftover harness this is for. Windows and macOS both have a command that still answers
	/// for a process of this user — the handler that started it is long gone, so the alternative is
	/// to report the process as unidentified.
	///
	/// @param pid the process
	/// @return the command line, or `null` when no command answers
	private static @Nullable String fallbackCommandLine(long pid) {
		List<String> lines = Collections.synchronizedList(new ArrayList<>());
		switch (OperatingSystem.CURRENT_OS) {
			case WINDOWS -> run(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
			                            "(Get-CimInstance Win32_Process -Filter \"ProcessId=" + pid +
			                            "\").CommandLine"), lines, FALLBACK_TIMEOUT);
			case MACOS, FREEBSD -> run(List.of("ps", "-p", Long.toString(pid), "-o", "command="),
			                           lines, FALLBACK_TIMEOUT);
			default -> {
				return null;
			}
		}
		for (String raw : List.copyOf(lines)) {
			String text = raw.trim();
			if (!text.isEmpty()) {
				return text;
			}
		}
		return null;
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
		return run(command, lines, COMMAND_TIMEOUT);
	}

	/// Runs a lookup command with a bound, collecting its output.
	///
	/// @param command the program and its arguments
	/// @param lines   the list the output lines are added to
	/// @param timeout how long the command is given
	/// @return the exit code, or `-1` when the command did not finish
	private static int run(List<String> command, List<String> lines, Duration timeout) {
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
			if (!raw.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				raw.destroyForcibly();
				raw.waitFor(1, TimeUnit.SECONDS);
				LOG.info(command.get(0) + " did not answer within " + timeout.toSeconds() + "s");
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
