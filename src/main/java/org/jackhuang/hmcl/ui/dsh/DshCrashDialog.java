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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshProcess;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.LogLine;
import org.jackhuang.hmcl.ui.LogWindow;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.setting.StyleSheets;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.Platform;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// What is shown when an instance does not come up.
///
/// The original's own crash window, section for section and sentence for sentence
/// (`GameCrashWindow`), with the one substitution the two programs need: where it says the game,
/// this says the instance, because an instance is what this launcher starts — the same window, for
/// the same reason, about the same kind of thing.
///
/// The original's shape, in its order, and it is a **window** rather than a dialog because the
/// original's is: a crash is something that happened to a program, not a question being asked about
/// one, and it stays on the screen to be read, copied from, and compared against the log window
/// beside it.
///
/// ```text
/// ┌ 实例意外退出 ─────────────────────────────┐   ← the window's own title
/// │ 实例非正常退出。请查看日志文件，或联系他人寻求帮助。 │   ← the banner, in the accent colour
/// │ 启动器   实例     DeepSeek Harness  操作系统  架构 │   ← a name with its value under it
/// │ HMCL-DSH pokemon  0.1.5-alpha.2      Linux    x86-64
/// │ 配置                                             │
/// │ pokemon · 独立 DSH_HOME
/// │ 实例文件夹路径                                    │
/// │ /home/thf/.local/share/hdsl/instances/pokemon     │
/// │ 崩溃原因                                         │
/// │ 实例非正常退出。请查看日志文件，或联系他人寻求帮助。
/// │ <the last of the output>
/// │ 请不要将本界面截图或拍照给他人！…                  │
/// │ [导出崩溃信息] [日志] [帮助]                       │   ← at the left, as the original has them
/// └──────────────────────────────────────────────────┘
/// ```
///
/// Two of those are worth keeping deliberately rather than by habit. The **feedback paragraph** is
/// the original telling people not to post screenshots and to send the exported file instead — it
/// exists because a screenshot of a crash is a picture of a stack trace nobody can search. And the
/// **reason** is a sentence rather than the raw output: the log is one press away for whoever wants
/// it, but the window's job is to say what happened first.
///
/// The one thing this has that the original does not is the **tail of the output** under the
/// reason. The original's reason comes from an analyser that reads the log and names the cause for
/// common failures; there is no analyser for this launcher's instances yet, so the honest substitute
/// is the end of what the process said — which is where a stack trace's last lines are, and which is
/// what an analyser would have read anyway.
@NotNullByDefault
public final class DshCrashDialog extends Stage {
    /// How many lines of output the window carries.
    ///
    /// Enough for a stack trace and the sentence that follows it, and few enough that the window is
    /// still about the failure. The log window is where the rest is.
    private static final int TAIL_LINES = 24;

    /// The instance that did not come up.
    private final DshInstance instance;

    /// The process, or `null` when it never started.
    private final @Nullable DshProcess process;

    /// What is known about why.
    private final String reason;

    /// What the banner says, in the original's own words for how the process died.
    private final String banner;

    /// Creates the window.
    ///
    /// @param instance the instance that did not come up
    /// @param banner   what the banner says
    /// @param reason   what is known about why
    /// @param process  the process, or `null` when it never started
    private DshCrashDialog(DshInstance instance, String banner, String reason,
                           @Nullable DshProcess process) {
        this.instance = instance;
        this.banner = banner;
        this.reason = reason;
        this.process = process;

        setTitle(i18n("dsh.crash.title"));
        FXUtils.setIcon(this);

        VBox root = new VBox();
        root.getStyleClass().add("game-crash-window");

        VBox.setVgrow(details(), Priority.ALWAYS);
        root.getChildren().setAll(banner(),
                factsPane(),
                details(),
                new HBox(8, exportButton(), logButton(), helpButton()));

        // The original's own three lines, and the middle one is the whole answer to "does it follow
        // the theme": the crash window is **not** styled separately. It loads the launcher's
        // stylesheets like every other window, so it takes the theme colour, the brightness mode and
        // the font that are in force — and the classes below are the ones the transplanted
        // stylesheet already carries rules for, which is why none of this needed new CSS.
        setScene(new Scene(root, 800, 480));
        StyleSheets.init(getScene());
        Themes.applyNativeDarkMode(this);
    }

    /// Shows the window for an instance whose process ended badly.
    ///
    /// @param instance the instance
    /// @param banner   what the banner says
    /// @param reason   what is known about why
    /// @param process  the process, or `null` when it never started
    public static void show(DshInstance instance, String banner, String reason,
                            @Nullable DshProcess process) {
        new DshCrashDialog(instance, banner, reason, process).show();
    }

    /// Builds the banner.
    ///
    /// The original's own first line, on the surface the original puts it on: the whole width, in the
    /// accent colour, because it is the one sentence that has to be read before anything else is.
    ///
    /// @return the banner
    private Node banner() {
        Label title = new Label(banner);
        HBox.setHgrow(title, Priority.ALWAYS);

        HBox pane = new HBox(title);
        pane.setAlignment(Pos.CENTER);
        // The original's own three classes, which is what makes this the accent-coloured bar: it is
        // the launcher's *second toolbar*, not a bar invented for this window.
        pane.getStyleClass().addAll("jfx-tool-bar-second", "depth-1", "padding-8");
        return pane;
    }

    /// Builds the block of names with their values under them.
    ///
    /// The original's own row of `TwoLineListItem`s: a name, and its value on the line below, laid
    /// **across** rather than down — which reads as a description of one thing rather than as a
    /// table of many. The first row is what the launcher and the machine are, and the second is what
    /// the instance is, which is the same division the original makes between its own facts and the
    /// game's.
    ///
    /// @return the block
    private Node factsPane() {
        HBox pane = new HBox(8);
        pane.setPadding(new Insets(8));
        pane.setAlignment(Pos.CENTER_LEFT);

        pane.getChildren().addAll(
                fact(i18n("launcher"), Metadata.TITLE),
                fact(i18n("dsh.crash.instance_name"), instance.id()),
                fact(i18n("dsh.pack.field.dsh"), instance.version()),
                fact(i18n("system.operating_system"), OperatingSystem.SYSTEM_NAME),
                fact(i18n("system.architecture"), Platform.SYSTEM_PLATFORM.getArchitecture().getDisplayName()));

        HBox second = new HBox(8);
        second.setPadding(new Insets(0, 8, 8, 8));
        second.setAlignment(Pos.CENTER_LEFT);
        second.getChildren().addAll(
                fact(i18n("dsh.pack.field.profile"), instance.profile()),
                fact(i18n("dsh.crash.home_mode"),
                        i18n("dsh.crash.home_" + instance.homeMode().name().toLowerCase(Locale.ROOT))),
                fact(i18n("dsh.crash.port"),
                        process == null ? "-" : Integer.toString(process.plan().port())),
                fact(i18n("dsh.crash.uptime"), process == null ? "-" : duration(process)),
                fact(i18n("dsh.crash.exit_code"),
                        process == null || process.exitCode().isEmpty()
                                ? "-" : Integer.toString(process.exitCode().get())));

        VBox both = new VBox(pane, second);
        return both;
    }

    /// Builds one name-and-value pair, as the original builds each of its own.
    ///
    /// @param name  the name
    /// @param value the value
    /// @return the row
    private static TwoLineListItem fact(String name, String value) {
        TwoLineListItem item = new TwoLineListItem();
        // The original's own class for these, which is what gives the name its dimmer, smaller
        // treatment and the value its brighter one.
        item.getStyleClass().setAll("two-line-item-second-large");
        item.setTitle(name);
        item.setSubtitle(value);
        return item;
    }

    /// Builds the block under the facts: where the instance lives, and why it stopped.
    ///
    /// The original's own arrangement — the folder, then the reason under its own heading, then the
    /// paragraph — with one addition, the tail of the output, which the original does not need
    /// because its analyser has already read the log and named the cause.
    ///
    /// @return the block
    private Node details() {
        VBox pane = new VBox(8);
        pane.setPadding(new Insets(8));

        TwoLineListItem folder = fact(i18n("dsh.crash.instance_path"), instanceDirectory());

        Label reasonTitle = new Label(i18n("game.crash.reason"));
        reasonTitle.getStyleClass().add("two-line-item-second-large-title");

        // The original's own flow and class: `crash-reason-text-flow` is what the transplanted
        // stylesheet colours, and a `Text` inside it is how the theme reaches the text.
        TextFlow reasonFlow = new TextFlow(new javafx.scene.text.Text(reason));
        reasonFlow.getStyleClass().add("crash-reason-text-flow");

        VBox reasonBox = new VBox(6, reasonFlow);
        String tail = tail();
        if (!tail.isEmpty()) {
            Label output = new Label(tail);
            output.getStyleClass().add("dsh-crash-log");
            // Not wrapped: a stack trace's indentation is how it is read.
            output.setWrapText(false);
            reasonBox.getChildren().add(output);
        }

        ScrollPane reasonPane = new ScrollPane(reasonBox);
        reasonPane.setFitToWidth(true);
        reasonPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        reasonPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        // The original's own paragraph, which exists because a screenshot of a crash is a picture of
        // a stack trace that nobody can search, and because whoever is helping needs the file.
        TextFlow feedback = new TextFlow();
        feedback.getStyleClass().add("crash-reason-text-flow");
        feedback.getChildren().addAll(
                FXUtils.parseSegment(i18n("dsh.crash.feedback"), Controllers::onHyperlinkAction));

        pane.getChildren().setAll(folder, reasonTitle, reasonPane, feedback);
        VBox.setVgrow(reasonPane, Priority.ALWAYS);
        return pane;
    }

    /// Builds the button that writes the report out, which the paragraph above points at.
    ///
    /// @return the button
    private JFXButton exportButton() {
        JFXButton export = FXUtils.newRaisedButton(i18n("dsh.crash.export"));
        export.setOnAction(event -> export());
        return export;
    }

    /// Builds the button that opens the log window.
    ///
    /// @return the button
    private JFXButton logButton() {
        JFXButton log = FXUtils.newRaisedButton(i18n("logwindow.title"));
        // With no process there is no window to open, and a button that does nothing is worse than
        // one that says so.
        log.setDisable(process == null);
        log.setOnAction(event -> {
            if (process != null) {
                new LogWindow(process.managedProcess(), process.windowLogs()).show();
            }
        });
        FXUtils.installFastTooltip(log, i18n("dsh.crash.view_log"));
        return log;
    }

    /// Builds the button that opens the place to ask somebody.
    ///
    /// @return the button
    private JFXButton helpButton() {
        JFXButton help = FXUtils.newRaisedButton(i18n("help"));
        help.setOnAction(event -> FXUtils.openLink(Metadata.CONTACT_URL));
        FXUtils.installFastTooltip(help, i18n("logwindow.help"));
        return help;
    }

    /// Returns the instance's own directory, which is what the original shows as the game folder.
    ///
    /// @return the path, or a note that it could not be resolved
    private String instanceDirectory() {
        try {
            return org.jackhuang.hmcl.dsh.DshPaths.instanceDirectory(instance.id()).toString();
        } catch (org.jackhuang.hmcl.dsh.DshException | RuntimeException e) {
            return "-";
        }
    }

    /// Writes the failure out to a file, as the original's export button does.
    ///
    /// The original exports a zip of the game's logs and its own diagnostics. This writes one text
    /// file holding everything the window knows: the instance, the reason, and the whole of the
    /// output — the tail above is what fits, and this is what somebody analysing it needs.
    private void export() {
        StringBuilder text = new StringBuilder();
        text.append(i18n("dsh.crash.title")).append('\n');
        text.append(banner).append('\n').append('\n');
        for (String[] pair : List.of(
                new String[]{i18n("launcher"), Metadata.TITLE},
                new String[]{i18n("dsh.crash.instance_name"), instance.id()},
                new String[]{i18n("dsh.pack.field.dsh"), instance.version()},
                new String[]{i18n("dsh.pack.field.profile"), instance.profile()},
                new String[]{i18n("dsh.crash.home_mode"),
                        i18n("dsh.crash.home_" + instance.homeMode().name().toLowerCase(Locale.ROOT))},
                new String[]{i18n("dsh.crash.instance_path"), instanceDirectory()})) {
            text.append(pair[0]).append(": ").append(pair[1]).append('\n');
        }
        text.append('\n').append(i18n("game.crash.reason")).append('\n');
        text.append(reason).append('\n').append('\n');
        if (process != null) {
            for (LogLine line : List.copyOf(process.windowLogs())) {
                text.append(line.getLog()).append('\n');
            }
        }

        try {
            Path target = Metadata.HMCL_USER_HOME.resolve("crash-reports")
                    .resolve("crash-" + instance.id() + "-"
                            + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
                            + ".txt");
            Files.createDirectories(target.getParent());
            Files.writeString(target, text.toString(), StandardCharsets.UTF_8);
            Controllers.dialog(i18n("settings.launcher.launcher_log.export.success", target.toString()),
                    i18n("message.success"), MessageType.INFO);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not export the crash report for " + instance.id(), e);
            Controllers.dialog(i18n("settings.launcher.launcher_log.export.failed") + "\n" + e.getMessage(),
                    i18n("message.error"), MessageType.ERROR);
        }
    }

    /// Returns the last of the process's output.
    ///
    /// @return the text, or empty when there is nothing to show
    private String tail() {
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

    /// Writes how long the instance ran.
    ///
    /// @param process the process
    /// @return the text
    private static String duration(DshProcess process) {
        Duration ran = process.uptime();
        long seconds = Math.max(ran.getSeconds(), 0);
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    /// Reports whether an exit is worth telling somebody about.
    ///
    /// A process that was asked to stop and stopped is not a failure, and neither is one that ended
    /// cleanly after being ready — that is a person closing a server.
    ///
    /// Note that `DshProcess` records the exit of a process that had been **ready** as `STOPPED`
    /// whatever its exit code, so the state alone cannot answer this and the exit code has to be
    /// asked for as well. Testing for `FAILED` instead misses exactly the case this was written for.
    ///
    /// @param process the process
    /// @return whether to show the crash window
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

    /// Says how the process ended, for the reason line.
    ///
    /// The banner is the original's sentence and this is the fact under it: the banner says the
    /// instance exited abnormally, and this says with what — a code, or the signal that killed it.
    ///
    /// @param process the process
    /// @return the description
    public static String describe(DshProcess process) {
        // The original's own sentence for the case where its analyser could not name a cause, which
        // is every case here: there is no analyser for this launcher's instances yet, so saying so —
        // and saying where the answer is — is the honest answer, and it is the original's own words
        // for exactly this situation rather than a sentence invented here.
        //
        // The exit code is **not** repeated into this line: it is a fact, and it has a place of its
        // own in the block above. Putting it here made the reason read as a second copy of the
        // banner with the code stuck on the end.
        return i18n("game.crash.reason.unknown");
    }

    /// Returns the banner, in the original's own words for how the process died.
    ///
    /// The original has one sentence per ending — `launch.failed.exited_abnormally` for a non-zero
    /// exit and `launch.failed.sigkill` for a signal — and they are reused here with the game read as
    /// the instance, because being killed is looked for in a different place from exiting.
    ///
    /// @param process the process
    /// @return the banner
    public static String bannerOf(DshProcess process) {
        Integer code = process.exitCode().orElse(null);
        if (code != null && code < 0) {
            return i18n("dsh.crash.sigkill", Integer.toString(-code));
        }
        return i18n("dsh.crash.exited_abnormally");
    }
}
