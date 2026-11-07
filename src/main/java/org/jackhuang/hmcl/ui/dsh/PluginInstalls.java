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

/**
 * Installs plugins, handling install-script approval dialogs when required by policy.
 */
@NotNullByDefault
public final class PluginInstalls {
	private PluginInstalls() {
	}

	/**
	 * Installs plugins into an existing instance.
	 *
	 * @param instance the instance being installed into
	 * @param work     the installation work
	 * @param onDone   callback run when finished, or {@code null}
	 */
	public static void run(DshInstance instance, ProgressDialog.Work work, @Nullable Runnable onDone) {
		DshPluginInstaller.beginInstallation();
		attempt(i18n("dsh.instance.plugins.install.into", instance.id()), () -> instance, work, onDone);
	}

	/**
	 * Installs plugins for an installation that creates its own instance.
	 *
	 * @param title   title for the progress dialog
	 * @param subject supplier providing the target instance
	 * @param work    the installation work
	 * @param onDone  callback run when finished, or {@code null}
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
			if (!failed.get()) {
				Controllers.showToast(i18n("download.install.success"));
			}
			if (onDone != null) {
				onDone.run();
			}
		}, failure -> {
			failed.set(true);
			if (!(failure instanceof DshPluginInstaller.DshBuildScriptApprovalRequired required)) {
				return false;
			}

			DshInstance instance = subject.get();
			if (instance == null) {
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
		DshPluginInstaller.answeredAbout(required.packages());
		LOG.info("Install scripts for " + required.packages() + (allowed ? " allowed" : " refused"));
		attempt(title, subject, work, onDone);
	}
}

