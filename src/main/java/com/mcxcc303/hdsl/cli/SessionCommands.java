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
package com.mcxcc303.hdsl.cli;

import com.mcxcc303.hdsl.cli.Cli.Invocation;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.InstanceStore;
import com.mcxcc303.hdsl.launch.DshAcpClient;
import com.mcxcc303.hdsl.launch.DshSession;
import com.mcxcc303.hdsl.launch.DshSessions;
import com.mcxcc303.hdsl.pack.DshSessionPacks;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// The commands that move or read sessions: importing an exported pack of them, listing them, and
/// migrating a session from one version's layout to another.  The ACP prompt lives here because it,
/// too, is one conversation with one instance.
@NotNullByDefault
public final class SessionCommands {
	private SessionCommands() {
	}

	/// Runs the `session import` command: another home's sessions, copied into an instance.
	///
	/// The source is read and never written: the point of importing is to take
	/// what an existing installation has without becoming its manager.
	///
	/// @param invocation the parsed invocation
	/// @param out        the stream for normal output
	/// @param err        the stream for error output
	/// @return the process exit code
	static int importSessions(Invocation invocation, PrintStream out, PrintStream err) {
		String id = invocation.option("id");
		DshInstance target = InstanceStore.find(id);
		if (target == null) {
			err.println("error: instance " + id + " does not exist");
			return 1;
		}

		if (RunningInstances.isUp(target, err)) {
			return 1;
		}
		String from = invocation.option("from");
		Path sourceHome =
				from == null || from.isBlank() ? Path.of(System.getProperty("user.home"), ".dsh") : Path.of(from);
		if (!Files.isDirectory(sourceHome)) {
			err.println("error: " + sourceHome + " is not a directory");
			return 1;
		}

		try {
			List<DshSession> sessions = DshSessions.readForeignHome(sourceHome);
			int imported = 0;
			int skipped = 0;
			int refused = 0;
			for (DshSession session : sessions) {
				try {
					DshSessions.importFrom(sourceHome, session, target);
					imported++;
					out.println("  imported " + session.id() + "  " + session.label());
				} catch (DshException e) {
					if (e.getMessage() != null && e.getMessage().contains("already has a session")) {
						skipped++;
					} else {
						refused++;
						out.println("  skipped  " + session.id() + "  " + e.getMessage());
					}
				}
			}
			DshSessions.regroup(target.homeDirectory());
			out.println("Imported " + imported + ", already present " + skipped + ", refused " + refused + " of " +
			            sessions.size() + " session(s) from " + sourceHome + " into " + target.id());
			return 0;
		} catch (DshException e) {
			err.println("error: " + e.getMessage());
			return 1;
		}
	}

	/**
	 * Runs the `session list` command
	 *
	 * @param invocation the parsed invocation
	 * @param out        the stream for normal output
	 * @param err        the stream for error output
	 * @return the process exit code
	 */
	static int listSessions(Invocation invocation, PrintStream out, PrintStream err) {
		String id = invocation.option("id");
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			err.println("error: instance " + id + " does not exist");
			return 1;
		}
		DshTable table = new DshTable("SESSION_ID", "FORMAT_VERSION", "SIZE", "STATUS", "LABEL");
		try {
			List<DshSession> sessions = DshSessions.list(instance.homeDirectory());
			for (DshSession session : sessions) {
//				out.println("  " + session.id() + "  v" + session.formatVersion() + "  " +
//				            DshSessions.formatSize(session.sizeBytes()) + (session.locked() ? "  [locked]" : "") +
//				            "  " + session.label());
				table.row(session.id(),
				          "v" + session.formatVersion(),
				          DshSessions.formatSize(session.sizeBytes()),
				          session.locked() ? "locked" : "-",
				          session.label());
			}
			table.print(out);
			out.println("\n" + sessions.size() + " session(s) in " + instance.id());
			return 0;
		} catch (DshException e) {
			err.println("error: " + e.getMessage());
			return 1;
		}
	}

	/// Runs the `session migrate` command: one session, moved to another instance.
	///
	/// @param invocation the parsed invocation
	/// @param out        the stream for normal output
	/// @param err        the stream for error output
	/// @return the process exit code
	static int migrateSession(Invocation invocation, PrintStream out, PrintStream err) {
		DshInstance source = InstanceStore.find(invocation.option("id"));
		DshInstance target = InstanceStore.find(invocation.option("to"));
		if (source == null || target == null) {
			err.println("error: the source or target instance does not exist");
			return 1;
		}
		// Both ends: the session is read out of the source's home and written into the target's, and
		// either of them running is a home being changed under a live harness.
		if (RunningInstances.isUp(source, err) || RunningInstances.isUp(target, err)) {
			return 1;
		}
		String sessionId = invocation.option("session");

		try {
			DshSession session = DshSessions.list(source.homeDirectory())
			                                .stream()
			                                .filter(candidate -> candidate.id().equals(sessionId))
			                                .findFirst()
			                                .orElse(null);
			if (session == null) {
				err.println("error: " + sessionId + " is not a session of " + source.id());
				return 1;
			}
			DshSessions.migrate(source, session, target, true);
			DshSessions.regroup(target.homeDirectory());
			out.println("Migrated " + sessionId + " from " + source.id() + " to " + target.id());
			return 0;
		} catch (DshException e) {
			err.println("error: " + e.getMessage());
			return 1;
		}
	}

	/// Runs the `session acp-prompt` command: one prompt over the Agent Client Protocol, and the
	/// reply it produces.
	///
	/// The protocol reserves stdout for JSON-RPC frames, so this is the only way
	/// to exercise the session path without a display.
	///
	/// @param invocation the parsed invocation
	/// @param out        the stream for normal output
	/// @param err        the stream for error output
	/// @return the process exit code
	static int acpPrompt(Invocation invocation, PrintStream out, PrintStream err) {
		String id = invocation.option("id");
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			err.println("error: instance " + id + " does not exist");
			return 1;
		}

		// The prompt is answered by a harness of its own, started on this instance's home — which is
		// the second process the launcher keeps an instance from having.
		if (RunningInstances.isUp(instance, err)) {
			return 1;
		}

		DshAcpClient.Listener listener = new DshAcpClient.Listener() {
			@Override
			public void onText(String text) {
				out.print(text);
				out.flush();
			}

			@Override
			public void onToolCall(String title, String status) {
				out.println();
				out.println("[tool] " + title + " (" + status + ")");
			}

			@Override
			public void onPromptFinished(String stopReason) {
				out.println();
				out.println("[stop] " + stopReason);
			}

			@Override
			public void onFailure(String message) {
				err.println("[acp] " + message);
			}
		};

		try (DshAcpClient client = DshAcpClient.connect(instance, listener)) {
			String sessionId = client.newSession();
			out.println("[session] " + sessionId);
			client.prompt(sessionId, invocation.option("text"));
			return 0;
		} catch (DshException e) {
			err.println("error: " + e.getMessage());
			return 1;
		}
	}

	/// Runs the `session export` command.
	static int exportSessions(Invocation invocation, PrintStream out, PrintStream err) throws DshException {
		String id = invocation.option("id");
		DshInstance instance = InstanceStore.find(id);
		if (instance == null) {
			err.println("error: instance " + id + " does not exist");
			return 1;
		}
		Path target = Path.of(invocation.option("file"));
		List<DshSession> sessions = DshSessions.list(instance.homeDirectory());
		out.println("Writing " + sessions.size() + " session(s) to " + target + " ...");
		DshSessionPacks.ExportResult exported = DshSessionPacks.export(instance, sessions, target, out::println);
		out.println("Wrote " + exported.sessions() + " session(s), " + exported.attachments() + " attachment(s), " +
		            exported.bytes() + " byte(s)");
		return 0;
	}
}
