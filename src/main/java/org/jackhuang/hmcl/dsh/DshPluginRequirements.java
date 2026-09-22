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
package org.jackhuang.hmcl.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Works out whether a plugin fits the harness an instance runs.
///
/// A plugin declares what it needs in its `peerDependencies`, and those are the
/// harness's own packages — a marketplace plugin asks for
/// `@deepseek-ai/dsh-settings: ^0.1.0-rc.7 || ^0.1.1-rc.2 || ^0.1.2-alpha.2`. The
/// harness an instance runs ships exact versions of exactly those packages, so the
/// two can be compared without asking anybody: what the instance has is read from its
/// own installation, and what the plugin wants is what it published.
///
/// This decides *what is shown* and nothing else. A plugin that does not fit an
/// instance is still installable, because the launcher is not the one who knows
/// better — a person may be installing it to then move the instance, or may know
/// something the ranges do not say.
@NotNullByDefault
public final class DshPluginRequirements {
    /// The prefix the harness's own packages share.
    private static final String CORE_PREFIX = "@deepseek-ai/";

    private DshPluginRequirements() {
    }

    /// Reads the versions of the harness's packages that a published version ships.
    ///
    /// Asked of the registry, once per version and remembered, for the case the picker
    /// offers a version the machine does not have: what that release can run is what it
    /// declares it depends on.
    ///
    /// @param version the harness version
    /// @return the package versions, empty when they cannot be read
    public static Map<String, String> coreVersionsOf(String version) {
        Map<String, String> cached = PUBLISHED_CORE_VERSIONS.get(version);
        if (cached != null) {
            return cached;
        }

        Map<String, String> versions = new java.util.LinkedHashMap<>();
        JsonObject dependencies = DshPackageRegistry.dependencies(
                "@deepseek-ai/dsh", version);
        for (Map.Entry<String, JsonElement> entry : dependencies.entrySet()) {
            if (entry.getKey().startsWith(CORE_PREFIX) && entry.getValue().isJsonPrimitive()) {
                versions.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        Map<String, String> result = Map.copyOf(versions);
        PUBLISHED_CORE_VERSIONS.put(version, result);
        return result;
    }

    /// The packages read so far, by harness version.
    private static final Map<String, Map<String, String>> PUBLISHED_CORE_VERSIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /// Reads the versions of the harness's packages that an instance holds.
    ///
    /// Read from the instance's own installation rather than from the registry: what an
    /// instance can run is what it has, and it has an exact set of these packages. The
    /// answer is remembered per instance version, because it cannot change while the
    /// instance does not.
    ///
    /// @param instance the instance
    /// @return the package versions, empty when they cannot be read
    public static Map<String, String> coreVersions(DshInstance instance) {
        String key = instance.id() + "@" + instance.version();
        Map<String, String> cached = CORE_VERSIONS.get(key);
        if (cached != null) {
            return cached;
        }

        Map<String, String> versions = new java.util.LinkedHashMap<>();
        try {
            java.nio.file.Path manifest = instance.dshDirectory()
                    .resolve("node_modules").resolve("@deepseek-ai").resolve("dsh")
                    .resolve("package.json");
            if (java.nio.file.Files.isRegularFile(manifest)) {
                JsonObject root = org.jackhuang.hmcl.util.gson.JsonUtils
                        .fromJsonFile(manifest, JsonObject.class);
                if (root != null && root.has("dependencies") && root.get("dependencies").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry
                            : root.getAsJsonObject("dependencies").entrySet()) {
                        if (entry.getKey().startsWith(CORE_PREFIX) && entry.getValue().isJsonPrimitive()) {
                            versions.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                }
            }
        } catch (DshException | java.io.IOException | RuntimeException e) {
            LOG.warning("Could not read the packages of " + instance.id(), e);
        }

        Map<String, String> result = Map.copyOf(versions);
        CORE_VERSIONS.put(key, result);
        return result;
    }

    /// The versions read so far, per instance and version.
    private static final Map<String, Map<String, String>> CORE_VERSIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /// Reports whether a plugin fits the versions an instance holds.
    ///
    /// @param peerDependencies the plugin's peer dependencies, or `null`
    /// @param coreVersions     the versions of the harness's packages the instance has
    /// @return whether every requirement that can be judged is satisfied
    public static boolean fits(@Nullable JsonObject peerDependencies, Map<String, String> coreVersions) {
        if (peerDependencies == null) {
            return true;
        }
        for (Map.Entry<String, JsonElement> entry : peerDependencies.entrySet()) {
            if (!entry.getKey().startsWith(CORE_PREFIX) || !entry.getValue().isJsonPrimitive()) {
                continue;
            }
            String held = coreVersions.get(entry.getKey());
            if (held == null) {
                // A package the instance does not have says nothing about whether the
                // plugin fits: it may be one the harness folds into another, and
                // guessing would hide plugins for no reason.
                continue;
            }
            if (!satisfies(entry.getValue().getAsString(), held)) {
                return false;
            }
        }
        return true;
    }

    /// Reports whether a version satisfies an npm range.
    ///
    /// The ranges plugins publish are the ones a package manager writes: alternatives
    /// separated by `||`, each an exact version, a `^` or `~` range, or a comparison.
    ///
    /// @param range   the range
    /// @param version the version
    /// @return whether it satisfies
    public static boolean satisfies(String range, String version) {
        if (range == null || range.isBlank() || version == null || version.isBlank()) {
            return true;
        }
        for (String alternative : range.split("\\|\\|")) {
            if (satisfiesSingle(alternative.trim(), version.trim())) {
                return true;
            }
        }
        return false;
    }

    /// Reports whether a version satisfies one alternative.
    ///
    /// The rules are npm's, because the ranges are npm's: a caret holds from the
    /// version it names up to the next change of the leftmost non-zero part, a tilde
    /// up to the next minor, and a pre-release never satisfies a range that does not
    /// name one — which is what keeps `0.1.2-alpha.2` out of `^0.1.2` while letting
    /// the release itself in.
    ///
    /// @param range   the alternative
    /// @param version the version
    /// @return whether it satisfies
    private static boolean satisfiesSingle(String range, String version) {
        if (range.isEmpty() || range.equals("*") || range.equalsIgnoreCase("latest")) {
            return true;
        }

        if (range.startsWith("^") || range.startsWith("~")) {
            boolean caret = range.startsWith("^");
            String base = range.substring(1).trim();

            if (hasPrerelease(version) && !hasPrerelease(base)) {
                return false;
            }
            if (range.contains(" ")) {
                // `^1.2.3 || ^1.2.4` never has a space; `^1.2.3 <1.5.0` does, and
                // each part is then applied on its own.
                for (String part : range.split("\\s+")) {
                    if (!part.isBlank() && !satisfiesSingle(part.trim(), version)) {
                        return false;
                    }
                }
                return true;
            }

            int[] bound = triple(base);
            int[] held = triple(version);
            if (compareTriples(held, bound) < 0) {
                return false;
            }

            int[] upper;
            if (!caret) {
                upper = new int[]{bound[0], bound[1] + 1, 0};
            } else if (bound[0] > 0) {
                upper = new int[]{bound[0] + 1, 0, 0};
            } else if (bound[1] > 0) {
                upper = new int[]{0, bound[1] + 1, 0};
            } else {
                upper = new int[]{0, 0, bound[2] + 1};
            }
            return compareTriples(held, upper) < 0;
        }

        if (range.contains(" ") && !range.startsWith("^") && !range.startsWith("~")) {
            for (String part : range.split("\\s+")) {
                if (!part.isBlank() && !satisfiesSingle(part.trim(), version)) {
                    return false;
                }
            }
            return true;
        }

        if (range.startsWith(">=") || range.startsWith("<=")
                || range.startsWith(">") || range.startsWith("<") || range.startsWith("=")) {
            String operator = range.startsWith(">=") || range.startsWith("<=")
                    ? range.substring(0, 2) : range.substring(0, 1);
            String base = range.substring(operator.length()).trim();

            if (hasPrerelease(version) && !hasPrerelease(base)) {
                return false;
            }
            int order = compareTriples(triple(version), triple(base));
            return switch (operator) {
                case ">=" -> order >= 0;
                case "<=" -> order <= 0;
                case ">" -> order > 0;
                case "<" -> order < 0;
                default -> order == 0;
            };
        }

        // A space-separated range is a conjunction, as npm writes `>=1.0.0 <2.0.0`.
        if (range.contains(" ")) {
            for (String part : range.split("\\s+")) {
                if (!part.isBlank() && !satisfiesSingle(part.trim(), version)) {
                    return false;
                }
            }
            return true;
        }

        return compare(triple(version), triple(range)) == 0 && version.equals(range);
    }

    /// Reports whether a version carries a pre-release.
    ///
    /// @param version the version
    /// @return whether it has one
    private static boolean hasPrerelease(String version) {
        return version.indexOf('-') >= 0;
    }

    /// Returns the first three numbers of a version.
    ///
    /// @param version the version
    /// @return major, minor and patch
    private static int[] triple(String version) {
        String clean = version.trim();
        if (clean.startsWith("v")) {
            clean = clean.substring(1);
        }
        String[] numbers = clean.split("-", 2)[0].split("\\.");
        int[] result = new int[3];
        for (int i = 0; i < 3; i++) {
            if (i < numbers.length) {
                try {
                    result[i] = Integer.parseInt(numbers[i].trim());
                } catch (NumberFormatException e) {
                    result[i] = 0;
                }
            }
        }
        return result;
    }

    /// Compares two first-three-numbers versions.
    ///
    /// @param left  the left version
    /// @param right the right version
    /// @return negative, zero or positive
    private static int compareTriples(int[] left, int[] right) {
        for (int i = 0; i < 3; i++) {
            if (left[i] != right[i]) {
                return Integer.compare(left[i], right[i]);
            }
        }
        return 0;
    }

    /// Splits a version into its numbers and its pre-release parts.
    ///
    /// @param version the version
    /// @return major, minor, patch, then the pre-release identifiers as numbers
    ///         where they are numbers and as high values where they are not
    private static int[] parts(String version) {
        String clean = version.trim();
        if (clean.startsWith("v")) {
            clean = clean.substring(1);
        }
        String[] halves = clean.split("-", 2);
        String[] numbers = halves[0].split("\\.");
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            result.add(i < numbers.length ? number(numbers[i]) : 0);
        }
        if (halves.length > 1) {
            for (String identifier : halves[1].split("\\.")) {
                result.add(number(identifier));
            }
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    /// Reads a number out of a version part.
    ///
    /// @param text the part
    /// @return its number, or a high value for a named pre-release, so that
    ///         `1.0.0-rc` sorts above `1.0.0-alpha` the way npm sorts them
    private static int number(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return NAMED;
        }
    }

    /// Where a named pre-release identifier sorts.
    private static final int NAMED = 1_000_000;

    /// Compares two parsed versions.
    ///
    /// A version with no pre-release is newer than the same version with one, which
    /// is npm's rule and the one that matters here: `0.1.2-alpha.2` does not satisfy
    /// `^0.1.2` unless the range says so, and `0.1.2` does.
    ///
    /// @param left  the left version
    /// @param right the right version
    /// @return negative, zero or positive
    private static int compare(int[] left, int[] right) {
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = i < left.length ? left[i] : (i >= 3 ? -1 : 0);
            int b = i < right.length ? right[i] : (i >= 3 ? -1 : 0);
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }
}
