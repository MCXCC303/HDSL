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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the rule that decides whether a plugin is shown for an instance.
///
/// The ranges are the ones plugins really publish, prereleases and alternatives
/// included, and getting them wrong hides plugins from the list — which is why the
/// rule this launcher uses is the one npm itself uses.
class DshPluginRequirementsTest {
    @Test
    void alternativesAreTriedInTurn() {
        // What the marketplace plugin publishes.
        String range = "^0.1.0-rc.7 || ^0.1.1-rc.2 || ^0.1.2-alpha.2";

        assertTrue(DshPluginRequirements.satisfies(range, "0.1.2-alpha.2"));
        assertTrue(DshPluginRequirements.satisfies(range, "0.1.1-rc.2"));
        assertTrue(DshPluginRequirements.satisfies(range, "0.1.0-rc.7"));
        assertTrue(DshPluginRequirements.satisfies(range, "0.1.0-rc.8"), "a later rc of the same line");
        assertTrue(DshPluginRequirements.satisfies(range, "0.1.3"),
                "0.1.3 is a release above the bound, so the caret that names 0.1.2-alpha.2 holds it");
        assertFalse(DshPluginRequirements.satisfies(range, "0.2.0"), "past the line every part of it names");
    }

    @Test
    void aPrereleaseIsNotTheReleaseItLeadsTo() {
        assertFalse(DshPluginRequirements.satisfies("^0.1.2", "0.1.2-alpha.2"),
                "npm does not let a prerelease satisfy a range that does not name one");
        assertTrue(DshPluginRequirements.satisfies("^0.1.2", "0.1.2"));
        assertTrue(DshPluginRequirements.satisfies("^0.1.2-alpha.2", "0.1.2-alpha.3"));
    }

    @Test
    void caretsHoldWithinTheLeftmostNonZeroPart() {
        assertTrue(DshPluginRequirements.satisfies("^1.2.3", "1.9.0"));
        assertFalse(DshPluginRequirements.satisfies("^1.2.3", "2.0.0"));
        assertTrue(DshPluginRequirements.satisfies("^0.2.3", "0.2.9"));
        assertFalse(DshPluginRequirements.satisfies("^0.2.3", "0.3.0"));
        assertTrue(DshPluginRequirements.satisfies("~1.2.3", "1.2.9"));
        assertFalse(DshPluginRequirements.satisfies("~1.2.3", "1.3.0"));
    }

    @Test
    void comparisonsAndConjunctionsWork() {
        assertTrue(DshPluginRequirements.satisfies(">=1.0.0 <2.0.0", "1.5.0"));
        assertFalse(DshPluginRequirements.satisfies(">=1.0.0 <2.0.0", "2.0.0"));
        assertTrue(DshPluginRequirements.satisfies("*", "0.0.1"));
        assertTrue(DshPluginRequirements.satisfies("1.2.3", "1.2.3"));
        assertFalse(DshPluginRequirements.satisfies("1.2.3", "1.2.4"));
    }

    @Test
    void aPluginFitsWhenEveryJudgeableRequirementIsMet() {
        JsonObject peers = JsonParser.parseString("""
                {"@deepseek-ai/dsh-settings": "^0.1.0-rc.7 || ^0.1.1-rc.2 || ^0.1.2-alpha.2",
                 "@deepseek-ai/cordis": "^4.0.1",
                 "@deepseek-ai/schemastery": "^3.18.1"}
                """).getAsJsonObject();

        assertTrue(DshPluginRequirements.fits(peers, Map.of("@deepseek-ai/dsh-settings", "0.1.2-alpha.2")),
                "a package the instance does not have cannot be judged, and is not held against it");
        assertFalse(DshPluginRequirements.fits(peers, Map.of("@deepseek-ai/dsh-settings", "0.2.0")),
                "a package it does have is judged");
        assertTrue(DshPluginRequirements.fits(null, Map.of()), "a plugin that asks for nothing fits");
    }
}
