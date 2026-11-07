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
package com.mcxcc303.hdsl.harness;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.plugin.DshDependencyPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the manifest an install is described by.
 * <p>
 * DeepSeek Harness and its boot library are published in lockstep. Mismatched library versions
 * fail at module import time, so a manifest failing to pin them together produces an installation
 * that installs cleanly but fails to launch.
 */
class DshManifestTest {
	@TempDir
	private Path prefix;

	/**
	 * Verifies the launcher dependency is pinned to an exact version rather than a semver range.
	 * A caret range like {@code ^0.1.6-alpha.1} resolves to {@code alpha.2} libraries, causing
	 * version divergence and runtime import failure.
	 */
	@Test
	void pinsTheLauncherExactly() throws IOException, DshException {
		HarnessInstaller.writeManifest(prefix, "0.1.6-alpha.1", "0.1.6-alpha.1", null, DshDependencyPolicy.LATEST);

		JsonObject manifest = JsonParser.parseString(Files.readString(prefix.resolve("package.json")))
		                                .getAsJsonObject();
		assertEquals("0.1.6-alpha.1",
		             manifest.getAsJsonObject("dependencies").get("\u0040deepseek-ai/dsh").getAsString(),
		             "the launcher should be pinned to the exact version");
	}

	/**
	 * Verifies the boot library override is written to {@code pnpm-workspace.yaml}.
	 * Since pnpm 10, overrides in {@code package.json} are ignored by pnpm; writing them to
	 * package.json leaves the boot library unpinned and allows it to drift.
	 */
	@Test
	void pinsTheBootLibraryWherePnpmReadsIt() throws IOException, DshException {
		HarnessInstaller.writeManifest(prefix, "0.1.6-alpha.1", "0.1.6-alpha.1", null, DshDependencyPolicy.LATEST);

		Path workspace = prefix.resolve("pnpm-workspace.yaml");
		assertTrue(Files.isRegularFile(workspace),
		           "pnpm reads its settings from pnpm-workspace.yaml, so the override belongs there");
		String text = Files.readString(workspace);
		assertTrue(text.contains("overrides:"), "the override section should be present");
		assertTrue(text.contains("\u0040deepseek-ai/dsh-app-boot"), "the boot library should be named");
		assertTrue(text.contains("0.1.6-alpha.1"), "held to the version asked for");
	}

	@Test
	void writesTheBootLibraryItIsGiven() throws IOException, DshException {
		HarnessInstaller.writeManifest(prefix, "0.1.6-alpha.1", "0.1.5-rc.2", null, DshDependencyPolicy.LATEST);

		assertTrue(Files.readString(prefix.resolve("pnpm-workspace.yaml")).contains("0.1.5-rc.2"),
		           "the override should say what was asked for, not the launcher's own version");
	}

	/**
	 * Verifies that the pin is parsed from the {@code overrides:} map rather than package listings.
	 * In {@code pnpm-workspace.yaml}, packages appear in {@code onlyBuiltDependencies} as {@code '@scope/pkg@1.2.3'}.
	 * Reading the package list line previously preserved the leading {@code @}, which resulted in
	 * invalid plain YAML scalars that pnpm rejected with syntax errors.
	 */
	@Test
	void thePinIsReadFromItsOwnEntryAndNotFromAListOfPackages() {
		String workspace = """
				onlyBuiltDependencies:
				  - '@deepseek-ai/dsh-api-job-controller@0.1.7-rc.1'
				  - '@deepseek-ai/dsh-app-boot@0.1.7-rc.1'
				overrides:
				  '@deepseek-ai/dsh-app-boot': 0.1.7-rc.1
				  '@deepseek-ai/cordis': 4.0.4
				""";
		assertEquals("0.1.7-rc.1",
		             AppBootPin.appBootPin(workspace),
		             "the list entry names the package and a version, but it is not the pin");

		assertNull(AppBootPin.appBootPin("  - '@deepseek-ai/dsh-app-boot@0.1.7-rc.1'\n"),
		           "a file that only lists the package pins nothing");
		assertEquals("0.1.5-alpha.2",
		             AppBootPin.appBootPin("overrides:\n  \"@deepseek-ai/dsh-app-boot\": '0.1.5-alpha.2'\n"),
		             "quotes around either half are the spelling, not the value");
	}

	@Test
	void aValueYamlWouldReadAsAnIndicatorIsQuoted() throws IOException, DshException {
		HarnessInstaller.writeManifest(prefix, "0.1.6-alpha.1", "@0.1.7-rc.1", null, DshDependencyPolicy.LATEST);

		String text = Files.readString(prefix.resolve("pnpm-workspace.yaml"));
		assertTrue(text.contains("  '@deepseek-ai/dsh-app-boot': '@0.1.7-rc.1'"),
		           "a key and a value that start with @ are both quoted, not written as syntax: " + text);
	}

	@Test
	void anOrdinaryVersionIsWrittenPlainly() throws IOException, DshException {
		HarnessInstaller.writeManifest(prefix, "0.1.6-alpha.1", "0.1.6-alpha.1", null, DshDependencyPolicy.LATEST);

		String text = Files.readString(prefix.resolve("pnpm-workspace.yaml"));
		assertTrue(text.contains(": 0.1.6-alpha.1"), "the version itself is a plain scalar");
		assertFalse(text.contains("'0.1.6-alpha.1'"), "and is not quoted for no reason");
	}

	@Test
	void theBootLibraryIsNamedOnceEvenWhenThePolicyAlsoHoldsIt() {
		Map<String, String> held = new LinkedHashMap<>();
		held.put("\u0040deepseek-ai/cordis", "4.0.2");
		held.put("\u0040deepseek-ai/dsh-app-boot", "0.1.6-alpha.1");

		Map<String, String> overrides = HarnessInstaller.mergeOverrides("0.1.5-alpha.2", held);

		assertEquals(2, overrides.size(), "two packages, and no key written twice");
		assertEquals("0.1.5-alpha.2",
		             overrides.get("\u0040deepseek-ai/dsh-app-boot"),
		             "the version the caller chose is the one that stands");
		assertEquals("4.0.2", overrides.get("\u0040deepseek-ai/cordis"));
	}

	@Test
	void theDependenciesAreReadFromTheShapeNpmActuallyAnswersWith() {
		String answer = "[{\"commander\":\"^15.0.0\"," + "\"@deepseek-ai/cordis\":\"^4.0.2\"," +
		                "\"@deepseek-ai/cordis-plugin-loader\":\"^1.0.3\"}]";

		JsonObject declared = HarnessInstaller.declaredDependencies(answer);

		assertNotNull(declared, "an array of one object is npm's answer for a named version");
		assertEquals("^4.0.2", declared.get("\u0040deepseek-ai/cordis").getAsString());
		assertEquals("4.0.2", HarnessInstaller.floorOf(declared.get("\u0040deepseek-ai/cordis").getAsString()));
	}

	@Test
	void anAnswerWithNoDependenciesIsNotAnError() {
		assertNull(HarnessInstaller.declaredDependencies("[]"));
		assertNull(HarnessInstaller.declaredDependencies("null"));
		assertNull(HarnessInstaller.declaredDependencies("not json"));
	}

	@Test
	void aFloorIsReadFromTheRangesThatHaveOne() {
		assertEquals("4.0.2", HarnessInstaller.floorOf("^4.0.2"));
		assertEquals("1.0.3", HarnessInstaller.floorOf("~1.0.3"));
		assertEquals("2.0.0", HarnessInstaller.floorOf(">=2.0.0"));
		assertEquals("0.1.5-alpha.2", HarnessInstaller.floorOf("0.1.5-alpha.2"), "an exact version is its own floor");
		assertEquals("0.1.5-alpha.2",
		             HarnessInstaller.floorOf("^0.1.5-alpha.2"),
		             "a prerelease floor is still a floor");
	}

	@Test
	void aRangeWithNoReadableFloorIsLeftAlone() {
		assertNull(HarnessInstaller.floorOf("*"));
		assertNull(HarnessInstaller.floorOf("latest"));
		assertNull(HarnessInstaller.floorOf(">=1.0.0 <2.0.0"));
		assertNull(HarnessInstaller.floorOf("github:owner/repo#sha"));
		assertNull(HarnessInstaller.floorOf("workspace:*"));
		assertNull(HarnessInstaller.floorOf(""));
		assertNull(HarnessInstaller.floorOf(null));
	}

	@Test
	void theDefaultPolicyHoldsOnlyTheVendorsOwnPackages() {
		DshDependencyPolicy policy = DshDependencyPolicy.CORE_PINNED;

		assertTrue(policy.pins("\u0040deepseek-ai/cordis"));
		assertTrue(policy.pins("\u0040deepseek-ai/cordis-plugin-loader"));
		assertTrue(policy.pins("\u0040deepseek-ai/dsh-app-boot"));
		assertFalse(policy.pins("dsh-wildmon"));
		assertFalse(policy.pins("\u0040hellosz/dsh-pets"));

		assertFalse(DshDependencyPolicy.LATEST.pins("\u0040deepseek-ai/cordis"), "following upstream holds nothing");
		assertTrue(DshDependencyPolicy.LOCKED.pins("\u0040hellosz/dsh-pets"),
		           "the strictest policy holds everything declared");
	}
}