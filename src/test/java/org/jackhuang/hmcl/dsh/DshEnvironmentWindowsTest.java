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

import org.jackhuang.hmcl.setting.SettingsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that the environment the launcher is configured with really arrives in a child process
/// on Windows.
///
/// A unit test that reads [DshLauncher#plan]'s map back answers a different question than the one
/// that matters: what a person configures is an environment for a *process*, and the things that go
/// wrong between the two are the platform's — a variable name that Windows will not carry, a value
/// that arrives empty, a home that is quietly moved. So the child here is a real `node` started the
/// way [DshProcess]'s own constructor starts one, running a stub that prints the environment it
/// actually has, and every assertion is made against what that process printed about itself.
///
/// The stub takes the place of DeepSeek Harness at the instance's own entry point, so the plan is
/// built by the real code: the same [DshLauncher#plan] the launcher calls, with the same instance,
/// the same settings, and the same merge order.
class DshEnvironmentWindowsTest {
    /// The instance these tests make and launch.
    private static final String INSTANCE_ID = "environment-windows";

    /// The profile the instance boots.
    ///
    /// The ACP surface adds no flags of its own, so what the child receives is only what the plan
    /// assembles — no port reservation and no capability probe in the way of the environment.
    private static final String PROFILE = "acp";

    /// Where the instance's own directory is made, one per test.
    @TempDir
    Path folder;

    /// The instance's workspace, one per test.
    @TempDir
    Path workspace;

    /// The folders the instance manager was pointed at before this test replaced them.
    private Supplier<List<Path>> previousFolders;

    /// The launcher-wide environment that was in place before this test replaced it.
    private Map<String, String> previousGlobalEnvironment;

    /// Points the manager at this test's own folder and remembers the launcher's environment.
    @BeforeEach
    void makeItIsolate() {
        previousFolders = DshInstanceManager.setFolders(() -> List.of(folder));
        previousGlobalEnvironment = Map.copyOf(SettingsManager.settings().globalEnvironment());
    }

    /// Puts the launcher's environment and folders back, so one test does not decide the next.
    @AfterEach
    void putItBack() {
        SettingsManager.settings().globalEnvironmentProperty().set(previousGlobalEnvironment);
        DshInstanceManager.setFolders(previousFolders);
    }

    /// Skips a test when the launcher has no Node.js it may run the stub with.
    ///
    /// The version range is part of the gate: a machine may hold a Node the launcher itself would
    /// refuse, and refusing it is the launcher's own tested behaviour.
    private static void requireNode() {
        Assumptions.assumeTrue(
                DshNodeRuntime.detect().map(DshNodeRuntime::isNodeSupported).orElse(false),
                "Node.js ^22.19.0 || >=24.0.0 is not on PATH; nothing can be started without it");
    }

    /// Creates an instance whose entry point is a stub that reports what it was given.
    ///
    /// @param environment the environment the instance is given
    /// @return the instance
    private DshInstance makeInstance(Map<String, String> environment) throws Exception {
        requireNode();
        DshInstance instance = DshInstanceManager.create(INSTANCE_ID, "1.2.3", PROFILE, workspace,
                DshNodeRuntime.SYSTEM, DshHomeMode.ISOLATED, null, List.of(), environment, folder);
        installProbeSurface(instance);
        return instance;
    }

    /// Writes the stub that stands in for DeepSeek Harness.
    ///
    /// It prints, on one line each, the arguments it was given and every `DSH_`/`HDSL_` variable it
    /// can see — so what the assertions read is the child's own view of its environment, not the
    /// map the launcher passed to `ProcessBuilder`. It also answers the help request the launcher
    /// makes about `--no-open`, which is what lets a real plan be built for a web instance.
    ///
    /// @param instance the instance to install it into
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

    /// Runs a plan's own command in a real child and returns what the child said.
    ///
    /// The three statements are the ones `DshProcess`'s constructor performs — the same command
    /// list, the same working directory, the same environment — so what is observed here is what a
    /// launch observes.
    ///
    /// @param plan the plan
    /// @return the child's exit code and output
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

    /// What a real child process printed about itself.
    ///
    /// @param exitCode the child's exit code
    /// @param output   everything the child printed
    private record Child(int exitCode, String output) {
        /// Returns the arguments the child was given, in order, without the runtime and the script.
        ///
        /// @return the arguments
        List<String> arguments() {
            List<String> arguments = new ArrayList<>();
            for (String line : lines()) {
                if (line.startsWith("ARG[")) {
                    arguments.add(line.substring(line.indexOf("]=") + 2));
                }
            }
            return arguments;
        }

        /// Returns the child's own environment, as the child sees it.
        ///
        /// @return the variables whose names begin with `DSH_` or `HDSL_`
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

        /// Returns the child's output as lines, without any carriage return.
        ///
        /// @return the lines
        private List<String> lines() {
            return List.of(output.replace("\r\n", "\n").split("\n"));
        }
    }

    /// The launcher-wide set and an instance's own set are one environment in the child, with the
    /// instance's value winning where both name the same variable.
    ///
    /// The launcher-wide text is put through [DshEnvironment#parse] because that is what the
    /// interface does with what somebody types into the box — comments and blank lines and all — so
    /// this is the whole way from the text field to the child process.
    @Test
    void theLauncherWideSetAndTheInstancesOwnBothReachTheChild() throws Exception {
        SettingsManager.settings().globalEnvironmentProperty().set(DshEnvironment.parse("""
                # HDSL_COMMENTED=a commented-out line is not a variable
                HDSL_GLOBAL_ONLY=from the launcher
                HDSL_SHARED=from the launcher

                HDSL_EMPTY=
                """));

        DshInstance instance = makeInstance(Map.of(
                "HDSL_INSTANCE_ONLY", "from the instance",
                "HDSL_SHARED", "from the instance"));

        Child child = run(DshLauncher.plan(instance));
        Map<String, String> environment = child.environment();

        assertEquals(0, child.exitCode(), child.output());
        assertEquals("from the launcher", environment.get("HDSL_GLOBAL_ONLY"),
                "a variable the launcher is set to has to be in the child's own environment");
        assertEquals("from the instance", environment.get("HDSL_INSTANCE_ONLY"),
                "and so does one the instance names for itself");
        assertEquals("from the instance", environment.get("HDSL_SHARED"),
                "the instance's set is laid over the launcher's, so its value is the one that arrives");
        assertEquals("", environment.get("HDSL_EMPTY"),
                "a name typed with no value arrives as an empty variable rather than not at all");
        assertFalse(environment.containsKey("HDSL_COMMENTED"),
                "a commented-out line is text, not a variable");
        assertEquals(instance.homeDirectory().toString(), environment.get("DSH_HOME"),
                "and the instance's own home is what the child runs on");
    }

    /// An instance that adds nothing of its own runs with the launcher's set.
    ///
    /// This is what "follow the launcher" has to mean for the environment: an instance whose own
    /// map is empty must not wipe the launcher's by being laid over it.
    @Test
    void anInstanceWithNoSetOfItsOwnRunsWithTheLaunchers() throws Exception {
        SettingsManager.settings().globalEnvironmentProperty()
                .set(DshEnvironment.parse("HDSL_GLOBAL_ONLY=from the launcher"));

        DshInstance instance = makeInstance(Map.of());

        Map<String, String> environment = run(DshLauncher.plan(instance)).environment();

        assertEquals("from the launcher", environment.get("HDSL_GLOBAL_ONLY"),
                "an instance that adds nothing of its own still inherits the launcher's set");
    }

    /// A `DSH_HOME` typed into an environment box does **not** move the instance's home.
    ///
    /// This used to be the opposite, and it is the reason [DshEnvironment#of] refuses the name:
    /// [DshLauncher#plan] stated the instance's own home and then laid the user's environment over
    /// it, so a `DSH_HOME=...` line typed into either environment box reached the child and
    /// overrode the home the launcher had resolved. The home is how one instance's profile,
    /// credentials and sessions are kept away from another's, which is why
    /// [DshLaunchArguments] refuses the same variable when it arrives as a launch argument — and
    /// the two doors cannot disagree about what belongs to the launcher.
    ///
    /// Both boxes are reachable from the interface with exactly this text: `InstanceDefaultsPage`
    /// and `InstanceSettingsPage` store `DshEnvironment.parse(...)` without filtering anything out,
    /// so the refusal has to be here, where the two of them meet.
    ///
    /// @throws Exception when the instance cannot be made or the child cannot be run
    @Test
    void aVariableNamedDshHomeInTheEnvironmentCannotMoveTheInstancesHome() throws Exception {
        Path decoy = folder.resolve("decoy-home");
        SettingsManager.settings().globalEnvironmentProperty()
                .set(DshEnvironment.parse("DSH_HOME=" + decoy + "\nHDSL_ALONGSIDE=kept"));

        DshInstance instance = makeInstance(Map.of());

        Map<String, String> environment = run(DshLauncher.plan(instance)).environment();

        assertEquals(instance.homeDirectory().toString(), environment.get("DSH_HOME"),
                "the home this launch states is the one the child gets");
        assertNotEquals(decoy.toString(), environment.get("DSH_HOME"));
        assertEquals("kept", environment.get("HDSL_ALONGSIDE"),
                "only the reserved name is dropped; the rest of the set is the person's");
        assertFalse(Files.exists(decoy), "nothing created it: the child was simply told to use it");
    }

    /// The refused name is refused whatever case it is written in, and from the instance too.
    ///
    /// Windows treats an environment name without regard to case, so `dsh_home` is a second spelling
    /// of the variable the launcher states — and it would win on the machine this launcher is used
    /// on most. Both boxes are checked, because both reach the same place.
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

    /// The shapes a value can take are carried as they were written.
    ///
    /// A Windows environment value is free text, so a `=` inside it, a space in the name and a
    /// value that ends in one are all things somebody can type into the box. What must not happen
    /// is one of them taking the rest of the set down with it: the instance would start with none
    /// of its own settings and nothing would say so.
    ///
    /// @throws Exception when the instance cannot be made or the child cannot be run
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
        assertEquals("from the instance", environment.get("HDSL_INSTANCE_ONLY"),
                "the rest of the set arrives whatever the shapes of the other entries");
        assertEquals("a=b=c", environment.get("HDSL_VALUED"),
                "only the first `=` separates the name from the value, so the value keeps its own");
        assertEquals("also kept", environment.get("HDSL_WITH SPACE"),
                "Windows carries a name holding a space, and the child sees it by that name");
        assertFalse(environment.keySet().stream()
                        .anyMatch(name -> name.toUpperCase(Locale.ROOT).contains("COMMENT")),
                "nothing but the variables themselves is in there");
    }
}
