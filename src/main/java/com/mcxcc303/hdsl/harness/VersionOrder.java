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
package com.mcxcc303.hdsl.harness;

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.Semver;
import com.mcxcc303.hdsl.runtime.DshNodeRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Version ordering and lockstep package compatibility resolution.
 */
@NotNullByDefault
public final class VersionOrder {
	/**
	 * The package the web application depends on with its own version line.
	 */
	public static final String LOCKSTEP_PACKAGE = "@deepseek-ai/dsh-client-ui-sidebar-documentpreview";

	private VersionOrder() {
	}

	/**
	 * Reports whether a harness version predates the lockstep package entirely.
	 *
	 * @param version the harness version
	 * @param oldest  the oldest published version of the lockstep package, or {@code null}
	 * @return whether the harness version is older than the package
	 */
	public static boolean predatesLockstep(String version, @Nullable String oldest) {
		return oldest != null && compareVersions(version, oldest) < 0;
	}

	/**
	 * Returns the published versions of the package the harness is published with.
	 *
	 * @return the versions, or an empty set when the registry cannot be reached
	 */
	public static Set<String> lockstepVersions() {
		return lockstepVersionsAndOldest().versions();
	}

	/**
	 * Reads the lockstep package's versions and its oldest one.
	 *
	 * @return what the registry holds, or empty when unreachable
	 */
	public static Lockstep lockstepVersionsAndOldest() {
		try {
			DshNodeRuntime runtime = HarnessInstaller.requireRuntime();
			if (!runtime.canInstall()) {
				return new Lockstep(Set.of(), null);
			}
			Collection<String> versions = ReleaseQueries.queryVersions(runtime.npm(), LOCKSTEP_PACKAGE);
			String oldest = null;
			for (String version : versions) {
				if (oldest == null || compareVersions(version, oldest) < 0) {
					oldest = version;
				}
			}
			return new Lockstep(Set.copyOf(versions), oldest);
		} catch (DshException | RuntimeException e) {
			LOG.warning("Could not read the versions of " + LOCKSTEP_PACKAGE, e);
			return new Lockstep(Set.of(), null);
		}
	}

	/**
	 * Compares two version strings with semantic-version precedence.
	 *
	 * @param left  the first version
	 * @param right the second version
	 * @return negative, zero or positive as {@code left} sorts before, equal to or after {@code right}
	 */
	public static int compareVersions(String left, String right) {
		return Semver.compare(left, right);
	}

	/**
	 * The published versions of the lockstep package and its oldest release.
	 *
	 * @param versions the published versions
	 * @param oldest   the oldest published version, or {@code null}
	 */
	public record Lockstep(Set<String> versions, @Nullable String oldest) {
	}
}
