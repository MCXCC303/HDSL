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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that the arguments somebody typed for an instance reach the child process on Windows.
///
/// A command line is where a Windows port goes wrong quietly: Java builds the raw line the child
/// receives out of the argument list, quoting what needs quoting, and the child's own parser then
/// splits it again. Anything that does not survive that round trip — a value with a space in it, an
/// `--flag=value` pair, a quoted path, a path separator — is a launch that starts the wrong thing
/// without anything saying so. So the child here is a real `node` started the way
/// [DshProcess]'s own constructor starts one, and the assertions are made against the argument list
/// that process printed from `process.argv`.
class DshLaunchArgumentsWindowsTest {
    /// The instance these tests make and launch.
    private static final String INSTANCE_ID = "arguments-windows";

    /// A profile whose surface adds no flags of its own.
    ///
    /// What the child receives is then only what the plan assembles from the typed line, with no
    /// port and no `--no-open` in the way of reading it.
    private static final String BARE_PROFILE = "acp";

    /// Where the instance's own directory is made, one per test.
    @TempDir
    Path folder;

    /// The instance's workspace, one per test.
    @TempDir
    Path workspace;

    /// The folders the instance manager was pointed at before this test replaced them.
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
    /// The line is stored through [DshLaunchArguments#tokenize] because that is what the interface
    /// does with the text somebody types into the field — `InstanceSettingsPage` writes
    /// `instance.withLaunchOptions(DshLaunchArguments.tokenize(text), ...)` — so the instance under
    /// test was configured the way a person configures one.
    ///
    /// @param profile     the profile to boot
    /// @param typed       the line somebody typed, or `null` for none
    /// @return the instance
    private DshInstance makeInstance(String profile, String typed) throws Exception {
        requireNode();
        DshInstance instance = DshInstanceManager.create(INSTANCE_ID, "1.2.3", profile, workspace,
                DshNodeRuntime.SYSTEM, DshHomeMode.ISOLATED, null,
                DshLaunchArguments.tokenize(typed), Map.of(), folder);
        installProbeSurface(instance);
        return instance;
    }

    /// Writes the stub that stands in for DeepSeek Harness.
    ///
    /// It prints the arguments it was given, one per line, from `process.argv` — the child's own
    /// view of its command line after Windows has handed it over and Node has split it again — and
    /// the `DSH_`/`HDSL_` variables it can see, so a refusal can be shown to have teeth. It also
    /// answers the help request the launcher makes about `--no-open`, which is what lets a real plan
    /// be built for a web instance.
    ///
    /// @param instance the instance to install it into
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

    /// Runs a plan's own command in a real child and returns what the child said.
    ///
    /// The three statements are the ones `DshProcess`'s constructor performs — the same command
    /// list, the same working directory, the same environment.
    ///
    /// @param plan the plan
    /// @return the child's exit code, argument count, arguments and output
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

    /// What a real child process printed about the arguments it was given.
    ///
    /// @param exitCode the child's exit code
    /// @param output   everything the child printed
    private record Child(int exitCode, String output) {
        /// Returns the number of arguments the child counted.
        ///
        /// @return the count the child printed
        int argumentCount() {
            for (String line : lines()) {
                if (line.startsWith("ARGC=")) {
                    return Integer.parseInt(line.substring(5));
                }
            }
            throw new AssertionError("the child printed no argument count: " + output);
        }

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
        /// @return the variables whose names begin with `DSH_`
        Map<String, String> environment() {
            Map<String, String> values = new java.util.LinkedHashMap<>();
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

    /// The line somebody typed arrives as those arguments, in that order, and as that many.
    ///
    /// `--profile=rescue` is the flag-with-its-value spelling; `--from-default-profile web` is a
    /// launcher flag whose value has to stay beside it; `"my host"` is a value holding a space,
    /// which must arrive as **one** argument rather than two. The profile the line names is stated
    /// last by the launcher, after the user's own flags, so that a profile they named wins.
    @Test
    void theLineSomebodyTypedArrivesAsThoseArguments() throws Exception {
        String typed = "--profile=rescue --from-default-profile web --trusted-host \"my host\" --no-open";
        DshInstance instance = makeInstance(BARE_PROFILE, typed);

        assertEquals(List.of("--profile=rescue", "--from-default-profile", "web",
                        "--trusted-host", "my host", "--no-open"),
                instance.extraArguments(), "the line is stored as the arguments it names");

        Child child = run(DshLauncher.plan(instance));

        assertEquals(0, child.exitCode(), child.output());
        assertEquals(child.argumentCount(), child.arguments().size(),
                "every argument the child counted is one it can name");
        assertEquals(List.of("--profile=rescue", "--from-default-profile", "web",
                        "--profile", "rescue", "--trusted-host", "my host", "--no-open"),
                child.arguments(),
                "the child's own `process.argv`: the typed line, then the profile the launcher states"
                        + " for it, and the value with a space as one argument");
    }

    /// A quoted Windows path arrives whole, separators and all.
    ///
    /// This used to be the opposite, and the name said so: the tokeniser removed a backslash that
    /// was not inside single quotes — `` `\x` `` became `x` — which is what `sh` does and is
    /// harmless on the two systems whose paths are separated by `/`. Windows separates its paths
    /// with that character, so the shape a person naturally types first,
    /// `--patch "C:\Users\me\my patches\a.yml"`, reached the child as `C:Usersmemy patchesa.yml`:
    /// the quotes grouped the path correctly and the path was destroyed inside them. Nothing
    /// reported it — the argument was neither refused nor empty — so the launch failed later inside
    /// the harness, which is the expensive place to find out.
    ///
    /// The rule now is a shell's own, applied inside double quotes as well: a backslash escapes
    /// only `"` and `\` there, so a separator survives. The child is a real process, and the
    /// argument asserted below is the one it printed from its own `process.argv`.
    ///
    /// @throws Exception when the instance cannot be made or the child cannot be run
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

    /// The two spellings of a Windows path agree, and both are what was typed.
    ///
    /// The single-quoted spelling is the one that always worked; the point of asserting them
    /// together is that a person should not have to know which spelling survives — the interface
    /// says nothing about it, and the answer is now "either".
    @Test
    void aSingleQuotedWindowsPathArrivesIntact() throws Exception {
        String typed = "--patch 'C:\\Users\\me\\my patches\\a.yml'";
        DshInstance instance = makeInstance(BARE_PROFILE, typed);

        assertEquals(List.of("--patch", "C:\\Users\\me\\my patches\\a.yml"),
                DshLaunchArguments.tokenize(typed));

        List<String> arguments = run(DshLauncher.plan(instance)).arguments();

        assertTrue(arguments.contains("C:\\Users\\me\\my patches\\a.yml"),
                "the path arrives whole, separators and space together: " + arguments);
    }

    /// A backslash outside quotes still escapes, so the old rule is not simply gone.
    ///
    /// The fix is about which character a backslash may escape inside which quoting, not about
    /// dropping escaping: `a\ b` is still one argument with a space in it, and `\"` inside double
    /// quotes is still a quotation mark rather than a quote character.
    ///
    /// **The one shape this rule does not serve is a UNC path**, and it is pinned here rather than
    /// left to be discovered: `\\` inside double quotes is one escaped separator, so
    /// `"\\server\share"` arrives as `\server\share` — a single leading separator, which is not the
    /// path that was typed. A UNC path has to be written with single quotes (`'\\server\share'`,
    /// where a backslash is literal), which is what
    /// [DshLaunchArgumentsWindowsTest#aSingleQuotedWindowsPathArrivesIntact] covers. The same note
    /// belongs in the launcher's own documentation of the row.
    @Test
    void aBackslashOutsideQuotesStillEscapes() {
        assertEquals(List.of("one two"), DshLaunchArguments.tokenize("one\\ two"));
        assertEquals(List.of("say \"hi\""), DshLaunchArguments.tokenize("\"say \\\"hi\\\"\""));
        assertEquals(List.of("\\server\\share"), DshLaunchArguments.tokenize("\"\\\\server\\share\""),
                "a doubled separator inside double quotes is one escaped separator, so a UNC path "
                        + "needs the single-quoted spelling");
        assertEquals(List.of("\\\\server\\share"), DshLaunchArguments.tokenize("'\\\\server\\share'"),
                "and in single quotes it is literal, which is the spelling a UNC path survives in");
    }

    /// An argument the launcher states for itself is reported, and never reaches the child.
    ///
    /// `--port` is part of an instance's identity — the browser interface keys its stored state by
    /// origin — and `DSH_HOME` is how one instance's state is kept away from another's. Both
    /// spellings of the port are refused, and the refusal has teeth: what the child is given is the
    /// launcher's port policy and the instance's own home, not what somebody typed.
    @Test
    void aRefusedArgumentNeverReachesTheChild() throws Exception {
        String typed = "--port=1234 DSH_HOME=C:\\evil --no-open";
        DshInstance instance = makeInstance(BARE_PROFILE, typed);

        DshLaunchArguments.Parsed parsed = DshLaunchArguments.parseArguments(instance.extraArguments());
        assertEquals(2, parsed.refusals().size(), parsed.refusals().toString());

        Child child = run(DshLauncher.plan(instance));

        assertEquals(List.of("--profile", "acp", "--no-open"), child.arguments(),
                "only what was asked for in the app's own terms reaches the child: " + child.arguments());
        assertEquals(instance.homeDirectory().toString(), child.environment().get("DSH_HOME"),
                "and the home the child runs on is the instance's own, not the one that was typed");
        assertFalse(child.arguments().stream()
                        .anyMatch(argument -> argument.contains("1234")
                                || argument.toLowerCase(Locale.ROOT).contains("evil")),
                "neither the port nor the home assignment is anywhere on the child's command line");
    }

    /// The flags the launcher states for the browser surface reach the child too.
    ///
    /// A web instance is the shape most instances are: the launcher states its port — chosen once,
    /// because the interface's state is keyed by it — and asks the version whether it knows
    /// `--no-open` before passing it. Both end up on the child's own command line, which is what
    /// this reads back from a real process.
    @Test
    void theFlagsTheLauncherStatesForTheSurfaceReachTheChild() throws Exception {
        DshInstance instance = makeInstance(DshInstance.DEFAULT_PROFILE, null);

        DshLauncher.LaunchPlan plan = DshLauncher.plan(instance);
        Child child = run(plan);

        assertTrue(plan.port() > 0, "a web instance is launched on a port the launcher chose");
        assertEquals(instance.portOrDefault(), plan.port(),
                "and that port is the instance's own, so its state stays under one origin");
        assertEquals(List.of("--profile", "web", "--port", Integer.toString(plan.port()), "--no-open"),
                child.arguments(),
                "the child's own `process.argv` carries the profile, the port and the flag");
    }
}
