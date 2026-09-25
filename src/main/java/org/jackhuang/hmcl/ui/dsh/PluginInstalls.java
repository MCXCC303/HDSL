/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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

import org.jackhuang.hmcl.dsh.DshBuildScripts;
import org.jackhuang.hmcl.dsh.DshException;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshPluginInstaller;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Installs plugins, asking about install scripts when the setting says to ask.
///
/// A package manager will not run a package's install script until it is told to, and it
/// only says which packages it is waiting for *after* it has refused to install them. So
/// the question cannot be asked before the installation starts; it is asked when the
/// package manager raises it, and the installation carries on from there — with the
/// answer written into the profile, which is where the package manager looks for it.
///
/// Every path that installs plugins goes through here rather than through the progress
/// dialog directly, because the asking is the same whatever is being installed.
@NotNullByDefault
public final class PluginInstalls {
    private PluginInstalls() {
    }

    /// Installs something, asking about install scripts if the policy says to ask.
    ///
    /// @param instance the instance being installed into
    /// @param work     the installation
    /// @param onDone   run when it finishes, or `null`
    public static void run(DshInstance instance, ProgressDialog.Work work, @Nullable Runnable onDone) {
        attempt(i18n("download.install"), () -> instance, work, onDone);
    }

    /// Runs an installation that makes its own instance, asking about install scripts when it has to.
    ///
    /// The instance does not exist until the work has made it, so it is looked up when the question
    /// comes rather than held. That is what installing a pack needs: the pack is what creates the
    /// instance, and the question pnpm raises is written into the profile of the instance it just
    /// made. It works because the pack installers **keep** that instance when this is what stopped
    /// them — the answer has to be written to the same profile the question is in — and because by
    /// the time anything is asked, that profile exists.
    ///
    /// @param title   what the progress dialog says it is doing
    /// @param subject the instance the work is making or finishing, looked up when it must be asked
    ///                about, or `null` when there is none to ask about
    /// @param work    the installation
    /// @param onDone  run when it finishes, or `null`
    public static void runCreating(String title, java.util.function.Supplier<@Nullable DshInstance> subject,
                                   ProgressDialog.Work work, @Nullable Runnable onDone) {
        attempt(title, subject, work, onDone);
    }

    /// Runs the installation once, and asks when it comes back needing an answer.
    ///
    /// @param title   the line the dialog is titled with
    /// @param subject the instance to answer about
    /// @param work     the installation
    /// @param onDone   run when it finishes, or `null`
    private static void attempt(String title, java.util.function.Supplier<@Nullable DshInstance> subject,
                                ProgressDialog.Work work, @Nullable Runnable onDone) {
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        ProgressDialog.run(title, work, () -> {
            // The original says so when an installation worked, and so does this:
            // the dialog goes away, and without a word the only thing a person knows
            // is that something stopped happening.
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

            // The answer is a decision about running code, so it is asked for in the
            // words that say what is at stake, and the installation is resumed with
            // whatever the person decided.
            DshInstance instance = subject.get();
            if (instance == null) {
                // The question outlived the instance it was about, so there is nothing left to
                // answer into. Saying so beats opening a question whose answer is dropped.
                Controllers.dialog(required.getMessage(), title, MessageDialogPane.MessageType.ERROR);
                return true;
            }
            Controllers.dialog(new MessageDialogPane.Builder(i18n("dsh.settings.build_scripts.ask"),
                    i18n("dsh.settings.build_scripts.approve"), MessageDialogPane.MessageType.QUESTION)
                    .yesOrNo(() -> answer(title, subject, required, true, work, onDone),
                            () -> answer(title, subject, required, false, work, onDone))
                    .build());
            return true;
        });
    }

    /// Records an answer and carries on with the installation.
    ///
    /// @param title    the line the dialog is titled with
    /// @param subject  the instance to answer about
    /// @param required what is waiting
    /// @param allowed  the answer
    /// @param work     the installation
    /// @param onDone   run when it finishes, or `null`
    private static void answer(String title, java.util.function.Supplier<@Nullable DshInstance> subject,
                               DshPluginInstaller.DshBuildScriptApprovalRequired required,
                               boolean allowed, ProgressDialog.Work work, @Nullable Runnable onDone) {
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
        LOG.info("Install scripts for " + required.packages() + (allowed ? " allowed" : " refused"));
        attempt(title, subject, work, onDone);
    }

}
