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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that a build script waiting for an answer is asked about, and that the setting decides
/// how.
///
/// The reported defect: the manual-approval setting never asked anything, whatever was installed. The
/// reason is that the question was tied to how the package manager exited. pnpm 11 writes the
/// undecided package into the profile's `allowBuilds` with a placeholder and **returns success** —
/// only `strictDepBuilds` makes it fail instead, and that is off by default — so a launcher waiting
/// for a non-zero exit waited forever while the profile said, in the file it reads for every other
/// decision, that somebody still had to answer.
///
/// What is pinned here is therefore the reading of the profile rather than the exit code: with an
/// entry waiting, the three policies do their three things, and a run that simply succeeded is enough
/// to make it happen. The entry is a package that nothing on this machine has to build, because the
/// package manager is never reached: the instance's entry point is a stub that exits successfully.
class DshPluginBuildApprovalTest {
    /// The name of the instance the tests work in.
    private static final String ID = "build-approval-test";

    /// The package left waiting, as a real profile would leave it.
    private static final String WAITING = """
            packages:
              - .

            nodeLinker: hoisted
            allowBuilds:
              node-pty: set this to true or false
            """;

    /// The same profile after somebody refused, which pnpm remembers as firmly as an approval.
    private static final String REFUSED = """
            packages:
              - .

            nodeLinker: hoisted
            allowBuilds:
              node-pty: false
            """;

    /// A profile that refused a package installed from a file, which pnpm keys by the whole id.
    private static final String REFUSED_FROM_A_FILE = """
            packages:
              - .

            nodeLinker: hoisted
            allowBuilds:
              build-script-probe@file:../../../pkg/build-script-probe-1.0.0.tgz: false
            """;

    /// The workspace the instance runs in, which JUnit makes and removes.
    @TempDir
    Path workspaceDirectory;

    /// Removes the instance the tests made.
    @AfterEach
    void removeInstance() {
        try {
            if (DshInstanceManager.find(ID) != null) {
                DshInstanceManager.delete(ID);
            }
        } catch (Exception ignored) {
            // The test's own cleanup: a failure here would hide the real one.
        }
    }

    @Test
    void theManualSettingIsAskedAboutAPackageThatIsWaiting() throws Exception {
        DshInstance instance = makeInstance(WAITING, "manual");

        DshPluginInstaller.DshBuildScriptApprovalRequired asked = assertThrows(
                DshPluginInstaller.DshBuildScriptApprovalRequired.class,
                () -> DshPluginInstaller.installSpecs(instance, List.of("some-plugin"), null));

        assertEquals(List.of("node-pty"), asked.packages(),
                "the question names what is waiting, which is what the dialog has to show");
        assertTrue(Files.readString(profileFile(instance)).contains("node-pty: set this to true or false"),
                "nothing is answered on the person's behalf");
    }

    @Test
    void theAutomaticSettingAnswersAndCarriesOn() throws Exception {
        DshInstance instance = makeInstance(WAITING, "auto");

        DshPluginInstaller.installSpecs(instance, List.of("some-plugin"), null);

        assertTrue(Files.readString(profileFile(instance)).contains("node-pty: true"),
                "a policy that runs install scripts says so in the profile, which is where pnpm reads it");
    }

    @Test
    void theNeverSettingAnswersNo() throws Exception {
        DshInstance instance = makeInstance(WAITING, "never");

        DshPluginInstaller.installSpecs(instance, List.of("some-plugin"), null);

        String written = Files.readString(profileFile(instance));
        assertTrue(written.contains("node-pty: false"),
                "installing is not the same decision as running what comes with it");
        assertEquals(List.of(), DshBuildScripts.unanswered(instance), "an answer ends the question");
    }

    @Test
    void aProfileThatIsWaitingForNothingIsLeftAlone() throws Exception {
        DshInstance instance = makeInstance("""
                packages:
                  - .
                """, "manual");

        DshPluginInstaller.installSpecs(instance, List.of("some-plugin"), null);

        assertEquals("""
                packages:
                  - .
                """, Files.readString(profileFile(instance)), "a profile with no question is not touched");
    }

    @Test
    void aPackageSomebodyRefusedIsAskedAboutAgainWhenItIsInstalledAgain() throws Exception {
        // The reported defect: refusing once was final. pnpm remembers the refusal in the same file it
        // remembers an approval in, so installing the package again ran nothing, asked nothing and said
        // nothing — the plugin was there, could not load, and there was no way back to the question.
        DshInstance instance = makeInstance(REFUSED, "manual");

        DshPluginInstaller.DshBuildScriptApprovalRequired asked = assertThrows(
                DshPluginInstaller.DshBuildScriptApprovalRequired.class,
                () -> DshPluginInstaller.installSpecs(instance, List.of("node-pty@1.0.0"), null),
                "installing a package the profile refused is a new decision about running its code");

        assertEquals(List.of("node-pty"), asked.packages(),
                "the question is about the package being installed, named the way the profile names it");
        assertTrue(Files.readString(profileFile(instance)).contains("node-pty: false"),
                "and nothing is decided on the person's behalf");
    }

    @Test
    void aPackageInstalledFromAFileIsAskedAboutAgainByItsName() throws Exception {
        // The key and the spec are written differently — pnpm keys a package from a file by its whole
        // id — so the question is matched by the name, which is what both of them begin with.
        DshInstance instance = makeInstance(REFUSED_FROM_A_FILE, "manual");

        DshPluginInstaller.DshBuildScriptApprovalRequired asked = assertThrows(
                DshPluginInstaller.DshBuildScriptApprovalRequired.class,
                () -> DshPluginInstaller.installSpecs(instance, List.of("build-script-probe@1.0.0"), null));

        assertEquals(List.of("build-script-probe@file:../../../pkg/build-script-probe-1.0.0.tgz"),
                asked.packages(),
                "the answer has to name the key pnpm wrote, not the name it is matched by");
    }

    @Test
    void aPackageSomebodyRefusedThatIsNotBeingInstalledIsNotAskedAbout() throws Exception {
        DshInstance instance = makeInstance(REFUSED, "manual");

        DshPluginInstaller.installSpecs(instance, List.of("some-other-plugin"), null);

        assertTrue(Files.readString(profileFile(instance)).contains("node-pty: false"),
                "a question nobody is installing anything about is not worth asking");
    }

    @Test
    void theAutomaticSettingDoesNotAskAgainAboutWhatWasAlreadyRefused() throws Exception {
        DshInstance instance = makeInstance(REFUSED, "auto");

        DshPluginInstaller.installSpecs(instance, List.of("node-pty"), null);

        assertTrue(Files.readString(profileFile(instance)).contains("node-pty: false"),
                "an instance-wide answer is already an answer, and this setting is not the asking one");
    }

    @Test
    void theNeverSettingDoesNotAskAgainAboutWhatWasAlreadyRefused() throws Exception {
        DshInstance instance = makeInstance(REFUSED, "never");

        DshPluginInstaller.installSpecs(instance, List.of("node-pty"), null);

        assertTrue(Files.readString(profileFile(instance)).contains("node-pty: false"));
    }

    @Test
    void theNameOfAPackageIsWhatComesBeforeItsVersion() {
        assertEquals("node-pty", DshPluginInstaller.packageNameOf("node-pty"));
        assertEquals("node-pty", DshPluginInstaller.packageNameOf("node-pty@1.0.0"));
        assertEquals("dsh-better-sidebar", DshPluginInstaller.packageNameOf("dsh-better-sidebar@0.21.1"));
        assertEquals("build-script-probe",
                DshPluginInstaller.packageNameOf("build-script-probe@file:../../x.tgz"));
        assertEquals("@scope/pkg", DshPluginInstaller.packageNameOf("@scope/pkg@1.2.3"),
                "a scope's own @ is not the one a version begins with");
    }

    /// Creates an instance with a stub harness and a profile holding the given workspace file.
    ///
    /// The stub is what makes this test possible without a package manager: the installer runs
    /// `node <entry point> plugin --profile <name> add <spec>`, so an entry point that exits
    /// successfully is a run that succeeded and changed nothing — which is exactly the run that used
    /// to end the question.
    ///
    /// The workspace is JUnit's temporary directory rather than one made here, because a directory
    /// made here is one nothing removes: running this suite used to leave one behind in `%TEMP%` per
    /// test, and an instance that is deleted by [#removeInstance] does not delete the directory it ran
    /// in.
    ///
    /// @param workspace the profile's `pnpm-workspace.yaml`
    /// @param policy    the instance's build-script policy
    /// @return the instance
    private DshInstance makeInstance(String workspace, String policy) throws Exception {
        Assumptions.assumeTrue(
                DshNodeRuntime.detect().map(DshNodeRuntime::isNodeSupported).orElse(false),
                "Node.js ^22.19.0 || >=24.0.0 is not on PATH; nothing can be started without it");
        // The installer refuses to install anything without pnpm, because pnpm is what `dsh plugin`
        // forwards to — a machine without it cannot install a plugin at all. Where that is the case
        // these tests are skipped rather than failed: what they cover is the answering of the
        // question, which is only reached on a machine where installing is possible. The workflow
        // installs pnpm on the runners so that they are reached there too.
        Assumptions.assumeTrue(DshNodeRuntime.which("pnpm").isPresent(),
                "pnpm is not on PATH; the installer refuses to install plugins without it");
        DshInstance instance = DshInstanceManager.create(ID, "0.1.6-alpha.2", DshInstance.DEFAULT_PROFILE,
                workspaceDirectory, DshHomeMode.ISOLATED, null, List.of(), Map.of());
        DshInstanceSettings.setBuildScriptPolicy(instance, policy);

        Path script = instance.dshEntryPoint();
        Files.createDirectories(script.getParent());
        Files.writeString(script, "process.exit(0);\n");

        Files.createDirectories(profileFile(instance).getParent());
        Files.writeString(profileFile(instance), workspace);
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
