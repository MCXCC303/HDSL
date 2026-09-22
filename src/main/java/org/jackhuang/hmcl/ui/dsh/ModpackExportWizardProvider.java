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

import javafx.scene.Node;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshModpacks;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardProvider;
import org.jetbrains.annotations.NotNullByDefault;


/// The wizard that writes an instance into a pack.
///
/// The original's export wizard, in the same three steps: what kind of pack to
/// write, what it says about itself, and what of the instance goes into it. The
/// kind is a step with one answer for now — a pack this launcher can read back —
/// because the other launchers that run DeepSeek Harness do not agree on a format
/// yet, and offering to write one of theirs before knowing what it is would be
/// writing something nobody can read.
///
/// What the pages collect travels in the wizard's settings, which is what those
/// settings are for: the manifest's fields are read from there when the pack is
/// written, so a step can be visited, left and returned to without losing what was
/// typed into it.
@NotNullByDefault
public final class ModpackExportWizardProvider implements WizardProvider {
    /// The key the pack's name is held under.
    public static final String NAME = "modpack.name";

    /// The key the pack's own version is held under.
    public static final String VERSION = "modpack.version";

    /// The key the author is held under.
    public static final String AUTHOR = "modpack.author";

    /// The key the description is held under.
    public static final String DESCRIPTION = "modpack.description";

    /// The key that says whether the conversations travel too.
    public static final String SESSIONS = "modpack.sessions";

    /// The instance being exported.
    private final DshInstance instance;

    /// Creates the provider.
    ///
    /// @param instance the instance to export
    public ModpackExportWizardProvider(DshInstance instance) {
        this.instance = instance;
    }

    @Override
    public void start(org.jackhuang.hmcl.util.SettingsMap settings) {
        settings.put(NAME, instance.id());
        settings.put(VERSION, "1.0");
        settings.put(AUTHOR, "");
        settings.put(DESCRIPTION, "");
        settings.put(SESSIONS, Boolean.FALSE);
        settings.put("modpack.instance", instance.id());
    }

    @Override
    public Node createPage(WizardController controller, int step, org.jackhuang.hmcl.util.SettingsMap settings) {
        return switch (step) {
            case 0 -> new ModpackTypePage(controller);
            case 1 -> new ModpackInfoPage(controller, instance, settings);
            case 2 -> new ModpackFilesPage(controller, instance, settings);
            default -> null;
        };
    }

    @Override
    public Object finish(org.jackhuang.hmcl.util.SettingsMap settings) {
        // The last page writes the pack, because it is the page that knows where
        // the person asked for it and what they chose to put in it.
        return null;
    }

    @Override
    public boolean cancel() {
        return true;
    }

    /// Reads the options the pages collected.
    ///
    /// @param settings the wizard's settings
    /// @return the options a pack is written with
    public static org.jackhuang.hmcl.dsh.DshModpacks.Options optionsOf(
            org.jackhuang.hmcl.util.SettingsMap settings) {
        return new org.jackhuang.hmcl.dsh.DshModpacks.Options(
                string(settings, NAME, ""),
                string(settings, VERSION, "1.0"),
                string(settings, AUTHOR, ""),
                string(settings, DESCRIPTION, ""),
                Boolean.TRUE.equals(settings.get(SESSIONS)));
    }

    /// Reads a string setting.
    ///
    /// @param settings the settings
    /// @param key      the key
    /// @param fallback what to return when it holds nothing
    /// @return the value
    private static String string(org.jackhuang.hmcl.util.SettingsMap settings, String key,
                                 String fallback) {
        Object value = settings.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

}
