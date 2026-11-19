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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DshDiskUsageTest {

	@Test
	void aSingleFileIsCountedInWholeBlocks(@TempDir Path directory) throws IOException {
		Path file = directory.resolve("file");
		Files.write(file, new byte[5000]);

		assertEquals(8192, DshDiskUsage.usageBytes(file));
	}

	@Test
	void everyFileOfATreeIsCountedInWholeBlocks(@TempDir Path directory) throws IOException {
		Files.write(directory.resolve("small"), new byte[100]);
		Files.write(directory.resolve("large"), new byte[5000]);

		assertEquals(4096 + 8192, DshDiskUsage.usageBytes(directory));
	}

	@Test
	void aLinkToAFileIsNotFollowed(@TempDir Path directory) throws IOException {
		Path inside = Files.createDirectory(directory.resolve("inside"));
		Path outside = directory.resolve("outside");
		Files.write(outside, new byte[5000]);
		try {
			Files.createSymbolicLink(inside.resolve("link"), outside);
		} catch (IOException | UnsupportedOperationException e) {
			Assumptions.assumeTrue(false, "this platform does not allow a symbolic link here");
		}

		assertEquals(0, DshDiskUsage.usageBytes(inside));
	}

	@Test
	void aMissingPathTakesNoSpace(@TempDir Path directory) {
		assertEquals(0, DshDiskUsage.usageBytes(directory.resolve("missing")));
	}

	@Test
	void bytesAreFormattedInBinaryUnits() {
		assertEquals("0 B", DshDiskUsage.formatBytes(0));
		assertEquals("1.0 KiB", DshDiskUsage.formatBytes(1024));
		assertEquals("1.0 MiB", DshDiskUsage.formatBytes(1024L * 1024));
		assertEquals("1.0 GiB", DshDiskUsage.formatBytes(1024L * 1024 * 1024));
		assertEquals("1.0 TiB", DshDiskUsage.formatBytes(1024L * 1024 * 1024 * 1024));
	}
}
