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

/// The rules the packages under `com.mcxcc303.hdsl` keep to.
///
/// The tree is arranged by subject, and that arrangement is what lets the command line and the
/// tests run without a display, and what keeps the transplanted interface layer on one side of the
/// line.  A rule nobody checks lasts until the next hurried change, so the rules are checked here,
/// against the sources themselves — the way [org.jackhuang.hmcl.ui.dsh.PageStringsTest] checks the
/// keys the interface asks for.
class PackageRulesTest {
	/// The root of the domain layer.
	private static final Path ROOT = Path.of("src/main/java/com/mcxcc303/hdsl");

	/// The package this one was moved out of: nothing may still name it.
	private static final String OLD_NAME = "org.jackhuang.hmcl.dsh";

	/// The interface layer and the toolkit behind it, which nothing here may use.
	private static final List<String> INTERFACE = List.of("javafx.", "org.jackhuang.hmcl.ui.");

	/// The files that still use the toolkit, and the reason each one is still allowed to.
	///
	/// The split left five of them, all of them presentation wearing a domain name: the two
	/// instance icons draw themselves, the three skin classes decode pictures, and the process
	/// manager opens the crash dialog.  **This list may only shrink.**  What each needs is the same
	/// shape of change: the data stays here — which icon an instance uses, which skin an account
	/// chose, what a process wrote — and the drawing moves to the interface, which is reached
	/// through a listener rather than a call.
	private static final List<String> INTERFACE_EXCEPTIONS = List.of("skin/DefaultSkin.java",
	                                                                 "skin/DshSkin.java",
	                                                                 "skin/NormalizedSkin.java",
	                                                                 "instance/DshInstanceIcon.java",
	                                                                 "instance/DshInstanceIcons.java",
	                                                                 "launch/DshProcess.java",
	                                                                 "launch/DshProcessManager.java");

	/// What the split had to widen from package-private to public, so that the members are known.
	///
	/// Each is a helper two packages now share: the catalogue's npm registry and cached file, the
	/// version manager's comparison, the session log-name test, a workspace title, a profile
	/// patch's entry writer, and the two pack dependency helpers.  They are the price of the
	/// arrangement, and the list of what to give a better home next.

	/// The packages that reach for each other, which is the thing the arrangement is meant to avoid.
	///
	/// A circle is what turns a tree of subjects back into a heap: each pair below is two packages
	/// that name each other, and every one of them is a package reaching for something that should
	/// have been handed to it. **This list may only shrink.**
	///
	/// It replaced a list of the forty-four edges the packages were allowed to have. That list could
	/// say an edge was deliberate, and it could not say which of them were wrong: the wiring was
	/// declared and the design was not checked, and every new package paid for it with an edit.
	private static final Set<String> KNOWN_CIRCLES = Set.of("harness<->instance",
	                                                        "harness<->plugin",
	                                                        "instance<->launch",
	                                                        "instance<->pack",
	                                                        "instance<->plugin");

	/// What an import of this domain looks like, the name captured without the prefix.
	private static final Pattern IMPORTED = Pattern.compile("^import (?:static )?com\\.mcxcc303\\.hdsl\\.([\\w.]+);",
	                                                        Pattern.MULTILINE);

	/// The files of the domain layer.
	///
	/// @return every source file under [#ROOT]
	/// @throws IOException when the tree cannot be walked
	private static List<Path> sources() throws IOException {
		try (var walk = Files.walk(ROOT)) {
			return walk.filter(path -> path.toString().endsWith(".java")).toList();
		}
	}

	/// Reads a source file.
	///
	/// @param file the file
	/// @return its text
	/// @throws IOException when it cannot be read
	private static String read(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}

	/// Returns the package a source file declares.
	///
	/// @param file the file
	/// @return the package, or an empty string when it declares none
	/// @throws IOException when the file cannot be read
	private static String declaredPackage(Path file) throws IOException {
		Matcher matcher = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE).matcher(read(file));
		return matcher.find() ? matcher.group(1) : "";
	}

	/// Returns the package a file's path says it is in.
	///
	/// @param file the file
	/// @return the package name the directory implies
	private static String packageFromPath(Path file) {
		Path parent = ROOT.relativize(file).getParent();
		String relative = parent == null ? "" : parent.toString().replace(File.separatorChar, '.');
		return relative.isEmpty() ? "com.mcxcc303.hdsl" : "com.mcxcc303.hdsl." + relative;
	}

	/// Returns the packages this tree actually has, longest name first.
	///
	/// The longest match is what tells a package apart from a nested type: an import of
	/// `...plugin.Catalog.Plugin` names the package `...plugin` and a type inside `Catalog`, and
	/// taking the text before the last dot for a package would invent one that does not exist.
	///
	/// @return the package names, longest first
	/// @throws IOException when the tree cannot be walked
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

	/// Returns every dependency between these packages, as \`a->b\`.
	///
	/// @return the edges
	/// @throws IOException when the tree cannot be walked
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

	/// Returns the pairs that name each other, as \`a<->b\`.
	///
	/// @return the pairs
	/// @throws IOException when the tree cannot be walked
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

	/// Returns a package name without the prefix everything here shares.
	///
	/// @param name the package
	/// @return the short name
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
	void nothingStillNamesThePackageTheDomainWasMovedFrom() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path file : sources()) {
			if (read(file).contains(OLD_NAME)) {
				offenders.add(ROOT.relativize(file).toString());
			}
		}
		assertTrue(offenders.isEmpty(), "the old package name is still named by: " + offenders);
	}

	@Test
	void everyPackageSaysWhatItIsAbout() throws IOException {
		List<String> missing = new ArrayList<>();
		List<Path> dirs = new ArrayList<>();
		for (Path file : sources()) {
			Path parent = file.getParent();
			if (parent != null && !dirs.contains(parent)) {
				dirs.add(parent);
			}
		}
		for (Path dir : dirs) {
			if (!Files.isRegularFile(dir.resolve("package-info.java"))) {
				missing.add(ROOT.relativize(dir).toString());
			}
		}
		assertTrue(missing.isEmpty(), "these packages do not say what they are about: " + missing);
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
		// Two things the arrangement says that a circle cannot: the command line is what everything
		// is arranged for and nothing may name it, and a leaf package is one that only names the
		// bottom.
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
