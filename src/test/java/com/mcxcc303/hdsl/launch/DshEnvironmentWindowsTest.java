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
import org.jackhuang.hmcl.setting.SettingsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the environment the launcher is configured with really arrives in a child process on Windows.
 */
class DshEnvironmentWindowsTest {
	private static final String INSTANCE_ID = "environment-windows";
	private static final String PROFILE = "acp";

	@TempDir
	Path folder;

	@TempDir
	Path workspace;

	private Supplier<List<Path>> previousFolders;
	private Map<String, String> previousGlobalEnvironment;

	private static void requireNode() {
		Assumptions.assumeTrue(DshNodeRuntime.detect().map(DshNodeRuntime::isNodeSupported).orElse(false),
		                       "Node.js ^22.19.0 || >=24.0.0 is not on PATH; nothing can be started without it");
	}

	private static void installProbeSurface(DshInstance instance) throws Exception {
		Path script = instance.dshEntryPoint();
		Files.createDirectories(script.getParent());
		Files.writeString(script, """
				const args = process.argv.slice(2);
				const lines = ['ARGC=' + args.length];
				args.forEach((argument, index) => lines.push('ARG[' + index + ']=' + argument));
				Object.keys(process.env)
				      .filter(name => /^(DSH|HDSL)_/i.test(name))
				      .sort()
				      .forEach(name => lines.push('ENV ' + name.toUpperCase() + '=' + process.env[name]));
				lines.push('--no-open');
				console.log(lines.join('\\n'));
				""");
	}

	private static Child run(DshLauncher.LaunchPlan plan) throws Exception {
		ProcessBuilder builder = new ProcessBuilder(plan.command());
		builder.directory(plan.workingDirectory().toFile());
		builder.environment().putAll(plan.environment());
		builder.redirectErrorStream(true);

		Process child = builder.start();
		String output;
		try (var stream = child.getInputStream()) {
			output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child has to finish on its own");
		return new Child(child.exitValue(), output);
	}

	@BeforeEach
	void makeItIsolate() {
		previousFolders = InstanceFolders.setFolders(() -> List.of(folder));
		previousGlobalEnvironment = Map.copyOf(SettingsManager.settings().globalEnvironment());
	}

	@AfterEach
	void putItBack() {
		SettingsManager.settings().globalEnvironmentProperty().set(previousGlobalEnvironment);
		InstanceFolders.setFolders(previousFolders);
	}

	private DshInstance makeInstance(Map<String, String> environment) throws Exception {
		requireNode();
		DshInstance instance = DshInstanceManager.create(INSTANCE_ID,
		                                                 "1.2.3",
		                                                 PROFILE,
		                                                 workspace,
		                                                 DshNodeRuntime.SYSTEM,
		                                                 DshHomeMode.ISOLATED,
		                                                 null,
		                                                 List.of(),
		                                                 environment,
		                                                 folder);
		installProbeSurface(instance);
		return instance;
	}

	@Test
	void theLauncherWideSetAndTheInstancesOwnBothReachTheChild() throws Exception {
		SettingsManager.settings().globalEnvironmentProperty().set(DshEnvironment.parse("""
				                                                                                # HDSL_COMMENTED=a commented-out line is not a variable
				                                                                                HDSL_GLOBAL_ONLY=from the launcher
				                                                                                HDSL_SHARED=from the launcher
				                                                                                
				                                                                                HDSL_EMPTY=
				                                                                                """));

		DshInstance instance = makeInstance(Map.of("HDSL_INSTANCE_ONLY",
		                                           "from the instance",
		                                           "HDSL_SHARED",
		                                           "from the instance"));

		Child child = run(DshLauncher.plan(instance));
		Map<String, String> environment = child.environment();

		assertEquals(0, child.exitCode(), child.output());
		assertEquals("from the launcher",
		             environment.get("HDSL_GLOBAL_ONLY"),
		             "a variable the launcher is set to has to be in the child's own environment");
		assertEquals("from the instance",
		             environment.get("HDSL_INSTANCE_ONLY"),
		             "and so does one the instance names for itself");
		assertEquals("from the instance",
		             environment.get("HDSL_SHARED"),
		             "the instance's set is laid over the launcher's, so its value is the one that arrives");
		assertEquals("",
		             environment.get("HDSL_EMPTY"),
		             "a name typed with no value arrives as an empty variable rather than not at all");
		assertFalse(environment.containsKey("HDSL_COMMENTED"), "a commented-out line is text, not a variable");
		assertEquals(instance.homeDirectory().toString(),
		             environment.get("DSH_HOME"),
		             "and the instance's own home is what the child runs on");
	}

	@Test
	void anInstanceWithNoSetOfItsOwnRunsWithTheLaunchers() throws Exception {
		SettingsManager.settings()
		               .globalEnvironmentProperty()
		               .set(DshEnvironment.parse("HDSL_GLOBAL_ONLY=from the launcher"));

		DshInstance instance = makeInstance(Map.of());

		Map<String, String> environment = run(DshLauncher.plan(instance)).environment();

		assertEquals("from the launcher",
		             environment.get("HDSL_GLOBAL_ONLY"),
		             "an instance that adds nothing of its own still inherits the launcher's set");
	}

	@Test
	void aVariableNamedDshHomeInTheEnvironmentCannotMoveTheInstancesHome() throws Exception {
		Path decoy = folder.resolve("decoy-home");
		SettingsManager.settings()
		               .globalEnvironmentProperty()
		               .set(DshEnvironment.parse("DSH_HOME=" + decoy + "\nHDSL_ALONGSIDE=kept"));

		DshInstance instance = makeInstance(Map.of());

		Map<String, String> environment = run(DshLauncher.plan(instance)).environment();

		assertEquals(instance.homeDirectory().toString(),
		             environment.get("DSH_HOME"),
		             "the home this launch states is the one the child gets");
		assertNotEquals(decoy.toString(), environment.get("DSH_HOME"));
		assertEquals("kept",
		             environment.get("HDSL_ALONGSIDE"),
		             "only the reserved name is dropped; the rest of the set is the person's");
		assertFalse(Files.exists(decoy), "nothing created it: the child was simply told to use it");
	}

	@Test
	void theRefusalDoesNotCareAboutCaseOrWhichBoxItWasTypedIn() throws Exception {
		DshInstance instance = makeInstance(Map.of("dsh_home", "C:\\somewhere-else"));

		Map<String, String> environment = DshEnvironment.of(instance);

		assertTrue(environment.isEmpty(), "nothing of the instance's own set survives: " + environment);
		assertTrue(DshEnvironment.isReserved("DSH_HOME"));
		assertTrue(DshEnvironment.isReserved("dsh_home"));
		assertFalse(DshEnvironment.isReserved("DSH_HOME_BACKUP"),
		            "a name that merely starts the same way is the person's");
	}

	@Test
	void avalueKeepsItsOwnShapeAndNeverTakesTheRestOfTheSetWithIt() throws Exception {
		SettingsManager.settings().globalEnvironmentProperty().set(DshEnvironment.parse("""
				                                                                                HDSL_GOOD=kept
				                                                                                HDSL_WITH SPACE=also kept
				                                                                                HDSL_VALUED=a=b=c
				                                                                                """));

		DshInstance instance = makeInstance(Map.of("HDSL_INSTANCE_ONLY", "from the instance"));

		Map<String, String> environment = run(DshLauncher.plan(instance)).environment();

		assertEquals("kept", environment.get("HDSL_GOOD"));
		assertEquals("from the instance",
		             environment.get("HDSL_INSTANCE_ONLY"),
		             "the rest of the set arrives whatever the shapes of the other entries");
		assertEquals("a=b=c",
		             environment.get("HDSL_VALUED"),
		             "only the first `=` separates the name from the value, so the value keeps its own");
		assertEquals("also kept",
		             environment.get("HDSL_WITH SPACE"),
		             "Windows carries a name holding a space, and the child sees it by that name");
		assertFalse(environment.keySet().stream().anyMatch(name -> name.toUpperCase(Locale.ROOT).contains("COMMENT")),
		            "nothing but the variables themselves is in there");
	}

	private record Child(int exitCode, String output) {
		List<String> arguments() {
			List<String> arguments = new ArrayList<>();
			for (String line : lines()) {
				if (line.startsWith("ARG[")) {
					arguments.add(line.substring(line.indexOf("]=") + 2));
				}
			}
			return arguments;
		}

		Map<String, String> environment() {
			Map<String, String> values = new LinkedHashMap<>();
			for (String line : lines()) {
				if (line.startsWith("ENV ")) {
					String pair = line.substring(4);
					int equals = pair.indexOf('=');
					values.put(pair.substring(0, equals), pair.substring(equals + 1));
				}
			}
			return values;
		}

		private List<String> lines() {
			return List.of(output.replace("\r\n", "\n").split("\n"));
		}
	}
}
