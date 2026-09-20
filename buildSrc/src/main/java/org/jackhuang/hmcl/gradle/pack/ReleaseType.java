/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
 *
 * Adapted from HMCL's packaging logic, which is licensed under the GNU General
 * Public License version 3. See the NOTICE file for the full statement.
 */
package org.jackhuang.hmcl.gradle.pack;

/// Debian packaging metadata for one HMCL-DSH release type.
///
/// The package name, installed command, desktop file and alternatives priority
/// are centralised here so [CreateDeb] can stay focused on archive layout
/// instead of duplicating channel-specific branching.
public enum ReleaseType {
    /// The stable channel.
    STABLE("stable", "hmcl-dsh", "HMCL-DSH", 100),
    /// The beta channel.
    DEVELOPMENT("beta", "hmcl-dsh-beta", "HMCL-DSH (Beta)", 200),
    /// The nightly channel.
    NIGHTLY("nightly", "hmcl-dsh-nightly", "HMCL-DSH (Nightly)", 300);

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
