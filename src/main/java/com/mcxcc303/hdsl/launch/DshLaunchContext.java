/*
 * HDSL
 * Copyright (C) 2026 HDSL contributors
 */
package com.mcxcc303.hdsl.launch;

import com.mcxcc303.hdsl.instance.DshInstance;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Immutable launch boundary for one instance.
 *
 * Separates launch description from process execution. Future runtime
 * isolation, multi-instance management and diagnostics can consume the same
 * context without duplicating launch resolution logic.
 */
@NotNullByDefault
public record DshLaunchContext(
        DshInstance instance,
        List<String> command,
        Map<String, String> environment,
        Path workingDirectory,
        Path homeDirectory) {

    public static DshLaunchContext from(DshLauncher.LaunchPlan plan) {
        return new DshLaunchContext(
                plan.instance(),
                plan.command(),
                plan.environment(),
                plan.workingDirectory(),
                plan.homeDirectory());
    }
}
