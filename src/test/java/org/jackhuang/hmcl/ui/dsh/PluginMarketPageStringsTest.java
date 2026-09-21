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

/// Verifies that the plugin page's labels are strings the interface has.
///
/// A key the bundle does not hold is not an error anywhere: the interface draws
/// the key itself, so `dsh.download.plugins` shows up as `dsh.download.plu…` on a
/// sidebar entry and `addon.sort` on a form label, and neither says anything is
/// wrong. Both of those happened while this page was written, and a screenshot
/// caught them; this catches them without one.
class PluginMarketPageStringsTest {
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
            "dsh.market.not_installable");

    @Test
    void everyLabelThePluginPageUsesIsInTheBundle() {
        for (String key : KEYS) {
            assertTrue(I18n.hasKey(key), "the bundle has no string for " + key);
        }
    }
}
