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
package com.mcxcc303.hdsl.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DshJdkTest {

	@Test
	void theFixReleasesAreNotAffected() {
		assertFalse(DshJdk.hasVulnerableBase64Intrinsic("17.0.21.1"));
		assertFalse(DshJdk.hasVulnerableBase64Intrinsic("21.0.13.1"));
		assertFalse(DshJdk.hasVulnerableBase64Intrinsic("25.0.5.1"));
		assertFalse(DshJdk.hasVulnerableBase64Intrinsic("27.0.1.1"));
	}

	@Test
	void theReleasesBeforeTheFixAreAffected() {
		assertTrue(DshJdk.hasVulnerableBase64Intrinsic("17.0.21"));
		assertTrue(DshJdk.hasVulnerableBase64Intrinsic("21.0.13"));
		assertTrue(DshJdk.hasVulnerableBase64Intrinsic("21.0.13.0.3"));
		assertTrue(DshJdk.hasVulnerableBase64Intrinsic("25.0.5"));
		assertTrue(DshJdk.hasVulnerableBase64Intrinsic("27.0.1"));
	}

	@Test
	void theIntrinsicAppearsWithJava17Point6() {
		assertFalse(DshJdk.hasVulnerableBase64Intrinsic("17.0.5"));
		assertTrue(DshJdk.hasVulnerableBase64Intrinsic("17.0.6"));
	}

	@Test
	void theUnmaintainedReleaseLinesAreAffected() {
		assertTrue(DshJdk.hasVulnerableBase64Intrinsic("18"));
		assertTrue(DshJdk.hasVulnerableBase64Intrinsic("20.0.2"));
		assertTrue(DshJdk.hasVulnerableBase64Intrinsic("22.0.1"));
		assertTrue(DshJdk.hasVulnerableBase64Intrinsic("24.0.2"));
		assertTrue(DshJdk.hasVulnerableBase64Intrinsic("26"));
	}

	@Test
	void theReleasesWithoutTheIntrinsicAreNotAffected() {
		assertFalse(DshJdk.hasVulnerableBase64Intrinsic("11.0.22"));
		assertFalse(DshJdk.hasVulnerableBase64Intrinsic("1.8.0_402"));
		assertFalse(DshJdk.hasVulnerableBase64Intrinsic("28.0.1"));
	}

	@Test
	void theBuildMetadataDoesNotChangeTheVerdict() {
		assertTrue(DshJdk.hasVulnerableBase64Intrinsic("21.0.13.0.3+1"));
		assertFalse(DshJdk.hasVulnerableBase64Intrinsic("21.0.13.1+9-LTS"));
	}

	@Test
	void theFixedVersionIsNamedForTheMaintainedReleaseLines() {
		assertEquals("17.0.21.1", DshJdk.base64IntrinsicFixedVersion("17.0.9"));
		assertEquals("21.0.13.1", DshJdk.base64IntrinsicFixedVersion("21.0.5"));
		assertEquals("25.0.5.1", DshJdk.base64IntrinsicFixedVersion("25.0.1"));
		assertEquals("27.0.1.1", DshJdk.base64IntrinsicFixedVersion("27"));
	}

	@Test
	void theUnmaintainedReleaseLinesHaveNoFixedVersion() {
		assertNull(DshJdk.base64IntrinsicFixedVersion("22.0.1"));
		assertNull(DshJdk.base64IntrinsicFixedVersion("11.0.22"));
	}
}
