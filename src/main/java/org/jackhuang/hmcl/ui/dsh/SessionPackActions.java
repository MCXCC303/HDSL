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
package org.jackhuang.hmcl.ui.dsh;

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.launch.DshSession;
import com.mcxcc303.hdsl.launch.DshSessions;
import com.mcxcc303.hdsl.pack.DshSessionPacks;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * File-level session pack operations shared between session pages.
 */
@NotNullByDefault
final class SessionPackActions {
	private SessionPackActions() {
	}

	/**
	 * Writes a set of sessions into a chosen pack file.
	 *
	 * @param instance the owning instance
	 * @param sessions the sessions to write
	 */
	static void export(DshInstance instance, List<DshSession> sessions) {
		if (sessions.isEmpty()) {
			Controllers.dialog(i18n("dsh.session.pack.export.empty"),
			                   i18n("dsh.session.pack.export"),
			                   MessageType.ERROR);
			return;
		}

		FileChooser chooser = new FileChooser();
		chooser.setTitle(i18n("dsh.session.pack.export"));
		chooser.getExtensionFilters()
		       .add(new FileChooser.ExtensionFilter(i18n("dsh.session.pack.filter"),
		                                            "*" + DshSessionPacks.FILE_EXTENSION));
		chooser.setInitialFileName(
				"hdsl-sessions-" + instance.id() + "-" + LocalDate.now() + DshSessionPacks.FILE_EXTENSION);
		File chosen = chooser.showSaveDialog(Controllers.getStage());
		if (chosen == null) {
			return;
		}

		Path target = chosen.toPath();
		ProgressDialog.run(i18n("dsh.session.pack.export"),
		                   progress -> DshSessionPacks.export(instance, sessions, target, progress::accept),
		                   null);
	}

	/**
	 * Imports sessions from a chosen pack file.
	 *
	 * @param instance the instance to import into
	 * @param onDone   callback run after a successful import
	 */
	static void importPack(DshInstance instance, Runnable onDone) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle(i18n("dsh.session.pack.import"));
		chooser.getExtensionFilters()
		       .add(new FileChooser.ExtensionFilter(i18n("dsh.session.pack.filter"),
		                                            DshSessionPacks.ACCEPTED_EXTENSIONS.stream()
		                                                                               .map(extension -> "*" +
		                                                                                                 extension)
		                                                                               .toList()));
		File chosen = chooser.showOpenDialog(Controllers.getStage());
		if (chosen == null) {
			return;
		}

		Path pack = chosen.toPath();
		ProgressDialog.run(i18n("dsh.session.pack.import"),
		                   progress -> DshSessionPacks.importFrom(instance.homeDirectory(), pack, progress::accept),
		                   onDone);
	}

	/**
	 * Copies sessions from the system DeepSeek Harness installation.
	 *
	 * @param instance the instance to import into
	 * @param onDone   callback run after the import finishes
	 */
	static void importFromSystem(DshInstance instance, Runnable onDone) {
		Path source = Path.of(System.getProperty("user.home"), ".dsh");
		if (!Files.isDirectory(source)) {
			Controllers.dialog(i18n("dsh.session.import.missing", source.toString()),
			                   i18n("dsh.session.import"),
			                   MessageType.ERROR);
			return;
		}

		CompletableFuture.supplyAsync(() -> {
			try {
				List<DshSession> sessions = DshSessions.readForeignHome(source);
				int imported = 0;
				int present = 0;
				int refused = 0;
				for (DshSession session : sessions) {
					try {
						DshSessions.importFrom(source, session, instance);
						imported++;
					} catch (DshException e) {
						if (e.getMessage() != null && e.getMessage().contains("already has a session")) {
							present++;
						} else {
							refused++;
						}
					}
				}
				DshSessions.regroup(instance.homeDirectory());
				return new int[]{imported, present, refused, sessions.size()};
			} catch (DshException e) {
				throw new CompletionException(e);
			}
		}, Schedulers.io()).whenComplete((counts, throwable) -> runInFX(() -> {
			if (throwable != null) {
				Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
				                  ? throwable.getCause()
				                  : throwable;
				LOG.warning("Failed to import sessions", cause);
				Controllers.dialog(cause.getMessage(), i18n("dsh.session.import.failed"), MessageType.ERROR);
			} else {
				Controllers.dialog(i18n("dsh.session.import.done", counts[0], counts[1], counts[2], counts[3]),
				                   i18n("dsh.session.import"));
			}
			onDone.run();
		}));
	}
}

