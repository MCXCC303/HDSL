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
package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that one launch opens one browser window, and says which side opens it.
///
/// The reported defect: launching an instance opened the interface twice. It had two askers — the
/// launcher opens the instance's address once the harness reports ready, and the harness opens a tab
/// of its own unless it is told not to — and each of them only knew what it was doing. The flag is
/// what settles it, and the flag can only be passed to a version whose own help lists it: one a
/// version does not know is a launch that does not start.
///
/// So the flag and the plan's answer are one decision, and the two answers a real version can give to
/// `--help` are what this asks about. Nothing here can be checked without a real child, because a
/// real child is what the launcher's question is asked of.
class DshBrowserWindowWindowsTest {
    /// The browser surface, which is the one that can be opened twice.
    private static final String WEB = "@deepseek-ai/dsh-web-app";

    /// An app that is not the browser, so a launch of it has no window to open at all.
    private static final String HEADLESS = "@deepseek-ai/dsh-headless";

    /// Where the instance's own directory is made.
    @TempDir
    Path folder;

    /// The instance's workspace.
    @TempDir
    Path workspace;

    /// The folders the manager was pointed at before this test replaced them.
    private Supplier<List<Path>> previousFolders;

    /// Points the manager at this test's own folder.
    @BeforeEach
    void makeItIsolate() {
        previousFolders = DshInstanceManager.setFolders(() -> List.of(folder));
    }

    /// Puts the folders back, so one test does not decide the next.
    @AfterEach
    void putItBack() {
        DshInstanceManager.setFolders(previousFolders);
    }

    /// An instance whose harness answers its own help request with a chosen text.
    ///
    /// The profile it is made in decides whether this is a browser surface — the instance declares
    /// which app it boots, which is how the launcher answers the question for a pack's profile as
    /// well as for its own.
    ///
    /// @param id            the instance id, which is also its profile name
    /// @param bundle        the app bundle the profile layers
    /// @param typed         the launch arguments somebody typed, or `null` for none
    /// @param helpMentions  whether the stub's help mentions `--no-open`
    /// @return the instance
    private DshInstance makeInstance(String id, String bundle, String typed, boolean helpMentions)
            throws Exception {
        Assumptions.assumeTrue(
                DshNodeRuntime.detect().map(DshNodeRuntime::isNodeSupported).orElse(false),
                "Node.js ^22.19.0 || >=24.0.0 is not on PATH; nothing can be started without it");
        DshInstance instance = DshInstanceManager.create(id, "1.2.3", id, workspace,
                DshNodeRuntime.SYSTEM, DshHomeMode.ISOLATED, null,
                DshLaunchArguments.tokenize(typed), Map.of(), folder);

        Path profile = instance.homeDirectory().resolve("profiles").resolve(id);
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("package.json"),
                "{\"name\":\"" + id + "\",\"dsh\":{\"profile\":{\"bundles\":[\"@deepseek-ai/dsh-base\",\""
                        + bundle + "\"]}}}");

        Path script = instance.dshEntryPoint();
        Files.createDirectories(script.getParent());
        Files.writeString(script, """
                if (process.argv.includes('--help')) {
                    console.log('%s');
                    process.exit(0);
                }
                console.log('STARTED');
                """.formatted(helpMentions ? "usage: dsh [--no-open]" : "usage: dsh"));
        return instance;
    }

    @Test
    void aVersionToldNotToOpenIsTheOneTheLauncherOpensFor() throws Exception {
        DshInstance instance = makeInstance("web-told", WEB, null, true);

        DshLauncher.LaunchPlan plan = DshLauncher.plan(instance);

        assertTrue(plan.command().contains("--no-open"),
                "a version whose help lists the flag is told not to open the interface: " + plan.command());
        assertTrue(plan.launcherOpensTheBrowser(),
                "having told the harness to stay off the browser, the launcher is what takes the person there");
    }

    @Test
    void aVersionThatWasNeverToldOpensItsOwnAndTheLauncherDoesNot() throws Exception {
        DshInstance instance = makeInstance("web-untold", WEB, null, false);

        DshLauncher.LaunchPlan plan = DshLauncher.plan(instance);

        assertFalse(plan.command().contains("--no-open"),
                "a flag the version does not list is a launch that does not start: " + plan.command());
        assertFalse(plan.launcherOpensTheBrowser(),
                "the harness opens the interface itself, so the launcher opening as well is two windows");
    }

    @Test
    void aLineThatAsksForNoOpenIsStillOpenedFor() throws Exception {
        DshInstance instance = makeInstance("web-typed", WEB, "--no-open", false);

        DshLauncher.LaunchPlan plan = DshLauncher.plan(instance);

        assertTrue(plan.command().contains("--no-open"),
                "a `--no-open` somebody typed belongs to the app and stays where they put it: " + plan.command());
        assertTrue(plan.launcherOpensTheBrowser(),
                "having asked the harness to stay off the browser, they still expect to be taken to it");
    }

    @Test
    void anAppWithNoWindowIsNeverOpenedFor() throws Exception {
        DshInstance instance = makeInstance("headless", HEADLESS, null, false);

        DshLauncher.LaunchPlan plan = DshLauncher.plan(instance);

        assertFalse(plan.launcherOpensTheBrowser(),
                "an app that serves no address has no window for the launcher to open");
    }
}
