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
package com.mcxcc303.hdsl.launch;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for reading the address out of the upstream readiness line.
 */
class DshReadinessLineTest {
	private static final String TOKEN = "dcuszlMez30wtcJpMkS1-VosQcMaTfgE7g4i_mc6Tq8";

	@Test
	void theAddressKeepsTheTokenItWasPrintedWith() {
		String line = "dsh web: http://127.0.0.1:3229/?token=" + TOKEN;

		Optional<URI> parsed = DshProcess.parseWebUrl(line);

		assertTrue(parsed.isPresent(), "a readiness line must be recognised");
		assertEquals("http://127.0.0.1:3229/?token=" + TOKEN, parsed.get().toString());
		assertEquals(TOKEN,
		             parsed.get().getQuery().substring("token=".length()),
		             "the token is the credential the browser presents; losing it opens a page that is refused");
	}

	@Test
	void theOlderSpellingsAreStillRead() {
		assertEquals("http://127.0.0.1:4061",
		             DshProcess.parseWebUrl("dsh web: http://127.0.0.1:4061").orElseThrow().toString());
		assertEquals("http://127.0.0.1:4061/",
		             DshProcess.parseWebUrl("dsh web: http://127.0.0.1:4061/").orElseThrow().toString());
	}

	@Test
	void aLanSuffixIsNotMistakenForTheAddress() {
		String line = "dsh web: http://127.0.0.1:3229/?token=" + TOKEN
		              + " (LAN: http://192.168.1.5:3229/?token=" + TOKEN + ")";

		assertEquals("http://127.0.0.1:3229/?token=" + TOKEN,
		             DshProcess.parseWebUrl(line).orElseThrow().toString());
	}

	@Test
	void whatIsNotAReadinessLineSaysSo() {
		assertTrue(DshProcess.parseWebUrl("").isEmpty());
		assertTrue(DshProcess.parseWebUrl("[INFO] starting up").isEmpty());
		assertTrue(DshProcess.parseWebUrl("dsh web: http://0.0.0.0:3229/?token=" + TOKEN).isEmpty(),
		           "only the loopback address is the one the launcher opens");
		assertTrue(DshProcess.parseWebUrl("dsh web: http://127.0.0.1:not-a-port").isEmpty());
	}
}
