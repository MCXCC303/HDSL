/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
 */
package com.mcxcc303.hdsl.instance;

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshPaths;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// Resolves the storage boundary of an instance.
///
/// The resolver intentionally does not create directories or mutate state. It
/// only provides a single place where future instance storage layout changes
/// can be made.
@NotNullByDefault
public final class DshInstanceStorageResolver {

    private DshInstanceStorageResolver() {
    }

    /// Resolves the launcher-owned storage for an instance.
    public static DshInstanceStorage resolve(DshInstance instance) throws DshException {
        Path root = DshPaths.instanceDirectory(instance.id()).toAbsolutePath().normalize();
        return new DshInstanceStorage(
                root,
                root.resolve("home"),
                root.resolve("logs"),
                root.resolve("state"));
    }
}
