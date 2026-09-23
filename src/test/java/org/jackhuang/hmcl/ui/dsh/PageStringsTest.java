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
    ///
    /// Every key below has to be in both of these. They are the two the launcher's own
    /// pages are written against, so a key neither holds is a page asking for something
    /// nobody wrote rather than a translation still to come.
    private static final List<String> BUNDLES = List.of("I18N.properties", "I18N_zh_Hans.properties");

    /// Every other bundle the launcher ships.
    ///
    /// Held to a weaker rule: the strings this launcher added have to be present, because
    /// those are the ones nobody else can supply and the ones whose absence is most
    /// visible. The transplanted interface's strings are not required here — most of these
    /// bundles are the original's, trimmed, and filling them is a translation job rather
    /// than a coding one.
    ///
    /// Where a string is missing the interface now shows the English one rather than the
    /// key, so this is no longer a visible defect; it is a list of work left to do, and it
    /// is kept so that the list does not grow.
    private static final List<String> TRANSLATED_BUNDLES = List.of(
            "I18N_zh_Hant.properties",
            "I18N_ja.properties",
            "I18N_ru.properties",
            "I18N_de.properties",
            "I18N_es.properties",
            "I18N_uk.properties",
            "I18N_ar.properties",
            "I18N_lzh.properties");

    /// The prefix every key this launcher added carries.
    private static final String OWN_PREFIX = "dsh.";

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
            "dsh.packforge.type",
            "dsh.packforge.type.detail",
            "dsh.packforge.filter",
            "dsh.modpack.filter",
            "dsh.modpack.exists",
            // The session pack's entries.
            "dsh.session.pack.export",
            "dsh.session.pack.export.all",
            "dsh.session.pack.export.project",
            "dsh.session.pack.export.empty",
            "dsh.session.pack.import",
            "dsh.session.pack.filter",
            // The build-script setting.
            "dsh.settings.build_scripts",
            "dsh.settings.build_scripts.approve",
            "dsh.settings.build_scripts.auto",
            "dsh.settings.build_scripts.manual",
            "dsh.settings.build_scripts.never",
            "dsh.settings.build_scripts.follow",
            "dsh.settings.build_scripts.ask",
            "dsh.instance.upgrade.missing",
            "download.install.success",
            "download.type.all",
            "dsh.market.dsh_version",
            "dsh.market.fitting",
            "dsh.market.fitting.hidden",
            "dsh.instance.port.mode.global",
            "dsh.settings.commands",
            "dsh.settings.commands.pre",
            "dsh.settings.commands.pre.hint",
            "dsh.settings.commands.post",
            "dsh.settings.commands.post.hint",
            "dsh.settings.commands.hint",
            "dsh.settings.inherit",
            "dsh.settings.env_vars",
            "dsh.settings.env_vars.new",
            "dsh.settings.env_vars.name",
            "dsh.settings.env_vars.value",
            "dsh.settings.env_vars.add",
            "dsh.settings.env_vars.remove",
            "dsh.settings.env_vars.empty",
            "dsh.settings.debug",
            "dsh.settings.debug.log",
            "dsh.settings.debug.log.hint",
            "dsh.settings.isolation",
            "dsh.settings.isolation.always",
            "dsh.settings.isolation.modded",
            "dsh.settings.isolation.never",
            "dsh.settings.proxy",
            "dsh.settings.background.title",
            "dsh.settings.background.fallback",
            "dsh.settings.download.cache",
            "dsh.settings.download.cache.default",
            "dsh.settings.download.cache.custom",
            "dsh.settings.download.threads",
            "dsh.settings.download.threads.custom",
            "dsh.settings.download.threads.auto",
            "dsh.settings.download.cache.choose",
            "dsh.settings.download.cache.hint",
            "dsh.settings.download.cache.clear",
            "dsh.settings.download.cache.cleared",
            "dsh.settings.proxy.mode.system",
            "dsh.settings.proxy.mode.none",
            "dsh.settings.proxy.mode.http",
            "dsh.settings.proxy.mode.socks",
            "dsh.settings.proxy.host",
            "dsh.settings.proxy.host.hint",
            "dsh.settings.proxy.port",
            "dsh.settings.proxy.port.hint",
            "dsh.settings.proxy.auth",
            "dsh.settings.proxy.auth.hint",
            "dsh.settings.proxy.user",
            "dsh.settings.proxy.user.hint",
            "dsh.settings.proxy.password",
            "dsh.settings.proxy.password.hint",
            "dsh.settings.proxy.http",
            "dsh.settings.proxy.https",
            "dsh.settings.proxy.hint",
            "dsh.settings.proxy.none",
            "dsh.settings.proxy.none.hint",
            "dsh.settings.proxy.concurrency",
            "dsh.settings.proxy.concurrency.hint",
            "dsh.settings.catalog",
            "dsh.settings.catalog.url",
            "dsh.settings.catalog.url.hint",
            "dsh.settings.launcher",
            "dsh.settings.launcher.visibility",
            "dsh.settings.launcher.visibility.keep",
            "dsh.settings.launcher.show_logs",
            "dsh.settings.launcher.visibility.hide",
            "dsh.settings.launcher.visibility.minimize",
            // The export wizard's steps.
            "modpack.wizard",
            "modpack.wizard.step.1.title",
            "modpack.wizard.step.3.title",
            "modpack.wizard.step.initialization.save",
            "modpack.export.as",
            "modpack.name",
            "modpack.export.url",
            "modpack.export.reference_url",
            "modpack.description",
            "archive.version",
            "archive.author",
            "dsh.modpack.type",
            "dsh.modpack.type.detail",
            "dsh.modpack.files",
            "dsh.modpack.files.title",
            "dsh.modpack.files.configuration",
            "dsh.modpack.files.plugins",
            "dsh.modpack.files.attachments",
            "dsh.modpack.files.configuration.detail",
            "dsh.modpack.files.sessions",
            "dsh.modpack.files.sessions.count",
            "dsh.modpack.files.sessions.none",
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

    /// Verifies that the strings this launcher added exist in the two bundles it maintains.
    ///
    /// The other languages are reported rather than checked: they are the original's
    /// trimmed bundles and none of them carries the strings this launcher added, so the
    /// interface falls back to English there. Failing on that would mean failing on a
    /// translation job; passing over it in silence would mean nobody knew. The counts are
    /// printed instead, which is what a person needs to decide whether to translate them.
    @Test
    void theStringsThisLauncherAddedAreInTheBundlesItMaintains() throws IOException {
        List<String> own = KEYS.stream().filter(key -> key.startsWith(OWN_PREFIX)).toList();
        assertTrue(!own.isEmpty(), "no keys with the " + OWN_PREFIX + " prefix to check");

        for (String bundle : BUNDLES) {
            Properties strings = load(bundle);
            List<String> missing = own.stream().filter(key -> !strings.containsKey(key)).toList();
            assertTrue(missing.isEmpty(),
                    bundle + " is one of the two bundles this launcher maintains, and has no string for " + missing);
        }
    }

    /// Reports how much of this launcher's own text each other language carries.
    ///
    /// Not an assertion: see the comment on the method above. It always passes, and its
    /// output is the point.
    @Test
    void everyTranslatedBundleCarriesEveryStringThisLauncherHas() throws IOException {
        // The set of strings is read from the English bundle rather than from the list above. That
        // list is a snapshot, and a snapshot is exactly what a check on "is everything translated"
        // must not be: it was written by hand, so it says nothing about the keys added after it was
        // written. It silently reported full coverage while forty-six strings were missing from
        // every language — the ones the account and skin features had just introduced.
        Properties english = load("I18N.properties");
        List<String> own = english.stringPropertyNames().stream()
                .filter(key -> key.startsWith(OWN_PREFIX))
                .sorted()
                .toList();
        assertTrue(!own.isEmpty(), "no keys with the " + OWN_PREFIX + " prefix to check");

        List<String> incomplete = new java.util.ArrayList<>();
        for (String bundle : TRANSLATED_BUNDLES) {
            Properties strings = load(bundle);
            List<String> missing = own.stream().filter(key -> !strings.containsKey(key)).toList();
            System.out.println(String.format("%-28s %3d / %3d of this launcher's strings%s",
                    bundle, own.size() - missing.size(), own.size(),
                    missing.isEmpty() ? "" : "   missing " + missing.size()));
            if (!missing.isEmpty()) {
                incomplete.add(bundle + ": " + missing);
            }
        }
        assertTrue(incomplete.isEmpty(),
                "these bundles do not carry every string, so the interface falls back to English in "
                        + "them:\n  " + String.join("\n  ", incomplete));
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
