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
package org.jackhuang.hmcl;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.setting.FontManager;
import org.jackhuang.hmcl.setting.StyleSheets;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.WindowsNativeUtils;
import org.jackhuang.hmcl.ui.dsh.MainPage;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.i18n.SupportedLocale;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.SystemUtils;
import org.jetbrains.annotations.NotNullByDefault;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The HMCL-DSH JavaFX application entry point.
///
/// Responsibilities are deliberately small: create the root page, hand it to
/// the ported window decorator, apply the stylesheets and manage shutdown.
/// Everything DeepSeek Harness specific lives in `org.jackhuang.hmcl.dsh`.
@NotNullByDefault
public final class Launcher extends Application {
    /// The root page created for this application run.
    private MainPage mainPage;

    @Override
    public void start(Stage primaryStage) {
        org.jackhuang.hmcl.util.logging.Logger.setDebugEnabled(
                SettingsManager.settings().debugLogProperty().get());
        LOG.info("HMCL-DSH " + Metadata.VERSION);
        LOG.info("JavaFX version: " + System.getProperty("javafx.runtime.version"));
        LOG.info("User home: " + Metadata.HMCL_USER_HOME);

        // The folders, the selected one and the instances inside it are observed
        // by the interface rather than looked up by it, so the manager that owns
        // them is brought up before the first page that shows them.
        org.jackhuang.hmcl.setting.GameDirectoryManager.init();

        mainPage = new MainPage();

        Scene mainScene = Controllers.initialize(primaryStage, mainPage);
        StyleSheets.init(mainScene);
        FontManager.attach(mainScene);

        FXUtils.setIcon(primaryStage);
        primaryStage.setTitle(Metadata.FULL_TITLE);
        // What the taskbar pins, relaunches and groups under is the launcher's own
        // Windows executable; a no-op everywhere that executable is not in play.
        WindowsNativeUtils.installWindowsAppUserModelRelaunchProperties(primaryStage);
        primaryStage.show();

        String page = startPage();
        if (page != null) {
            mainPage.openPage(page);
        }

        LOG.info("Main window shown");
    }

    /// Reads the `--page` start-up option.
    ///
    /// Lets a desktop entry, a script or a screenshot harness open a specific
    /// page without navigating by hand.
    ///
    /// @return the requested page name, or `null` when none was given
    private @Nullable String startPage() {
        List<String> raw = getParameters().getRaw();
        int index = raw.indexOf("--page");
        return index >= 0 && index + 1 < raw.size() ? raw.get(index + 1) : null;
    }

    @Override
    public void stop() {
        SettingsManager.save();
    }

    /// Closes the main window, shuts down the schedulers and exits JavaFX.
    ///
    /// Safe to call from any thread; the work is re-posted onto the JavaFX
    /// application thread. Calling it twice is harmless.
    public static void stopApplication() {
        LOG.info("Stopping application");
        runInFX(() -> {
            try {
                Stage stage = Controllers.getStage();
                if (stage == null)
                    return;
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

    /// Starts the launcher.
    ///
    /// @param args command-line arguments, currently unused
    public static void main(String[] args) {
        setupUiScale();
        // Before the toolkit starts: the language every page will speak, and the text
        // rendering the renderer reads as it initialises.
        applyStartupSettings();
        launch(Launcher.class, args);
    }

    /// Applies the settings the toolkit needs before it starts.
    ///
    /// Both are read here rather than from the interface for the same reason: the language
    /// decides what every string in the first frame says, and the anti-aliasing property is
    /// one the renderer has already read by the time a page exists. The original reads them
    /// in its own `main` for exactly this.
    private static void applyStartupSettings() {
        LauncherSettings settings = SettingsManager.settings();
        I18n.setLocale(settings.languageProperty().get() == null
                ? SupportedLocale.DEFAULT : settings.languageProperty().get());

        if (System.getProperty("prism.lcdtext") == null) {
            String lcdText = settings.fontAntiAliasing().lcdTextProperty();
            if (lcdText != null) {
                System.getProperties().put("prism.lcdtext", lcdText);
            }
        }
    }

    /// Sets the interface scale the toolkit starts with.
    ///
    /// Read here, before the toolkit starts, because the property it sets is one
    /// the GTK backend reads while it initialises: from the application it would
    /// be set too late, which is why the original reads it in its own `main` too.
    ///
    /// The value is `HMCL_UI_SCALE`, or `-Dhmcl.uiScale` for a launch that would
    /// rather not use the environment. A factor (`1.5`), a percentage (`150%`)
    /// and a resolution (`144dpi`) all say the same thing to the backend, which
    /// is the original's own vocabulary for it.
    ///
    /// Where the environment names no scale, a Wayland session running KDE is
    /// asked instead: that desktop keeps its scale in the X resources, and the
    /// toolkit does not read it on its own — the original asks for the same
    /// reason and in the same place.
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
            // Logged where the original logs it. Nothing comes of it as things
            // stand — this launcher never starts its logger, so no level of it
            // reaches the console or a file — but the value belongs on the
            // record for when it does, and the message is the original's.
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
                    String dpiLine = xrdb == null ? null : SystemUtils.run(
                            List.of(xrdb.toString(), "-query"),
                            input -> {
                                try (BufferedReader reader = new BufferedReader(
                                        new InputStreamReader(input, OperatingSystem.NATIVE_CHARSET))) {
                                    return reader.lines()
                                            .map(String::trim)
                                            .filter(line -> line.startsWith("Xft.dpi:"))
                                            .findFirst()
                                            .orElse(null);
                                }
                            },
                            Duration.ofSeconds(1));

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
}
