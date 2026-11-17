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
package com.mcxcc303.hdsl.runtime;

import com.mcxcc303.hdsl.runtime.npm.DshNpm;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins how an npm is run.
 */
class DshNpmTest {
	@Test
	void aBundledNpmIsRunByTheDistributionNode() {
		DshNpm npm = new DshNpm(Path.of("distribution", "lib", "node_modules", "npm", "bin", "npm-cli.js"),
		                        Path.of("distribution", "bin", "node"),
		                        "11.20.0",
		                        Path.of("distribution", "bin"));
		assertEquals(List.of(Path.of("distribution", "bin", "node").toString(),
		                     Path.of("distribution", "lib", "node_modules", "npm", "bin", "npm-cli.js").toString(),
		                     "install"),
		             npm.commandFor(List.of("install")));
	}

	@Test
	void aSystemNpmRunsItself() {
		DshNpm npm = new DshNpm(Path.of("npm"), null, "11.20.0", Path.of("usr", "bin"));
		assertEquals(List.of("npm", "install"), npm.commandFor(List.of("install")));
	}

	@Test
	void thePathPutsTheNpmFirst() {
		DshNpm npm = new DshNpm(Path.of("npm"), null, "11.20.0", Path.of("usr", "bin"));
		Map<String, String> environment = npm.pathEnvironment();
		String[] entries = environment.get("PATH").split(Pattern.quote(File.pathSeparator));
		assertEquals(Path.of("usr", "bin").toString(), entries[0]);
	}
}
