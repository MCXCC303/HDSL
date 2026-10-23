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
package com.mcxcc303.hdsl.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The picture a catalogue entry shows: the repository owner's, at GitHub's fixed address.
 */
class CatalogPluginAvatarTest {

	private static Catalog.Plugin entry(String owner) {
		return new Catalog.Plugin("dsh-example", owner, "https://github.com/" + owner + "/dsh-example",
		                          "ui", null, null, null, null, 0, 0, null, null);
	}

	@Test
	void theOwnersPictureIsTheOneGithubServes() {
		assertEquals("https://github.com/AnonyJcy.png?size=64", entry("AnonyJcy").ownerAvatarUrl());
	}

	@Test
	void anEntryWithoutAnOwnerHasNoPicture() {
		assertNull(entry("").ownerAvatarUrl());
	}
}
