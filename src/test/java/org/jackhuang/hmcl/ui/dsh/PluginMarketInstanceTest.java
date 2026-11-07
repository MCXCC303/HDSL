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

import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests verifying plugin market instance resolution and matching logic.
 */
class PluginMarketInstanceTest {

	private static DshInstance instance(String id, int port) {
		return new DshInstance(id, "0.1.7-rc.2", DshInstance.DEFAULT_PROFILE,
				System.getProperty("java.io.tmpdir"), DshNodeRuntime.SYSTEM, DshHomeMode.ISOLATED,
				null, List.of(), Map.of(), null, null, null, port, 0L);
	}

	@Test
	void theInstanceIsFoundByIdEvenWhenTheTwoCopiesDiffer() {
		DshInstance held = instance("target", 3800);
		DshInstance onDisk = instance("target", 4022);

		assertEquals(onDisk, PluginMarketPage.matchingInstance(List.of(instance("other", 1), onDisk), held),
				"the picker's own copy is what gets selected, because that is the one it holds");
	}

	@Test
	void anInstanceThatIsNotInTheListIsNotFound() {
		assertNull(PluginMarketPage.matchingInstance(List.of(instance("other", 1)), instance("gone", 2)),
				"an instance that is no longer in the list leaves the picker to its own choice");
	}

	@Test
	void theNameIsWhatIsMatchedRatherThanThePosition() {
		DshInstance second = instance("second", 2);

		assertEquals(second, PluginMarketPage.matchingInstance(List.of(instance("first", 1), second),
						instance("second", 99)),
				"the list is ordered newest first, so a picker that took its own first entry would be"
						+ " wrong for every instance but one");
	}
}
