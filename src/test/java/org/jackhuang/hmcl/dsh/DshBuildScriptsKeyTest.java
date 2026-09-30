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
package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins what a package waiting in `allowBuilds` is *named* by, and that answering it by that name is
/// what makes its install script run.
///
/// The key pnpm writes is the package's **id**, not its name. It writes the name for a package that
/// came from the registry, but for one that came from a file — which is how a plugin picked out of the
/// file system is installed — it writes `build-script-probe@file:../../../pkg/….tgz`, because the
/// version such a dependency resolves to is not a version. Reading that with the first colon as the
/// separator yields `build-script-probe@file`, and then the approval the interface offers is for a
/// package pnpm has never heard of: the answer is written, the entry pnpm reads stays undecided, the
/// install fails again, and the id of the package that was actually refused is lost from the file.
///
/// What [DshBuildScriptsTest] pins is what happens to a profile with entries in it; what is pinned
/// here is the shape of the entries themselves, and — in the last test — the whole loop against the
/// real package manager rather than against strings.
class DshBuildScriptsKeyTest {

    /// The profile of the instance these tests work in.
    private static final String PROFILE = DshInstance.DEFAULT_PROFILE;

    /// A profile whose package manager refused one package from the registry.
    private static final String FROM_THE_REGISTRY = """
            packages:
              - .

            nodeLinker: hoisted
            allowBuilds:
              node-pty: set this to true or false
            """;

    /// What pnpm 11.24 actually wrote for a package installed from a file.
    ///
    /// Recorded from a real run — `dsh plugin --profile web add file:…/dsh-postinstall-probe-1.0.0.tgz`
    /// against a throwaway `DSH_HOME` — rather than written by hand from what the format is supposed to
    /// be, because the shape of the key is the thing in question.
    private static final String FROM_A_FILE = """
            packages:
              - .

            nodeLinker: hoisted
            autoInstallPeers: false
            allowBuilds:
              dsh-postinstall-probe@file:../../../pkg/dsh-postinstall-probe-1.0.0.tgz: set this to true or false
            """;

    /// The key in [#FROM_A_FILE], which is what has to survive being read and written back.
    private static final String FILE_KEY =
            "dsh-postinstall-probe@file:../../../pkg/dsh-postinstall-probe-1.0.0.tgz";

    @Test
    void aPackageFromTheRegistryIsReadByItsName(@TempDir Path home) throws Exception {
        DshInstance instance = instance(home, FROM_THE_REGISTRY);

        assertEquals(List.of("node-pty"), DshBuildScripts.unanswered(instance));
        assertEquals(List.of(new DshBuildScripts.Pending("node-pty", null)),
                DshBuildScripts.pending(instance), "a placeholder is not an answer");
    }

    @Test
    void aPackageInstalledFromAFileIsReadByItsWholeId(@TempDir Path home) throws Exception {
        DshInstance instance = instance(home, FROM_A_FILE);

        assertEquals(List.of(FILE_KEY), DshBuildScripts.unanswered(instance),
                "the id pnpm wrote is the id it reads back, so it is the whole of what is waiting");
    }

    @Test
    void answeringWritesTheIdBackWhole(@TempDir Path home) throws Exception {
        DshInstance instance = instance(home, FROM_A_FILE);

        assertEquals(1, DshBuildScripts.answer(instance, DshBuildScripts.unanswered(instance), true));

        assertTrue(Files.readString(profileFile(instance)).contains(FILE_KEY + ": true"),
                "an answer that renames the package leaves the entry undecided: "
                        + Files.readString(profileFile(instance)));
        assertEquals(List.of(), DshBuildScripts.unanswered(instance));
        assertEquals(List.of(new DshBuildScripts.Pending(FILE_KEY, Boolean.TRUE)),
                DshBuildScripts.pending(instance));
    }

    @Test
    void aFileKeyIsTheOnlyThingAnAnswerChanges(@TempDir Path home) throws Exception {
        DshInstance instance = instance(home, FROM_A_FILE);

        DshBuildScripts.answer(instance, List.of(FILE_KEY), false);

        assertEquals(FROM_A_FILE.replace("set this to true or false", "false"),
                Files.readString(profileFile(instance)));
    }

    @Test
    void aRefusedBuildScriptRunsOnceItHasBeenAnswered(@TempDir Path work) throws Exception {
        Path pnpm = DshNodeRuntime.which("pnpm").orElse(null);
        Assumptions.assumeTrue(pnpm != null,
                "pnpm is not on PATH; what this measures is the agreement between it and the launcher");
        Path npm = DshNodeRuntime.which("npm").orElse(null);
        Assumptions.assumeTrue(npm != null, "npm is not on PATH, so the probe package cannot be packed");

        Path tarball = packAProbeWithABuildScript(work, npm);
        DshInstance instance = instance(work, null);
        Path profile = profileFile(instance).getParent();
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("package.json"),
                "{\n  \"name\": \"build-script-probe-profile\",\n  \"private\": true\n}\n");
        Files.writeString(profileFile(instance), "packages:\n  - .\n\nnodeLinker: hoisted\n");

        // One: the package manager refuses, and says what is waiting in the file the launcher reads.
        DshCommand.Result refused = DshCommand.run(
                List.of(pnpm.toString(), "add", "file:" + tarball), profile, null);
        assertNotEquals(0, refused.exitCode(), "a build script nobody answered for is refused: " + refused.text());
        assertTrue(refused.text().contains("ERR_PNPM_IGNORED_BUILDS"), refused.text());

        // Two: the launcher reads it — the whole id, which is what an answer has to name.
        //
        // What was refused is recorded by the package manager itself, in the file read here: pnpm
        // writes the entry with the placeholder that stands for "nobody has answered yet". **Where**
        // it records that is the package manager's business, and it has differed between versions —
        // 11.24.0 writes `allowBuilds` into this file — so the file is read before it is interpreted,
        // and the two cases are told apart rather than both arriving as an empty answer. A pnpm that
        // recorded nothing has nothing for any reader to find, and that is not this launcher's
        // defect; a pnpm that recorded the question and a launcher that cannot read it is exactly
        // what this test exists for. (The suite says which pnpm it was, so a skip is actionable.)
        String recorded = Files.readString(profileFile(instance));
        Assumptions.assumeTrue(recorded.contains("allowBuilds"),
                "the pnpm at " + pnpm + " (" + pnpmVersion(pnpm) + ") recorded no refused build script "
                        + "in " + profileFile(instance) + ", so there is nothing for the launcher to "
                        + "read. It wrote: " + recorded);
        List<String> waiting = DshBuildScripts.unanswered(instance);
        assertEquals(1, waiting.size(), "one package is waiting: " + waiting);
        assertTrue(waiting.get(0).startsWith("build-script-probe@file:"),
                "the key is the id pnpm wrote, not the part of it before the first colon: " + waiting);

        // Three: the answer is written where pnpm reads it.
        assertEquals(1, DshBuildScripts.answer(instance, waiting, true));
        assertEquals(List.of(), DshBuildScripts.unanswered(instance));

        // Four: the same install goes through, and the script the question was about has run.
        DshCommand.Result installed = DshCommand.run(List.of(pnpm.toString(), "install"), profile, null);
        assertEquals(0, installed.exitCode(), "an answered build is not refused: " + installed.text());
        assertTrue(Files.isRegularFile(profile.resolve("node_modules").resolve("build-script-probe")
                        .resolve("built.txt")),
                "and the script ran: " + installed.text());
    }

    /// Returns what a pnpm says its version is, so a skipped run names the package manager it saw.
    ///
    /// Its version is the whole question when the two disagree about where a refused build script is
    /// written, and a message saying only "nothing was recorded" leaves that to be guessed.
    ///
    /// @param pnpm the pnpm
    /// @return its version, or what running it said instead
    private static String pnpmVersion(Path pnpm) {
        try {
            return DshCommand.run(List.of(pnpm.toString(), "--version"), null, null).text().trim();
        } catch (Exception e) {
            return "version unknown: " + e;
        }
    }

    /// Packs a package whose install script leaves a file behind.
    ///
    /// @param work where to pack it
    /// @param npm  the npm to pack with
    /// @return the tarball
    private static Path packAProbeWithABuildScript(Path work, Path npm) throws Exception {
        Path source = Files.createDirectories(work.resolve("build-script-probe"));
        Files.writeString(source.resolve("package.json"), """
                {
                  "name": "build-script-probe",
                  "version": "1.0.0",
                  "scripts": {
                    "postinstall": "node -e \\"require('fs').writeFileSync('built.txt','yes')\\""
                  }
                }
                """);
        Files.writeString(source.resolve("index.js"), "module.exports = 1;\n");

        DshCommand.Result packed = DshCommand.run(
                List.of(npm.toString(), "pack", "--pack-destination", work.toString()), source, null);
        assertEquals(0, packed.exitCode(), packed.text());
        Path tarball = work.resolve("build-script-probe-1.0.0.tgz");
        assertTrue(Files.isRegularFile(tarball), "npm pack wrote nothing: " + packed.text());
        return tarball;
    }

    /// Creates an instance whose home is the given directory and whose profile holds the given file.
    ///
    /// A custom home rather than a created instance: what is under test here is the reading of a file,
    /// and creating an instance needs a Node runtime on the machine to do it — [DshBuildScriptsTest]
    /// is where the created-instance path is covered.
    ///
    /// @param home      the home directory
    /// @param workspace the profile's `pnpm-workspace.yaml`, or `null` to leave it out
    /// @return the instance
    private static DshInstance instance(Path home, String workspace) throws Exception {
        DshInstance instance = new DshInstance("build-scripts-key-test", "0.1.6-alpha.2", PROFILE,
                home.toString(), null, DshHomeMode.CUSTOM, home.toString(),
                List.of(), Map.of(), null, null, null, 0, 0L);
        if (workspace != null) {
            Files.createDirectories(profileFile(instance).getParent());
            Files.writeString(profileFile(instance), workspace);
        }
        return instance;
    }

    /// Returns an instance's `pnpm-workspace.yaml`.
    ///
    /// @param instance the instance
    /// @return the file
    private static Path profileFile(DshInstance instance) throws Exception {
        return instance.homeDirectory().resolve("profiles").resolve(instance.profile())
                .resolve("pnpm-workspace.yaml");
    }
}
