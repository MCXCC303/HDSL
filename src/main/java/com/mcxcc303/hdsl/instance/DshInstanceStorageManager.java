/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
 */
package com.mcxcc303.hdsl.instance;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.mcxcc303.hdsl.core.DshException;

/// Manages the filesystem lifecycle of an instance storage boundary.
///
/// This class intentionally builds on [DshInstanceStorageResolver] instead of
/// changing [DshInstance]. Storage lifecycle is a filesystem concern, not part
/// of the persisted instance model.
@NotNullByDefault
public final class DshInstanceStorageManager {
    private DshInstanceStorageManager() {
    }

    public static void initialize(DshInstance instance) throws DshException {
        DshInstanceStorage storage = DshInstanceStorageResolver.resolve(instance);
        try {
            Files.createDirectories(storage.root());
            Files.createDirectories(storage.home());
            Files.createDirectories(storage.logs());
            Files.createDirectories(storage.state());
        } catch (IOException e) {
            throw new DshException("Failed to initialize instance storage: " + storage.root(), e);
        }
    }

    public static DshInstanceStorageHealth check(DshInstance instance) throws DshException {
        DshInstanceStorage storage = DshInstanceStorageResolver.resolve(instance);
        if (!Files.isDirectory(storage.root())) {
            return DshInstanceStorageHealth.MISSING_ROOT;
        }
        if (!Files.isDirectory(storage.home())
                || !Files.isDirectory(storage.logs())
                || !Files.isDirectory(storage.state())) {
            return DshInstanceStorageHealth.INCOMPLETE;
        }
        return DshInstanceStorageHealth.OK;
    }

    public static void delete(DshInstance instance) throws DshException {
        DshInstanceStorage storage = DshInstanceStorageResolver.resolve(instance);
        Path root = storage.root().toAbsolutePath().normalize();
        try {
            if (Files.exists(root)) {
                Files.walk(root)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                throw new StorageDeletionException(e);
                            }
                        });
            }
        } catch (StorageDeletionException e) {
            throw new DshException("Failed to remove instance storage: " + root, e.getCause());
        } catch (IOException e) {
            throw new DshException("Failed to inspect instance storage: " + root, e);
        }
    }

    private static final class StorageDeletionException extends RuntimeException {
        private StorageDeletionException(IOException cause) {
            super(cause);
        }
    }
}
