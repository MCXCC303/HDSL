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
package com.mcxcc303.hdsl.plugin;

import com.mcxcc303.hdsl.instance.*;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that packages with native build scripts awaiting user confirmation trigger approval prompts.
 * <p>
 * <b>Reported Defect:</b> Manual build approval was never prompted regardless of plugin configuration.
 * Previously, approval checks waited for the package manager to fail with a non-zero exit code.
 * However, pnpm 11 writes pending packages into {@code allowBuilds} with placeholder strings
 * ("set this to true or false") and <i>exits with status 0</i> (success) by default unless
 * {@code strictDepBuilds} is explicitly configured. Consequently, checking exit codes alone caused the
 * prompt to be skipped completely.
 * <p>
 * These tests verify inspection of {@code pnpm-workspace.yaml}'s {@code allowBuilds} map rather than exit code.
 * Requires Node.js or pnpm on PATH; marked with {@code @Tag("external")}.
 */
@Tag("external")
class DshPluginBuildApprovalTest {
	private static final String ID = "build-approval-test";
	private static final String OTHER_ID = "build-approval-other-test";

	private static final String WAITING = """
			packages:
			  - .
			
			nodeLinker: hoisted
			allowBuilds:
			  node-pty: set this to true or false
			""";

	private static final String REFUSED = """
			packages:
			  - .
			
			nodeLinker: hoisted
			allowBuilds:
			  node-pty: false
			""";

	private static final String REFUSED_FROM_A_FILE = """
			packages:
			  - .
			
			nodeLinker: hoisted
			allowBuilds:
			  build-script-probe@file:../../../pkg/build-script-probe-1.0.0.tgz: false
			""";

	@TempDir
	Path workspaceDirectory;

	private static Path packedPlugin(Path directory, String name, String version) throws Exception {
		String manifest = "{\"name\":\"" + name + "\",\"version\":\"" + version + "\"}";
		Path file = directory.resolve(name + "-" + version + ".tgz");
		try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file))) {
			gzip.write(tarEntry("package/package.json", manifest.getBytes(StandardCharsets.UTF_8)));
			gzip.write(new byte[1024]);
		}
		return file;
	}

	private static byte[] tarEntry(String name, byte[] body) {
		byte[] block = new byte[512];
		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		System.arraycopy(nameBytes, 0, block, 0, Math.min(nameBytes.length, 100));
		byte[] size = Long.toOctalString(body.length).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(size, 0, block, 124, size.length);
		block[156] = '0';
		byte[] entry = new byte[512 + ((body.length + 511) / 512) * 512];
		System.arraycopy(block, 0, entry, 0, 512);
		System.arraycopy(body, 0, entry, 512, body.length);
		return entry;
	}

	private static Path profileFile(DshInstance instance) throws Exception {
		return instance.homeDirectory().resolve("profiles").resolve(instance.profile()).resolve("pnpm-workspace.yaml");
	}

	/**
	 * Resets installation approval state before each test.
	 * Decisions already prompted are tracked in-memory per process; starting a fresh installation
	 * clears this set so tests do not leak approval state into sibling test runs.
	 */
	@BeforeEach
	void newInstallation() {
		DshPluginInstaller.beginInstallation();
	}

	@AfterEach
	void removeInstance() {
		for (String id : List.of(ID, OTHER_ID)) {
			try {
				if (InstanceStore.find(id) != null) {
					DshInstanceManager.delete(id);
				}
			} catch (Exception ignored) {
				// The test's own cleanup: a failure here would hide the real one.
			}
		}
	}

	@Test
	void theManualSettingIsAskedAboutAPackageThatIsWaiting() throws Exception {
		DshInstance instance = makeInstance(WAITING, "manual");

		DshPluginInstaller.DshBuildScriptApprovalRequired asked = assertThrows(DshPluginInstaller.DshBuildScriptApprovalRequired.class,
		                                                                       () -> DshPluginInstaller.installSpecs(
				                                                                       instance,
				                                                                       List.of("some-plugin"),
				                                                                       null));

		assertEquals(List.of("node-pty"),
		             asked.packages(),
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

		DshPluginInstaller.DshBuildScriptApprovalRequired asked = assertThrows(DshPluginInstaller.DshBuildScriptApprovalRequired.class,
		                                                                       () -> DshPluginInstaller.installSpecs(
				                                                                       instance,
				                                                                       List.of("node-pty@1.0.0"),
				                                                                       null),
		                                                                       "installing a package the profile refused is a new decision about running its code");

		assertEquals(List.of("node-pty"),
		             asked.packages(),
		             "the question is about the package being installed, named the way the profile names it");
		assertTrue(Files.readString(profileFile(instance)).contains("node-pty: false"),
		           "and nothing is decided on the person's behalf");
	}

	@Test
	void aPackageInstalledFromAFileIsAskedAboutAgainByItsName() throws Exception {
		// The key and the spec are written differently — pnpm keys a package from a file by its whole
		// id — so the question is matched by the name, which is what both of them begin with.
		DshInstance instance = makeInstance(REFUSED_FROM_A_FILE, "manual");

		DshPluginInstaller.DshBuildScriptApprovalRequired asked = assertThrows(DshPluginInstaller.DshBuildScriptApprovalRequired.class,
		                                                                       () -> DshPluginInstaller.installSpecs(
				                                                                       instance,
				                                                                       List.of("build-script-probe@1.0.0"),
				                                                                       null));

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
		assertEquals("build-script-probe", DshPluginInstaller.packageNameOf("build-script-probe@file:../../x.tgz"));
		assertEquals("@scope/pkg",
		             DshPluginInstaller.packageNameOf("@scope/pkg@1.2.3"),
		             "a scope's own @ is not the one a version begins with");
	}

	@Test
	void aPluginInstalledFromAFileIsAskedAboutAgainWhenItsFileIsInstalledAgain(@TempDir Path directory)
	throws Exception {
		// The reported defect, reached the way the interface reaches it: the plugin list's "add" button
		// copies the file the user picked into the instance and installs it by *that path*, and the
		// profile records the path — while pnpm keys the refusal by the package's name and the file it
		// came from (`build-script-probe@file:../../../plugins/….tgz`). The two are not the same string,
		// and the path names no package: the first installation asked, because pnpm writes the
		// undecided package into the profile itself, and every installation after it was silent. A
		// plugin that cannot load, refused once, could never be allowed.
		DshInstance instance = makeInstance(REFUSED_FROM_A_FILE, "manual");

		DshPluginInstaller.DshBuildScriptApprovalRequired asked = assertThrows(DshPluginInstaller.DshBuildScriptApprovalRequired.class,
		                                                                       () -> DshLocalPlugins.install(instance,
		                                                                                                     packedPlugin(
				                                                                                                     directory,
				                                                                                                     "build-script-probe",
				                                                                                                     "1.0.0"),
		                                                                                                     null),
		                                                                       "the same plugin arriving again is a new decision about running its code");

		assertEquals(List.of("build-script-probe@file:../../../pkg/build-script-probe-1.0.0.tgz"),
		             asked.packages(),
		             "the answer names the key the profile holds, not the path it was given");
	}

	@Test
	void aPathThatHoldsNoPackageIsNotAskedAbout(@TempDir Path directory) throws Exception {
		DshInstance instance = makeInstance(REFUSED, "manual");
		Path nothing = directory.resolve("not-a-package.tgz");
		Files.writeString(nothing, "not a package");

		DshPluginInstaller.installSpecs(instance, List.of(nothing.toString()), null);

		assertTrue(Files.readString(profileFile(instance)).contains("node-pty: false"),
		           "a spec no package can be named from matches nothing rather than matching everything");
	}

	@Test
	void answeringNoEndsTheAskingForThatInstallation(@TempDir Path directory) throws Exception {
		// The reported defect, third round: answering **no** brought the question straight back, after
		// the progress dialog had run again — an installation that would not take no for an answer. The
		// answer is written where pnpm reads it, which leaves the profile refused *and* being installed
		// again, which is the state the question is asked in: so every re-run of the same installation
		// asked again, and the only way past it was to press yes.
		DshInstance instance = makeInstance(REFUSED_FROM_A_FILE, "manual");
		DshPluginInstaller.beginInstallation();
		Path packed = packedPlugin(directory, "build-script-probe", "1.0.0");

		DshPluginInstaller.DshBuildScriptApprovalRequired asked = assertThrows(DshPluginInstaller.DshBuildScriptApprovalRequired.class,
		                                                                       () -> DshLocalPlugins.install(instance,
		                                                                                                     packed,
		                                                                                                     null));

		// What the interface does with the answer: write it, record it, and carry on with the *same*
		// installation — which is the step that used to ask again.
		DshBuildScripts.answer(instance, asked.packages(), false);
		DshPluginInstaller.answeredAbout(asked.packages());

		DshLocalPlugins.install(instance, packed, null);

		assertTrue(Files.readString(profileFile(instance)).contains("false"),
		           "the answer that was given stands: nothing is decided on the person's behalf");
	}

	@Test
	void theNextInstallationIsAskedAboutAgain(@TempDir Path directory) throws Exception {
		// The other half of "once per installation": the decision is not final for good, which is what
		// the whole re-asking exists for. A new installation the person starts is a new decision.
		DshInstance instance = makeInstance(REFUSED_FROM_A_FILE, "manual");
		Path packed = packedPlugin(directory, "build-script-probe", "1.0.0");
		DshPluginInstaller.beginInstallation();
		DshPluginInstaller.DshBuildScriptApprovalRequired asked = assertThrows(DshPluginInstaller.DshBuildScriptApprovalRequired.class,
		                                                                       () -> DshLocalPlugins.install(instance,
		                                                                                                     packed,
		                                                                                                     null));
		DshBuildScripts.answer(instance, asked.packages(), false);
		DshPluginInstaller.answeredAbout(asked.packages());
		DshLocalPlugins.install(instance, packed, null);

		DshPluginInstaller.beginInstallation();

		assertThrows(DshPluginInstaller.DshBuildScriptApprovalRequired.class,
		             () -> DshLocalPlugins.install(instance, packed, null),
		             "installing the same plugin again later is a decision about running its code, asked once more");
	}

	@Test
	void anotherPackageIsNotAskedAboutARefusalOfADifferentOne(@TempDir Path directory) throws Exception {
		// The question has to stay about the package in hand: a profile that refused one plugin must
		// not be asked about it while a different plugin is being installed.
		DshInstance instance = makeInstance(REFUSED_FROM_A_FILE, "manual");
		DshPluginInstaller.beginInstallation();

		DshLocalPlugins.install(instance, packedPlugin(directory, "dsh-quiet", "2.0.0"), null);

		assertTrue(Files.readString(profileFile(instance)).contains("build-script-probe@file:"),
		           "the refused entry is left exactly as it was");
		assertThrows(DshPluginInstaller.DshBuildScriptApprovalRequired.class,
		             () -> DshLocalPlugins.install(instance,
		                                           packedPlugin(directory, "build-script-probe", "1.0.0"),
		                                           null),
		             "and the package that was refused does ask, in the same installation");
	}

	@Test
	void anotherInstanceIsNotAskedAboutARefusalOfTheSamePackage(@TempDir Path directory) throws Exception {
		// A refusal is one instance's answer: another instance has its own profile, and its own answer.
		DshInstance refusing = makeInstance(OTHER_ID,
		                                    REFUSED_FROM_A_FILE,
		                                    "manual",
		                                    workspaceDirectory.resolve("refusing"));
		DshInstance other = makeInstance("packages:\n  - .\n", "manual");
		DshPluginInstaller.beginInstallation();

		DshLocalPlugins.install(other, packedPlugin(directory, "build-script-probe", "1.0.0"), null);

		assertTrue(Files.readString(profileFile(refusing)).contains("false"),
		           "the instance that refused is untouched by an installation into another one");
	}

	private DshInstance makeInstance(String workspace, String policy) throws Exception {
		return makeInstance(ID, workspace, policy, workspaceDirectory);
	}

	private DshInstance makeInstance(String id, String workspace, String policy, Path directory) throws Exception {
		Assumptions.assumeTrue(DshNodeRuntime.detect().map(DshNodeRuntime::isNodeSupported).orElse(false),
		                       "Node.js ^22.19.0 || >=24.0.0 is not on PATH; nothing can be started without it");
		// The installer refuses to install anything without pnpm, because pnpm is what `dsh plugin`
		// forwards to — a machine without it cannot install a plugin at all. Where that is the case
		// these tests are skipped rather than failed: what they cover is the answering of the
		// question, which is only reached on a machine where installing is possible. The workflow
		// installs pnpm on the runners so that they are reached there too.
		Assumptions.assumeTrue(DshNodeRuntime.which("pnpm").isPresent(),
		                       "pnpm is not on PATH; the installer refuses to install plugins without it");
		Files.createDirectories(directory);
		DshInstance instance = DshInstanceManager.create(id,
		                                                 "0.1.6-alpha.2",
		                                                 DshInstance.DEFAULT_PROFILE,
		                                                 directory,
		                                                 DshHomeMode.ISOLATED,
		                                                 null,
		                                                 List.of(),
		                                                 Map.of());
		DshInstanceSettings.setBuildScriptPolicy(instance, policy);

		Path script = instance.dshEntryPoint();
		Files.createDirectories(script.getParent());
		Files.writeString(script, "process.exit(0);\n");

		Files.createDirectories(profileFile(instance).getParent());
		Files.writeString(profileFile(instance), workspace);
		return instance;
	}
}
