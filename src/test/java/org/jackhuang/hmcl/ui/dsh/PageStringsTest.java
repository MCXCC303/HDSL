package org.jackhuang.hmcl.ui.dsh;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that the pages added here only ask the bundles for strings they have.
///
/// A key a bundle does not hold is not an error anywhere: the interface draws the key
/// itself, so `dsh.download.plugins` shows up as `dsh.download.plu…` on a sidebar entry
/// and `addon.sort` on a form label, and neither says anything is wrong. Both of those
/// happened while these pages were written, and a screenshot caught them.
///
/// The two bundles this launcher maintains are both checked rather than whichever one
/// the machine's language happens to select: the same list read through one language
/// passes on a machine set to it and fails in a build that is not, which is exactly how
/// this test first failed — in CI, on the English bundle, for a string that had only
/// ever been added to the Chinese one.
class PageStringsTest {
    /// The bundles this launcher keeps complete.
    private static final List<String> BUNDLES = List.of("I18N.properties", "I18N_zh_Hans.properties");

    /// Every key the pages added here ask a bundle for.
    private static final List<String> KEYS = List.of(
            "search.hint.chinese",
            "mods.name",
            "addon.category",
            "search.sort",
            "dsh.download.instance",
            "dsh.download.plugins",
            "download.type.all",
            "addon.sort.popularity",
            "addon.sort.date_created",
            "addon.sort.total_downloads",
            "search.first_page",
            "search.previous_page",
            "search.next_page",
            "search.last_page",
            "search",
            "search.no_results_found",
            "download.install",
            "download.release_page",
            "dsh.versions.load_failed",
            "dsh.market.no_instance",
            "dsh.market.not_installable",
            // The instance list's own new entries and the pack that the two of them
            // read and write: the original's own labels, so that a pack is called what
            // the original calls one.
            "install.modpack",
            "modpack.export",
            "dsh.modpack.filter",
            "dsh.modpack.exists",
            // The session pack's entries.
            "dsh.session.pack.export",
            "dsh.session.pack.export.all",
            "dsh.session.pack.export.project",
            "dsh.session.pack.export.empty",
            "dsh.session.pack.import",
            "dsh.session.pack.filter",
            // The plugin list's own entries.
            "dsh.instance.plugins.add",
            "dsh.instance.plugins.add.filter",
            // The batch toolbar on the plugin list.
            "button.remove",
            "button.remove.confirm",
            "button.select_all",
            "button.cancel");

    @Test
    void everyLabelThesePagesUseIsInEveryBundle() throws IOException {
        for (String bundle : BUNDLES) {
            Properties strings = load(bundle);
            for (String key : KEYS) {
                assertTrue(strings.containsKey(key), bundle + " has no string for " + key);
            }
        }
    }

    /// Reads one bundle from the resources.
    ///
    /// @param name the bundle's file name
    /// @return its strings
    private static Properties load(String name) throws IOException {
        Properties strings = new Properties();
        try (InputStream input = PageStringsTest.class.getResourceAsStream("/assets/lang/" + name)) {
            if (input == null) {
                throw new IOException("no bundle named " + name);
            }
            strings.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return strings;
    }
}
