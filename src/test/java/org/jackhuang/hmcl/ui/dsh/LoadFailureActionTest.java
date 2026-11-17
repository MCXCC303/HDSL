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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that every page which shows a load gives its failure an action. */
class LoadFailureActionTest {
	private static final Path ROOT = Path.of("src/main/java/org/jackhuang/hmcl/ui/dsh");

	@Test
	void everyPageThatShowsALoadGivesItsFailureAnAction() throws IOException {
		List<String> withoutAction = new ArrayList<>();
		for (Path file : sources()) {
			if (file.getFileName().toString().equals("DshLoads.java")) {
				// DshLoads runs the loads; it is not a page.
				continue;
			}
			String source = Files.readString(file, StandardCharsets.UTF_8);
			if (!source.contains("setLoading(true)") && !source.contains("loads.run(")) {
				continue;
			}
			if (!source.contains("setOnFailedAction")) {
				withoutAction.add(ROOT.relativize(file).toString());
			}
		}
		assertTrue(withoutAction.isEmpty(),
				"a page that shows a load has to give its failure an action: " + withoutAction);
	}

	/**
	 * Returns the sources of the pages.
	 *
	 * @throws IOException when the sources cannot be walked
	 */
	private static List<Path> sources() throws IOException {
		try (Stream<Path> walk = Files.walk(ROOT)) {
			return walk.filter(path -> path.toString().endsWith(".java")).toList();
		}
	}
}
