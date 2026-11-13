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

import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pins that command noise does not hide the answer. */
class DshJsonTest {

	@Test
	void theAnswerIsFoundAfterWhatTheCommandSaidFirst() {
		JsonElement parsed = DshJson.parseFirstValue("""
			npm warn deprecated
			npm notice
			["1.0.0", "2.0.0"]
			""");
		assertEquals(2, parsed == null ? -1 : parsed.getAsJsonArray().size());
		assertEquals("1.0.0", parsed == null ? null : parsed.getAsJsonArray().get(0).getAsString());
	}

	@Test
	void outputWithoutAValueIsNoValue() {
		assertNull(DshJson.parseFirstValue(""));
		assertNull(DshJson.parseFirstValue("npm warn nothing here"));
	}
}
