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

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import com.mcxcc303.hdsl.runtime.DshPnpm;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pnpm floor below which harness installation is refused
 * <p>
 * pnpm 8 and 9 reject the workspace file; 10.0 through 10.8 install without the native build
 * scripts, leaving a tree that looks complete until it is used.
 */
class HarnessInstallerPnpmTest {

		private static DshPnpm pnpm(String pnpmVersion) {
		return new DshPnpm(Path.of("pnpm"), null, pnpmVersion, Path.of("bin"));
	}

	@Test
	void tenPointNineIsTheBoundary() {
		assertTrue(HarnessInstaller.isPnpmTooOld("8.15.9"));
		assertTrue(HarnessInstaller.isPnpmTooOld("9.15.0"));
		assertTrue(HarnessInstaller.isPnpmTooOld("10.0.0"));
		assertTrue(HarnessInstaller.isPnpmTooOld("10.8.9"));
		assertFalse(HarnessInstaller.isPnpmTooOld("10.9"));
		assertFalse(HarnessInstaller.isPnpmTooOld("10.9.0"));
		assertFalse(HarnessInstaller.isPnpmTooOld("10.9.1"));
		assertFalse(HarnessInstaller.isPnpmTooOld("10.34.7"));
		assertFalse(HarnessInstaller.isPnpmTooOld("12.14.0"));
	}

	@Test
	void aVersionIsReadThroughItsPrefixAndSuffix() {
		assertFalse(HarnessInstaller.isPnpmTooOld("v10.9.0"));
		assertFalse(HarnessInstaller.isPnpmTooOld("10.9.0+build"));
		assertTrue(HarnessInstaller.isPnpmTooOld("v9.15.0"));
	}

	@Test
	void aPreReleaseOfTenPointNineIsNotTenPointNine() {
		assertTrue(HarnessInstaller.isPnpmTooOld("10.9.0-rc.1"));
	}

	@Test
	void aVersionThatCannotBeReadDoesNotBlock() {
		assertFalse(HarnessInstaller.isPnpmTooOld(null));
		assertFalse(HarnessInstaller.isPnpmTooOld(""));
		assertFalse(HarnessInstaller.isPnpmTooOld("unknown"));
	}

	@Test
	void theRefusalNamesTheVersion() {
		DshException refusal = assertThrows(DshException.class,
		                                   () -> HarnessInstaller.requireSupportedPnpm(pnpm("10.8.9")));
		assertTrue(refusal.getMessage().contains("10.8.9 is too old"), refusal.getMessage());
		assertTrue(refusal.getMessage().contains("10.9 or newer"), refusal.getMessage());
	}
}
