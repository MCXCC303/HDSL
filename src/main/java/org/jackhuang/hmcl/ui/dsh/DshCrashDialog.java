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

import com.jfoenix.controls.JFXButton;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshPermissions;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.launch.DshProcess;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.setting.StyleSheets;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.LogLine;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
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

/**
 * Displays diagnostic information when an instance fails to start or exits abnormally.
 */
@NotNullByDefault
public final class DshCrashDialog extends Stage {
	private static final int TAIL_LINES = 24;

	private final DshInstance instance;
	private final @Nullable DshProcess process;
	private final String reason;
	private final String banner;

	private DshCrashDialog(DshInstance instance, String banner, String reason, @Nullable DshProcess process) {
		this.instance = instance;
		this.banner = banner;
		this.reason = reason;
		this.process = process;

		setTitle(i18n("dsh.crash.title"));
		FXUtils.setIcon(this);

		VBox root = new VBox();
		root.getStyleClass().add("game-crash-window");

		Node details = details();
		VBox.setVgrow(details, Priority.ALWAYS);

		HBox toolBar = new HBox();
		toolBar.setPadding(new Insets(8));
		toolBar.setSpacing(8);
		toolBar.getStyleClass().add("jfx-tool-bar");
		toolBar.getChildren().setAll(exportButton(), logButton(), helpButton());
		VBox.setMargin(toolBar, new Insets(0, 0, 4, 0));

		root.getChildren().setAll(banner(), factsPane(), details, toolBar);

		setScene(new Scene(root, 800, 480));
		StyleSheets.init(getScene());
		Themes.applyNativeDarkMode(this);
	}

	/**
	 * Shows the crash window for an instance.
	 */
	public static void show(DshInstance instance, String banner, String reason, @Nullable DshProcess process) {
		new DshCrashDialog(instance, banner, reason, process).show();
	}

	private static TwoLineListItem fact(String name, String value) {
		TwoLineListItem item = new TwoLineListItem();
		item.getStyleClass().setAll("two-line-item-second-large");
		item.setTitle(name);
		item.setSubtitle(value);
		return item;
	}

	private static String duration(DshProcess process) {
		Duration ran = process.uptime();
		long seconds = Math.max(ran.getSeconds(), 0);
		if (seconds < 60) return seconds + "s";

		if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";

		return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
	}

	/**
	 * A ready process is recorded as STOPPED regardless of its exit code,
	 * so the exit code must also be checked.
	 */
	public static boolean isCrash(DshProcess process) {
		if (process.isStopRequested()) return false;

		return switch (process.state()) {
			case FAILED -> true;
			case STOPPED -> process.exitCode().orElse(0) != 0;
			default -> false;
		};
	}

	public static String describe(DshProcess process) {
		return i18n("game.crash.reason.unknown");
	}

	public static String bannerOf(DshProcess process) {
		Integer code = process.exitCode().orElse(null);
		if (code != null && code < 0) {
			return i18n("dsh.crash.sigkill", Integer.toString(-code));
		}
		return i18n("dsh.crash.exited_abnormally");
	}

	private Node banner() {
		Label title = new Label(banner);
		HBox.setHgrow(title, Priority.ALWAYS);

		HBox pane = new HBox(title);
		pane.setAlignment(Pos.CENTER);
		pane.getStyleClass().addAll("jfx-tool-bar-second", "depth-1", "padding-8");
		return pane;
	}

	private Node factsPane() {
		HBox pane = new HBox(8);
		pane.setPadding(new Insets(8));
		pane.setAlignment(Pos.CENTER_LEFT);

		pane.getChildren()
		    .addAll(fact(i18n("launcher"), Metadata.TITLE),
		            fact(i18n("dsh.crash.instance_name"), instance.id()),
		            fact(i18n("dsh.pack.field.dsh"), instance.version()),
		            fact(i18n("system.operating_system"), OperatingSystem.SYSTEM_NAME),
		            fact(i18n("system.architecture"), Platform.SYSTEM_PLATFORM.getArchitecture().getDisplayName()));

		HBox second = new HBox(8);
		second.setPadding(new Insets(0, 8, 8, 8));
		second.setAlignment(Pos.CENTER_LEFT);
		second.getChildren()
		      .addAll(fact(i18n("dsh.pack.field.profile"), instance.profile()),
		              fact(i18n("dsh.crash.home_mode"),
		                   i18n("dsh.crash.home_" + instance.homeMode().name().toLowerCase(Locale.ROOT))),
		              fact(i18n("dsh.crash.port"), process == null ? "-" : Integer.toString(process.plan().port())),
		              fact(i18n("dsh.crash.uptime"), process == null ? "-" : duration(process)),
		              fact(i18n("dsh.crash.exit_code"),
		                   process == null || process.exitCode().isEmpty()
		                   ? "-"
		                   : Integer.toString(process.exitCode().get())));

		return new VBox(pane, second);
	}

	private Node details() {
		VBox pane = new VBox(8);
		pane.setPadding(new Insets(8));

		TwoLineListItem folder = fact(i18n("dsh.crash.instance_path"), instanceDirectory());

		Label reasonTitle = new Label(i18n("game.crash.reason"));
		reasonTitle.getStyleClass().add("two-line-item-second-large-title");

		TextFlow reasonFlow = new TextFlow(new Text(reason));
		reasonFlow.getStyleClass().add("crash-reason-text-flow");

		VBox reasonBox = new VBox(6, reasonFlow);
		String tail = tail();
		if (!tail.isEmpty()) {
			Label output = new Label(tail);
			output.getStyleClass().add("dsh-crash-log");
			output.setWrapText(false);
			reasonBox.getChildren().add(output);
		}

		ScrollPane reasonPane = new ScrollPane(reasonBox);
		reasonPane.setFitToWidth(true);
		reasonPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		reasonPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		reasonPane.setPrefHeight(120);
		javafx.application.Platform.runLater(() -> reasonPane.setVvalue(1.0));

		TextFlow feedback = new TextFlow();
		feedback.getStyleClass().add("crash-reason-text-flow");
		feedback.getChildren().addAll(FXUtils.parseSegment(i18n("dsh.crash.feedback"), Controllers::onHyperlinkAction));

		pane.getChildren().setAll(folder, reasonTitle, reasonPane, feedback);

		// Grow the details block, not the reason pane, to keep the feedback text above the toolbar.
		return pane;
	}

	private Node exportButton() {
		JFXButton export = FXUtils.newRaisedButton(i18n("dsh.crash.export"));

		StackPane spinner = new StackPane(export);
		spinner.getStyleClass().add("small-spinner-pane");

		export.setOnAction(event -> export());
		return spinner;
	}

	private JFXButton logButton() {
		JFXButton log = FXUtils.newRaisedButton(i18n("logwindow.title"));
		log.setDisable(process == null);
		log.setOnAction(event -> {
			if (process != null) {
				DshLaunchService.openLogWindow(process);
			}
		});
		FXUtils.installFastTooltip(log, i18n("dsh.crash.view_log"));
		return log;
	}

	private JFXButton helpButton() {
		JFXButton help = FXUtils.newRaisedButton(i18n("help"));
		help.setOnAction(event -> FXUtils.openLink(Metadata.HOMEPAGE_URL));
		FXUtils.installFastTooltip(help, i18n("logwindow.help"));
		return help;
	}

	/**
	 * Returns the instance's actual directory, which may differ from the launcher's default instance path.
	 */
	private String instanceDirectory() {
		try {
			return instance.instanceDirectory().toString();
		} catch (DshException | RuntimeException e) {
			return "-";
		}
	}

	/**
	 * Writes a crash report with restricted file permissions.
	 */
	private void export() {
		StringBuilder text = new StringBuilder();
		text.append(i18n("dsh.crash.title")).append('\n');
		text.append(banner).append('\n').append('\n');
		for (String[] pair : List.of(new String[]{i18n("launcher"), Metadata.TITLE},
		                             new String[]{i18n("dsh.crash.instance_name"), instance.id()},
		                             new String[]{i18n("dsh.pack.field.dsh"), instance.version()},
		                             new String[]{i18n("dsh.pack.field.profile"), instance.profile()},
		                             new String[]{
				                             i18n("dsh.crash.home_mode"),
				                             i18n("dsh.crash.home_" +
				                                  instance.homeMode().name().toLowerCase(Locale.ROOT))
		                             },
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
			Path target = Metadata.HDSL_USER_HOME.resolve("crash-reports")
			                                     .resolve("crash-" + instance.id() + "-" +
			                                              DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
			                                                               .format(LocalDateTime.now()) + ".txt");
			DshPermissions.createRestrictedDirectory(target.getParent(), DshPermissions.SECRET_DIRECTORY);
			DshPermissions.createRestricted(target, DshPermissions.SECRET_FILE);
			Files.writeString(target, text.toString(), StandardCharsets.UTF_8);
			Controllers.dialog(i18n("settings.launcher.launcher_log.export.success", target.toString()),
			                   i18n("message.success"),
			                   MessageType.INFO);
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not export the crash report for " + instance.id(), e);
			Controllers.dialog(i18n("settings.launcher.launcher_log.export.failed") + "\n" + e.getMessage(),
			                   i18n("message.error"),
			                   MessageType.ERROR);
		}
	}

	private String tail() {
		if (process == null) return "";

		StringBuilder text = new StringBuilder();
		List<LogLine> lines = List.copyOf(process.windowLogs());
		int from = Math.max(0, lines.size() - TAIL_LINES);
		for (LogLine line : lines.subList(from, lines.size())) {
			text.append(line.getLog()).append('\n');
		}
		return text.toString();
	}
}