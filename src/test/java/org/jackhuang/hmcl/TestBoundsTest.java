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
 * Pins that timeout bounds on this test suite use valid configuration keys recognized by JUnit.
 * <p>
 * <b>Historical Incident:</b> The suite timeout was originally configured as
 * {@code junit.jupiter.execution.timeout.method.default}. JUnit does not recognize this property key
 * and ignores it silently. Consequently, when a CI runner failed to bring up the JavaFX toolkit, the build
 * hung undetected for 76 minutes without triggering a timeout.
 * <p>
 * This class serves as a tripwire ensuring:
 * <ul>
 *   <li>The global timeout uses the valid key {@code junit.jupiter.execution.timeout.default} (120s).</li>
 *   <li>Thread dumps are enabled upon timeout ({@code junit.jupiter.execution.timeout.threaddump.enabled}).</li>
 *   <li>The invalid key {@code junit.jupiter.execution.timeout.method.default} is explicitly asserted {@code null}.</li>
 * </ul>
 */
class TestBoundsTest {

	/**
	 * Verifies that the global test execution timeout is set in JUnit's recognized format.
	 */
	@Test
	void everyTestAndLifecycleMethodIsBounded() {
		String bound = System.getProperty("junit.jupiter.execution.timeout.default");

		assertNotNull(bound, "the suite has no timeout bound, so a wedged test holds the build");
		assertEquals("120s", bound, "the bound is the one the build sets, in JUnit's own spelling");
	}

	/**
	 * Verifies that thread dumps are generated when a test execution timeout occurs.
	 */
	@Test
	void aTestThatReachesItsBoundIsReportedWithItsStacks() {
		assertEquals("true", System.getProperty("junit.jupiter.execution.timeout.threaddump.enabled"),
				"a timeout without a thread dump says that something hung and not what did");
	}

	/**
	 * Asserts that the typo'd property {@code junit.jupiter.execution.timeout.method.default} is null.
	 * JUnit silently ignores this key, which previously caused builds to hang indefinitely.
	 */
	@Test
	void theNameJUnitDoesNotReadIsNotTheOneUsed() {
		assertNull(System.getProperty("junit.jupiter.execution.timeout.method.default"),
				"`junit.jupiter.execution.timeout.method.default` is not a key JUnit reads: it is ignored "
						+ "in silence. The names it does read are `junit.jupiter.execution.timeout.default` "
						+ "and its `…testable.method…`, `…lifecycle.method…`, `…beforeall.method…` siblings.");
	}
}
