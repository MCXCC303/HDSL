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

/**
 * Tests for YAML scalar value formatting.
 */
class YamlScalarTest {
	@Test
	void aValueYamlWouldReadAsSomethingElseIsQuoted() {
		assertEquals("\"@deepseek-ai/dsh-web-search-deepseek\"", YamlScalar.of("@deepseek-ai/dsh-web-search-deepseek"));
		assertEquals("\"true\"", YamlScalar.of("true"));
		assertEquals("0.1.7-rc.1", YamlScalar.of("0.1.7-rc.1"));
		assertEquals("a@b", YamlScalar.of("a@b"), "the rule is about the first character");
	}
}
