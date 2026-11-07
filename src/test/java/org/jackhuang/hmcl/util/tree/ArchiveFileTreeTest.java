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
package org.jackhuang.hmcl.util.tree;

import org.jackhuang.hmcl.util.io.CompressingUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that archive file trees reject path traversal and entries resolving outside the target directory.
 */
class ArchiveFileTreeTest {

    @Test
    void anEntryThatWouldLeaveTheDirectoryIsNotInTheTree(@TempDir Path directory) throws Exception {
        Map<String, String> entries = escaping();
        entries.put("node-v22/bin/node", "the runtime");
        entries.put("a/./b.txt", "a walk that stays inside");
        Path archive = zip(directory, entries);

        // Collect entry paths by traversing the tree rather than direct lookup.
        List<String> names = new ArrayList<>();
        try (ArchiveFileTree<?, ?> tree = CompressingUtils.openZipTree(archive)) {
            collect(tree.getRoot(), "", names);
        }

        assertTrue(names.contains("node-v22/bin/node"),
                "an entry that stays inside is in the tree for an extractor to write: " + names);
        for (String name : escaping().keySet()) {
            assertFalse(names.contains(name),
                    name + " is a path that leaves the directory being unpacked, so it must not be"
                            + " in the tree for an extractor to write: " + names);
        }
    }

    @Test
    void everyNameInTheTreeIsOneComponentOfAPath(@TempDir Path directory) throws Exception {
        Map<String, String> entries = escaping();
        entries.put("node-v22/bin/node", "the runtime");
        entries.put("a/./b.txt", "a walk that stays inside");
        Path archive = zip(directory, entries);

        List<String> names = new ArrayList<>();
        try (ArchiveFileTree<?, ?> tree = CompressingUtils.openZipTree(archive)) {
            collect(tree.getRoot(), "", names);
        }

        // Verify path components do not escape the target directory or contain Windows separators.
        Path root = Path.of("root").toAbsolutePath();
        assertTrue(names.contains("node-v22/bin/node"), "the walk reads the names: " + names);
        assertTrue(names.contains("a/./b.txt"), "and carries a `.` through as it always has: " + names);
        for (String name : names) {
            assertFalse(name.split("/", -1)[0].isEmpty() || name.equals(".."),
                    name + " is not a name under the directory it was found in");
            for (String component : name.split("/", -1)) {
                assertFalse(component.equals(".."), name + " steps out of the directory being unpacked");
                assertFalse(component.contains("\\"),
                        name + " carries a component a Windows path parser reads as a separator");
            }
            assertTrue(root.resolve(name).normalize().startsWith(root),
                    name + " resolves outside the directory being unpacked");
        }
    }

    private static Map<String, String> escaping() {
        Map<String, String> names = new HashMap<>();
        names.put("..\\..\\evil.bat", "escapes with backslashes");
        names.put("../../evil.txt", "escapes with slashes");
        names.put("a/../../evil.txt", "escapes through a directory that is real");
        return names;
    }

    private static void collect(ArchiveFileTree.Dir<?> dir, String prefix, List<String> names) {
        for (String name : dir.getFiles().keySet()) {
            names.add(prefix + name);
        }
        for (Map.Entry<String, ? extends ArchiveFileTree.Dir<?>> sub : dir.getSubDirs().entrySet()) {
            collect(sub.getValue(), prefix + sub.getKey() + "/", names);
        }
    }

    private static Path zip(Path directory, Map<String, String> entries) throws IOException {
        Path archive = directory.resolve("archive.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }
}
