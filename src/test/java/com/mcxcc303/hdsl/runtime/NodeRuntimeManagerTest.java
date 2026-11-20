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
import com.mcxcc303.hdsl.runtime.node.NodeRuntime;
import com.mcxcc303.hdsl.runtime.node.NodeRuntimeManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
public final class NodeRuntimeManagerTest {
    @Test
    public void refusesToRemoveARuntimeOutsideTheLauncherDirectory(@TempDir Path directory) throws Exception {
        Path node = NodeRuntime.nodeIn(directory);
        Files.createDirectories(node.getParent());
        Files.createFile(node);

        assertThrows(DshException.class, () -> NodeRuntimeManager.uninstall(directory));
        assertTrue(Files.isRegularFile(node));
    }
}
