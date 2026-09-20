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
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;

/// A DeepSeek Harness release published to the npm registry.
///
/// The registry is the only discovery channel: upstream ships no version
/// manager and no release feed, so `npm view` is the authoritative source.
///
/// @param version  the published version string
/// @param distTags the npm dist-tags that point at this version, such as `latest` or `alpha`
@NotNullByDefault
public record DshRelease(String version, @Unmodifiable Set<String> distTags) {
    /// Reports whether this release is only reachable through a non-`latest` tag.
    ///
    /// Upstream publishes every release under a channel tag (`alpha`, `rc`, …)
    /// and only promotes a version to `latest` once it is considered stable.
    ///
    /// @return whether this is a pre-release
    public boolean isPrerelease() {
        return version.contains("-") && !distTags.contains("latest");
    }

    /// Reports whether this release is the current `latest` tag.
    ///
    /// @return whether `latest` points at this version
    public boolean isLatest() {
        return distTags.contains("latest");
    }

    /// Returns the most specific tag for this release, for display.
    ///
    /// @return the tag name, or `null` when the version carries none
    public String primaryTag() {
        if (isLatest()) {
            return "latest";
        }
        return distTags.stream().sorted().findFirst().orElse(null);
    }
}
