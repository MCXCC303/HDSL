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
package com.mcxcc303.hdsl.plugin;

import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceIcon;
import com.mcxcc303.hdsl.instance.DshPortMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a profile's plugins are, and what is known about each of them
 */
class DshInstalledPluginsTest {
	private static final String HARNESS = "0.2.0-rc.2";

	@TempDir
	Path folder;

	private static Pkg plain() {
		return new Pkg("1.0.0", "1.0.0", null, null, List.of());
	}

	private static Pkg absent() {
		return new Pkg("1.0.0", null, null, null, List.of());
	}

	private static Pkg withPeers(String peers) {
		return new Pkg("1.0.0", "1.0.0", peers, null, List.of());
	}

	private static Pkg withRows(String... rows) {
		return new Pkg("1.0.0", "1.0.0", null, null,
		               Arrays.stream(rows).map(id -> new LoaderRow(id, null)).toList());
	}

	private static Pkg withNamedRows(LoaderRow... rows) {
		return new Pkg("1.0.0", "1.0.0", null, null, List.of(rows));
	}

	private static Pkg withIcon() {
		return new Pkg("1.0.0", "1.0.0", null, "icon.svg", List.of());
	}

	private static String manifest(String name, Pkg pkg) {
		String peers = pkg.peers() == null ? "" : ",\n  \"peerDependencies\": " + pkg.peers();
		String icon = pkg.icon() == null ? "" : ",\n  \"icon\": \"" + pkg.icon() + "\"";
		return """
				{
				  "name": "%s",
				  "version": "%s"%s%s
				}
				""".formatted(name, pkg.installed(), peers, icon);
	}

	@Test
	void aDependencyThatIsNotABundleIsNotInEffect() throws Exception {
		DshInstance instance = instance("not-a-bundle", List.of(), Map.of("dsh-plain", plain()), List.of());

		DshInstalledPlugins.Installed plugin = only(instance);

		assertEquals(DshInstalledPlugins.State.INACTIVE, plugin.state(),
		             "a package the profile does not load as a bundle is not in effect");
	}

	@Test
	void aBundleThePatchLayerSwitchesOffIsDisabled() throws Exception {
		DshInstance instance = instance("switched-off",
		                                List.of("dsh-switched"),
		                                Map.of("dsh-switched", withRows("dsh-switched-row")),
		                                List.of("dsh-switched-row"));

		DshInstalledPlugins.Installed plugin = only(instance);

		assertEquals(DshInstalledPlugins.State.DISABLED, plugin.state(),
		             "a bundle the patch layer switches off is not running");
	}

	@Test
	void aDependencyTheProfileHasNotInstalledIsMissing() throws Exception {
		DshInstance instance = instance("not-installed", List.of(), Map.of("dsh-absent", absent()), List.of());

		DshInstalledPlugins.Installed plugin = only(instance);

		assertEquals(DshInstalledPlugins.State.MISSING, plugin.state());
		assertNull(plugin.version(), "a package that is not there has no version");
	}

	@Test
	void aPluginThatSaysNothingAboutTheHarnessIsUnclaimed() throws Exception {
		DshInstance instance = instance("says-nothing", List.of(), Map.of("dsh-plain", plain()), List.of());

		DshInstalledPlugins.Installed plugin = only(instance);

		assertEquals(DshPluginVersions.Fit.UNCLAIMED, plugin.fit());
		assertEquals("", plugin.requirements(), "and it requires nothing that could be judged");
	}

	@Test
	void aPluginThatAsksForAnyVersionSaysNothing() throws Exception {
		String peers = "{\"@deepseek-ai/dsh\": \"*\"}";
		DshInstance instance = instance("asks-for-any", List.of(), Map.of("dsh-any", withPeers(peers)), List.of());

		DshInstalledPlugins.Installed plugin = only(instance);

		assertEquals(DshPluginVersions.Fit.UNCLAIMED, plugin.fit(),
		             "a range of \"*\" asks for any version, which is not a requirement");
		assertEquals("", plugin.requirements());
	}

	@Test
	void aPluginThatAsksForTheHarnessItRunsOnFits() throws Exception {
		String peers = "{\"@deepseek-ai/dsh\": \">=" + HARNESS + "\"}";
		DshInstance instance = instance("fits", List.of(), Map.of("dsh-fitting", withPeers(peers)), List.of());

		DshInstalledPlugins.Installed plugin = only(instance);

		assertEquals(DshPluginVersions.Fit.FITS, plugin.fit());
		assertEquals("@deepseek-ai/dsh >=" + HARNESS, plugin.requirements());
	}

	@Test
	void aPluginThatAsksForANewerHarnessDoesNotFit() throws Exception {
		String peers = "{\"@deepseek-ai/dsh\": \">=9.0.0\"}";
		DshInstance instance = instance("does-not-fit", List.of(), Map.of("dsh-newer", withPeers(peers)), List.of());

		DshInstalledPlugins.Installed plugin = only(instance);

		assertEquals(DshPluginVersions.Fit.NOT_FITS, plugin.fit());
	}

	@Test
	void theLoaderRowsABundleInsertsAreListed() throws Exception {
		DshInstance instance = instance("rows",
		                                List.of("dsh-with-rows"),
		                                Map.of("dsh-with-rows", withRows("dsh-market")),
		                                List.of());

		DshInstalledPlugins.Installed plugin = only(instance);

		assertEquals(List.of("dsh-market"), plugin.rowIds());
	}

	@Test
	void theRowThatMountsThePackageNamesThePlugin() throws Exception {
		DshInstance instance = instance("mounted",
		                                List.of("dshmarket"),
		                                Map.of("dshmarket",
		                                       withNamedRows(new LoaderRow("dashboard", "@other/dashboard"),
		                                                     new LoaderRow("dsh-market", "dshmarket"))),
		                                List.of());

		DshInstalledPlugins.Installed plugin = only(instance);

		assertEquals("dsh-market", plugin.displayName(),
		             "the row that mounts the package names the plugin, not the first row");
	}

	@Test
	void aPluginThatInsertsNoRowsIsNamedByItsPackageName() throws Exception {
		DshInstance instance = instance("named-by-package", List.of(), Map.of("dsh-plain", plain()), List.of());

		assertEquals("dsh-plain", only(instance).displayName());
	}

	@Test
	void aSubpathRowNamesThePluginWhenItIsTheOnlyOne() throws Exception {
		DshInstance instance = instance("subpath",
		                                List.of("dsh-demo"),
		                                Map.of("dsh-demo",
		                                       withNamedRows(new LoaderRow("demo-preset", "dsh-demo/preset"))),
		                                List.of());

		assertEquals("demo-preset", only(instance).displayName());
	}

	@Test
	void thePluginCarriesTheIconItDeclares() throws Exception {
		DshInstance instance = instance("icon", List.of(), Map.of("dsh-with-icon", withIcon()), List.of());

		assertNotNull(only(instance).icon());
	}

	@Test
	void aPluginInsideTheProfileIsNamedTheWayTheProfileHoldsIt() throws Exception {
		DshInstance instance = instance("inside", List.of(), Map.of("dsh-inside", plain()), List.of());

		DshInstalledPlugins.Installed plugin = only(instance);

		assertEquals("node_modules/dsh-inside", plugin.path());
	}

	private DshInstalledPlugins.Installed only(DshInstance instance) throws Exception {
		List<DshInstalledPlugins.Installed> plugins = DshInstalledPlugins.of(instance);
		assertEquals(1, plugins.size(), "the fixture holds one plugin");
		return plugins.get(0);
	}

	private DshInstance instance(String id,
	                             List<String> bundles,
	                             Map<String, Pkg> packages,
	                             List<String> disabled) throws IOException {
		Path directory = folder.resolve(id);
		Path profile = directory.resolve("home").resolve("profiles").resolve("web");
		Files.createDirectories(profile);

		StringBuilder dependencies = new StringBuilder();
		StringBuilder bundleList = new StringBuilder();
		for (Map.Entry<String, Pkg> entry : packages.entrySet()) {
			if (dependencies.length() > 0) {
				dependencies.append(",\n");
			}
			dependencies.append("    \"")
			            .append(entry.getKey())
			            .append("\": \"")
			            .append(entry.getValue().declared())
			            .append("\"");
		}
		for (String bundle : bundles) {
			if (!bundleList.isEmpty()) {
				bundleList.append(",\n");
			}
			bundleList.append("        \"").append(bundle).append("\"");
		}
		Files.writeString(profile.resolve("package.json"), """
				{
				  "name": "dsh-profile-web",
				  "private": true,
				  "dependencies": {
				%s
				  },
				  "dsh": {
				    "profile": {
				      "bundles": [
				%s
				      ]
				    }
				  }
				}
				""".formatted(dependencies, bundleList));

		StringBuilder layer = new StringBuilder();
		for (String row : disabled) {
			layer.append("- id: ").append(row).append("\n  disabled: true\n");
		}
		Files.writeString(profile.resolve("cordis.patch.yml"), layer.toString());

		for (Map.Entry<String, Pkg> entry : packages.entrySet()) {
			Pkg pkg = entry.getValue();
			if (pkg.installed() == null) {
				continue;
			}
			Path packageDirectory = profile.resolve("node_modules").resolve(entry.getKey());
			Files.createDirectories(packageDirectory);
			Files.writeString(packageDirectory.resolve("package.json"), manifest(entry.getKey(), pkg));
			if (!pkg.rows().isEmpty()) {
				StringBuilder patch = new StringBuilder("- insert:\n");
				for (LoaderRow row : pkg.rows()) {
					patch.append("    - id: ").append(row.id()).append("\n");
					if (row.name() != null) {
						patch.append("      name: '").append(row.name()).append("'\n");
					}
				}
				Files.writeString(packageDirectory.resolve("cordis.patch.yml"), patch.toString());
			}
			if (pkg.icon() != null) {
				Files.writeString(packageDirectory.resolve(pkg.icon()), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
			}
		}

		Path harness = directory.resolve("dsh").resolve("node_modules").resolve("@deepseek-ai").resolve("dsh");
		Files.createDirectories(harness);
		Files.writeString(harness.resolve("package.json"), """
				{
				  "name": "@deepseek-ai/dsh",
				  "version": "%s",
				  "dependencies": {
				    "@deepseek-ai/dsh-settings": "%s"
				  }
				}
				""".formatted(HARNESS, HARNESS));

		return new DshInstance(id,
		                       HARNESS,
		                       "web",
		                       folder.toString(),
		                       null,
		                       DshHomeMode.ISOLATED,
		                       null,
		                       List.of(),
		                       Map.of(),
		                       DshInstanceIcon.DEFAULT.id(),
		                       null,
		                       DshPortMode.AUTO,
		                       0,
		                       0L,
		                       directory.toString());
	}

	private record Pkg(String declared, String installed, String peers, String icon, List<LoaderRow> rows) {}

	private record LoaderRow(String id, String name) {}
}
