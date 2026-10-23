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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The rules that decide whether a package's own icon is shown.
 */
class PluginIconsTest {

	@TempDir
	Path temp;

	private Path packageDirectory(String icon) throws IOException {
		Path directory = Files.createDirectories(temp.resolve("package"));
		Files.writeString(directory.resolve("package.json"),
		                  "{\"name\":\"dsh-example\",\"version\":\"1.0.0\",\"icon\":\"" + icon + "\"}");
		return directory;
	}

	@Test
	void aPackageShowsTheIconItDeclares() throws IOException {
		Path directory = packageDirectory("icon.svg");
		Files.writeString(directory.resolve("icon.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");

		assertEquals(directory.resolve("icon.svg").toRealPath(), PluginIcons.iconFile(directory));
	}

	@Test
	void anIconOutsideThePackageIsNotShown() throws IOException {
		Path directory = packageDirectory("../outside.svg");
		Files.writeString(temp.resolve("outside.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");

		assertNull(PluginIcons.iconFile(directory));
	}

	@Test
	void anIconThatIsAnAddressIsNotShown() throws IOException {
		Path directory = packageDirectory("https://example.com/icon.svg");

		assertNull(PluginIcons.iconFile(directory));
	}

	@Test
	void anIconInAnUnknownFormatIsNotShown() throws IOException {
		Path directory = packageDirectory("icon.txt");
		Files.writeString(directory.resolve("icon.txt"), "not a picture");

		assertNull(PluginIcons.iconFile(directory));
	}

	@Test
	void anIconLargerThanTheHarnessAllowsIsNotShown() throws IOException {
		Path directory = packageDirectory("icon.png");
		Files.write(directory.resolve("icon.png"), new byte[256 * 1024 + 1]);

		assertNull(PluginIcons.iconFile(directory));
	}

	@Test
	void aPackageWithoutAnIconHasNone() throws IOException {
		Path directory = Files.createDirectories(temp.resolve("bare"));
		Files.writeString(directory.resolve("package.json"), "{\"name\":\"dsh-example\"}");

		assertNull(PluginIcons.iconFile(directory));
	}
}
