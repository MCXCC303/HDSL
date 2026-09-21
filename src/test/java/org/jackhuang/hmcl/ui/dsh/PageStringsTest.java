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
package org.jackhuang.hmcl.ui.dsh;

import org.jackhuang.hmcl.util.i18n.I18n;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that the pages added here only ask the bundle for strings it has.
///
/// A key the bundle does not hold is not an error anywhere: the interface draws
/// the key itself, so `dsh.download.plugins` shows up as `dsh.download.plu…` on a
/// sidebar entry and `addon.sort` on a form label, and neither says anything is
/// wrong. Both of those happened while this page was written, and a screenshot
/// caught them; this catches them without one.
class PageStringsTest {
    /// Every key the plugin page asks the bundle for.
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
            // The instance list's own new entries and the pack that the two of
            // them read and write: the original's own labels, so that a pack is
            // called what the original calls one.
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
            // The batch toolbar on the plugin list.
            "button.remove",
            "button.remove.confirm",
            "button.select_all",
            "button.cancel");

    @Test
    void everyLabelThesePagesUseIsInTheBundle() {
        for (String key : KEYS) {
            assertTrue(I18n.hasKey(key), "the bundle has no string for " + key);
        }
    }
}
