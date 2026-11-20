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
package com.mcxcc303.hdsl.cli;

import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.instance.DshPortMode;
import com.mcxcc303.hdsl.instance.InstanceFolders;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceCommandsTest {
	@TempDir
	Path folder;

	private Supplier<List<Path>> previousFolders;

	@BeforeEach
	void pointAtTheTestFolder() {
		previousFolders = InstanceFolders.setFolders(() -> List.of(folder));
	}

	@AfterEach
	void restoreTheFolders() {
		InstanceFolders.setFolders(previousFolders);
	}

	@Test
	void instanceListWithSizeShowsTheSizeOfEachInstance() throws IOException {
		writeInstance();

		CliRun run = CliRun.of("instance", "list", "--size");

		assertEquals(0, run.code(), run.err());
		assertTrue(run.out().contains("SIZE"), run.out());
		assertTrue(run.out().contains("12.0 KiB"), run.out());
	}

	@Test
	void instanceListWithoutSizeHasNoSizeColumn() throws IOException {
		writeInstance();

		CliRun run = CliRun.of("instance", "list");

		assertEquals(0, run.code(), run.err());
		assertFalse(run.out().contains("SIZE"), run.out());
	}

	private void writeInstance() throws IOException {
		Path directory = folder.resolve("mine");
		Files.createDirectories(directory.resolve("home"));
		Files.write(directory.resolve("home").resolve("log"), new byte[5000]);
		JsonUtils.writeToJsonFile(directory.resolve("instance.json"),
		                          new DshInstance("mine",
		                                         "0.2.0-rc.2",
		                                         "web",
		                                         folder.resolve("workspace").toString(),
		                                         null,
		                                         DshHomeMode.ISOLATED,
		                                         null,
		                                         List.of(),
		                                         Map.of(),
		                                         DshInstanceIcon.DEFAULT.id(),
		                                         null,
		                                         DshPortMode.AUTO,
		                                         0,
		                                         0L));
	}
}
