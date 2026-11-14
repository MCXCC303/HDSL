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
package com.mcxcc303.hdsl.runtime;

import com.mcxcc303.hdsl.runtime.node.DshNodeRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Node range the launcher accepts
 * <p>
 * The floor is Node 22.19.0; 23.x is not covered by either branch of the requirement, and 24.0.0
 * starts the line that is always accepted.
 */
class NodeVersionTest {

	@Test
	void the22LineStartsAtMinor19() {
		assertTrue(DshNodeRuntime.isSupportedNodeVersion("22.19.0"));
		assertTrue(DshNodeRuntime.isSupportedNodeVersion("22.19.7"));
		assertFalse(DshNodeRuntime.isSupportedNodeVersion("22.18.9"));
	}

	@Test
	void node23IsNotCoveredByEitherBranch() {
		assertFalse(DshNodeRuntime.isSupportedNodeVersion("23.0.0"));
	}

	@Test
	void node24AndLaterAreSupported() {
		assertTrue(DshNodeRuntime.isSupportedNodeVersion("24.0.0"));
		assertTrue(DshNodeRuntime.isSupportedNodeVersion("25.1.0"));
	}

	@Test
	void aPrereleaseKeepsItsNumericComponents() {
		assertTrue(DshNodeRuntime.isSupportedNodeVersion("22.19.0-rc.1"));
		assertTrue(DshNodeRuntime.isSupportedNodeVersion("v22.19.0"));
	}

	@Test
	void anUnreadableVersionIsNotSupported() {
		assertFalse(DshNodeRuntime.isSupportedNodeVersion("22.x"));
		assertFalse(DshNodeRuntime.isSupportedNodeVersion("22.19.0.1"));
		assertFalse(DshNodeRuntime.isSupportedNodeVersion("unknown"));
		assertFalse(DshNodeRuntime.isSupportedNodeVersion(""));
	}
}
