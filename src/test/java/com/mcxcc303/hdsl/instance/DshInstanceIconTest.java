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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the instance icon set is complete and resolvable.
 *
 * The images are named by hand, so a typo is a silent blank icon in the chooser. This checks the name
 * against the bundled resources instead.
 */
class DshInstanceIconTest {
	@Test
	void everyIconResolvesToABundledImage() {
		for (DshInstanceIcon icon : DshInstanceIcon.values()) {
			assertNotNull(DshInstanceIcon.class.getResource("/assets/img/" + icon.assetName() + ".png"),
			              "no image is bundled for " + icon.name() + " (looked for " + icon.assetName() + ".png)");
		}
	}

	@Test
	void everyIconHasADenseVariant() {
		// The set is a 32-pixel image with a 64-pixel @2x beside it, and a
		// missing half is invisible until someone looks at a dense display.
		for (DshInstanceIcon icon : DshInstanceIcon.values()) {
			assertNotNull(DshInstanceIcon.class.getResource("/assets/img/" + icon.assetName() + "@2x.png"),
			              "no @2x image is bundled for " + icon.name());
		}
	}

	@Test
	void anUnknownNameFallsBackToTheDefault() {
		assertSame(DshInstanceIcon.DEFAULT, DshInstanceIcon.of("no-such-icon"));
		assertSame(DshInstanceIcon.DEFAULT, DshInstanceIcon.of(null));
		assertSame(DshInstanceIcon.DSH_APPLICATION, DshInstanceIcon.of("dsh_application"));
	}
}
