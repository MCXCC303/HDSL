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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the arguments somebody typed for an instance reach the child process on Windows.
 */
class DshLaunchArgumentsWindowsTest {
	private static final String INSTANCE_ID = "arguments-windows";
	private static final String BARE_PROFILE = "acp";

	@TempDir
	Path folder;

	@TempDir
	Path workspace;

	private Supplier<List<Path>> previousFolders;

	private static void requireNode() {
		Assumptions.assumeTrue(DshNodeRuntime.detect().map(DshNodeRuntime::isNodeSupported).orElse(false),
		                       "Node.js ^22.19.0 || >=24.0.0 is not on PATH; nothing can be started without it");
	}

	private static void installProbeSurface(DshInstance instance) throws Exception {
		Path script = instance.dshEntryPoint();
		Files.createDirectories(script.getParent());
		Files.writeString(script, """
				const args = process.argv.slice(2);
				console.log('ARGC=' + args.length);
				args.forEach((argument, index) => console.log('ARG[' + index + ']=' + argument));
				Object.keys(process.env)
				      .filter(name => /^(DSH|HDSL)_/i.test(name))
				      .sort()
				      .forEach(name => console.log('ENV ' + name.toUpperCase() + '=' + process.env[name]));
				console.log('--no-open');
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
	}

	@AfterEach
	void putItBack() {
		InstanceFolders.setFolders(previousFolders);
	}

	private DshInstance makeInstance(String profile, String typed) throws Exception {
		requireNode();
		DshInstance instance = DshInstanceManager.create(INSTANCE_ID,
		                                                 "1.2.3",
		                                                 profile,
		                                                 workspace,
		                                                 DshNodeRuntime.SYSTEM,
		                                                 DshHomeMode.ISOLATED,
		                                                 null,
		                                                 DshLaunchArguments.tokenize(typed),
		                                                 Map.of(),
		                                                 folder);
		installProbeSurface(instance);
		return instance;
	}

	@Test
	void theLineSomebodyTypedArrivesAsThoseArguments() throws Exception {
		String typed = "--profile=rescue --from-default-profile web --trusted-host \"my host\" --no-open";
		DshInstance instance = makeInstance(BARE_PROFILE, typed);

		assertEquals(List.of("--profile=rescue",
		                     "--from-default-profile",
		                     "web",
		                     "--trusted-host",
		                     "my host",
		                     "--no-open"), instance.extraArguments(), "the line is stored as the arguments it names");

		Child child = run(DshLauncher.plan(instance));

		assertEquals(0, child.exitCode(), child.output());
		assertEquals(child.argumentCount(),
		             child.arguments().size(),
		             "every argument the child counted is one it can name");
		assertEquals(List.of("--profile=rescue",
		                     "--from-default-profile",
		                     "web",
		                     "--profile",
		                     "rescue",
		                     "--trusted-host",
		                     "my host",
		                     "--no-open"),
		             child.arguments(),
		             "the child's own `process.argv`: the typed line, then the profile the launcher states" +
		             " for it, and the value with a space as one argument");
	}

	@Test
	void aDoubleQuotedWindowsPathArrivesIntact() throws Exception {
		String typed = "--patch \"C:\\Users\\me\\my patches\\a.yml\"";
		DshInstance instance = makeInstance(BARE_PROFILE, typed);

		assertEquals(List.of("--patch", "C:\\Users\\me\\my patches\\a.yml"),
		             DshLaunchArguments.tokenize(typed),
		             "every separator survives the tokeniser, inside double quotes too");

		List<String> arguments = run(DshLauncher.plan(instance)).arguments();

		assertTrue(arguments.contains("C:\\Users\\me\\my patches\\a.yml"),
		           "the child is given the path as typed, as one argument: " + arguments);
	}

	@Test
	void aSingleQuotedWindowsPathArrivesIntact() throws Exception {
		String typed = "--patch 'C:\\Users\\me\\my patches\\a.yml'";
		DshInstance instance = makeInstance(BARE_PROFILE, typed);

		assertEquals(List.of("--patch", "C:\\Users\\me\\my patches\\a.yml"), DshLaunchArguments.tokenize(typed));

		List<String> arguments = run(DshLauncher.plan(instance)).arguments();

		assertTrue(arguments.contains("C:\\Users\\me\\my patches\\a.yml"),
		           "the path arrives whole, separators and space together: " + arguments);
	}

	@Test
	void aBackslashOutsideQuotesStillEscapes() {
		assertEquals(List.of("one two"), DshLaunchArguments.tokenize("one\\ two"));
		assertEquals(List.of("say \"hi\""), DshLaunchArguments.tokenize("\"say \\\"hi\\\"\""));
		assertEquals(List.of("\\server\\share"),
		             DshLaunchArguments.tokenize("\"\\\\server\\share\""),
		             "a doubled separator inside double quotes is one escaped separator, so a UNC path " +
		             "needs the single-quoted spelling");
		assertEquals(List.of("\\\\server\\share"),
		             DshLaunchArguments.tokenize("'\\\\server\\share'"),
		             "and in single quotes it is literal, which is the spelling a UNC path survives in");
	}

	@Test
	void aRefusedArgumentNeverReachesTheChild() throws Exception {
		String typed = "--port=1234 DSH_HOME=C:\\evil --no-open";
		DshInstance instance = makeInstance(BARE_PROFILE, typed);

		DshLaunchArguments.Parsed parsed = DshLaunchArguments.parseArguments(instance.extraArguments());
		assertEquals(2, parsed.refusals().size(), parsed.refusals().toString());

		Child child = run(DshLauncher.plan(instance));

		assertEquals(List.of("--profile", "acp", "--no-open"),
		             child.arguments(),
		             "only what was asked for in the app's own terms reaches the child: " + child.arguments());
		assertEquals(instance.homeDirectory().toString(),
		             child.environment().get("DSH_HOME"),
		             "and the home the child runs on is the instance's own, not the one that was typed");
		assertFalse(child.arguments()
		                 .stream()
		                 .anyMatch(argument -> argument.contains("1234") ||
		                                       argument.toLowerCase(Locale.ROOT).contains("evil")),
		            "neither the port nor the home assignment is anywhere on the child's command line");
	}

	@Test
	void theFlagsTheLauncherStatesForTheSurfaceReachTheChild() throws Exception {
		DshInstance instance = makeInstance(DshInstance.DEFAULT_PROFILE, null);

		DshLauncher.LaunchPlan plan = DshLauncher.plan(instance);
		Child child = run(plan);

		assertTrue(plan.port() > 0, "a web instance is launched on a port the launcher chose");
		assertEquals(instance.portOrDefault(),
		             plan.port(),
		             "and that port is the instance's own, so its state stays under one origin");
		assertEquals(List.of("--profile", "web", "--port", Integer.toString(plan.port()), "--no-open"),
		             child.arguments(),
		             "the child's own `process.argv` carries the profile, the port and the flag");
	}

	private record Child(int exitCode, String output) {
		int argumentCount() {
			for (String line : lines()) {
				if (line.startsWith("ARGC=")) {
					return Integer.parseInt(line.substring(5));
				}
			}
			throw new AssertionError("the child printed no argument count: " + output);
		}

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
