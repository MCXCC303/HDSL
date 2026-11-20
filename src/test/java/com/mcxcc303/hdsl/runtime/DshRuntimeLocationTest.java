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
package com.mcxcc303.hdsl.runtime;

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshFiles;
import com.mcxcc303.hdsl.core.DshPaths;
import com.mcxcc303.hdsl.runtime.node.DshNodeRuntime;
import com.mcxcc303.hdsl.runtime.node.NodeRuntime;
import com.mcxcc303.hdsl.runtime.pnpm.DshPnpm;
import com.mcxcc303.hdsl.runtime.pnpm.PnpmRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@NotNullByDefault
public final class DshRuntimeLocationTest {

    @Test
    public void resolvesTheNodeVersionAnOlderReleaseStored() throws Exception {
        Path directory = installNode("24.1.0");
        try {
            DshNodeRuntime runtime = DshToolchain.resolve("24.1.0");

            assertEquals(NodeRuntime.nodeIn(directory), runtime.node());
        } finally {
            DshFiles.deleteTreeQuietly(directory);
        }
    }

    @Test
    public void resolvesThePnpmVersionAnOlderReleaseStored() throws Exception {
        Path prefix = installPnpm("10.9.0");
        String node = DshToolchain.nodeSelection();
        String pnpm = DshToolchain.pnpmSelection();
        try {
            DshToolchain.install(() -> node, () -> "10.9.0");

            DshPnpm resolved = DshToolchain.findPnpm(null);

            assertNotNull(resolved);
            assertEquals(PnpmRuntime.cliIn(prefix), resolved.command());
        } finally {
            DshToolchain.install(() -> node, () -> pnpm);
            DshFiles.deleteTreeQuietly(prefix);
        }
    }

    @Test
    public void usesTheNodeOnPathWhenNothingHasBeenChosen() throws Exception {
        assertNull(DshRuntimeLocation.pathOf(DshNodeRuntime.SYSTEM));
    }

    @Test
    public void fallsBackToTheNodeOnPathWhenTheSelectedRuntimeIsRemoved() throws Exception {
        Path directory = installNode("24.1.0");
        try {
            String selection = DshRuntimeLocation.selectionOf(directory);

            assertEquals(DshRuntimeLocation.SYSTEM,
                         DshRuntimeLocation.afterRemoval(selection, directory, directory));
        } finally {
            DshFiles.deleteTreeQuietly(directory);
        }
    }

    @Test
    public void keepsTheSelectionWhenAnotherRuntimeIsRemoved() throws Exception {
        Path selected = installNode("24.1.0");
        Path removed = installNode("22.19.0");
        try {
            String selection = DshRuntimeLocation.selectionOf(selected);

            assertEquals(selection, DshRuntimeLocation.afterRemoval(selection, selected, removed));
        } finally {
            DshFiles.deleteTreeQuietly(selected);
            DshFiles.deleteTreeQuietly(removed);
        }
    }

    private static Path installNode(String version) throws Exception {
        Path directory = DshPaths.runtimeDirectory(version);
        Path node = NodeRuntime.nodeIn(directory);
        Files.createDirectories(node.getParent());
        Files.createFile(node);
        return directory;
    }

    private static Path installPnpm(String version) throws Exception {
        Path prefix = DshPaths.pnpmDirectory(version);
        Path cli = PnpmRuntime.cliIn(prefix);
        Files.createDirectories(cli.getParent());
        Files.createFile(cli);
        return prefix;
    }
}
