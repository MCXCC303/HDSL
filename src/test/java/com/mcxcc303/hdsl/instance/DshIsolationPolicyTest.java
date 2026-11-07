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
package com.mcxcc303.hdsl.instance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the rule that decides whether a new instance keeps its own home.
 */
class DshIsolationPolicyTest {
	@Test
	void missingOrInvalidPolicyDefaultsToIsolation() {
		assertEquals(DshIsolationPolicy.ALWAYS, DshIsolationPolicy.of(null));
		assertEquals(DshIsolationPolicy.ALWAYS, DshIsolationPolicy.of(""));
		assertEquals(DshIsolationPolicy.ALWAYS, DshIsolationPolicy.of("  "));
		assertEquals(DshIsolationPolicy.ALWAYS, DshIsolationPolicy.of("nonsense"));
		assertEquals(DshIsolationPolicy.ALWAYS, DshIsolationPolicy.of("always"));
		assertEquals(DshIsolationPolicy.NEVER, DshIsolationPolicy.of("NEVER"));
		assertEquals(DshIsolationPolicy.WITH_PLUGINS, DshIsolationPolicy.of(" modded "));
	}

	@Test
	void alwaysAndNeverDoNotDependOnWhatIsInstalled() {
		assertEquals(DshHomeMode.ISOLATED, DshIsolationPolicy.ALWAYS.homeMode(false, DshHomeMode.VERSION_SHARED));
		assertEquals(DshHomeMode.ISOLATED, DshIsolationPolicy.ALWAYS.homeMode(true, DshHomeMode.VERSION_SHARED));
		assertEquals(DshHomeMode.VERSION_SHARED, DshIsolationPolicy.NEVER.homeMode(true, DshHomeMode.VERSION_SHARED));
		assertEquals(DshHomeMode.VERSION_SHARED, DshIsolationPolicy.NEVER.homeMode(false, DshHomeMode.VERSION_SHARED));
	}

	@Test
	void theMiddleAnswerLooksAtWhatTheInstallationBrings() {
		assertEquals(DshHomeMode.ISOLATED,
		             DshIsolationPolicy.WITH_PLUGINS.homeMode(true, DshHomeMode.VERSION_SHARED),
		             "an instance given plugins of its own keeps them to itself");
		assertEquals(DshHomeMode.VERSION_SHARED,
		             DshIsolationPolicy.WITH_PLUGINS.homeMode(false, DshHomeMode.VERSION_SHARED),
		             "a bare instance shares, which is what the mode below says");
		assertEquals(DshHomeMode.GLOBAL,
		             DshIsolationPolicy.WITH_PLUGINS.homeMode(false, DshHomeMode.GLOBAL),
		             "and what it shares is whatever the mode below names");
	}
}
