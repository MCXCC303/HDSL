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

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The shell a terminal is opened with: what it exports, and in what order.
///
/// The assertions are the same on every platform and only their spelling
/// follows the platform's own script, so the suite says the same thing
/// wherever it runs.
class DshInstanceTerminalTest {
    @TempDir
    private Path home;

    /// Whether the scripts of this platform are the Windows ones.
    private static final boolean WINDOWS = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS;

    /// How this platform separates PATH entries.
    private static final String SEPARATOR = WINDOWS ? ";" : ":";

    private DshInstance instance(Map<String, String> environment) {
        return new DshInstance("test", "0.1.6-alpha.2", "web", home.resolve("work").toString(),
                "system", DshHomeMode.CUSTOM, home.resolve("dsh-home").toString(),
                List.of(), environment, null, null, null, 0, 0L);
    }

    private static DshNodeRuntime runtime(Path bin) {
        return new DshNodeRuntime(bin.resolve(WINDOWS ? "node.exe" : "node"), "22.19.0",
                bin.resolve(WINDOWS ? "npm.cmd" : "npm"), "10.9.0", null, null);
    }

    /// How this platform's script assigns a variable.
    private static String assignment(String name, String value) {
        return WINDOWS
                ? "set \"" + name + "=" + value + "\""
                : "export " + name + "='" + value + "'";
    }

    @Test
    void quotesAValueForAShell() {
        assertTrue("'plain'".equals(DshInstanceTerminal.quote("plain")));
        assertTrue("'it'\\''s'".equals(DshInstanceTerminal.quote("it's")), DshInstanceTerminal.quote("it's"));
    }

    @Test
    void theScriptStandsWhereTheInstanceStands() throws Exception {
        Path bin = Files.createDirectories(home.resolve("node").resolve("bin"));
        String script = DshInstanceTerminal.scriptText(instance(Map.of()), null, runtime(bin));

        assertTrue(script.contains(WINDOWS
                ? "cd /d \"" + DshPaths.instanceDirectory("test") + "\""
                : "cd '" + DshPaths.instanceDirectory("test") + "'"), script);
        assertTrue(script.contains(assignment("DSH_HOME", home.resolve("dsh-home").toString())), script);
        // The instance's own tools first, and its dsh shim before even those.
        // The PATH line is taken apart rather than looked for as a whole: what
        // follows the instance's own entries is the machine's business — an
        // empty inheritance, or one whose own quoting differs — so the
        // assertion names the two entries that have to lead and lets the rest
        // be whatever it is.
        String pathAssignment = WINDOWS ? "set \"PATH=" : "export PATH='";
        String pathLine = script.lines().filter(line -> line.startsWith(pathAssignment))
                .findFirst().orElse("");
        assertFalse(pathLine.isEmpty(), script);
        List<String> path = List.of(pathLine
                .substring(pathAssignment.length(), pathLine.length() - 1)
                .split(WINDOWS ? ";" : ":"));
        String shim = DshInstanceTerminal.directory().resolve("test").resolve("bin").toString();
        assertEquals(shim, path.get(0), pathLine);
        assertEquals(bin.toString(), path.get(1), pathLine);
        // A session with no account carries no key.
        assertFalse(script.contains(DshAccountOverlay.KEY_ENVIRONMENT_VARIABLE), script);
    }

    @Test
    void theAccountKeyTravelsWithTheSession() throws Exception {
        Path bin = Files.createDirectories(home.resolve("node").resolve("bin"));
        DshAccount account = new DshAccount("deepseek", "sk-test", null, null);
        String script = DshInstanceTerminal.scriptText(instance(Map.of()), account, runtime(bin));

        assertTrue(script.contains(assignment(DshAccountOverlay.KEY_ENVIRONMENT_VARIABLE, "sk-test")),
                script);
    }

    @Test
    void aNameAShellCannotHoldIsLeftOut() throws Exception {
        Path bin = Files.createDirectories(home.resolve("node").resolve("bin"));
        String script = DshInstanceTerminal.scriptText(
                instance(Map.of("GOOD_NAME", "yes", "not a name", "no")), null, runtime(bin));

        assertTrue(script.contains(assignment("GOOD_NAME", "yes")), script);
        assertFalse(script.contains("not a name"), script);
    }

    @Test
    void theShimRunsTheInstancesOwnEntryScript() throws Exception {
        Path bin = Files.createDirectories(home.resolve("node").resolve("bin"));
        DshInstance instance = instance(Map.of());
        String shim = DshInstanceTerminal.shimText(instance, runtime(bin));

        if (WINDOWS) {
            assertTrue(shim.contains("\"" + bin.resolve("node.exe") + "\" \""
                    + instance.dshEntryPoint() + "\""), shim);
            assertTrue(shim.contains("%*"), shim);
        } else {
            assertTrue(shim.contains("exec '" + bin.resolve("node") + "' '"
                    + instance.dshEntryPoint() + "'"), shim);
            assertTrue(shim.contains("\"$@\""), shim);
        }
    }

    @Test
    void aCommandLineReachesTheSystemShell() {
        List<String> command = DshCustomCommands.shellCommand("echo hello");
        if (WINDOWS) {
            // The interpreter is named by ComSpec, so only its shape is fixed.
            assertTrue(command.size() >= 4, command.toString());
            assertTrue(command.get(command.size() - 4).equalsIgnoreCase("/d"), command.toString());
            assertTrue(command.get(command.size() - 3).equalsIgnoreCase("/s"), command.toString());
            assertTrue(command.get(command.size() - 2).equalsIgnoreCase("/c"), command.toString());
            assertTrue("echo hello".equals(command.get(command.size() - 1)), command.toString());
        } else {
            assertTrue(List.of("/bin/sh", "-c", "echo hello").equals(command), command.toString());
        }
    }
}
