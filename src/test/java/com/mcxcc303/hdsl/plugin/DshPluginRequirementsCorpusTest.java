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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies range satisfaction against the node-semver corpus. */
class DshPluginRequirementsCorpusTest {
	private static final String CASES = "/semver/npm-satisfies.tsv";
	private static final String DIFFERENCES = "/semver/npm-differences.tsv";

	private static List<String[]> read(String resource) throws IOException {
		InputStream stream = DshPluginRequirementsCorpusTest.class.getResourceAsStream(resource);
		assertNotNull(stream, "the corpus is not on the classpath: " + resource);
		List<String[]> entries = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\t", -1);
				if (parts.length >= 3) {
					entries.add(parts);
				}
			}
		}
		return entries;
	}

	private static String text(String field) {
		return field.replace("\\t", "\t");
	}

	@Test
	void everyCaseNpmChecksItsOwnRangesWithIsAnsweredTheSameWay() throws IOException {
		List<String[]> cases = read(CASES);
		assertTrue(cases.size() > 100, "only " + cases.size() + " cases were read, so this is not reading them");

		List<String> wrong = new ArrayList<>();
		for (String[] entry : cases) {
			boolean expected = entry[0].equals("yes");
			boolean answered = DshPluginRequirements.satisfies(text(entry[1]), text(entry[2]));
			if (answered != expected) {
				wrong.add(entry[1] + " -> " + entry[2]);
				System.out.println(
						"DIFF\t" + (expected ? "missed" : "wrongly fits") + "\t" + entry[1] + "\t" + entry[2]);
			}
		}
		assertTrue(wrong.isEmpty(),
		           wrong.size() + " of " + cases.size() +
		           " cases are answered differently from npm and are not written down as differences: " + wrong);
	}

	@Test
	void everyDifferenceFromNpmIsStillThere() throws IOException {
		List<String[]> differences = read(DIFFERENCES);
		assertTrue(differences.size() > 10,
		           "only " + differences.size() + " differences were read, so this is not reading them");

		for (String[] entry : differences) {
			// "missed" is npm's yes that this launcher does not give, and "wrongly fits" is the
			// same disagreement the other way round: the two words are npm's answer.
			boolean npmSays = entry[0].equals("missed");
			assertEquals(!npmSays,
			             DshPluginRequirements.satisfies(text(entry[1]), text(entry[2])),
			             "this launcher now answers " + entry[1] + " -> " + entry[2] + " the way npm does," +
			             " so it is no longer a difference and has to be struck off the list");
		}
	}
}