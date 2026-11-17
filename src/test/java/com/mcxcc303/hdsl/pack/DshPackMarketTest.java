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
package com.mcxcc303.hdsl.pack;

import com.mcxcc303.hdsl.core.DshException;
import org.jackhuang.hmcl.util.DigestUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

// Tests for reading the market's index and for installing what it points at.
class DshPackMarketTest {
	private static final String INDEX = """
			{
			  "schemaVersion": 2,
			  "generatedAt": "2026-09-23T09:57:39.723Z",
			  "modpacks": [
			    {
			      "manifestVersion": 5,
			      "type": "profile",
			      "name": "pokemon",
			      "version": "1.0.0",
			      "displayName": "宝可梦风格整合包",
			      "description": "加入了宝可梦宠物",
			      "category": "uncategorized",
			      "dshVersion": "0.1.5-alpha.2",
			      "profileName": "pokemon",
			      "downloadUrl": "https://example.invalid/pokemon-1.0.0.dspack",
			      "sha256": "3d6a2748f424ecebca2c9dd0ea108a91eaa729fd4ba40cceedaa304d9165087f",
			      "size": 2574,
			      "updatedAt": "2026-09-22",
			      "id": "hxh230802.pokemon",
			      "owner": "hxh230802",
			      "repo": "pokemon",
			      "bundleCount": 4,
			      "depCount": 2
			    },
			    {
			      "name": "described-in-a-map",
			      "version": "2.0.0",
			      "displayName": { "zh-Hans": "以语言为键的名字", "en": "A name keyed by language" },
			      "description": { "ja": "日本語だけの説明" },
			      "downloadUrl": "https://example.invalid/a.dspack",
			      "sha256": "aa",
			      "size": 10,
			      "id": "some.one",
			      "owner": "some",
			      "repo": "one"
			    },
			    {
			      "name": "cannot-be-fetched",
			      "version": "1.0.0",
			      "displayName": "No digest",
			      "downloadUrl": "https://example.invalid/b.dspack",
			      "id": "no.digest",
			      "owner": "no",
			      "repo": "digest"
			    }
			  ]
			}
			""";

	private static Path archive(String... members) throws IOException {
		Path file = Files.createTempFile("pack", ".dspack");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
			for (int i = 0; i < members.length; i += 2) {
				zip.putNextEntry(new ZipEntry(members[i]));
				zip.write(members[i + 1].getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return file;
	}

	private static DshPackMarket.Entry indexEntry(String id) {
		return new DshPackMarket.Entry(id,
		                               id,
		                               "1.0.0",
		                               id,
		                               null,
		                               "https://example.invalid/" + id + ".dspack",
		                               "1111111111111111111111111111111111111111111111111111111111111111",
		                               1,
		                               "some",
		                               "one.with.dots",
		                               null,
		                               null,
		                               null,
		                               null,
		                               null,
		                               null,
		                               null,
		                               null,
		                               null);
	}

	private static Path container(String... extraMembers) throws IOException {
		String[] base = {
				"dspack.json",
				"{\"format\":\"dspack\",\"version\":3}",
				"manifest.json",
				"{\"manifestVersion\":5,\"type\":\"profile\",\"profileName\":\"demo\"," +
				"\"displayName\":\"Demo pack\",\"name\":\"demo\",\"version\":\"1.0.0\"}",
				};
		String[] all = new String[base.length + extraMembers.length];
		System.arraycopy(base, 0, all, 0, base.length);
		System.arraycopy(extraMembers, 0, all, base.length, extraMembers.length);
		return archive(all);
	}

	@Test
	void theIndexIsReadInTheShapeTheCollectorWrites() throws Exception {
		DshPackMarket.Index index = DshPackMarket.parse(INDEX);

		assertEquals("2026-09-23T09:57:39.723Z", index.generatedAt());
		assertEquals(2, index.entries().size(), "the entry with no digest is not a pack that can be fetched");

		DshPackMarket.Entry pokemon = index.entries().get(0);
		assertEquals("hxh230802.pokemon", pokemon.id());
		assertEquals("宝可梦风格整合包", pokemon.displayName());
		assertEquals("hxh230802", pokemon.author() == null ? pokemon.owner() : pokemon.author());
		assertEquals(2574, pokemon.size());
		assertEquals("3d6a2748f424ecebca2c9dd0ea108a91eaa729fd4ba40cceedaa304d9165087f", pokemon.sha256());
		assertFalse(pokemon.isWholeHome());
		assertEquals("pokemon", pokemon.profileName());
	}

	@Test
	void aNameGivenAsAMapOfLanguagesIsStillAName() throws Exception {
		DshPackMarket.Index index = DshPackMarket.parse(INDEX);
		DshPackMarket.Entry entry = index.entries().get(1);

		assertFalse(entry.displayName().startsWith("{"), "a map is resolved, not shown raw");
		assertTrue(entry.displayName().equals("A name keyed by language") ||
		           entry.displayName().equals("以语言为键的名字"),
		           "one of the languages the author wrote, not the JSON: " + entry.displayName());
		assertEquals("日本語だけの説明", entry.description());
	}

	@Test
	void anIndexFromAFutureContractIsRefused() {
		String future = INDEX.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3");
		assertThrows(DshException.class, () -> DshPackMarket.parse(future));
	}

	@Test
	void anIndexWithoutAListOfPacksIsRefused() {
		assertThrows(DshException.class, () -> DshPackMarket.parse("{\"schemaVersion\": 2}"));
		assertThrows(DshException.class, () -> DshPackMarket.parse("not json at all"));
	}

	@Test
	void aContainerIsRecognisedByItsMarker() throws Exception {
		Path pack = container();
		DspackContainer.Container recognised = DspackContainer.identify(pack);

		assertEquals(3, recognised.containerVersion());
		assertEquals(5, recognised.manifestVersion());
		assertEquals("demo", recognised.profileName("fallback"));
		assertEquals("Demo pack", recognised.name("fallback"));
		assertFalse(recognised.wholeHome());
		Files.deleteIfExists(pack);
	}

	@Test
	void anArchiveThatIsNotAPackIsRefused() throws Exception {
		Path plain = archive("readme.txt", "just a zip");
		assertThrows(DshException.class, () -> DspackContainer.identify(plain));
		Files.deleteIfExists(plain);

		Path future = archive("dspack.json", "{\"format\":\"dspack\",\"version\":9}", "manifest.json", "{}");
		assertThrows(DshException.class, () -> DspackContainer.identify(future));
		Files.deleteIfExists(future);

		Path mismatched = archive("dspack.json",
		                          "{\"format\":\"dspack\",\"version\":3}",
		                          "manifest.json",
		                          "{\"manifestVersion\":4,\"type\":\"profile\"}");
		assertThrows(DshException.class, () -> DspackContainer.identify(mismatched));
		Files.deleteIfExists(mismatched);
	}

	@Test
	void aPacksFilesLandInTheProfileOrTheHome() throws Exception {
		Path pack = container("overrides/cordis.patch.yml",
		                      "[]",
		                      "overrides/settings.yaml",
		                      "model: demo",
		                      "home/state.json",
		                      "{}",
		                      "package.json",
		                      "{\"name\":\"demo\"}",
		                      "pnpm-workspace.yaml",
		                      "packages: []");
		Path destination = Files.createTempDirectory("profile");
		Path home = Files.createTempDirectory("home");

		DspackLander.Landed landed = DspackLander.land(pack, destination, home);

		assertEquals(2, landed.overrides(), "both overrides land in the profile");
		assertEquals(1, landed.home(), "home/ lands in the home");
		assertEquals(2, landed.machine(), "the pack's own machine files land in the profile");
		assertTrue(Files.isRegularFile(destination.resolve("cordis.patch.yml")),
		           "the override is written into the profile");
		assertTrue(Files.isRegularFile(destination.resolve("settings.yaml")),
		           "the second override is written into the profile");
		assertTrue(Files.isRegularFile(home.resolve("state.json")), "the home file is written into the home");
		assertTrue(Files.isRegularFile(destination.resolve("package.json")),
		           "the pack's machine file is written into the profile");
		assertFalse(Files.exists(destination.resolve("manifest.json")),
		            "the manifest is not installed into the profile");
		assertFalse(Files.exists(destination.resolve("dspack.json")),
		            "the marker is not installed into the profile");

		Files.deleteIfExists(pack);
	}

	@Test
	void thePartsOfAPackCanBeWrittenSeparately() throws Exception {
		Path pack = container("overrides/cordis.patch.yml",
		                      "[]",
		                      "package.json",
		                      "{\"name\":\"demo\"}",
		                      "home/state.json",
		                      "{}");
		Path destination = Files.createTempDirectory("profile");
		Path home = Files.createTempDirectory("home");

		DspackLander.Landed machine = DspackLander.land(pack, destination, home, EnumSet.of(DspackLander.Part.MACHINE));
		assertEquals(1, machine.machine());
		assertEquals(0, machine.overrides());
		assertTrue(Files.isRegularFile(destination.resolve("package.json")),
		           "the machine file is written in the first pass");
		assertFalse(Files.exists(destination.resolve("cordis.patch.yml")),
		            "the overrides are not written before the dependencies are installed");

		DspackLander.Landed rest = DspackLander.land(pack,
		                                             destination,
		                                             home,
		                                             EnumSet.of(DspackLander.Part.OVERRIDES, DspackLander.Part.HOME));
		assertEquals(1, rest.overrides());
		assertEquals(1, rest.home());
		assertEquals(0, rest.machine());
		assertTrue(Files.isRegularFile(destination.resolve("cordis.patch.yml")),
		           "the override is written once the dependencies are installed");
		assertTrue(Files.isRegularFile(home.resolve("state.json")), "the home file is written in the second pass");

		Files.deleteIfExists(pack);
	}

	@Test
	void aMemberThatClimbsOutOfTheDestinationIsRefused() throws Exception {
		Path pack = container("overrides/../../planted.txt", "escaped");
		Path destination = Files.createTempDirectory("profile");
		Path home = Files.createTempDirectory("home");

		assertThrows(DshException.class, () -> DspackLander.land(pack, destination, home));
		Files.deleteIfExists(pack);
	}

	@Test
	void aMemberNamingCredentialsIsRefused() throws Exception {
		for (String name : new String[]{
				"overrides/.env",
				"overrides/id_ed25519",
				"overrides/credentials.yaml",
				"overrides/server.pem",
				"home/.netrc"
		}) {
			Path pack = container(name, "secret");
			Path destination = Files.createTempDirectory("profile");
			Path home = Files.createTempDirectory("home");
			assertThrows(DshException.class,
			             () -> DspackLander.land(pack, destination, home),
			             name + " is not written from a pack");
			Files.deleteIfExists(pack);
		}
	}

	@Test
	void aNestedArchiveIsRefused() throws Exception {
		Path pack = container("overrides/inner.zip", "PK");
		Path destination = Files.createTempDirectory("profile");
		Path home = Files.createTempDirectory("home");
		assertThrows(DshException.class, () -> DspackLander.land(pack, destination, home));
		Files.deleteIfExists(pack);
	}

	@Test
	void aPackThatIsNotWhatTheIndexDescribedIsRefused() throws Exception {
		Path pack = container("overrides/a.txt", "hello");
		byte[] bytes = Files.readAllBytes(pack);

		DshPackMarket.Entry right = new DshPackMarket.Entry("x.y",
		                                                    "y",
		                                                    "1.0.0",
		                                                    "Y",
		                                                    null,
		                                                    "https://example.invalid/y.dspack",
		                                                    DigestUtils.digestToString("SHA-256", pack),
		                                                    bytes.length,
		                                                    "x",
		                                                    "y",
		                                                    5,
		                                                    "profile",
		                                                    null,
		                                                    null,
		                                                    null,
		                                                    null,
		                                                    null,
		                                                    null,
		                                                    null);
		DspackDownload.verify(right, pack);

		DshPackMarket.Entry wrongDigest = new DshPackMarket.Entry("x.y",
		                                                          "y",
		                                                          "1.0.0",
		                                                          "Y",
		                                                          null,
		                                                          "https://example.invalid/y.dspack",
		                                                          "0000000000000000000000000000000000000000000000000000000000000000",
		                                                          bytes.length,
		                                                          "x",
		                                                          "y",
		                                                          5,
		                                                          "profile",
		                                                          null,
		                                                          null,
		                                                          null,
		                                                          null,
		                                                          null,
		                                                          null,
		                                                          null);
		assertThrows(DshException.class, () -> DspackDownload.verify(wrongDigest, pack));

		DshPackMarket.Entry wrongSize = new DshPackMarket.Entry("x.y",
		                                                        "y",
		                                                        "1.0.0",
		                                                        "Y",
		                                                        null,
		                                                        "https://example.invalid/y.dspack",
		                                                        right.sha256(),
		                                                        bytes.length + 100,
		                                                        "x",
		                                                        "y",
		                                                        5,
		                                                        "profile",
		                                                        null,
		                                                        null,
		                                                        null,
		                                                        null,
		                                                        null,
		                                                        null,
		                                                        null);
		DshException refused = assertThrows(DshException.class, () -> DspackDownload.verify(wrongSize, pack));
		assertTrue(refused.getMessage().contains("incomplete"),
		           "a file of the wrong length is reported as an incomplete download: " + refused.getMessage());

		Files.deleteIfExists(pack);
	}

	@Test
	void aPacksDocumentAddressesKeepTheWholeId() {
		DshPackMarket.Entry entry = indexEntry("some.one.with.dots");

		String manifest = DshPackMarket.manifestUrl(entry);
		assertTrue(manifest.endsWith("/packs/some.one.with.dots/manifest.json"),
		           "the address keeps the dotted id whole: " + manifest);
		assertTrue(DshPackMarket.readmeUrl(entry).endsWith("/packs/some.one.with.dots/README.md"),
		           "the readme sits beside the manifest, under the same id");
	}

	@Test
	void aPacksDocumentsAreNotAddressedInsideTheIndex() {
		DshPackMarket.Entry entry = indexEntry("some.one.with.dots");

		assertFalse(DshPackMarket.manifestUrl(entry).contains("index.json"),
		            "the index's own file name is not part of the pack's address");
		assertFalse(DshPackMarket.readmeUrl(entry).contains("index.json"),
		            "the index's own file name is not part of the readme's address");
	}

	@Test
	void aPackThatWasNeverFetchedIsNotTakenFromTheCache() {
		assertFalse(DspackDownload.isCached(indexEntry("no.such")));
	}
}