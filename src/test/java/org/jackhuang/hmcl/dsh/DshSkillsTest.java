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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The skill packs a DSH home holds, and the one frontmatter line that switches one off.
class DshSkillsTest {
    @TempDir
    private Path home;

    private Path bundle(String name, String frontmatter) throws IOException {
        Path directory = DshSkills.directory(home).resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), frontmatter);
        return directory;
    }

    private Path flat(String name, String frontmatter) throws IOException {
        Path directory = DshSkills.directory(home);
        Files.createDirectories(directory);
        Path file = directory.resolve(name + ".md");
        Files.writeString(file, frontmatter);
        return file;
    }

    @Test
    void aHomeWithNoSkillsDirectoryHasNoSkills() throws Exception {
        assertEquals(List.of(), DshSkills.list(home));
        assertEquals(home.resolve("skills"), DshSkills.directory(home));
    }

    @Test
    void bundlesAndFlatFilesAreBothSkillsAndNothingElseIs() throws Exception {
        bundle("alpha", "---\nname: alpha\ndescription: First\n---\n\n# Alpha\n");
        flat("beta", "---\nname: beta\ndescription: >\n  Second\n  one\n---\n");
        // Neither of these is a skill: a bundle nested below the top level, a file that is
        // not Markdown, and the hidden child the harness skips.
        Path nested = DshSkills.directory(home).resolve("gamma").resolve("inner");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("SKILL.md"), "---\nname: inner\ndescription: x\n---\n");
        Files.writeString(DshSkills.directory(home).resolve("notes.txt"), "x");
        Files.createDirectories(DshSkills.directory(home).resolve(".system"));

        List<DshSkill> skills = DshSkills.list(home);
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
        assertTrue(DshSkills.list(home).get(0).enabled());

        DshSkills.setEnabled(DshSkills.list(home).get(0), false);
        assertEquals("---\nname: alpha\ndescription: First\ndisable-model-invocation: true\n---\n\nBody\n",
                Files.readString(file));
        assertFalse(DshSkills.list(home).get(0).enabled());

        DshSkills.setEnabled(DshSkills.list(home).get(0), true);
        assertEquals("---\nname: alpha\ndescription: First\n---\n\nBody\n", Files.readString(file));
        assertTrue(DshSkills.list(home).get(0).enabled());
    }

    @Test
    void aPackWithNoFrontmatterCannotBeSwitched() throws Exception {
        Path file = flat("bare", "# nothing here\n");
        assertThrows(DshException.class, () -> DshSkills.setEnabled(DshSkills.list(home).get(0), false));
        assertEquals("# nothing here\n", Files.readString(file));
    }

    @Test
    void installingCopiesAPackAndRefusesToOverwriteOne() throws Exception {
        Path source = Files.createTempDirectory("gamma-pack");
        Files.writeString(source.resolve("SKILL.md"), "---\nname: gamma\ndescription: Third\n---\n");
        DshSkill installed = DshSkills.install(home, source);
        assertEquals("gamma", installed.name());
        assertTrue(Files.isRegularFile(DshSkills.directory(home)
                .resolve(source.getFileName().toString()).resolve("SKILL.md")));
        assertThrows(DshException.class, () -> DshSkills.install(home, source));
    }

    @Test
    void aPackWithoutANameOrDescriptionIsRefused() throws Exception {
        Path file = Files.createTempFile("unnamed", ".md");
        Files.writeString(file, "# no frontmatter\n");
        assertThrows(DshException.class, () -> DshSkills.install(home, file));
        assertEquals(List.of(), DshSkills.list(home));
    }

    @Test
    void removingTakesADirectoryBundleAndAFlatFileAway() throws Exception {
        bundle("alpha", "---\nname: alpha\ndescription: First\n---\n");
        flat("beta", "---\nname: beta\ndescription: Second\n---\n");
        DshSkills.remove(DshSkills.list(home).get(0));
        DshSkills.remove(DshSkills.list(home).get(0));
        assertEquals(List.of(), DshSkills.list(home));
    }
    @Test
    void anArchiveIsUnpackedAndThePackInsideItIsInstalled() throws Exception {
        Path zip = Files.createTempFile("pack", ".zip");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("alpha/SKILL.md"));
            out.write(("---" + "\n" + "name: alpha" + "\n" + "description: From an archive"
                    + "\n" + "---" + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("alpha/notes.txt"));
            out.write("hi".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }

        DshSkill installed = DshSkills.install(home, zip);
        assertEquals("alpha", installed.name());
        assertEquals("From an archive", installed.description());
        assertTrue(Files.isRegularFile(DshSkills.directory(home).resolve("alpha").resolve("notes.txt")));
    }

    @Test
    void anArchiveWithoutAPackIsRefused() throws Exception {
        Path zip = Files.createTempFile("empty", ".zip");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
            out.write("nothing".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertThrows(DshException.class, () -> DshSkills.install(home, zip));
        assertEquals(List.of(), DshSkills.list(home));
    }
}
