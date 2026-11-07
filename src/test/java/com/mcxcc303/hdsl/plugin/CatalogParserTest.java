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

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the plugin catalogue, and what installing from an entry means. */
class CatalogParserTest {
	private static final String CATALOGUE = """
			{
			  "name": "awesome-dsh-plugin",
			  "updated": "2026-09-20",
			  "count": 4,
			  "plugins": [
			    {"name": "dsh-j-space", "owner": "AnonyJcy", "url": "https://github.com/AnonyJcy/dsh-j-space",
			     "category": "agi", "description": {"en": "A space", "zh": "一个空间"},
			     "npm": "@anonyjcy/dsh-j-space", "version": "1.1.1", "stars": 3, "downloads": 259,
			     "added": "2026-08-22"},
			    {"name": "repo-only", "owner": "someone", "url": "https://github.com/someone/repo-only",
			     "category": "tools", "description": {"en": "Repository only"}, "npm": null,
			     "stars": 1, "downloads": 0, "added": "2026-08-01"},
			    {"name": "prebuilt", "owner": "someone", "url": "https://github.com/someone/prebuilt",
			     "category": "tools", "description": {"en": "Has an archive"},
			     "tarball": "https://github.com/someone/prebuilt/releases/download/v1/prebuilt.tgz"},
			    {"name": "monorepo", "owner": "Jonah-Wu23", "url": "https://github.com/Jonah-Wu23/dsh-gungnir/tree/main/packages/dsh-plugin",
			     "category": "tools", "description": {"en": "One package of a monorepo"}}
			  ]
			}
			""";

	private static Catalog.Plugin plugin(String name) {
		return CatalogParser.parse(CATALOGUE)
		                    .plugins()
		                    .stream()
		                    .filter(plugin -> plugin.name().equals(name))
		                    .findFirst()
		                    .orElseThrow();
	}

	@Test
	void aPublishedPackageIsPreferredAndPinnedToTheCataloguedVersion() {
		Catalog.Plugin plugin = plugin("dsh-j-space");

		assertEquals("@anonyjcy/dsh-j-space@1.1.1",
		             plugin.installSpec(),
		             "a registry install is the one that can be verified and updated by version");
		assertEquals("npm", plugin.sourceKind());
	}

	@Test
	void anEntryWithoutAPackageInstallsFromItsRepository() {
		Catalog.Plugin plugin = plugin("repo-only");

		assertEquals("github:someone/repo-only", plugin.installSpec());
		assertEquals("github", plugin.sourceKind());
	}

	@Test
	void aPrebuiltArchiveIsUsedOnlyWhenItBelongsToTheEntrysOwnRepository() {
		assertEquals("https://github.com/someone/prebuilt/releases/download/v1/prebuilt.tgz",
		             plugin("prebuilt").installSpec(),
		             "an archive attached to the entry's own release is what the entry meant");

		// The same entry naming somebody else's archive falls back to the
		// repository: without the check, an entry could name a trusted repository
		// and hand out an archive built elsewhere.
		Catalog.Plugin borrowed = new Catalog.Plugin("borrowed",
		                                             "someone",
		                                             "https://github.com/someone/borrowed",
		                                             "tools",
		                                             null,
		                                             null,
		                                             null,
		                                             null,
		                                             0,
		                                             0,
		                                             "https://github.com/elsewhere/other/releases/download/v1/other.tgz",
		                                             null);
		assertEquals("github:someone/borrowed", borrowed.installSpec());
		assertEquals("github", borrowed.sourceKind());
	}

	@Test
	void aMonorepoEntryNamesThePackageInsideIt() {
		assertEquals("github:Jonah-Wu23/dsh-gungnir#path:/packages/dsh-plugin",
		             plugin("monorepo").installSpec(),
		             "installing the repository root would install whatever it happens to build");
	}

	@Test
	void theCatalogueIsReadWithItsCategoriesAndLocalisedDescriptions() {
		Catalog catalog = CatalogParser.parse(CATALOGUE);

		assertEquals(4, catalog.plugins().size());
		assertEquals(List.of("agi", "tools"), catalog.categories());
		assertEquals(3, catalog.inCategory("tools").size());
		assertEquals(4, catalog.inCategory("").size(), "the empty category is every category");

		Catalog.Plugin plugin = plugin("dsh-j-space");
		assertEquals(0, plugin.installSpec().indexOf('@'), "the package name keeps its scope");

		// Which description is shown depends on the machine's language, so the rule
		// is pinned rather than whatever this machine happens to be set to.
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
			assertEquals("一个空间", plugin.localizedDescription());
			Locale.setDefault(Locale.ENGLISH);
			assertEquals("A space",
			             plugin.localizedDescription(),
			             "an entry's English description is what a machine that is not Chinese shows");
		} finally {
			Locale.setDefault(previous);
		}
	}

	@Test
	void sortingPutsTheMostDownloadedFirst() {
		List<Catalog.Plugin> sorted = CatalogParser.sorted(CatalogParser.parse(CATALOGUE).plugins(), "downloads");

		assertEquals("dsh-j-space", sorted.get(0).name());
		assertTrue(sorted.get(0).downloads() >= sorted.get(1).downloads());
	}

	@Test
	void anEntryWithNothingInstallableResolvesToNothing() {
		Catalog.Plugin broken = new Catalog.Plugin("broken",
		                                           "",
		                                           "not a repository",
		                                           "",
		                                           null,
		                                           null,
		                                           "Not A Package Name",
		                                           null,
		                                           0,
		                                           0,
		                                           null,
		                                           null);

		assertNull(broken.installSpec(), "an entry that names nothing installable installs nothing");
	}
}
