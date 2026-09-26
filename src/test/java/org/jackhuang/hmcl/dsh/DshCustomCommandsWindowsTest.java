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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that the commands somebody asks to run around an instance really run on Windows.
///
/// What a person types into a command box is a **shell line** — pipes, redirections, `&&`, `%VAR%`
/// expansion, quoted paths with spaces — and the whole point of passing it to the system's own
/// interpreter rather than splitting it is that all of it keeps working. On Windows that
/// interpreter is `cmd.exe`, named by `ComSpec`, and every test here starts a real one and reads
/// what it did: a command's business is what it left on disk, not what a list in memory said.
///
/// The class is Windows-only because the language it proves the launcher speaks is `cmd.exe`'s:
/// `%VAR%` is not expansion on the shells of the other two systems, and a test asserting it there
/// would be a test about `/bin/sh`.
@EnabledOnOs(OS.WINDOWS)
class DshCustomCommandsWindowsTest {
    /// The instance these commands are about.
    private static final String INSTANCE_ID = "commands-windows";

    /// Where the instance's own directory is made, one per test.
    @TempDir
    Path folder;

    /// The directory the commands run in, one per test.
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

    /// The interpreter the launcher has to be handing the line to.
    ///
    /// Read the same way [DshCustomCommands#shellCommand] reads it, because the test is about what
    /// that method returns rather than about a `cmd.exe` of the test's own choosing.
    ///
    /// @return the interpreter's path or bare name
    private static String interpreter() {
        String interpreter = System.getenv("ComSpec");
        return interpreter == null || interpreter.isBlank() ? "cmd.exe" : interpreter;
    }

    /// Creates the instance the commands are run for.
    ///
    /// @param environment the variables the instance runs with
    /// @return the instance
    private DshInstance makeInstance(Map<String, String> environment) throws DshException {
        return DshInstanceManager.create(INSTANCE_ID, "1.2.3", DshInstance.DEFAULT_PROFILE, workspace,
                DshNodeRuntime.SYSTEM, DshHomeMode.ISOLATED, null, List.of(), environment, folder);
    }

    /// Runs a line the way the launcher runs it and returns everything it printed.
    ///
    /// @param instance the instance
    /// @param line     the shell line
    /// @return the output lines
    private static List<String> output(DshInstance instance, String line) throws DshException {
        List<String> lines = new java.util.ArrayList<>();
        DshCustomCommands.run(instance, line, "pre-launch", lines::add);
        return lines;
    }

    /// Asserts that some line of the output holds a piece of text.
    ///
    /// @param lines the output
    /// @param text  the text
    /// @param what  what it means
    private static void assertSays(List<String> lines, String text, String what) {
        assertTrue(lines.stream().anyMatch(line -> line.contains(text)),
                what + ": " + lines);
    }

    /// The line is handed to the system's own interpreter, and to a real one.
    ///
    /// `cmd.exe /d /s /c <line>` is the Windows spelling of `/bin/sh -c <line>`, and the three
    /// switches are what make the rest of these tests meaningful: `/d` skips the machine's own
    /// startup script so what runs is what was typed, `/s` takes the line after `/c` as it was
    /// typed however it is quoted, and `ComSpec` names the interpreter because that is where
    /// Windows itself records which one to use — it is not always at a fixed path.
    @Test
    void theSystemsOwnInterpreterIsWhatRunsTheLine() throws Exception {
        String line = "echo hello | findstr hello";
        String interpreter = interpreter();

        assertEquals(List.of(interpreter, "/d", "/s", "/c", line),
                DshCustomCommands.shellCommand(line),
                "the line goes to the interpreter as one argument, not split into program and flags");
        assertTrue(Files.isRegularFile(Path.of(interpreter)),
                "ComSpec names a real interpreter: " + interpreter);

        DshInstance instance = makeInstance(Map.of());
        assertSays(output(instance, line), "hello",
                "a pipe is the shell's business and the shell ran it");
    }

    /// Pipes, `&&`, redirection and quoted paths with spaces are all run as written.
    ///
    /// These are the four things a person loses the moment a launcher decides to split a command
    /// into a program and its arguments instead of handing it to a shell — which is why it does
    /// not, and why this is worth pinning on the platform whose quoting rules are its own.
    @Test
    void aLineWithPipesAndRedirectsAndQuotedPathsRunsAsWritten() throws Exception {
        DshInstance instance = makeInstance(Map.of());
        Path spaced = workspace.resolve("dir with space");
        Files.createDirectories(spaced);

        assertSays(output(instance, "echo one && echo two"), "two",
                "the second half of an `&&` chain runs when the first half succeeds");

        output(instance, "echo written> plain.txt");
        assertEquals("written", Files.readString(workspace.resolve("plain.txt")).trim(),
                "a redirection writes where it names, relative to the instance's workspace");

        output(instance, "echo spaced> \"dir with space\\out.txt\"");
        assertEquals("spaced", Files.readString(spaced.resolve("out.txt")).trim(),
                "and a quoted path with a space in it is one path, not two arguments");

        assertSays(output(instance, "dir /b \"dir with space\""), "out.txt",
                "the quoted path reaches the program inside the line with its space intact");
    }

    /// A command runs where the instance runs and sees what the instance runs with.
    ///
    /// This is what makes a command useful rather than decorative: a script of somebody's own that
    /// is meant to prepare a directory has to be looking at that directory, and one that is meant
    /// to talk to the instance has to be able to name it.
    @Test
    void aCommandRunsWhereTheInstanceRunsAndSeesItsVariables() throws Exception {
        DshInstance instance = makeInstance(Map.of("HDSL_COMMAND_VARIABLE", "from the instance"));

        assertSays(output(instance, "cd"), workspace.toString(),
                "the command's working directory is the instance's workspace");

        assertSays(output(instance, "echo %HDSL_COMMAND_VARIABLE%"), "from the instance",
                "a variable the instance runs with is expanded by the shell that runs the command");

        assertSays(output(instance, "echo %DSH_INSTANCE% %DSH_VERSION%"), INSTANCE_ID + " 1.2.3",
                "and the command is told which instance and which version it is about");

        assertSays(output(instance, "echo [%DSH_HOME%]"), instance.homeDirectory().toString(),
                "with the same home the instance itself runs on");
    }

    /// A command that fails before the launch stops the launch.
    ///
    /// A pre-launch command was asked to run first, and starting anyway would be ignoring what it
    /// was for. After the instance has ended there is nothing left to stop, which is why that form
    /// reports instead.
    @Test
    void aCommandThatExitsNonZeroStopsTheLaunch() throws Exception {
        DshInstance instance = makeInstance(Map.of());

        DshException refused = assertThrows(DshException.class,
                () -> DshCustomCommands.run(instance, "exit /b 7", "pre-launch", null));
        assertTrue(refused.getMessage().contains("7"), refused.getMessage());
        assertTrue(refused.getMessage().contains("pre-launch"), refused.getMessage());

        // A line that is not a command at all fails the same way, and says which code it failed with.
        DshException unknown = assertThrows(DshException.class,
                () -> DshCustomCommands.run(instance, "no-such-command-hdsl", "pre-launch", null));
        assertTrue(unknown.getMessage().contains("1"), unknown.getMessage());

        assertDoesNotThrow(() -> DshCustomCommands.runQuietly(instance, "exit /b 7", "post-exit", null),
                "after an instance has ended a failing command is something to report, not to raise");
        assertDoesNotThrow(() -> DshCustomCommands.run(instance, "   ", "pre-launch", null),
                "and no command at all is a no-op rather than an empty line to the shell");
    }

    /// A `DSH_HOME` typed into the environment does **not** move the home a command runs on.
    ///
    /// This used to be the opposite, and it is the same defect the launch plan had, in the place
    /// where it does the most damage: a command is given the instance's environment so that it sees
    /// what the instance would, and [DshCustomCommands#run] stated the instance's own home and then
    /// laid that environment over it. A command written to prepare or back up "the instance's home"
    /// therefore operated on another directory, and nothing said so. Both now read the environment
    /// through [DshEnvironment#of], which refuses the name.
    ///
    /// @throws Exception when the instance cannot be made
    @Test
    void aVariableNamedDshHomeInTheEnvironmentCannotMoveTheHomeACommandRunsOn() throws Exception {
        Path decoy = folder.resolve("decoy-home");
        var settings = SettingsManager.settings();
        Map<String, String> before = Map.copyOf(settings.globalEnvironment());
        try {
            settings.globalEnvironmentProperty()
                    .set(DshEnvironment.parse("DSH_HOME=" + decoy + "\nHDSL_COMMAND_SIDE=kept"));

            DshInstance instance = makeInstance(Map.of());

            assertSays(output(instance, "echo [%DSH_HOME%]"),
                    "[" + instance.homeDirectory() + "]",
                    "the command works on the instance's own home, whatever a box says");
            assertSays(output(instance, "echo [%HDSL_COMMAND_SIDE%]"), "[kept]",
                    "only the reserved name is dropped");
        } finally {
            settings.globalEnvironmentProperty().set(before);
        }
    }

    /// An instance's own command wins over the launcher's, and going back hands it over again.
    ///
    /// The command is stored beside the instance, so it travels with the instance's folder; the
    /// launcher's own two fields are what every instance without one runs. What the settings
    /// answer with is what actually runs — which is the join this test makes: the answer comes
    /// from the same `preLaunchCommandFor` the launch calls, and the line it returns is run here.
    @Test
    void anInstancesOwnCommandWinsOverTheLaunchers() throws Exception {
        DshInstance instance = makeInstance(Map.of());
        var settings = SettingsManager.settings();
        String launcherPre = settings.preLaunchCommandProperty().get();
        String launcherPost = settings.postExitCommandProperty().get();
        try {
            settings.preLaunchCommandProperty().set("echo launcher-wide");
            settings.postExitCommandProperty().set("echo launcher-wide-exit");

            assertEquals("echo launcher-wide", settings.preLaunchCommandFor(INSTANCE_ID),
                    "with nothing of its own, the launcher's command applies");
            assertEquals("echo launcher-wide-exit", settings.postExitCommandFor(INSTANCE_ID));

            DshInstanceSettings.setPreLaunchCommand(instance, "echo instance own");
            DshInstanceSettings.setPostExitCommand(instance, "echo instance exit");

            assertEquals("echo instance own", settings.preLaunchCommandFor(INSTANCE_ID),
                    "an instance's own command is the one the launch is given");
            assertEquals("echo instance exit", settings.postExitCommandFor(INSTANCE_ID));

            assertSays(output(instance, settings.preLaunchCommandFor(INSTANCE_ID)), "instance own",
                    "and it is a line the shell really runs");

            DshInstanceSettings.setPreLaunchCommand(instance, null);
            assertEquals("echo launcher-wide", settings.preLaunchCommandFor(INSTANCE_ID),
                    "removing it hands the instance back to the launcher's command");
        } finally {
            settings.preLaunchCommandProperty().set(launcherPre);
            settings.postExitCommandProperty().set(launcherPost);
        }
    }
}
