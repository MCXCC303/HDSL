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
package com.mcxcc303.hdsl;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architectural dependency rules for {@code com.mcxcc303.hdsl}.
 */
class PackageRulesTest {
	private static final Path ROOT = Path.of("src/main/java/com/mcxcc303/hdsl");

	private static final List<String> INTERFACE = List.of("javafx.", "org.jackhuang.hmcl.ui.");

	private static final List<String> INTERFACE_EXCEPTIONS = List.of(
			"skin/DefaultSkin.java",
			"skin/DshSkin.java",
			"skin/NormalizedSkin.java",
			"instance/DshInstanceIcon.java",
			"instance/DshInstanceIcons.java",
			"launch/DshProcess.java"
	);

	private static final Set<String> KNOWN_CIRCLES = Set.of(
			"harness<->instance",
			"harness<->plugin",
			"instance<->launch",
			"instance<->pack",
			"instance<->plugin"
	);

	private static final Pattern IMPORTED = Pattern.compile(
			"^import (?:static )?com\\.mcxcc303\\.hdsl\\.([\\w.*]+);",
			Pattern.MULTILINE
	);

	private static List<Path> sources() throws IOException {
		try (var walk = Files.walk(ROOT)) {
			return walk.filter(path -> path.toString().endsWith(".java")).toList();
		}
	}

	private static String read(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}

	private static String declaredPackage(Path file) throws IOException {
		Matcher matcher = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE).matcher(read(file));
		return matcher.find() ? matcher.group(1) : "";
	}

	private static String packageFromPath(Path file) {
		Path parent = ROOT.relativize(file).getParent();
		String relative = parent == null ? "" : parent.toString().replace(File.separatorChar, '.');
		return relative.isEmpty() ? "com.mcxcc303.hdsl" : "com.mcxcc303.hdsl." + relative;
	}

	private static List<String> knownPackages() throws IOException {
		List<String> packages = new ArrayList<>();
		for (Path file : sources()) {
			String name = declaredPackage(file);
			if (!name.isEmpty() && !packages.contains(name)) {
				packages.add(name);
			}
		}
		packages.sort((left, right) -> Integer.compare(right.length(), left.length()));
		return packages;
	}

	private static Set<String> edges() throws IOException {
		List<String> packages = knownPackages();
		Set<String> edges = new TreeSet<>();
		for (Path file : sources()) {
			String mine = declaredPackage(file);
			for (String line : read(file).split("\\R")) {
				Matcher matcher = IMPORTED.matcher(line);
				if (!matcher.find()) {
					continue;
				}
				String named = "com.mcxcc303.hdsl." + matcher.group(1);
				String target = null;
				for (String candidate : packages) {
					if (named.equals(candidate) || named.startsWith(candidate + ".")) {
						target = candidate;
						break;
					}
				}
				if (target != null && !target.equals(mine)) {
					edges.add(withoutPrefix(mine) + "->" + withoutPrefix(target));
				}
			}
		}
		return edges;
	}

	private static Set<String> circles() throws IOException {
		Set<String> edges = edges();
		Set<String> circles = new TreeSet<>();
		for (String edge : edges) {
			String[] halves = edge.split("->");
			if (edges.contains(halves[1] + "->" + halves[0])) {
				circles.add(halves[0].compareTo(halves[1]) < 0
				            ? halves[0] + "<->" + halves[1]
				            : halves[1] + "<->" + halves[0]);
			}
		}
		return circles;
	}

	private static String withoutPrefix(String name) {
		return name.replace("com.mcxcc303.hdsl.", "");
	}

	@Test
	void theDomainNeverUsesTheInterfaceToolkit() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path file : sources()) {
			String source = read(file);
			String relative = ROOT.relativize(file).toString().replace(File.separatorChar, '/');
			if (INTERFACE_EXCEPTIONS.contains(relative)) {
				continue;
			}
			for (String forbidden : INTERFACE) {
				if (source.contains(forbidden)) {
					offenders.add(ROOT.relativize(file) + " uses " + forbidden);
				}
			}
		}
		assertTrue(offenders.isEmpty(), "the domain layer has to stay usable without a toolkit: " + offenders);
	}

	@Test
	void everyFileIsInThePackageItsPathSays() throws IOException {
		for (Path file : sources()) {
			assertEquals(packageFromPath(file),
			             declaredPackage(file),
			             ROOT.relativize(file) + " declares a package its path does not");
		}
	}

	@Test
	void thePackagesDoNotReachForEachOtherInACircle() throws IOException {
		assertEquals(KNOWN_CIRCLES,
		             circles(),
		             "a new circle is two packages reaching for each other rather than one handing the" +
		             " other what it needs: " + circles());
	}

	@Test
	void theCommandLineIsOnTopAndTheLeavesAreLeaves() throws IOException {
		Set<String> edges = edges();
		assertTrue(edges.stream().noneMatch(edge -> edge.endsWith("->cli")),
		           "the command line is on top of the tree, and nothing below it may name it: " + edges);
		assertTrue(edges.stream().noneMatch(edge -> edge.startsWith("core->")),
		           "core is the foundation and must not depend on other packages: " + edges);
		for (String leaf : List.of("runtime", "skill", "skin")) {
			List<String> out = edges.stream()
			                        .filter(edge -> edge.startsWith(leaf + "->"))
			                        .filter(edge -> !edge.equals(leaf + "->core"))
			                        .toList();
			assertTrue(out.isEmpty(), leaf + " is a leaf, so it names only core: " + out);
		}
	}

	@Test
	void coreDoesNotDependOnSettingsOrInterface() throws IOException {
		List<String> offenders = new ArrayList<>();
		Path coreDir = ROOT.resolve("core");
		for (Path file : sources()) {
			if (file.startsWith(coreDir)) {
				String source = read(file);
				for (String forbidden : List.of("org.jackhuang.hmcl.setting.", "org.jackhuang.hmcl.ui.")) {
					if (source.contains(forbidden)) {
						offenders.add(ROOT.relativize(file) + " uses " + forbidden);
					}
				}
			}
		}
		assertTrue(offenders.isEmpty(), "core must not import settings or interface types: " + offenders);
	}
}
