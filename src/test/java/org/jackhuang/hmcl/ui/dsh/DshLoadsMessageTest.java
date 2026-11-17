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
package org.jackhuang.hmcl.ui.dsh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies what a failed load says. */
class DshLoadsMessageTest {

	@Test
	void aFailureNamesWhatDidNotHappenAndWhy() {
		String message = DshLoads.registry().message("Could not load the plugin list",
				new IllegalStateException("the network is gone"));

		assertTrue(message.contains("Could not load the plugin list"), message);
		assertTrue(message.contains("the network is gone"), message);
	}

	@Test
	void aFailureWithoutAReasonIsNamedByWhatWasBeingLoaded() {
		String message = DshLoads.registry().message("Could not load the plugin list",
				new IllegalStateException());

		assertTrue(message.contains("Could not load the plugin list"), message);
	}

	@Test
	void aFailureThatOnlyHasAReasonSaysIt() {
		String message = DshLoads.registry().message(new IllegalStateException("the network is gone"));

		assertTrue(message.contains("the network is gone"), message);
	}
}
