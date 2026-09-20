/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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

import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Locale;

/// One of the application surfaces DeepSeek Harness can boot a profile into.
///
/// The surface is inferred from the profile name, because that is how upstream
/// models it: the shipped profile templates are named after the app they boot,
/// and the app plugin adds its own flags once the launcher hands over control.
@NotNullByDefault
public enum DshSurface {
    /// The browser interface. Served over local HTTP until stopped.
    WEB("web", List.of("--no-open", "--port", "0")),

    /// Answers one task and exits. Output is NDJSON when `--json` is passed.
    HEADLESS("headless", List.of()),

    /// Speaks the Agent Client Protocol over stdio.
    ACP("acp", List.of()),

    /// Exposes the programmatic SDK over stdio.
    SDK("sdk", List.of()),

    /// A profile that does not match any shipped surface.
    OTHER("", List.of());

    private final String profileName;
    private final List<String> arguments;

    DshSurface(String profileName, List<String> arguments) {
        this.profileName = profileName;
        this.arguments = arguments;
    }

    /// Returns the profile template this surface boots.
    ///
    /// @return the profile name
    public String profileName() {
        return profileName;
    }

    /// Returns the flags the launcher must add for this surface.
    ///
    /// The browser surface is always started with `--no-open --port 0`: the
    /// launcher opens the browser itself once the readiness line arrives, and
    /// letting the kernel pick the port avoids the hard failure upstream has on
    /// an occupied port (it exits with status 1 instead of probing further).
    ///
    /// @return the extra arguments, never `null`
    public List<String> arguments() {
        return arguments;
    }

    /// Reports whether this surface serves a local HTTP endpoint.
    ///
    /// @return whether a readiness URL line is expected on stdout
    public boolean isWeb() {
        return this == WEB;
    }

    /// Infers the surface from a profile name.
    ///
    /// @param profile the profile name
    /// @return the matching surface, or [DshSurface#OTHER]
    public static DshSurface ofProfile(String profile) {
        String normalized = profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT);
        for (DshSurface surface : values()) {
            if (!surface.profileName.isEmpty() && surface.profileName.equals(normalized)) {
                return surface;
            }
        }
        return OTHER;
    }
}
