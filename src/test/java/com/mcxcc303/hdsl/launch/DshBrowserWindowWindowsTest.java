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
package com.mcxcc303.hdsl.launch;

import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceManager;
import com.mcxcc303.hdsl.instance.InstanceFolders;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies browser window opening behavior on instance launch.
 */
class DshBrowserWindowWindowsTest {
	private static final String WEB = "@deepseek-ai/dsh-web-app";
	private static final String HEADLESS = "@deepseek-ai/dsh-headless";

	@TempDir
	Path folder;

	@TempDir
	Path workspace;

	private Supplier<List<Path>> previousFolders;

	@BeforeEach
	void makeItIsolate() {
		previousFolders = InstanceFolders.setFolders(() -> List.of(folder));
	}

	@AfterEach
	void putItBack() {
		InstanceFolders.setFolders(previousFolders);
	}

	private DshInstance makeInstance(String id, String bundle, String typed, boolean helpMentions) throws Exception {
		Assumptions.assumeTrue(DshNodeRuntime.detect().map(DshNodeRuntime::isNodeSupported).orElse(false),
		                       "Node.js ^22.19.0 || >=24.0.0 is not on PATH; nothing can be started without it");
		DshInstance instance = DshInstanceManager.create(id,
		                                                 "1.2.3",
		                                                 id,
		                                                 workspace,
		                                                 DshNodeRuntime.SYSTEM,
		                                                 DshHomeMode.ISOLATED,
		                                                 null,
		                                                 DshLaunchArguments.tokenize(typed),
		                                                 Map.of(),
		                                                 folder);

		Path profile = instance.homeDirectory().resolve("profiles").resolve(id);
		Files.createDirectories(profile);
		Files.writeString(profile.resolve("package.json"),
		                  "{\"name\":\"" + id + "\",\"dsh\":{\"profile\":{\"bundles\":[\"@deepseek-ai/dsh-base\",\"" +
		                  bundle + "\"]}}}");

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
	void aVersionWithNoSuchFlagIsStillOpenedFor() throws Exception {
		// The reported defect: 0.0.1-rc.5 started, served the interface, and opened no browser. Its
		// help has no `--no-open` and no browser option at all, so the flag cannot be passed — and the
		// launcher used to conclude from that alone that the harness would open the tab itself.
		DshInstance instance = makeInstance("web-untold", WEB, null, false);

		DshLauncher.LaunchPlan plan = DshLauncher.plan(instance);

		assertFalse(plan.command().contains("--no-open"),
		            "a flag the version does not list is a launch that does not start: " + plan.command());
		assertTrue(plan.launcherOpensTheBrowser(),
		           "a version that knows no --no-open opens no browser of its own, so nobody would open one");
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

	@Test
	void whoOpensTheInterfaceIsDecidedByWhatTheVersionSaid() {
		// The decision on its own, without a child to ask: every combination of what the surface wants
		// and what the version answered. The probe's own answers are exercised above.
		for (DshLauncher.NoOpen support : DshLauncher.NoOpen.values()) {
			boolean expected = support != DshLauncher.NoOpen.UNKNOWN;
			assertEquals(expected,
			             DshLauncher.launcherOpensTheInterface(true, true, support),
			             "a browser surface that asked for the interface, with help saying " + support);
			assertFalse(DshLauncher.launcherOpensTheInterface(false, true, support),
			            "a surface that serves no address is never opened, whatever the help says");
			assertFalse(DshLauncher.launcherOpensTheInterface(true, false, support),
			            "a surface that did not ask for the interface is not the launcher's to open");
		}
	}
}
