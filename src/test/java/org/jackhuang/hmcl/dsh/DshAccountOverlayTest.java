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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// What a launch hands the harness for an account, as text.
///
/// The capacities matter as much as the address. A route the launcher writes is not in the
/// harness's catalogue, so a model on it is the size the launcher says and nothing else — and
/// `pi-ai`'s fallback for a model it cannot size is 256 Ki, a quarter of what the harness's own
/// DeepSeek route gives the same model.
class DshAccountOverlayTest {
    @TempDir
    private Path home;

    private DshInstance instance() {
        return new DshInstance("test", "0.1.6-alpha.2", "web", home.toString(), "system",
                DshHomeMode.CUSTOM, home.toString(), List.of(), Map.of(), null, null, null, 0, 0L);
    }

    private String overlayFor(String vendor) {
        DshAccountOverlay.Prepared prepared = DshAccountOverlay
                .prepare(instance(), new DshAccount(vendor, "sk-test", null, null))
                .orElseThrow();
        return prepared.render();
    }

    @Test
    void aDeepSeekRouteSaysTheCapacityTheAdaptersOwnRouteHas() {
        String yaml = overlayFor("deepseek");
        assertTrue(yaml.contains("      MCXCC303:") || yaml.contains("      DeepSeek:"), yaml);
        assertTrue(yaml.contains("        defaultContextWindow: 1000000\n"), yaml);
        assertTrue(yaml.contains("        defaultMaxTokens: 256000\n"), yaml);
    }

    @Test
    void theWebSearchIsPointedAtTheKeyTheLaunchCarries() {
        String yaml = overlayFor("deepseek");

        assertTrue(yaml.contains("- id: web-search-deepseek\n"), yaml);
        assertTrue(yaml.contains("  name: \"@deepseek-ai/dsh-web-search-deepseek\"\n"),
                "the harness mounts its search under a package name, which has to survive YAML: " + yaml);
        assertTrue(yaml.contains("    apiKeyEnv: " + DshAccountOverlay.KEY_ENVIRONMENT_VARIABLE + "\n"),
                "the search reads the key the launcher injects, by the only name it has: " + yaml);
    }

    @Test
    void anotherVendorsRouteIsNotSizedByDeepSeeksNumbers() {
        String yaml = overlayFor("openrouter");
        assertFalse(yaml.contains("defaultContextWindow"), yaml);
        assertFalse(yaml.contains("defaultMaxTokens"), yaml);
    }

    @Test
    void aSupplierThePersonAddedArrivesWithItsAddress() {
        // What the harness reads, and the half of the defect that was visible from outside: a route
        // written without a `baseURL` is refused with "needs a baseURL; the installed catalog does
        // not describe this route", because a route the launcher invents is not one the harness can
        // look an address up for.
        DshVendor added = DshVendor.discovered("opencode", "OpenCode", "https://api.opencode.ai/v1");
        java.util.List<DshVendor> addedVendors =
                org.jackhuang.hmcl.setting.SettingsManager.settings().getCustomVendors();
        addedVendors.add(added);
        try {
            String yaml = overlayFor("opencode");

            assertTrue(yaml.contains("        baseURL: \"https://api.opencode.ai/v1\"\n"), yaml);
            assertTrue(yaml.contains("        api: openai-completions\n"), yaml);
        } finally {
            addedVendors.remove(added);
        }
    }
}
