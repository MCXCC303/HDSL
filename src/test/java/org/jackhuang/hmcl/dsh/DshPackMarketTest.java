/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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
package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for reading the market's index and for installing what it points at.
///
/// The index is somebody else's document and the archive is somebody else's file, so the two things
/// worth pinning are that a shape the collector may legitimately write is understood, and that a
/// shape an attacker may write is refused. Both are cheap to check here and expensive to discover in
/// the field.
class DshPackMarketTest {
    /// An index in the shape the collector actually writes, taken from the live document.
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
        // The specification allows both forms because the collector copies what the author's manifest
        // said. A reader that understood only the plain form would put a raw JSON object on a card.
        DshPackMarket.Index index = DshPackMarket.parse(INDEX);
        DshPackMarket.Entry entry = index.entries().get(1);

        assertFalse(entry.displayName().startsWith("{"), "a map must be resolved, not shown raw");
        assertTrue(entry.displayName().equals("A name keyed by language")
                        || entry.displayName().equals("以语言为键的名字"),
                "one of the languages the author wrote, not the JSON: " + entry.displayName());
        // Only Japanese was written for the description, and showing it beats showing nothing.
        assertEquals("日本語だけの説明", entry.description());
    }

    @Test
    void anIndexFromAFutureContractIsRefusedRatherThanMisread() {
        // The field exists so that a changed shape can be detected. Ignoring it is the one thing a
        // reader must not do with it.
        String future = INDEX.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3");
        assertThrows(DshException.class, () -> DshPackMarket.parse(future));
    }

    @Test
    void anIndexWithoutAListOfPacksIsRefused() {
        assertThrows(DshException.class,
                () -> DshPackMarket.parse("{\"schemaVersion\": 2}"));
        assertThrows(DshException.class,
                () -> DshPackMarket.parse("not json at all"));
    }

    /// Builds an archive with the given members.
    ///
    /// @param members name and content pairs
    /// @return the file
    private static Path archive(String... members) throws IOException {
        Path file = Files.createTempFile("pack", ".dspack");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file),
                StandardCharsets.UTF_8)) {
            for (int i = 0; i < members.length; i += 2) {
                zip.putNextEntry(new ZipEntry(members[i]));
                zip.write(members[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    /// Builds a well-formed container.
    ///
    /// @param extraMembers appended after the marker and manifest
    /// @return the file
    private static Path container(String... extraMembers) throws IOException {
        String[] base = {
                "dspack.json", "{\"format\":\"dspack\",\"version\":3}",
                "manifest.json", "{\"manifestVersion\":5,\"type\":\"profile\",\"profileName\":\"demo\","
                        + "\"displayName\":\"Demo pack\",\"name\":\"demo\",\"version\":\"1.0.0\"}",
        };
        String[] all = new String[base.length + extraMembers.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extraMembers, 0, all, base.length, extraMembers.length);
        return archive(all);
    }

    @Test
    void aContainerIsRecognisedByItsMarker() throws Exception {
        Path pack = container();
        DshPackInstaller.Container recognised = DshPackInstaller.identify(pack);

        assertEquals(3, recognised.containerVersion());
        assertEquals(5, recognised.manifestVersion());
        assertEquals("demo", recognised.profileName("fallback"));
        assertEquals("Demo pack", recognised.name("fallback"));
        assertFalse(recognised.wholeHome());
        Files.deleteIfExists(pack);
    }

    @Test
    void whatIsNotAPackIsRefusedWithoutGuessing() throws Exception {
        Path plain = archive("readme.txt", "just a zip");
        assertThrows(DshException.class, () -> DshPackInstaller.identify(plain));
        Files.deleteIfExists(plain);

        Path future = archive(
                "dspack.json", "{\"format\":\"dspack\",\"version\":9}",
                "manifest.json", "{}");
        assertThrows(DshException.class, () -> DshPackInstaller.identify(future));
        Files.deleteIfExists(future);

        Path mismatched = archive(
                "dspack.json", "{\"format\":\"dspack\",\"version\":3}",
                "manifest.json", "{\"manifestVersion\":4,\"type\":\"profile\"}");
        assertThrows(DshException.class, () -> DshPackInstaller.identify(mismatched));
        Files.deleteIfExists(mismatched);
    }

    @Test
    void aPacksFilesLandWhereTheContainerSays() throws Exception {
        Path pack = container(
                "overrides/cordis.patch.yml", "[]",
                "overrides/settings.yaml", "model: demo",
                "home/state.json", "{}",
                "package.json", "{\"name\":\"demo\"}",
                "pnpm-workspace.yaml", "packages: []");
        Path destination = Files.createTempDirectory("profile");
        Path home = Files.createTempDirectory("home");

        DshPackInstaller.Landed landed = DshPackInstaller.land(pack, destination, home);

        assertEquals(2, landed.overrides(), "both overrides land in the profile");
        assertEquals(1, landed.home(), "home/ lands in the home");
        assertEquals(2, landed.machine(), "the pack's own machine files land in the profile");
        assertTrue(Files.isRegularFile(destination.resolve("cordis.patch.yml")));
        assertTrue(Files.isRegularFile(destination.resolve("settings.yaml")));
        assertTrue(Files.isRegularFile(home.resolve("state.json")));
        assertTrue(Files.isRegularFile(destination.resolve("package.json")));
        // The marker and the manifest describe the archive; installing them would put files in the
        // profile that no profile has.
        assertFalse(Files.exists(destination.resolve("manifest.json")));
        assertFalse(Files.exists(destination.resolve("dspack.json")));

        Files.deleteIfExists(pack);
    }

    @Test
    void aMemberThatClimbsOutOfTheDestinationIsRefused() throws Exception {
        // The same class of bug this project has already had once, in a session pack:
        // `sessions/../planted/x`. A name is what an attacker controls, so the check is on where the
        // name resolves to, not on what it looks like.
        Path pack = container("overrides/../../planted.txt", "escaped");
        Path destination = Files.createTempDirectory("profile");
        Path home = Files.createTempDirectory("home");

        assertThrows(DshException.class, () -> DshPackInstaller.land(pack, destination, home));
        Files.deleteIfExists(pack);
    }

    @Test
    void aMemberNamingCredentialsIsRefused() throws Exception {
        for (String name : new String[]{"overrides/.env", "overrides/id_ed25519",
                "overrides/credentials.yaml", "overrides/server.pem", "home/.netrc"}) {
            Path pack = container(name, "secret");
            Path destination = Files.createTempDirectory("profile");
            Path home = Files.createTempDirectory("home");
            assertThrows(DshException.class, () -> DshPackInstaller.land(pack, destination, home),
                    name + " must not be written from a pack");
            Files.deleteIfExists(pack);
        }
    }

    @Test
    void aNestedArchiveIsRefused() throws Exception {
        Path pack = container("overrides/inner.zip", "PK");
        Path destination = Files.createTempDirectory("profile");
        Path home = Files.createTempDirectory("home");
        assertThrows(DshException.class, () -> DshPackInstaller.land(pack, destination, home));
        Files.deleteIfExists(pack);
    }

    @Test
    void aPackThatIsNotWhatTheIndexDescribedIsRefused() throws Exception {
        Path pack = container("overrides/a.txt", "hello");
        byte[] bytes = Files.readAllBytes(pack);

        DshPackMarket.Entry right = new DshPackMarket.Entry("x.y", "y", "1.0.0", "Y", null,
                "https://example.invalid/y.dspack",
                org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-256", pack),
                bytes.length, "x", "y", 5, "profile", null, null, null, null, null, null, null);
        // The one the index described is accepted, so the check is not simply refusing everything.
        DshPackInstaller.verify(right, pack);

        // A digest that does not match is a different file, and must not be installed.
        DshPackMarket.Entry wrongDigest = new DshPackMarket.Entry("x.y", "y", "1.0.0", "Y", null,
                "https://example.invalid/y.dspack",
                "0000000000000000000000000000000000000000000000000000000000000000",
                bytes.length, "x", "y", 5, "profile", null, null, null, null, null, null, null);
        assertThrows(DshException.class, () -> DshPackInstaller.verify(wrongDigest, pack));

        // A file that is the wrong length did not arrive whole, which is worth saying in those words
        // rather than as a digest mismatch.
        DshPackMarket.Entry wrongSize = new DshPackMarket.Entry("x.y", "y", "1.0.0", "Y", null,
                "https://example.invalid/y.dspack", right.sha256(), bytes.length + 100,
                "x", "y", 5, "profile", null, null, null, null, null, null, null);
        DshException refused = assertThrows(DshException.class,
                () -> DshPackInstaller.verify(wrongSize, pack));
        assertTrue(refused.getMessage().contains("whole"),
                "the length failure must be reported as one: " + refused.getMessage());

        Files.deleteIfExists(pack);
    }

    @Test
    void aPacksManifestAndReadmeAddressesAreBuiltFromTheIdWithoutTakingItApart() {
        // An owner has no dot and a repository may, so the id is used as it stands. Splitting it back
        // into two halves is the mistake this pins against.
        DshPackMarket.Entry entry = new DshPackMarket.Entry("some.one.with.dots", "n", "1", "N", null,
                "https://example.invalid/a.dspack", "aa", 1, "some", "one.with.dots",
                null, null, null, null, null, null, null, null, null);
        String manifest = DshPackMarket.manifestUrl(entry);
        assertTrue(manifest.endsWith("/packs/some.one.with.dots/manifest.json"), manifest);
        assertTrue(DshPackMarket.readmeUrl(entry).endsWith("/packs/some.one.with.dots/README.md"));
        // The index is a **file**, so its own name must not survive into the address of anything
        // beside it. Appending produced `…/index.json/packs/…`, a directory that does not exist, and
        // every detail page then reported that the pack published no manifest.
        assertFalse(manifest.contains("index.json"), manifest);
        assertFalse(DshPackMarket.readmeUrl(entry).contains("index.json"));
    }

    @Test
    void aPacksDocumentsAreAddressedBesideTheIndexRatherThanInsideIt() {
        // Against the address the launcher really uses, because the bug this pins was invisible in a
        // made-up one: the published index ends in `/index.json`.
        assertEquals("https://dsh-packforge.github.io/dsh-pack-market/packs/a.b/manifest.json",
                java.net.URI.create(DshPackMarket.INDEX_URL)
                        .resolve("packs/a.b/manifest.json").toString());
    }

    @Test
    void aPackThatIsNotInTheCacheSaysSo() {
        DshPackMarket.Entry entry = new DshPackMarket.Entry("no.such", "such", "9.9.9", "No", null,
                "https://example.invalid/n.dspack",
                "1111111111111111111111111111111111111111111111111111111111111111",
                12345, "no", "such", null, null, null, null, null, null, null, null, null);
        assertFalse(DshPackInstaller.isCached(entry));
        assertNotNull(DshPackInstaller.archiveFile(entry));
    }
}
