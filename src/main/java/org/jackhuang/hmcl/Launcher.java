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

import java.util.List;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.setting.FontManager;
import org.jackhuang.hmcl.setting.StyleSheets;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.dsh.MainPage;
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
        launch(Launcher.class, args);
    }
}
