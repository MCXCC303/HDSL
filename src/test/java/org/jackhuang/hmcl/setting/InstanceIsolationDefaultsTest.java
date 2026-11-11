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
package org.jackhuang.hmcl.setting;

import com.google.gson.JsonObject;
import com.mcxcc303.hdsl.instance.DshHomeMode;
import com.mcxcc303.hdsl.instance.DshIsolationPolicy;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceIsolationDefaultsTest {
	private Path settingsFile;
	private byte[] previousFile;
	private LauncherSettings liveSettings;
	private DshIsolationPolicy previousPolicy;
	private DshHomeMode previousHomeMode;

	@BeforeEach
	void useTheBuildsTestHome() throws IOException {
		// SettingsManager uses a fixed path. Never exercise save/load against a real user HOME.
		String configured = System.getProperty("hdsl.home");
		assertTrue(configured != null && !configured.isBlank(), "a dedicated test HOME is required");
		Path testHome = Path.of(configured).toAbsolutePath().normalize();
		assertTrue(testHome.endsWith(Path.of("build", "test-home")), "only the build's test HOME may be written");
		assertEquals(testHome, Metadata.HDSL_USER_HOME.toAbsolutePath().normalize());
		settingsFile = testHome.resolve("launcher-settings.json");
		assertTrue(!Files.isSymbolicLink(testHome) && !Files.isSymbolicLink(settingsFile));
		previousFile = Files.isRegularFile(settingsFile) ? Files.readAllBytes(settingsFile) : null;

		liveSettings = SettingsManager.settings();
		previousPolicy = liveSettings.isolationPolicyProperty().get();
		previousHomeMode = liveSettings.defaultHomeModeProperty().get();
	}

	@AfterEach
	void restoreTheTestHome() throws IOException {
		if (liveSettings != null) {
			liveSettings.isolationPolicyProperty().set(previousPolicy);
			liveSettings.defaultHomeModeProperty().set(previousHomeMode);
		}
		if (settingsFile != null) {
			if (previousFile == null) {
				Files.deleteIfExists(settingsFile);
			} else {
				Files.write(settingsFile, previousFile);
			}
		}
	}

	@Test
	void newSettingsAndNullPolicyDefaultToIsolation() {
		LauncherSettings settings = new LauncherSettings();
		assertEquals(DshIsolationPolicy.ALWAYS, settings.isolationPolicy());
		settings.isolationPolicyProperty().set(null);
		assertEquals(DshIsolationPolicy.ALWAYS, settings.isolationPolicy());
	}

	@Test
	void missingPolicyDefaultsToIsolation() throws IOException {
		assertEquals(DshIsolationPolicy.ALWAYS, loadFixture("{}").isolationPolicy());
	}

	@Test
	void legacyAutomaticDefaultDoesNotKeepNewInstancesShared() throws IOException {
		LauncherSettings settings = loadFixture("""
				{"isolationPolicy":"modded", "defaultHomeMode":"VERSION_SHARED"}
				""");
		assertEquals(DshIsolationPolicy.ALWAYS, settings.isolationPolicy());
		assertEquals(DshHomeMode.VERSION_SHARED, settings.defaultHomeModeProperty().get(),
		             "the runtime default used by existing GLOBAL instances must not change");
	}

	@Test
	void legacyExplicitSharingRemainsAvailable() throws IOException {
		assertEquals(DshIsolationPolicy.NEVER,
		             loadFixture("{\"isolationPolicy\":\"never\"}").isolationPolicy());
	}

	@Test
	void newExplicitPolicyTakesPrecedenceOverLegacyDefault() throws IOException {
		assertEquals(DshIsolationPolicy.WITH_PLUGINS, loadFixture("""
				{"isolationPolicy":"always", "newInstanceIsolationPolicy":"modded"}
				""").isolationPolicy());
	}

	@Test
	void invalidNewPolicyFallsBackToIsolation() throws IOException {
		assertEquals(DshIsolationPolicy.ALWAYS, loadFixture("""
				{"isolationPolicy":"never", "newInstanceIsolationPolicy":"invalid"}
				""").isolationPolicy());
	}

	@Test
	void explicitPoliciesSurviveSavingAndReloading() throws IOException {
		for (DshIsolationPolicy policy : DshIsolationPolicy.values()) {
			liveSettings.isolationPolicyProperty().set(policy);
			liveSettings.defaultHomeModeProperty().set(DshHomeMode.VERSION_SHARED);
			SettingsManager.save();

			JsonObject disk = JsonUtils.fromJsonFile(settingsFile, JsonObject.class);
			assertEquals(policy.id(), disk.get("newInstanceIsolationPolicy").getAsString());
			LauncherSettings restored = SettingsManager.load();
			assertEquals(policy, restored.isolationPolicy());
			assertEquals(DshHomeMode.VERSION_SHARED, restored.defaultHomeModeProperty().get());
		}
	}

	private LauncherSettings loadFixture(String json) throws IOException {
		Files.createDirectories(settingsFile.getParent());
		Files.writeString(settingsFile, json, StandardCharsets.UTF_8);
		return SettingsManager.load();
	}
}
