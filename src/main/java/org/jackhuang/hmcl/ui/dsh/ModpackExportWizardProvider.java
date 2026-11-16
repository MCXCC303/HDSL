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
package org.jackhuang.hmcl.ui.dsh;

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.pack.DshPackForge;
import com.mcxcc303.hdsl.pack.PackExporter;
import com.mcxcc303.hdsl.pack.PackManifest;
import com.mcxcc303.hdsl.plugin.DshPluginSettings;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.wizard.WizardController;
import org.jackhuang.hmcl.ui.wizard.WizardProvider;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The wizard that writes an instance into a pack.
@NotNullByDefault
public final class ModpackExportWizardProvider implements WizardProvider {
	public static final String NAME = "modpack.name";

	public static final String VERSION = "modpack.version";

	public static final String AUTHOR = "modpack.author";

	public static final String DESCRIPTION = "modpack.description";

	public static final String FORMAT = "modpack.format";

	public static final String FORMAT_HDSL = "hdsl";

	public static final String FORMAT_PACKFORGE = "packforge";

	public static final String URL = "modpack.url";

	public static final String REFERENCE_URL = "modpack.referenceUrl";

	public static final String EXCLUDED_BUNDLES = "modpack.excludedBundles";

	public static final String SESSIONS = "modpack.sessions";

	/// The key the `settings.yaml` sections a pack carries are held under.
	///
	/// The plugin sections of the harness's settings file travel with the pack; the values that
	/// look like credentials do not.
	public static final String SETTINGS = "modpack.settings";

	/// The key the plugins installed from this machine are held under.
	///
	/// A `file:` plugin travels as the file it is; a directory the profile only links to is left
	/// out of the pack.
	public static final String LOCAL_PLUGINS = "modpack.localPlugins";

	public static final String LOCAL_WARNED = "modpack.localWarned";

	/// The key the skill packs a pack carries are held under.
	///
	/// A skill cannot be fetched by name, so it travels inside the pack.
	public static final String SKILLS = "modpack.skills";

	private final DshInstance instance;

	public ModpackExportWizardProvider(DshInstance instance) {
		this.instance = instance;
	}

	@SuppressWarnings("unchecked")
	public static List<PackExporter.LocalPlugin> localPluginsOf(SettingsMap settings) {
		Object value = settings.get(LOCAL_PLUGINS);
		return value instanceof List<?> list ? List.copyOf((List<PackExporter.LocalPlugin>) list) : List.of();
	}

	@SuppressWarnings("unchecked")
	public static Set<String> excludedBundlesOf(SettingsMap settings) {
		Object value = settings.get(EXCLUDED_BUNDLES);
		return value instanceof Set<?> set ? Set.copyOf((Set<String>) set) : Set.of();
	}

	@SuppressWarnings("unchecked")
	public static Set<String> settingsOf(SettingsMap settings) {
		Object value = settings.get(SETTINGS);
		return value instanceof Set<?> set ? Set.copyOf((Set<String>) set) : Set.of();
	}

	@SuppressWarnings("unchecked")
	public static Set<String> skillsOf(SettingsMap settings) {
		Object value = settings.get(SKILLS);
		return value instanceof Set<?> set ? Set.copyOf((Set<String>) set) : Set.of();
	}

	public static PackManifest.Options optionsOf(SettingsMap settings) {
		return new PackManifest.Options(string(settings, NAME, ""),
		                                string(settings, VERSION, "1.0"),
		                                string(settings, AUTHOR, ""),
		                                string(settings, DESCRIPTION, ""),
		                                string(settings, URL, ""),
		                                string(settings, REFERENCE_URL, ""),
		                                Boolean.TRUE.equals(settings.get(SESSIONS)),
		                                excludedBundlesOf(settings),
		                                settingsOf(settings),
		                                skillsOf(settings));
	}

	private static String string(SettingsMap settings, String key, String fallback) {
		Object value = settings.get(key);
		return value == null || value.toString().isBlank() ? fallback : value.toString();
	}

	@Override
	public void start(SettingsMap settings) {
		settings.put(NAME, instance.id());
		settings.put(VERSION, "1.0");
		settings.put(AUTHOR, "");
		settings.put(DESCRIPTION, "");
		settings.put(FORMAT, FORMAT_HDSL);
		settings.put(SESSIONS, Boolean.FALSE);
		settings.put("modpack.instance", instance.id());
		try {
			settings.put(SETTINGS, new LinkedHashSet<>(DshPluginSettings.sectionsOf(instance.homeDirectory())));
		} catch (DshException e) {
			settings.put(SETTINGS, new LinkedHashSet<String>());
		}
		try {
			settings.put(LOCAL_PLUGINS, PackExporter.localPlugins(instance));
		} catch (DshException e) {
			settings.put(LOCAL_PLUGINS, List.of());
		}
	}

	@Override
	public Node createPage(WizardController controller, int step, SettingsMap settings) {
		return switch (step) {
			case 0 -> new ModpackTypePage(controller);
			case 1 -> new ModpackInfoPage(controller, instance, settings);
			case 2 -> new ModpackFilesPage(controller, instance, settings);
			default -> null;
		};
	}

	@Override
	public Object finish(SettingsMap settings) {
		boolean packforge = FORMAT_PACKFORGE.equals(string(settings, FORMAT, FORMAT_HDSL));

		FileChooser chooser = new FileChooser();
		chooser.setTitle(i18n("modpack.wizard.step.initialization.save"));
		chooser.getExtensionFilters()
		       .add(new FileChooser.ExtensionFilter(packforge
		                                            ? i18n("dsh.packforge.filter")
		                                            : i18n("dsh.modpack.filter"),
		                                            packforge ? "*.dspack" : "*" + PackManifest.FILE_EXTENSION));

		String name = string(settings, NAME, instance.id());
		chooser.setInitialFileName(packforge
		                           ? DshPackForge.Options.kebab(name) + "-" + string(settings, VERSION, "1.0.0") +
		                             ".dspack"
		                           : name + PackManifest.FILE_EXTENSION);

		File chosen = chooser.showSaveDialog(Controllers.getStage());
		if (chosen == null) {
			return null;
		}
		Path target = chosen.toPath();

		settings.put("title", i18n("modpack.export"));
		SimpleStringProperty message = new SimpleStringProperty();
		Consumer<String> report = line -> FXUtils.runInFX(() -> message.set(line));

		Task<Void> task;
		if (packforge) {
			DshPackForge.Options options = new DshPackForge.Options(string(settings, NAME, instance.id()),
			                                                        string(settings, VERSION, "1.0.0"),
			                                                        string(settings, NAME, instance.id()),
			                                                        string(settings, DESCRIPTION, ""),
			                                                        string(settings, AUTHOR, ""));
			task = Task.runAsync(i18n("modpack.export"),
			                     () -> ModpackFilesPage.writePackForge(instance, target, options, report));
		} else {
			PackManifest.Options options = optionsOf(settings);
			task = Task.runAsync(i18n("modpack.export"), () -> ModpackFilesPage.write(instance, target, options, report));
		}
		task.getProperties().put("message", message);
		return task;
	}

	@Override
	public boolean cancel() {
		return true;
	}

}
