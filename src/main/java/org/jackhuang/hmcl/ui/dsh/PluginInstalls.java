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
import com.mcxcc303.hdsl.plugin.DshBuildScripts;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

@NotNullByDefault
public final class PluginInstalls {
	private PluginInstalls() {
	}

	/**
	 * Installs a plugin. Asks about install scripts when the build-script policy requires it.
	 *
	 * @param instance the instance to install into
	 * @param work     the installation work
	 * @param onDone   runs when the installation finishes, or {@code null}
	 */
	public static void run(DshInstance instance, ProgressDialog.Work work, @Nullable Runnable onDone) {
		DshPluginInstaller.beginInstallation();
		attempt(i18n("dsh.instance.plugins.install.into", instance.id()), () -> instance, work, onDone);
	}

	/**
	 * Runs an installation that creates an instance. Asks about install scripts when needed.
	 *
	 * @param title   the title shown in the progress dialog
	 * @param subject supplies the instance created or finished by the work, or {@code null} if
	 *                none; looked up when the question arises
	 * @param work    the installation work
	 * @param onDone  runs when the installation finishes, or {@code null}
	 */
	public static void runCreating(String title,
	                               Supplier<@Nullable DshInstance> subject,
	                               ProgressDialog.Work work,
	                               @Nullable Runnable onDone) {
		DshPluginInstaller.beginInstallation();
		attempt(title, subject, work, onDone);
	}

	private static void attempt(String title,
	                            Supplier<@Nullable DshInstance> subject,
	                            ProgressDialog.Work work,
	                            @Nullable Runnable onDone) {
		AtomicBoolean failed = new AtomicBoolean();
		ProgressDialog.run(title, work, () -> {
			// The dialog closes when the work ends. A toast reports the success.
			if (!failed.get()) {
				Controllers.showToast(i18n("download.install.success"));
			}
			if (onDone != null) {
				onDone.run();
			}
		}, failure -> {
			failed.set(true);
			if (DshToolchainPrompt.offer(failure)) {
				return true;
			}
			if (!(failure instanceof DshPluginInstaller.DshBuildScriptApprovalRequired required)) {
				return false;
			}

			DshInstance instance = subject.get();
			if (instance == null) {
				// The question outlived the instance.
				Controllers.dialog(required.getMessage(), title, MessageDialogPane.MessageType.ERROR);
				return true;
			}
			Controllers.dialog(new MessageDialogPane.Builder(i18n("dsh.settings.build_scripts.ask"),
			                                                 i18n("dsh.settings.build_scripts.approve"),
			                                                 MessageDialogPane.MessageType.QUESTION).yesOrNo(() -> answer(
					title,
					subject,
					required,
					true,
					work,
					onDone), () -> answer(title, subject, required, false, work, onDone)).build());
			return true;
		});
	}

	private static void answer(String title,
	                           Supplier<@Nullable DshInstance> subject,
	                           DshPluginInstaller.DshBuildScriptApprovalRequired required,
	                           boolean allowed,
	                           ProgressDialog.Work work,
	                           @Nullable Runnable onDone) {
		DshInstance instance = subject.get();
		if (instance == null) {
			Controllers.dialog(required.getMessage(), title, MessageDialogPane.MessageType.ERROR);
			return;
		}
		try {
			DshBuildScripts.answer(instance, required.packages(), allowed);
		} catch (DshException e) {
			LOG.warning("Failed to record the build script answer", e);
			Controllers.dialog(e.getMessage(), title, MessageDialogPane.MessageType.ERROR);
			return;
		}
		// Do not raise these packages again in this installation.
		// A refusal keeps the profile refused and still installing.
		DshPluginInstaller.answeredAbout(required.packages());
		LOG.info("Install scripts for " + required.packages() + (allowed ? " allowed" : " refused"));
		attempt(title, subject, work, onDone);
	}

}
