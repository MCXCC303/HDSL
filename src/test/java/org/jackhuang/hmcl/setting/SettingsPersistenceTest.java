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
package org.jackhuang.hmcl.setting;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that every setting a page can bind to is one the settings file holds.
///
/// The settings file is restored through an explicit mirror — `SettingsManager.save()`
/// writes `Snapshot.of(settings())` and `load()` reads that snapshot back — so a
/// property that no snapshot field carries is a setting somebody can change, watch take
/// effect, and lose at the next start, with nothing said about it. Seventeen were in
/// that state when the general, download-source and global instance-settings tabs were
/// reworked: the install-script policy, the launcher visibility, the log and debug
/// switches, both commands, the environment variables, the plugin catalogue address, the
/// cache folder, the thread count and the whole proxy.
///
/// The check is a source-level one rather than a round trip through the file: what can go
/// wrong here is a property missing from one of the two halves of the mirror, and a test
/// that has to initialise JavaFX and a home directory to notice that would be one more
/// thing to keep working.
class SettingsPersistenceTest {
    /// The properties that are deliberately not stored.
    ///
    /// Each is a value the launcher works out again on every start, so storing it would
    /// be storing a copy that can disagree with its source.
    private static final List<String> NOT_STORED = List.of(
            // Both are read when a background is painted and written by nothing: this
            // launcher offers no flat-colour background, so their contents belong to the
            // theme pack, which is itself stored.
            "backgroundFallbackPaint",
            "customBackgroundPaint");

    @Test
    void everySettingAPropertyExposesIsCapturedAndRestored() throws IOException {
        String manager = sourceOf(SettingsManager.class);
        String captured = bodyOf(manager, "static Snapshot of(");
        String restored = bodyOf(manager, "void applyTo(");

        List<String> notCaptured = new ArrayList<>();
        List<String> notRestored = new ArrayList<>();
        for (String name : propertiesOf(LauncherSettings.class)) {
            if (NOT_STORED.contains(name)) {
                continue;
            }
            if (!captured.contains(name)) {
                notCaptured.add(name);
            }
            if (!restored.contains(name)) {
                notRestored.add(name);
            }
        }

        assertTrue(notCaptured.isEmpty(),
                "these settings can be changed in the interface but are never written to the settings file,"
                        + " so they revert on the next start: " + notCaptured);
        assertTrue(notRestored.isEmpty(),
                "these settings are written to the settings file but never read back,"
                        + " so they revert on the next start: " + notRestored);
    }

    /// Reads the names of the public property accessors of a settings class.
    ///
    /// Read from the source rather than by reflection: `LauncherSettings` builds JavaFX
    /// properties in its field initialisers, so loading the class needs a JavaFX toolkit
    /// that a headless test run does not have.
    ///
    /// @param type the settings class
    /// @return the property names, without the `Property` suffix
    /// @throws IOException when the source cannot be read
    private static List<String> propertiesOf(Class<?> type) throws IOException {
        Matcher matcher = Pattern.compile(
                "public\\s+[\\w.<>@\\s]+?\\s+(\\w+)Property\\(\\)").matcher(sourceOf(type));
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        assertTrue(!names.isEmpty(), "found no property accessors in " + type.getSimpleName());
        return names;
    }

    /// Reads a method body out of a source file.
    ///
    /// @param source the file's text
    /// @param header the text the method's signature starts with
    /// @return everything up to the closing brace in the first column
    private static String bodyOf(String source, String header) {
        int start = source.indexOf(header);
        assertTrue(start >= 0, "no method starting with `" + header + "` in SettingsManager");
        int end = source.indexOf("\n        }", start);
        assertTrue(end > start, "the method starting with `" + header + "` is not closed at method indentation");
        return source.substring(start, end);
    }

    /// Locates the source file of a class in this project.
    ///
    /// @param type the class
    /// @return its source
    /// @throws IOException when the file cannot be read
    private static String sourceOf(Class<?> type) throws IOException {
        Path path = Path.of("src/main/java").resolve(type.getName().replace('.', '/') + ".java");
        assertTrue(Files.isRegularFile(path), "no source at " + path.toAbsolutePath());
        return Files.readString(path);
    }
}
