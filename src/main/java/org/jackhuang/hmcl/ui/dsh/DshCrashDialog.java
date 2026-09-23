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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshProcess;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.LogLine;
import org.jackhuang.hmcl.ui.LogWindow;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// What is shown when an instance does not come up.
///
/// The original shows a window rather than a line of text, and the reason is the one thing a person
/// needs at that moment: **something to act on**. A message box that says "the instance failed" and
/// nothing else leaves them with nowhere to go; this one carries the last of the output, so the
/// answer is usually already on the screen, and two ways to get the whole of it — the log window the
/// launcher already has, and the clipboard.
///
/// It is deliberately **not** shown when the user stopped the instance themselves: an instance that
/// was told to stop and stopped is not a crash, and a dialog for it would be the launcher crying
/// wolf at the thing it was just asked to do.
///
/// The tail is a fixed number of lines rather than everything, because the end of the output is
/// where a failure says what it was — a stack trace's last lines, or the sentence after it. The
/// whole log is one press away.
@NotNullByDefault
public final class DshCrashDialog extends JFXDialogLayout {
    /// How many lines of output the dialog carries.
    ///
    /// Enough for a stack trace and the sentence that follows it, and few enough that the dialog is
    /// still a dialog. The log window is where the rest is.
    private static final int TAIL_LINES = 24;

    /// Creates the dialog.
    ///
    /// @param instance the instance that did not come up
    /// @param reason   what is known about why
    /// @param process  the process, or `null` when it never started
    private DshCrashDialog(DshInstance instance, String reason, @Nullable DshProcess process) {
        setHeading(new Label(i18n("dsh.crash.title")));

        VBox body = new VBox(10);

        Label what = new Label(i18n("dsh.crash.instance", instance.id(), instance.version()));
        what.getStyleClass().add("dsh-crash-heading");

        Label why = new Label(reason);
        why.setWrapText(true);

        body.getChildren().addAll(what, why);

        String tail = tail(process);
        if (!tail.isEmpty()) {
            Label output = new Label(tail);
            output.getStyleClass().add("dsh-crash-log");
            output.setWrapText(false);
            // Not wrapped: a stack trace's indentation is how it is read, and a wrapped one is a
            // paragraph that no longer looks like one.
            javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(output);
            scroll.setFitToWidth(false);
            scroll.setPrefHeight(240);
            scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
            VBox.setVgrow(scroll, Priority.ALWAYS);
            body.getChildren().add(scroll);
        }

        setBody(body);
        setActions(actions(reason, process));
    }

    /// Shows the dialog for an instance whose process ended badly.
    ///
    /// @param instance the instance
    /// @param reason   what is known about why
    /// @param process  the process, or `null` when it never started
    public static void show(DshInstance instance, String reason, @Nullable DshProcess process) {
        Controllers.dialog(new DshCrashDialog(instance, reason, process));
    }

    /// Builds the buttons.
    ///
    /// The log window first, because it is what somebody who wants to understand the failure will
    /// press: the tail above is what fits, and this is the whole of it, in the window the launcher
    /// already opens for a running instance so there is nothing new to learn.
    ///
    /// @param reason  what is known about why
    /// @param process the process, or `null`
    /// @return the actions
    private Node actions(String reason, @Nullable DshProcess process) {
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);

        if (process != null) {
            JFXButton openLog = new JFXButton(i18n("dsh.crash.view_log"));
            openLog.getStyleClass().add("dialog-accept");
            openLog.setOnAction(event -> {
                LogWindow window = new LogWindow(process.managedProcess(), process.windowLogs());
                window.show();
            });
            actions.getChildren().add(openLog);
        }

        JFXButton copy = new JFXButton(i18n("dsh.crash.copy"));
        copy.getStyleClass().add("dialog-cancel");
        copy.setOnAction(event -> {
            FXUtils.copyText(copyText(process, reason));
            Controllers.showToast(i18n("dsh.crash.copied"));
        });
        actions.getChildren().add(copy);

        JFXButton close = new JFXButton(i18n("button.ok"));
        close.getStyleClass().add("dialog-cancel");
        close.setOnAction(event -> fireEvent(new DialogCloseEvent()));
        actions.getChildren().add(close);

        return actions;
    }

    /// Returns the last of a process's output.
    ///
    /// @param process the process, or `null`
    /// @return the text, or empty when there is nothing to show
    private static String tail(@Nullable DshProcess process) {
        if (process == null) {
            return "";
        }
        List<LogLine> lines = List.copyOf(process.windowLogs());
        int from = Math.max(0, lines.size() - TAIL_LINES);
        StringBuilder text = new StringBuilder();
        for (LogLine line : lines.subList(from, lines.size())) {
            text.append(line.getLog()).append('\n');
        }
        return text.toString();
    }

    /// Returns what the copy button puts on the clipboard.
    ///
    /// Everything, not only the tail: somebody pasting this into a bug report wants the whole of what
    /// the launcher saw, and the dialog's own truncation is a limit of the dialog rather than of the
    /// answer.
    ///
    /// @param process the process, or `null`
    /// @param reason  what is known about why
    /// @return the text
    private static String copyText(@Nullable DshProcess process, String reason) {
        StringBuilder text = new StringBuilder();
        text.append(i18n("dsh.crash.title")).append('\n');
        text.append(reason).append('\n');
        if (process != null) {
            text.append('\n');
            for (LogLine line : List.copyOf(process.windowLogs())) {
                text.append(line.getLog()).append('\n');
            }
        }
        return text.toString();
    }

    /// Reports whether an exit is worth telling somebody about.
    ///
    /// A process that was asked to stop and stopped is not a failure, and neither is one that ended
    /// cleanly after being ready — that is a person closing a server. What is left is the case this
    /// dialog exists for: it ended by itself, before or without ever answering.
    ///
    /// @param process the process
    /// @return whether to show the crash dialog
    public static boolean isCrash(DshProcess process) {
        if (process.isStopRequested()) {
            return false;
        }
        return switch (process.state()) {
            case FAILED -> true;
            case STOPPED -> process.exitCode().orElse(0) != 0;
            default -> false;
        };
    }

    /// Describes how a process ended, in one line.
    ///
    /// @param process the process
    /// @return the description
    public static String describe(DshProcess process) {
        Integer code = process.exitCode().orElse(null);
        if (code == null) {
            return i18n("dsh.crash.ended_unknown");
        }
        // A negative code is a signal on Unix, and saying so is the difference between "it exited"
        // and "something killed it" — which are looked for in different places.
        if (code < 0) {
            return i18n("dsh.crash.killed", -code);
        }
        return i18n("dsh.crash.exited", code);
    }
}
