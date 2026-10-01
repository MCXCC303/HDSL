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
package org.jackhuang.hmcl.dsh;

import org.jackhuang.hmcl.setting.SettingsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies where the plugin catalogue is read from, and what several sources come to.
///
/// One catalogue is one document on one host, which is one point of failure and
/// one list of plugins. Reading several is what a mirror is for and what a person
/// who wants more plugins than one list holds is asking for — and it is only worth
/// having if the entries stay one list: the same plugin listed by two sources has
/// to be one entry, and the source that comes first has to be the one that
/// describes it.
///
/// Nothing here goes to the network. What a source *answers* is what the merge
/// works on, and the merge is where the rules are.
class DshPluginCatalogSourcesTest {
    /// The sources this test configured, so they can be taken back out.
    private final List<String> configuredAtStart = DshPluginCatalog.configuredSources();

    /// Puts the sources back, so one test cannot change what the next one reads.
    @AfterEach
    void restoreTheSources() {
        if (configuredAtStart.isEmpty()) {
            DshPluginCatalog.resetSources();
            return;
        }
        try {
            SettingsManager.settings().pluginCatalogSourcesProperty().setAll(configuredAtStart);
            SettingsManager.save();
        } catch (RuntimeException e) {
            throw new AssertionError("Could not put the catalogue sources back", e);
        }
    }

    /// Parses a catalogue document with the given entries.
    ///
    /// @param entries the JSON entries
    /// @return the catalogue
    private static DshPluginCatalog.Catalog catalogue(String entries) {
        return DshPluginCatalog.parse("{\"updated\":\"2026-09-20\",\"plugins\":[" + entries + "]}");
    }

    /// One entry, named after its package.
    ///
    /// @param name the name and the package name
    /// @param category the category
    /// @return the entry's JSON
    private static String entry(String name, String category) {
        return "{\"name\":\"" + name + "\",\"owner\":\"someone\",\"url\":\"https://github.com/someone/"
                + name + "\",\"category\":\"" + category + "\",\"npm\":\"" + name + "\"}";
    }

    @Test
    void theLauncherReadsThePublishedPairWhenNothingIsConfigured() {
        DshPluginCatalog.resetSources();

        assertEquals(List.of(DshPluginCatalog.CATALOG_URL), DshPluginCatalog.sources(),
                "a launcher nobody configured reads the address the ecosystem publishes");
        assertEquals(DshPluginCatalog.NPM_CATALOG_PACKAGE, DshPluginCatalog.fallbackSource(),
                "and reaches the same document through npm when that address does not answer");
    }

    @Test
    void whatTheUserAddedIsReadInTheOrderTheyPutIt() throws Exception {
        DshPluginCatalog.resetSources();
        DshPluginCatalog.addSource("https://mirror.example/plugins.json");
        DshPluginCatalog.addSource("another-catalog");

        assertEquals(List.of("https://mirror.example/plugins.json", "another-catalog"),
                DshPluginCatalog.configuredSources());
        assertEquals(List.of("https://mirror.example/plugins.json", "another-catalog"),
                DshPluginCatalog.sources(),
                "the npm package that publishes the community catalogue is already named here");
    }

    @Test
    void aSourceThatWasAddedIsNotJoinedByTheNpmPackage() throws Exception {
        DshPluginCatalog.resetSources();
        DshPluginCatalog.addSource("https://mirror.example/plugins.json");

        assertEquals(List.of("https://mirror.example/plugins.json"), DshPluginCatalog.sources(),
                "the npm rescue is read only when nothing answered, so adding a source"
                        + " does not make every fetch read the same catalogue twice");

        DshPluginCatalog.addSource(DshPluginCatalog.NPM_CATALOG_PACKAGE);
        assertEquals(List.of("https://mirror.example/plugins.json", DshPluginCatalog.NPM_CATALOG_PACKAGE),
                DshPluginCatalog.sources(),
                "naming it as a source is how somebody asks for it to be merged rather than rescued");
    }

    @Test
    void removingTheLastSourceGoesBackToThePublishedPair() throws Exception {
        DshPluginCatalog.resetSources();
        DshPluginCatalog.addSource("https://mirror.example/plugins.json");
        DshPluginCatalog.addSource("another-catalog");

        DshPluginCatalog.removeSource("another-catalog");
        assertEquals(List.of("https://mirror.example/plugins.json"), DshPluginCatalog.configuredSources());

        DshPluginCatalog.removeSource("https://mirror.example/plugins.json");
        assertTrue(DshPluginCatalog.configuredSources().isEmpty(),
                "removing the last one is how somebody says stop using my list");
        assertEquals(List.of(DshPluginCatalog.CATALOG_URL), DshPluginCatalog.sources());
    }

    @Test
    void theSameSourceIsNotAddedTwice() throws Exception {
        DshPluginCatalog.resetSources();
        DshPluginCatalog.addSource("https://mirror.example/plugins.json");

        DshException refused = assertThrows(DshException.class,
                () -> DshPluginCatalog.addSource("https://mirror.example/plugins.json"));
        assertTrue(refused.getMessage().contains("already"), refused.getMessage());
    }

    @Test
    void somethingThatIsNeitherAnAddressNorAPackageIsRefused() {
        assertFalse(DshPluginCatalog.isUsableSource(""));
        assertFalse(DshPluginCatalog.isUsableSource(null));
        assertFalse(DshPluginCatalog.isUsableSource("not an address"));
        assertFalse(DshPluginCatalog.isUsableSource("https://"));
        assertTrue(DshPluginCatalog.isUsableSource("https://mirror.example/plugins.json"));
        assertTrue(DshPluginCatalog.isUsableSource("http://127.0.0.1:8080/plugins.json"));
        assertTrue(DshPluginCatalog.isUsableSource(DshPluginCatalog.NPM_CATALOG_PACKAGE));
        assertTrue(DshPluginCatalog.isUsableSource("@someone/dsh-plugin-catalog"));
    }

    @Test
    void anAddressIsNotAPackageAndAPackageIsNotAnAddress() {
        assertFalse(DshPluginCatalog.isPackageSource("https://mirror.example/plugins.json"));
        assertFalse(DshPluginCatalog.isPackageSource("http://mirror.example/plugins.json"));
        assertTrue(DshPluginCatalog.isPackageSource(DshPluginCatalog.NPM_CATALOG_PACKAGE));
        assertTrue(DshPluginCatalog.isPackageSource("@someone/plugins"));
    }

    @Test
    void whatTwoSourcesBothListIsOneEntry() {
        DshPluginCatalog.Catalog first = catalogue(entry("shared", "agi"));
        DshPluginCatalog.Catalog second = catalogue(entry("shared", "agi") + "," + entry("only-here", "tools"));

        DshPluginCatalog.Catalog merged = DshPluginCatalog.merge(List.of(first, second), List.of());

        assertEquals(List.of("shared", "only-here"),
                merged.plugins().stream().map(DshPluginCatalog.Plugin::name).toList(),
                "the first source to list a plugin is the one it is described by");
    }

    @Test
    void theFirstSourceDescribesAPluginBothList() {
        DshPluginCatalog.Catalog detailed = DshPluginCatalog.parse("""
                {"plugins":[{"name":"shared","owner":"someone","url":"https://github.com/someone/shared",
                 "category":"agi","npm":"shared","version":"2.0.0","downloads":900}]}
                """);
        DshPluginCatalog.Catalog vague = DshPluginCatalog.parse("""
                {"plugins":[{"name":"shared","owner":"someone","url":"https://github.com/someone/shared",
                 "category":"agi","npm":"shared","version":"1.0.0","downloads":3}]}
                """);

        DshPluginCatalog.Catalog merged = DshPluginCatalog.merge(List.of(detailed, vague), List.of());

        assertEquals(1, merged.plugins().size());
        assertEquals("2.0.0", merged.plugins().get(0).version(),
                "which is what makes the order the user arranged mean something");
    }

    @Test
    void twoRepositoryOnlyEntriesAreOneWhenTheyNameOneRepository() {
        DshPluginCatalog.Catalog first = DshPluginCatalog.parse("""
                {"plugins":[{"name":"a","owner":"someone","url":"https://github.com/someone/repo",
                 "category":"agi"}]}
                """);
        DshPluginCatalog.Catalog second = DshPluginCatalog.parse("""
                {"plugins":[{"name":"b","owner":"someone","url":"https://github.com/someone/repo/tree/main",
                 "category":"agi"}]}
                """);

        DshPluginCatalog.Catalog merged = DshPluginCatalog.merge(List.of(first, second), List.of());

        assertEquals(1, merged.plugins().size(),
                "an entry with no package is the same plugin when it is the same repository");
    }

    @Test
    void theCategoriesOfEverySourceAreOffered() {
        DshPluginCatalog.Catalog first = catalogue(entry("a", "agi"));
        DshPluginCatalog.Catalog second = catalogue(entry("b", "tools"));

        DshPluginCatalog.Catalog merged = DshPluginCatalog.merge(List.of(first, second), List.of());

        assertEquals(List.of("agi", "tools"), merged.categories());
    }

    @Test
    void aSourceThatDidNotAnswerIsSaidRatherThanHidden() {
        DshPluginCatalog.Catalog merged = DshPluginCatalog.merge(
                List.of(catalogue(entry("a", "agi"))), List.of("https://down.example/plugins.json"));

        assertTrue(merged.isPartial(), "a catalogue built from what answered has to say what did not");
        assertEquals(List.of("https://down.example/plugins.json"), merged.unreadable());
        assertEquals(1, merged.plugins().size(), "one source being down must not take the others with it");
    }

    @Test
    void aCatalogueThatCameFromOneSourceIsNotPartial() {
        assertFalse(catalogue(entry("a", "agi")).isPartial());
    }

    @Test
    void theSourcesAreReadFromTheSettingsWheneverTheyAreAskedFor() {
        // The point of reading them each time rather than installing them once: a
        // command-line run and the interface are different processes, and both
        // have to see the list the user arranged.
        Supplier<List<String>> asked = DshPluginCatalog::sources;
        DshPluginCatalog.resetSources();
        List<String> before = asked.get();
        try {
            DshPluginCatalog.addSource("https://mirror.example/plugins.json");
            assertFalse(before.equals(asked.get()), "a change has to be visible without a restart");
        } catch (DshException e) {
            throw new AssertionError(e);
        }
    }
}
