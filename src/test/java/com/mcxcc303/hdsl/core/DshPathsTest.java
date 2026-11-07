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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies rules for instance name validation.
 */
class DshPathsTest {
	private static final List<String> ACCEPTED = List.of("default",
	                                                     "e2e1",
	                                                     "my instance",          // a space inside is a space Windows keeps
	                                                     "2026-09-26",
	                                                     "实例",
	                                                     "a.b",                  // a dot inside is not a trailing dot
	                                                     ".hidden",
	                                                     "dsh_2",
	                                                     "a~b");                 // '~' only matters as a whole segment

	private static final List<String> REFUSED = List.of("",                     // empty names no directory
	                                                    ".",
	                                                    "..",
	                                                    "~",                    // the home directory in every shell
	                                                    "a/b",                  // one segment, not two
	                                                    "a\\b",
	                                                    "C:instance",           // a drive separator inside a segment
	                                                    "a:b",
	                                                    "trailing.",            // Windows strips the dot
	                                                    "CON",                  // reserved devices, with and without an extension
	                                                    "con",
	                                                    "CON.txt",
	                                                    "prn",
	                                                    "aux",
	                                                    "nul",
	                                                    "COM1",
	                                                    "com9",
	                                                    "LPT1",
	                                                    "lpt9",
	                                                    "com\u00b9",            // the superscript spellings Windows also reserves
	                                                    "clock$",
	                                                    "a<b",
	                                                    "a>b",
	                                                    "a\"b",
	                                                    "a|b",
	                                                    "a?b",
	                                                    "a*b",
	                                                    "a\u0000b",             // a NUL ends the string halfway through
	                                                    "a\u0007b");            // a control character

	@Test
	void theNamesAnInstanceMayBeCalledAreAccepted() {
		for (String name : ACCEPTED) {
			assertTrue(DshPaths.isUsableSegment(name), name + " should be usable as an instance name");
		}
	}

	@Test
	void theNamesThatWouldBreakAFolderAreRefused() {
		for (String name : REFUSED) {
			assertFalse(DshPaths.isUsableSegment(name), name + " should not be usable as an instance name");
		}
	}

	@Test
	void aNameIsTrimmedBeforeItIsJudged() throws Exception {
		assertTrue(DshPaths.isUsableSegment("  e2e1  "));
		assertEquals("e2e1", DshPaths.instanceDirectory("  e2e1  ").getFileName().toString());
	}

	@Test
	void aNullNameIsNotAName() {
		assertFalse(DshPaths.isUsableSegment(null));
	}

	@Test
	void aRefusedNameIsAnErrorRatherThanADirectoryBesideTheInstances() throws Exception {
		DshException refused = assertThrows(DshException.class, () -> DshPaths.instanceDirectory("../escape"));
		assertTrue(refused.getMessage().contains("instance id"), refused.getMessage());
	}

	@Test
	void whatTheRuleAcceptsCanBeCreated(@TempDir Path root) throws Exception {
		for (String name : ACCEPTED) {
			Path directory = Files.createDirectory(root.resolve(name));
			assertTrue(Files.isDirectory(directory), name + " should have been created");
		}
	}
}
