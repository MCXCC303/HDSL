/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
 *
 * Adapted from HMCL's packaging logic, which is licensed under the GNU General
 * Public License version 3. See the NOTICE file for the full statement.
 */
package org.jackhuang.hmcl.gradle.pack;

/// Debian packaging metadata for one HDSL release type.
///
/// The package name, installed command, desktop file and alternatives priority
/// are centralised here so [CreateDeb] can stay focused on archive layout
/// instead of duplicating channel-specific branching.
public enum ReleaseType {
    /// The stable channel.
    STABLE("stable", "hdsl", "HDSL", 100),
    /// The beta channel.
    DEVELOPMENT("beta", "hdsl-beta", "HDSL (Beta)", 200),
    /// The nightly channel.
    NIGHTLY("nightly", "hdsl-nightly", "HDSL (Nightly)", 300);

    private final String name;
    private final String packageName;
    private final String displayName;
    private final int alternativesPriority;

    ReleaseType(String name, String packageName, String displayName, int alternativesPriority) {
        this.name = name;
        this.packageName = packageName;
        this.displayName = displayName;
        this.alternativesPriority = alternativesPriority;
    }

    /// Returns the channel suffix used in file and command names.
    ///
    /// @return the channel name
    public String getName() {
        return name;
    }

    /// Returns the Debian package name written into `control`.
    ///
    /// @return the package name
    public String getPackageName() {
        return packageName;
    }

    /// The package name this channel had while the software was called HMCL-DSH.
    ///
    /// It is only ever written into a new package's `Replaces` and `Breaks`, so that a machine
    /// that installed one of those is upgraded to this channel instead of keeping both, each
    /// owning its own command.
    private static final String PREVIOUS_NAME = "hmcl-dsh";

    /// Returns the package name this channel replaces.
    ///
    /// @return the previous package name, channel suffix included
    public String getReplacedPackageName() {
        return "stable".equals(name) ? PREVIOUS_NAME : PREVIOUS_NAME + "-" + name;
    }

    /// Returns the human-readable name used in the desktop entry.
    ///
    /// @return the display name
    public String getDisplayName() {
        return displayName;
    }

    /// Returns the priority used when registering the generic alias.
    ///
    /// @return the alternatives priority
    public int getAlternativesPriority() {
        return alternativesPriority;
    }
}
