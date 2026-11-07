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
package org.jackhuang.hmcl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that test timeout bounds and execution settings match JUnit engine configuration.
 */
class TestBoundsTest {

	@Test
	void everyTestAndLifecycleMethodIsBounded() {
		String bound = System.getProperty("junit.jupiter.execution.timeout.default");

		assertNotNull(bound, "the suite has no timeout bound, so a wedged test holds the build");
		assertEquals("120s", bound, "the bound is the one the build sets, in JUnit's own spelling");
	}

	@Test
	void aTestThatReachesItsBoundIsReportedWithItsStacks() {
		assertEquals("true", System.getProperty("junit.jupiter.execution.timeout.threaddump.enabled"),
				"a timeout without a thread dump says that something hung and not what did");
	}

	@Test
	void theNameJUnitDoesNotReadIsNotTheOneUsed() {
		assertNull(System.getProperty("junit.jupiter.execution.timeout.method.default"),
				"`junit.jupiter.execution.timeout.method.default` is not a key JUnit reads: it is ignored "
						+ "in silence. The names it does read are `junit.jupiter.execution.timeout.default` "
						+ "and its `…testable.method…`, `…lifecycle.method…`, `…beforeall.method…` siblings.");
	}
}
