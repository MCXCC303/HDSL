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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the manifest an install is described by.
///
/// The pinning is not a preference. DeepSeek Harness and its boot library are
/// published together and a release whose libraries disagree with it fails at
/// import, so a manifest that fails to hold them together produces a runtime that
/// installs cleanly and does not start. It did: the override was written where npm
/// reads it and pnpm does not, and nothing said so.
class DshManifestTest {
    /// A temporary directory standing in for the install prefix.
    @TempDir
    private Path prefix;

    /// The launcher is asked for the exact version, not a range.
    ///
    /// A caret cannot express what these packages promise: the whole family is
    /// published in lockstep, so `^0.1.6-alpha.1` resolves to alpha.2's libraries
    /// and the tree comes out inconsistent.
    ///
    /// @throws IOException  when the manifest cannot be read back
    /// @throws DshException when the manifest cannot be written
    @Test
    void pinsTheLauncherExactly() throws IOException, DshException {
        DshVersionManager.writeManifest(prefix, "0.1.6-alpha.1", "0.1.6-alpha.1", null,
                DshDependencyPolicy.LATEST);

        JsonObject manifest = JsonParser.parseString(
                Files.readString(prefix.resolve("package.json"))).getAsJsonObject();
        assertEquals("0.1.6-alpha.1",
                manifest.getAsJsonObject("dependencies").get("\u0040deepseek-ai/dsh").getAsString(),
                "the launcher should be pinned to the exact version");
    }

    /// The boot library is pinned where pnpm actually reads it.
    ///
    /// Since pnpm 10 the settings it used to take from `package.json` live in
    /// `pnpm-workspace.yaml`; a `pnpm` field in the manifest is ignored, and npm's
    /// `overrides` field is not read by pnpm at all. Writing either leaves the
    /// boot library free to resolve to whatever the launcher's own dependencies
    /// ask for, which is a different version as soon as the two have diverged.
    ///
    /// @throws IOException when the file cannot be read back
    @Test
    void pinsTheBootLibraryWherePnpmReadsIt() throws IOException, DshException {
        DshVersionManager.writeManifest(prefix, "0.1.6-alpha.1", "0.1.6-alpha.1", null,
                DshDependencyPolicy.LATEST);

        Path workspace = prefix.resolve("pnpm-workspace.yaml");
        assertTrue(Files.isRegularFile(workspace),
                "pnpm reads its settings from pnpm-workspace.yaml, so the override belongs there");
        String text = Files.readString(workspace);
        assertTrue(text.contains("overrides:"), "the override section should be present");
        assertTrue(text.contains("\u0040deepseek-ai/dsh-app-boot"), "the boot library should be named");
        assertTrue(text.contains("0.1.6-alpha.1"), "held to the version asked for");
    }

    /// A boot library other than the launcher's own is written as given.
    ///
    /// @throws IOException when the file cannot be read back
    @Test
    void writesTheBootLibraryItIsGiven() throws IOException, DshException {
        DshVersionManager.writeManifest(prefix, "0.1.6-alpha.1", "0.1.5-rc.2", null,
                DshDependencyPolicy.LATEST);

        assertTrue(Files.readString(prefix.resolve("pnpm-workspace.yaml")).contains("0.1.5-rc.2"),
                "the override should say what was asked for, not the launcher's own version");
    }

    /// npm answers about a specifier with an array, and that is what has to be read.
    ///
    /// Captured verbatim from `npm view @deepseek-ai/dsh@0.1.5-alpha.2 dependencies --json`, because
    /// the shape is the whole point: the first version of this read only the object form, found
    /// nothing in the array, and would have applied the policy to precisely nothing.
    @Test
    void theDependenciesAreReadFromTheShapeNpmActuallyAnswersWith() {
        String answer = "[{\"commander\":\"^15.0.0\","
                + "\"@deepseek-ai/cordis\":\"^4.0.2\","
                + "\"@deepseek-ai/cordis-plugin-loader\":\"^1.0.3\"}]";

        com.google.gson.JsonObject declared = DshVersionManager.declaredDependencies(answer);

        assertNotNull(declared, "an array of one object is npm's answer for a named version");
        assertEquals("^4.0.2", declared.get("\u0040deepseek-ai/cordis").getAsString());
        assertEquals("4.0.2", DshVersionManager.floorOf(
                declared.get("\u0040deepseek-ai/cordis").getAsString()));
    }

    /// A package that declares nothing, and an answer that is not JSON, both say "nothing to hold".
    @Test
    void anAnswerWithNoDependenciesIsNotAnError() {
        assertNull(DshVersionManager.declaredDependencies("[]"));
        assertNull(DshVersionManager.declaredDependencies("null"));
        assertNull(DshVersionManager.declaredDependencies("not json"));
    }

    /// Only the ranges that have a floor are read; the rest are left alone.
    ///
    /// A range whose floor this cannot name must not be guessed at, and a dependency with no override
    /// simply resolves freely — which is the policy's own answer for anything it does not hold.
    @Test
    void aFloorIsReadFromTheRangesThatHaveOne() {
        assertEquals("4.0.2", DshVersionManager.floorOf("^4.0.2"));
        assertEquals("1.0.3", DshVersionManager.floorOf("~1.0.3"));
        assertEquals("2.0.0", DshVersionManager.floorOf(">=2.0.0"));
        assertEquals("0.1.5-alpha.2", DshVersionManager.floorOf("0.1.5-alpha.2"),
                "an exact version is its own floor");
        assertEquals("0.1.5-alpha.2", DshVersionManager.floorOf("^0.1.5-alpha.2"),
                "a prerelease floor is still a floor");
    }

    /// What cannot be read is not guessed at.
    @Test
    void aRangeWithNoReadableFloorIsLeftAlone() {
        assertNull(DshVersionManager.floorOf("*"));
        assertNull(DshVersionManager.floorOf("latest"));
        assertNull(DshVersionManager.floorOf(">=1.0.0 <2.0.0"));
        assertNull(DshVersionManager.floorOf("github:owner/repo#sha"));
        assertNull(DshVersionManager.floorOf("workspace:*"));
        assertNull(DshVersionManager.floorOf(""));
        assertNull(DshVersionManager.floorOf(null));
    }

    /// The default holds the vendor's own packages and nothing else.
    ///
    /// The plugin framework and the harness's libraries are published together in lockstep and have
    /// broken a launch twice between them; a pack's own plugins are the author's business and are
    /// left free.
    @Test
    void theDefaultPolicyHoldsOnlyTheVendorsOwnPackages() {
        DshDependencyPolicy policy = DshDependencyPolicy.CORE_PINNED;

        assertTrue(policy.pins("\u0040deepseek-ai/cordis"));
        assertTrue(policy.pins("\u0040deepseek-ai/cordis-plugin-loader"));
        assertTrue(policy.pins("\u0040deepseek-ai/dsh-app-boot"));
        assertFalse(policy.pins("dsh-wildmon"));
        assertFalse(policy.pins("\u0040hellosz/dsh-pets"));

        assertFalse(DshDependencyPolicy.LATEST.pins("\u0040deepseek-ai/cordis"),
                "following upstream holds nothing");
        assertTrue(DshDependencyPolicy.LOCKED.pins("\u0040hellosz/dsh-pets"),
                "the strictest policy holds everything declared");
    }
}