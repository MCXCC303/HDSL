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
package org.jackhuang.hmcl.dsh;

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the two ways removing a tree the launcher owns used to fail.
///
/// Both were measured before they were written down: the transplanted
/// `FileUtils#deleteDirectory` leaves the whole tree behind when it meets a
/// read-only entry, and it deletes what is on the *other* side of a junction
/// because every Java API calls a junction a directory. An instance folder is a
/// package manager's tree, so it has both.
class DshFilesTest {
    /// Builds a small tree shaped like the inside of an instance folder.
    ///
    /// @param root the folder to build in
    /// @return the tree's root, which is named `instance`
    private static Path instanceTree(Path root) throws IOException {
        Path instance = root.resolve("instance");
        Path packageDirectory = instance.resolve("dsh/node_modules/pkg");
        Files.createDirectories(packageDirectory);
        for (int i = 0; i < 5; i++) {
            Files.writeString(packageDirectory.resolve("file" + i + ".js"), "// x");
        }
        Files.writeString(instance.resolve("instance.json"), "{}");
        return instance;
    }

    /// Marks an entry read-only the way a package manager does on Windows.
    ///
    /// The DOS attribute is a Windows filesystem feature. Elsewhere the JDK has no such view at all
    /// and `Files#setAttribute` throws `UnsupportedOperationException: View 'dos' not available`
    /// instead of quietly doing nothing — which is how three tests of this class failed on the macOS
    /// runners. What is asked here is the *file store*, not the operating system's name: the same
    /// rule the rest of this suite follows, and it skips exactly where the attribute is not there
    /// while still running where it is.
    ///
    /// @param path the entry to mark
    private static void markReadOnly(Path path) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileStore(path).supportsFileAttributeView("dos"),
                "this file store has no DOS attribute view to mark an entry read-only with");
        Files.setAttribute(path, "dos:readonly", true);
    }

    @Test
    void aTreeWithAReadOnlyFileIsRemoved(@TempDir Path root) throws Exception {
        Path instance = instanceTree(root);
        Path readOnly = instance.resolve("dsh/node_modules/pkg/file0.js");
        markReadOnly(readOnly);

        DshFiles.deleteTree(instance);

        assertFalse(Files.exists(instance),
                "a read-only file must not leave the whole instance behind");
    }

    @Test
    void aTreeWithAReadOnlyDirectoryIsRemoved(@TempDir Path root) throws Exception {
        Path instance = instanceTree(root);
        markReadOnly(instance.resolve("dsh/node_modules/pkg"));

        DshFiles.deleteTree(instance);

        assertFalse(Files.exists(instance), "a read-only directory must not stop the removal");
    }

    /// The junction is made with `mklink /J`, which is what a package manager
    /// uses on Windows where a symbolic link needs a privilege it does not have.
    ///
    /// Jumped over rather than entered: entering it deletes files the launcher
    /// never listed, which no permission check would catch.
    @Test
    void aJunctionInsideTheTreeIsNotFollowed(@TempDir Path root) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
                "junctions are a Windows feature");

        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "somebody else's file");

        Path instance = instanceTree(root);
        Path junction = instance.resolve("link");
        Process link = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                junction.toString(), outside.toString()).redirectErrorStream(true).start();
        try (var reader = link.inputReader()) {
            while (reader.readLine() != null) {
                // Drain, so the process is not left blocked on a full pipe.
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(link.waitFor() == 0,
                "this machine will not make a junction");

        DshFiles.deleteTree(instance);

        assertFalse(Files.exists(instance), "the instance has to be gone");
        assertTrue(Files.exists(outside.resolve("keep.txt")),
                "what the junction pointed at is not ours to delete");
    }

    @Test
    void aPathThatIsNotThereIsNotAFailure(@TempDir Path root) throws Exception {
        DshFiles.deleteTree(root.resolve("nothing-here"));
        assertTrue(DshFiles.deleteTreeQuietly(root.resolve("nothing-here")));
    }

    @Test
    void aSingleFileIsRemovedToo(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("archive.zip"), "x");
        DshFiles.deleteTree(file);
        assertFalse(Files.exists(file));
    }

    @Test
    void whatIsLeftIsNestedAsDeeplyAsAPackageTree(@TempDir Path root) throws Exception {
        Path instance = root.resolve("instance");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            deep.append("/node_modules/.pnpm/@deepseek-ai+dsh-client-ui-sidebar-documentpreview@0.1.7-rc.2/node_modules/@deepseek-ai");
        }
        Path leaf = Path.of(instance + deep.toString());
        Files.createDirectories(leaf);
        Files.writeString(leaf.resolve("index.js"), "// x");
        assertTrue(leaf.toString().length() > 260, "the tree has to be past the legacy limit");

        DshFiles.deleteTree(instance);

        assertFalse(Files.exists(instance), "a deep tree is a package manager's normal tree");
    }

    /// The names the test above relies on are the ones an instance really has.
    @Test
    void anInstanceFolderIsRemovedWhole(@TempDir Path root) throws Exception {
        DshInstance instance = DshInstanceManager.create("dsh-files-test", "1.0.0",
                DshInstance.DEFAULT_PROFILE, root, DshHomeMode.ISOLATED, null,
                List.of(), java.util.Map.of());
        try {
            Path directory = instance.instanceDirectory();
            Files.createDirectories(directory.resolve("dsh/node_modules/pkg"));
            Files.writeString(directory.resolve("dsh/node_modules/pkg/index.js"), "// x");
            markReadOnly(directory.resolve("dsh/node_modules/pkg/index.js"));

            DshInstanceManager.delete(instance.id());

            assertFalse(Files.exists(directory), "the instance has to be gone, home and all");
            assertFalse(DshInstanceManager.exists(instance.id()));
        } finally {
            // This instance lives in the folder the whole suite shares, so leaving it behind changes
            // what every test after this one sees there. That is not hypothetical: on the macOS
            // runners the `dos` attribute above failed, the instance stayed, and the next test to look
            // at that folder — [org.jackhuang.hmcl.setting.DshInstancesWiringTest], which waits for an
            // empty folder to select nothing — failed fifteen seconds later for a reason of its own.
            if (DshInstanceManager.exists(instance.id())) {
                try {
                    DshInstanceManager.delete(instance.id());
                } catch (DshException ignored) {
                    // The test's own cleanup: a failure here would hide the real one.
                }
            }
        }
    }
}
