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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.Semver;
import com.mcxcc303.hdsl.harness.DshPackageRegistry;
import com.mcxcc303.hdsl.instance.DshInstance;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Evaluates whether a plugin's requirements fit the core harness versions of an instance.
 */
@NotNullByDefault
public final class DshPluginRequirements {
	private static final String CORE_PREFIX = "@deepseek-ai/";
	private static final String HARNESS = CORE_PREFIX + "dsh";
	private static final List<String> WORKSPACE = List.of("workspace:^", "workspace:~", "workspace:*");
	private static final Pattern HYPHEN = Pattern.compile("\\s+-\\s+");
	private static final Map<String, Map<String, String>> PUBLISHED_CORE_VERSIONS = new ConcurrentHashMap<>();
	private static final Map<String, Map<String, String>> CORE_VERSIONS = new ConcurrentHashMap<>();

	private DshPluginRequirements() {
	}

	/**
	 * Reads the versions of harness packages that a published version ships.
	 *
	 * @param version the harness version
	 * @return the package versions, empty when they cannot be read
	 */
	public static Map<String, String> coreVersionsOf(String version) {
		Map<String, String> cached = PUBLISHED_CORE_VERSIONS.get(version);
		if (cached != null) {
			return cached;
		}

		Map<String, String> versions = new LinkedHashMap<>();
		// The harness package does not depend on itself, so it is added directly
		versions.put(HARNESS, version);
		JsonObject dependencies = DshPackageRegistry.dependencies(HARNESS, version);
		for (Map.Entry<String, JsonElement> entry : dependencies.entrySet()) {
			if (entry.getKey().startsWith(CORE_PREFIX) && entry.getValue().isJsonPrimitive()) {
				versions.put(entry.getKey(), entry.getValue().getAsString());
			}
		}

		Map<String, String> result = Map.copyOf(versions);
		PUBLISHED_CORE_VERSIONS.put(version, result);
		return result;
	}

	/**
	 * Reads the versions of the harness's packages that an instance holds.
	 *
	 * @param instance the instance
	 * @return the package versions, empty when they cannot be read
	 */
	public static Map<String, String> coreVersions(DshInstance instance) {
		String key = instance.id() + "@" + instance.version();
		Map<String, String> cached = CORE_VERSIONS.get(key);
		if (cached != null) {
			return cached;
		}

		Map<String, String> versions = Map.of();
		try {
			versions = readCoreVersions(instance.dshDirectory()
			                                    .resolve("node_modules")
			                                    .resolve("@deepseek-ai")
			                                    .resolve("dsh")
			                                    .resolve("package.json"));
		} catch (DshException | IOException | RuntimeException e) {
			LOG.warning("Could not read the packages of " + instance.id(), e);
		}

		CORE_VERSIONS.put(key, versions);
		return versions;
	}

	/**
	 * Reads the versions of the harness's packages that an installation holds.
	 *
	 * @param manifest the manifest of the harness's own package in that installation
	 * @return the versions, by package name, empty when there is no installation to read
	 * @throws IOException when the manifest is there and cannot be read
	 */
	static Map<String, String> readCoreVersions(Path manifest) throws IOException {
		if (!Files.isRegularFile(manifest)) {
			return Map.of();
		}
		JsonObject root = JsonUtils.fromJsonFile(manifest, JsonObject.class);
		if (root == null) {
			return Map.of();
		}

		Map<String, String> versions = new LinkedHashMap<>();
		if (root.has("version") && root.get("version").isJsonPrimitive()) {
			versions.put(HARNESS, root.get("version").getAsString());
		}
		if (root.has("dependencies") && root.get("dependencies").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("dependencies").entrySet()) {
				if (entry.getKey().startsWith(CORE_PREFIX) && entry.getValue().isJsonPrimitive()) {
					versions.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
		}
		return Map.copyOf(versions);
	}

	/**
	 * Returns what a manifest requires of the harness's own packages.
	 *
	 * @param peerDependencies the manifest's peer dependencies, or {@code null}
	 * @return the requirements, empty when the manifest names none
	 */
	public static JsonObject coreRequirements(@Nullable JsonObject peerDependencies) {
		JsonObject requirements = new JsonObject();
		if (peerDependencies == null) {
			return requirements;
		}
		for (Map.Entry<String, JsonElement> entry : peerDependencies.entrySet()) {
			if (!entry.getKey().startsWith(CORE_PREFIX) || !entry.getValue().isJsonPrimitive()) {
				continue;
			}
			requirements.add(entry.getKey(), entry.getValue());
		}
		return requirements;
	}

	/**
	 * Reports whether a plugin fits the versions an instance holds.
	 *
	 * @param peerDependencies the plugin's peer dependencies, or {@code null}
	 * @param coreVersions     the versions of the harness's packages the instance has
	 * @return whether every requirement that can be judged is satisfied
	 */
	public static boolean fits(@Nullable JsonObject peerDependencies, Map<String, String> coreVersions) {
		for (Map.Entry<String, JsonElement> entry : coreRequirements(peerDependencies).entrySet()) {
			String held = coreVersions.get(entry.getKey());
			if (held == null) {
				continue;
			}
			if (!satisfies(entry.getValue().getAsString(), held)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Reports whether a version satisfies an npm range.
	 *
	 * @param range   the range
	 * @param version the version
	 * @return whether it satisfies
	 */
	public static boolean satisfies(String range, String version) {
		if (range == null || version == null || range.isBlank() || version.isBlank()) {
			return false;
		}

		String asked = range.trim();
		if (WORKSPACE.contains(asked)) {
			return true;
		}

		String held = version.trim();
		for (String alternative : asked.split("\\|\\|", -1)) {
			if (satisfiesAlternative(alternative.trim(), held)) {
				return true;
			}
		}
		return false;
	}

	private static boolean satisfiesAlternative(String alternative, String version) {
		if (alternative.isEmpty()) {
			return true;
		}
		Matcher dash = HYPHEN.matcher(alternative);
		if (!dash.find()) {
			for (String comparator : comparatorsOf(alternative)) {
				if (!satisfiesComparator(comparator, version)) {
					return false;
				}
			}
			return true;
		}
		return satisfiesHyphen(alternative.substring(0, dash.start()).trim(),
		                       alternative.substring(dash.end()).trim(),
		                       version);
	}

	private static List<String> comparatorsOf(String alternative) {
		List<String> comparators = new ArrayList<>();
		String operator = null;
		for (String token : alternative.split("\\s+")) {
			if (token.isEmpty()) {
				continue;
			}
			if (operator != null) {
				comparators.add(operator + token);
				operator = null;
			} else if (isOperatorAlone(token)) {
				operator = token;
			} else {
				comparators.add(token);
			}
		}
		if (operator != null) {
			comparators.add(operator);
		}
		return comparators;
	}

	private static boolean isOperatorAlone(String token) {
		for (int i = 0; i < token.length(); i++) {
			if ("^~<>=vV".indexOf(token.charAt(i)) < 0) {
				return false;
			}
		}
		return !token.isEmpty();
	}

	private static boolean satisfiesHyphen(String from, String to, String version) {
		if (!isBound(from) || !isBound(to)) {
			return false;
		}
		int floors = countOf(from);
		int ceilings = countOf(to);
		if (floors == 0 && ceilings == 0) {
			return false;
		}

		Semver held = parse(version);
		if (floors > 0) {
			Semver floor = parse(from);
			if (floors < 3 || floor.prerelease() == null) {
				int[] named = floor.numbers();
				floor = release(named[0], named[1], named[2]);
			}
			if (compare(held, floor) < 0) {
				return false;
			}
		}
		if (ceilings == 0) {
			return true;
		}

		Semver ceiling = parse(to);
		if (ceilings < 3) {
			return compare(held, upperBound(false, ceilings, ceiling.numbers())) < 0;
		}
		if (ceiling.prerelease() != null) {
			return compare(held, ceiling) <= 0;
		}
		int[] named = ceiling.numbers();
		return compare(held, release(named[0], named[1], named[2] + 1)) < 0;
	}

	private static boolean satisfiesComparator(String comparator, String version) {
		if (comparator.isEmpty()) {
			return false;
		}
		if (comparator.startsWith("~>")) {
			comparator = "~" + comparator.substring(2).trim();
		}
		if (isWildcard(comparator)) {
			return true;
		}

		char first = comparator.charAt(0);
		if (first == '^' || first == '~') {
			String base = comparator.substring(1).trim();
			if (isWildcard(base)) {
				return true;
			}
			if (!isBound(base)) {
				return false;
			}
			int count = countOf(base);
			if (count == 0) {
				return false;
			}
			int[] named = parse(base).numbers();
			Semver lower = count == 3 ? parse(base) : release(named[0], named[1], named[2]);
			return within(version, lower, upperBound(first == '^', count, named));
		}

		if (first == '>' || first == '<' || first == '=') {
			String operator = comparator.startsWith(">=") || comparator.startsWith("<=")
			                  ? comparator.substring(0, 2)
			                  : comparator.substring(0, 1);
			String base = comparator.substring(operator.length()).trim();
			if (isWildcard(base)) {
				return !operator.equals(">") && !operator.equals("<");
			}
			if (!isBound(base)) {
				return false;
			}
			int count = countOf(base);
			if (count == 0) {
				return false;
			}
			if (count < 3) {
				int[] named = parse(base).numbers();
				int above = compare(parse(version), plain(named[0], named[1], named[2]));
				int below = compare(parse(version), upperBound(false, count, named));
				return switch (operator) {
					case ">=" -> above >= 0;
					case ">" -> below >= 0;
					case "<" -> compare(parse(version), release(named[0], named[1], named[2])) < 0;
					case "<=" -> below < 0;
					default -> above >= 0 && below < 0;
				};
			}
			int order = compare(parse(version), parse(base));
			return switch (operator) {
				case ">=" -> order >= 0;
				case "<=" -> order <= 0;
				case ">" -> order > 0;
				case "<" -> order < 0;
				default -> order == 0;
			};
		}

		if (!isBound(comparator)) {
			return false;
		}
		int count = countOf(comparator);
		if (count == 0) {
			return false;
		}
		int[] named = parse(comparator).numbers();
		return count == 3
		       ? compare(parse(version), parse(comparator)) == 0
		       : within(version, release(named[0], named[1], named[2]), upperBound(false, count, named));
	}

	/**
	 * Reports whether a comparator names no version at all.
	 *
	 * @param text the comparator
	 * @return whether it is {@code "*"}, {@code "x"} or {@code "X"}
	 */
	public static boolean isWildcard(String text) {
		return text.equals("*") || text.equals("x") || text.equals("X");
	}

	private static boolean isBound(String version) {
		String clean = compared(version);
		String[] halves = clean.split("-", 2);
		String[] written = halves[0].split("\\.", -1);
		if (written.length > 3) {
			return false;
		}
		for (String part : written) {
			if (!isNumber(part) && !isWildcard(part)) {
				return false;
			}
		}
		if (halves.length == 1) {
			return true;
		}
		if (halves[1].isEmpty()) {
			return false;
		}
		for (String identifier : halves[1].split("\\.", -1)) {
			if (identifier.isEmpty() || !isIdentifier(identifier)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isIdentifier(String identifier) {
		for (int i = 0; i < identifier.length(); i++) {
			char c = identifier.charAt(i);
			if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && c != '-') {
				return false;
			}
		}
		return true;
	}

	private static boolean within(String version, Semver lower, Semver upper) {
		Semver held = parse(version);
		return compare(held, lower) >= 0 && compare(held, upper) < 0;
	}

	private static Semver upperBound(boolean caret, int count, int[] named) {
		if (!caret) {
			return count >= 2 ? release(named[0], named[1] + 1, 0) : release(named[0] + 1, 0, 0);
		}
		if (count == 1 || named[0] > 0) {
			return release(named[0] + 1, 0, 0);
		}
		if (count == 2 || named[1] > 0) {
			return release(named[0], named[1] + 1, 0);
		}
		return release(named[0], named[1], named[2] + 1);
	}

	private static Semver plain(int major, int minor, int patch) {
		return new Semver(major, minor, patch, null);
	}

	private static Semver release(int major, int minor, int patch) {
		return new Semver(major, minor, patch, List.of("0"));
	}

	private static int countOf(String version) {
		String clean = compared(version);
		int count = 0;
		for (String part : clean.split("-", 2)[0].split("\\.", -1)) {
			if (!isNumber(part)) {
				break;
			}
			count++;
		}
		return Math.min(count, 3);
	}

	private static Semver parse(String version) {
		return Semver.parsePartial(version);
	}

	private static String compared(String version) {
		String clean = version.trim();
		if (clean.startsWith("v") || clean.startsWith("V")) {
			clean = clean.substring(1);
		}
		int build = clean.indexOf('+');
		return build < 0 ? clean : clean.substring(0, build);
	}

	private static boolean isNumber(String identifier) {
		if (identifier.isEmpty()) {
			return false;
		}
		for (int i = 0; i < identifier.length(); i++) {
			char c = identifier.charAt(i);
			if (c < '0' || c > '9') {
				return false;
			}
		}
		return true;
	}

	private static int compare(Semver left, Semver right) {
		return left.compareTo(right);
	}
}

