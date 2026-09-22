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

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

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

    /// The key the kind of pack is held under.
    public static final String FORMAT = "modpack.format";

    /// The kind this launcher writes for itself.
    public static final String FORMAT_HDSL = "hdsl";

    /// The kind the community's DSH-PackForge tooling reads.
    public static final String FORMAT_PACKFORGE = "packforge";

    /// The key the pack's download address prefix is held under.
    public static final String URL = "modpack.url";

    /// The key the pack's own site is held under.
    public static final String REFERENCE_URL = "modpack.referenceUrl";

    /// The key the bundles left out of a pack are held under.
    public static final String EXCLUDED_BUNDLES = "modpack.excludedBundles";

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
        settings.put(FORMAT, FORMAT_HDSL);
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
        // Where to write it is the last thing asked, so the wizard's own forward button is
        // what asks: on the last step it finishes, and finishing is writing the pack.
        boolean packforge = FORMAT_PACKFORGE.equals(
                string(settings, FORMAT, FORMAT_HDSL));

        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(i18n("modpack.wizard.step.initialization.save"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                packforge ? i18n("dsh.packforge.filter") : i18n("dsh.modpack.filter"),
                packforge ? "*.dspack" : "*.zip"));

        String name = string(settings, NAME, instance.id());
        chooser.setInitialFileName(packforge
                ? org.jackhuang.hmcl.dsh.DshPackForge.Options.kebab(name) + "-"
                        + string(settings, VERSION, "1.0.0") + ".dspack"
                : name + ".zip");

        java.io.File chosen = chooser.showSaveDialog(org.jackhuang.hmcl.ui.Controllers.getStage());
        if (chosen == null) {
            return null;
        }
        java.nio.file.Path target = chosen.toPath();

        if (packforge) {
            org.jackhuang.hmcl.dsh.DshPackForge.Options options =
                    new org.jackhuang.hmcl.dsh.DshPackForge.Options(
                            string(settings, NAME, instance.id()),
                            string(settings, VERSION, "1.0.0"),
                            string(settings, NAME, instance.id()),
                            string(settings, DESCRIPTION, ""),
                            string(settings, AUTHOR, ""));
            ModpackFilesPage.runPackForge(instance, target, options);
        } else {
            ModpackFilesPage.run(instance, target, optionsOf(settings));
        }
        return null;
    }

    @Override
    public boolean cancel() {
        return true;
    }

    /// Reads the bundles the person chose to leave out.
    ///
    /// @param settings the wizard's settings
    /// @return the names, never `null`
    @SuppressWarnings("unchecked")
    public static java.util.Set<String> excludedBundlesOf(org.jackhuang.hmcl.util.SettingsMap settings) {
        Object value = settings.get(EXCLUDED_BUNDLES);
        return value instanceof java.util.Set<?> set
                ? java.util.Set.copyOf((java.util.Set<String>) set) : java.util.Set.of();
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
                string(settings, URL, ""),
                string(settings, REFERENCE_URL, ""),
                Boolean.TRUE.equals(settings.get(SESSIONS)),
                excludedBundlesOf(settings));
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
