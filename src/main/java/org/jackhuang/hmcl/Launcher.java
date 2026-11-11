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
package org.jackhuang.hmcl;

import com.mcxcc303.hdsl.core.DshPaths;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.jackhuang.hmcl.setting.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.WindowsNativeUtils;
import org.jackhuang.hmcl.ui.dsh.MainPage;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.i18n.SupportedLocale;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.SystemUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

@NotNullByDefault
public final class Launcher extends Application {
	// How many runs' logs are kept.
	private static final int LOG_RETENTION = 10;
	private static boolean loggingStarted;

	private MainPage mainPage;

	static void startLogging() {
		if (loggingStarted) return;
		loggingStarted = true;
		Logger.LOG.setLogRetention(LOG_RETENTION);
		Logger.LOG.start(DshPaths.LOGS);
	}

	/**
	 * Closes the main window, shuts down the schedulers and exits JavaFX.
	 * <p>
	 * Safe to call from any thread.
	 */
	public static void stopApplication() {
		LOG.info("Stopping application");
		runInFX(() -> {
			try {
				Stage stage = Controllers.getStage();
				if (stage == null) return;

				stage.close();
				Schedulers.shutdown();
				Controllers.shutdown();
				SettingsManager.save();
				Platform.exit();
			} catch (NullPointerException ignored) {
				// The window was already torn down.
			}
		});
	}

	public static void main(String[] args) {
		startLogging();
		setupUiScale();
		applyStartupSettings();
		launch(Launcher.class, args);
	}

	private static void applyStartupSettings() {
		LauncherSettings settings = SettingsManager.settings();
		I18n.setLocale(settings.languageProperty().get() == null
		               ? SupportedLocale.DEFAULT
		               : settings.languageProperty().get());

		if (System.getProperty("prism.lcdtext") == null) {
			String lcdText = settings.fontAntiAliasing().lcdTextProperty();
			if (lcdText != null) System.getProperties().put("prism.lcdtext", lcdText);
		}
	}

	private static void setupUiScale() {
		float lowerBound;
		float upperBound;
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			// JavaFX misbehaves when the scaling factor is too high.
			lowerBound = 0.25f;
			upperBound = 4f;
		} else {
			lowerBound = 0.01f;
			upperBound = 10f;
		}

		String requested = System.getProperty("hmcl.uiScale", System.getenv("HMCL_UI_SCALE"));
		float scale = Float.NaN;

		if (requested != null && !(requested = requested.trim()).isEmpty()) {
			LOG.info("HMCL_UI_SCALE: " + requested);

			try {
				if (requested.endsWith("%")) {
					scale = Integer.parseInt(requested.substring(0, requested.length() - 1)) / 100.0f;
				} else if (requested.endsWith("dpi") || requested.endsWith("DPI")) {
					scale = Integer.parseInt(requested.substring(0, requested.length() - 3)) / 96.0f;
				} else {
					scale = Float.parseFloat(requested);
				}
			} catch (Throwable e) {
				LOG.warning("Invalid UI scale: " + requested);
			}
		} else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
			String sessionType = Objects.requireNonNullElse(System.getenv("XDG_SESSION_TYPE"), "");
			String desktop = Objects.requireNonNullElse(System.getenv("XDG_CURRENT_DESKTOP"), "");

			if ("wayland".equals(sessionType) && StringUtils.startsWithIgnoreCase(desktop, "KDE")) {
				try {
					Path xrdb = SystemUtils.which("xrdb");
					String dpiLine =
							xrdb == null ? null : SystemUtils.run(List.of(xrdb.toString(), "-query"), input -> {
								try (BufferedReader reader = new BufferedReader(new InputStreamReader(input,
								                                                                      OperatingSystem.NATIVE_CHARSET))) {
									return reader.lines()
									             .map(String::trim)
									             .filter(line -> line.startsWith("Xft.dpi:"))
									             .findFirst()
									             .orElse(null);
								}
							}, Duration.ofSeconds(1));

					if (dpiLine != null) {
						float dpi = Float.parseFloat(dpiLine.substring("Xft.dpi:".length()).trim());
						float detected = dpi / 96f;
						if (detected > 1 && detected <= 10) {
							LOG.info("Detected Xft.dpi: " + dpi + " (" + detected + "x)");
							scale = detected;
							requested = Float.toString(detected);
						}
					}
				} catch (Exception e) {
					LOG.warning("Failed to read Xft.dpi from xrdb", e);
				}
			}
		}

		if (!Float.isFinite(scale)) {
			return;
		}
		if (scale < lowerBound || scale > upperBound) {
			LOG.warning("UI scale out of range: " + requested);
			return;
		}

		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			System.getProperties().putIfAbsent("glass.win.uiScale", requested);
		} else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
			LOG.warning("macOS does not support setting UI scale, so it will be ignored");
		} else {
			System.getProperties().putIfAbsent("glass.gtk.uiScale", requested);
		}
	}

	@Override
	public void start(Stage primaryStage) {
		startLogging();
		Logger.setDebugEnabled(SettingsManager.settings().debugLogProperty().get());
		LOG.info("HDSL " + Metadata.VERSION);
		LOG.info("JavaFX version: " + System.getProperty("javafx.runtime.version"));
		LOG.info("User home: " + Metadata.HDSL_USER_HOME);
		LOG.info("Log folder: " + DshPaths.LOGS);

		GameDirectoryManager.init();

		mainPage = new MainPage();
		Scene mainScene = Controllers.initialize(primaryStage, mainPage);
		StyleSheets.init(mainScene);
		FontManager.attach(mainScene);

		FXUtils.setIcon(primaryStage);
		primaryStage.setTitle(Metadata.FULL_TITLE);

		WindowsNativeUtils.installWindowsAppUserModelRelaunchProperties(primaryStage);
		primaryStage.show();

		String page = startPage();
		if (page != null) mainPage.openPage(page);

		LOG.info("Main window shown");
	}

	private @Nullable String startPage() {
		List<String> raw = getParameters().getRaw();
		int index = raw.indexOf("--page");
		return index >= 0 && index + 1 < raw.size() ? raw.get(index + 1) : null;
	}

	@Override
	public void stop() {
		SettingsManager.save();
	}
}
