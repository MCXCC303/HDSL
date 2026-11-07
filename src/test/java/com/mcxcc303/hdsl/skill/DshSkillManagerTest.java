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
package com.mcxcc303.hdsl.skill;

import com.mcxcc303.hdsl.core.DshException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The skill packs a DSH home holds, and the one frontmatter line that switches one off.
 */
class DshSkillManagerTest {
	@TempDir
	private Path home;

	private Path bundle(String name, String frontmatter) throws IOException {
		Path directory = DshSkillManager.directory(home).resolve(name);
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("SKILL.md"), frontmatter);
		return directory;
	}

	private Path flat(String name, String frontmatter) throws IOException {
		Path directory = DshSkillManager.directory(home);
		Files.createDirectories(directory);
		Path file = directory.resolve(name + ".md");
		Files.writeString(file, frontmatter);
		return file;
	}

	@Test
	void aHomeWithNoSkillsDirectoryHasNoSkills() throws Exception {
		assertEquals(List.of(), DshSkillManager.list(home));
		assertEquals(home.resolve("skills"), DshSkillManager.directory(home));
	}

	@Test
	void bundlesAndFlatFilesAreBothSkillsAndNothingElseIs() throws Exception {
		bundle("alpha", "---\nname: alpha\ndescription: First\n---\n\n# Alpha\n");
		flat("beta", "---\nname: beta\ndescription: >\n  Second\n  one\n---\n");
		// Neither of these is a skill: a bundle nested below the top level, a file that is
		// not Markdown, and the hidden child the harness skips.
		Path nested = DshSkillManager.directory(home).resolve("gamma").resolve("inner");
		Files.createDirectories(nested);
		Files.writeString(nested.resolve("SKILL.md"), "---\nname: inner\ndescription: x\n---\n");
		Files.writeString(DshSkillManager.directory(home).resolve("notes.txt"), "x");
		Files.createDirectories(DshSkillManager.directory(home).resolve(".system"));

		List<DshSkill> skills = DshSkillManager.list(home);
		assertEquals(List.of("alpha", "beta"), skills.stream().map(DshSkill::name).toList());
		assertEquals("First", skills.get(0).description());
		assertEquals("Second one", skills.get(1).description());
		assertTrue(skills.get(0).bundle());
		assertFalse(skills.get(1).bundle());
		assertTrue(skills.get(0).enabled());
	}

	@Test
	void switchingOffWritesTheKeyAndSwitchingOnTakesItAway() throws Exception {
		Path file = flat("alpha", "---\nname: alpha\ndescription: First\n---\n\nBody\n");
		assertTrue(DshSkillManager.list(home).get(0).enabled());

		DshSkillManager.setEnabled(DshSkillManager.list(home).get(0), false);
		assertEquals("---\nname: alpha\ndescription: First\ndisable-model-invocation: true\n---\n\nBody\n",
		             Files.readString(file));
		assertFalse(DshSkillManager.list(home).get(0).enabled());

		DshSkillManager.setEnabled(DshSkillManager.list(home).get(0), true);
		assertEquals("---\nname: alpha\ndescription: First\n---\n\nBody\n", Files.readString(file));
		assertTrue(DshSkillManager.list(home).get(0).enabled());
	}

	@Test
	void aPackWithNoFrontmatterCannotBeSwitched() throws Exception {
		Path file = flat("bare", "# nothing here\n");
		assertThrows(DshException.class, () -> DshSkillManager.setEnabled(DshSkillManager.list(home).get(0), false));
		assertEquals("# nothing here\n", Files.readString(file));
	}

	@Test
	void installingCopiesAPackAndRefusesToOverwriteOne() throws Exception {
		Path source = Files.createTempDirectory("gamma-pack");
		Files.writeString(source.resolve("SKILL.md"), "---\nname: gamma\ndescription: Third\n---\n");
		DshSkill installed = DshSkillManager.install(home, source);
		assertEquals("gamma", installed.name());
		assertTrue(Files.isRegularFile(DshSkillManager.directory(home)
		                                              .resolve(source.getFileName().toString())
		                                              .resolve("SKILL.md")));
		assertThrows(DshException.class, () -> DshSkillManager.install(home, source));
	}

	@Test
	void aPackWithoutANameOrDescriptionIsRefused() throws Exception {
		Path file = Files.createTempFile("unnamed", ".md");
		Files.writeString(file, "# no frontmatter\n");
		assertThrows(DshException.class, () -> DshSkillManager.install(home, file));
		assertEquals(List.of(), DshSkillManager.list(home));
	}

	@Test
	void removingTakesADirectoryBundleAndAFlatFileAway() throws Exception {
		bundle("alpha", "---\nname: alpha\ndescription: First\n---\n");
		flat("beta", "---\nname: beta\ndescription: Second\n---\n");
		DshSkillManager.remove(DshSkillManager.list(home).get(0));
		DshSkillManager.remove(DshSkillManager.list(home).get(0));
		assertEquals(List.of(), DshSkillManager.list(home));
	}

	@Test
	void anArchiveIsUnpackedAndThePackInsideItIsInstalled() throws Exception {
		Path zip = Files.createTempFile("pack", ".zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			out.putNextEntry(new ZipEntry("alpha/SKILL.md"));
			out.write(("---" + "\n" + "name: alpha" + "\n" + "description: From an archive" + "\n" + "---" +
			           "\n").getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
			out.putNextEntry(new ZipEntry("alpha/notes.txt"));
			out.write("hi".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}

		DshSkill installed = DshSkillManager.install(home, zip);
		assertEquals("alpha", installed.name());
		assertEquals("From an archive", installed.description());
		assertTrue(Files.isRegularFile(DshSkillManager.directory(home).resolve("alpha").resolve("notes.txt")));
	}

	@Test
	void anArchiveWithoutAPackIsRefused() throws Exception {
		Path zip = Files.createTempFile("empty", ".zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			out.putNextEntry(new ZipEntry("readme.txt"));
			out.write("nothing".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		assertThrows(DshException.class, () -> DshSkillManager.install(home, zip));
		assertEquals(List.of(), DshSkillManager.list(home));
	}

	@Test
	void aPackCarriesEverySkillFileAndSkipsTheHarnessOwn() throws Exception {
		Path alpha = bundle("alpha", "---\nname: alpha\ndescription: First\n---\n");
		Files.writeString(alpha.resolve("notes.txt"), "extra");
		Files.createDirectories(alpha.resolve("assets"));
		Files.writeString(alpha.resolve("assets").resolve("icon.png"), "png");
		flat("beta", "---\nname: beta\ndescription: Second\n---\n");
		Path shipped = DshSkillManager.directory(home).resolve(".system").resolve("shipped");
		Files.createDirectories(shipped);
		Files.writeString(shipped.resolve("SKILL.md"), "---\nname: shipped\ndescription: x\n---\n");

		Path pack = Files.createTempFile("skills", ".zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
			DshSkillManager.writeInto(zip, DshSkillManager.packable(home, Set.of("alpha", "beta")), null);
		}

		Set<String> members = new TreeSet<>();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null;
			     entry = zip.getNextEntry()) {
				members.add(entry.getName());
			}
		}
		assertEquals(Set.of("skills/alpha/SKILL.md",
		                    "skills/alpha/notes.txt",
		                    "skills/alpha/assets/icon.png",
		                    "skills/beta.md"),
		             members,
		             "a bundle travels as every file under it and a flat skill as its one .md");

		Path restored = Files.createTempDirectory("skills-restored");
		assertEquals(4, DshSkillManager.restoreInto(pack, restored, null));
		assertTrue(Files.isRegularFile(DshSkillManager.directory(restored)
		                                              .resolve("alpha")
		                                              .resolve("assets")
		                                              .resolve("icon.png")));
		assertTrue(Files.isRegularFile(DshSkillManager.directory(restored).resolve("beta.md")));
		assertFalse(Files.exists(DshSkillManager.directory(restored).resolve(".system")),
		            "the skills the harness ships are not the ones a person installed, so they do not travel");
		Files.deleteIfExists(pack);
	}

	@Test
	void restoringRefusesToWriteOutsideTheSkillsDirectory() throws Exception {
		Path pack = Files.createTempFile("skills", ".zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
			for (String name : List.of("skills/../escaped.md", "skills/.system/shipped/SKILL.md", "skills/good.md")) {
				zip.putNextEntry(new ZipEntry(name));
				zip.write("x".getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}

		Path target = Files.createTempDirectory("skills-restored");
		DshSkillManager.restoreInto(pack, target, null);

		assertTrue(Files.isRegularFile(DshSkillManager.directory(target).resolve("good.md")));
		assertFalse(Files.exists(target.resolve("escaped.md")),
		            "an entry that would land above the skills directory is dropped, not sanitised");
		assertFalse(Files.exists(DshSkillManager.directory(target).resolve(".system")),
		            "an archive cannot put anything into the directory the harness owns");
		Files.deleteIfExists(pack);
	}
}
