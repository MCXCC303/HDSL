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
        attempt(instance, work, onDone);
    }

    /// Runs the installation once, and asks when it comes back needing an answer.
    ///
    /// @param instance the instance
    /// @param work     the installation
    /// @param onDone   run when it finishes, or `null`
    private static void attempt(DshInstance instance, ProgressDialog.Work work, @Nullable Runnable onDone) {
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        ProgressDialog.run(i18n("download.install"), work, () -> {
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
            Controllers.dialog(new MessageDialogPane.Builder(i18n("dsh.settings.build_scripts.ask"),
                    i18n("dsh.settings.build_scripts.approve"), MessageDialogPane.MessageType.QUESTION)
                    .yesOrNo(() -> answer(instance, required, true, work, onDone),
                            () -> answer(instance, required, false, work, onDone))
                    .build());
            return true;
        });
    }

    /// Records an answer and carries on with the installation.
    ///
    /// @param instance the instance
    /// @param required what is waiting
    /// @param allowed  the answer
    /// @param work     the installation
    /// @param onDone   run when it finishes, or `null`
    private static void answer(DshInstance instance, DshPluginInstaller.DshBuildScriptApprovalRequired required,
                               boolean allowed, ProgressDialog.Work work, @Nullable Runnable onDone) {
        try {
            DshBuildScripts.answer(instance, required.packages(), allowed);
        } catch (DshException e) {
            LOG.warning("Failed to record the build script answer", e);
            Controllers.dialog(e.getMessage(), i18n("download.install"),
                    MessageDialogPane.MessageType.ERROR);
            return;
        }
        LOG.info("Install scripts for " + required.packages() + (allowed ? " allowed" : " refused"));
        attempt(instance, work, onDone);
    }

}
