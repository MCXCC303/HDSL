/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
 */
package com.mcxcc303.hdsl.instance;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// The filesystem locations owned by one HDSL instance.
///
/// This is deliberately separate from [DshInstance]. The instance model describes
/// configuration and identity; storage describes where mutable resources live.
/// Keeping this boundary allows future features such as runtime isolation,
/// exporting and lifecycle management to share one resolver without expanding
/// the persisted instance model.
@NotNullByDefault
public record DshInstanceStorage(
        Path root,
        Path home,
        Path logs,
        Path state) {
}
