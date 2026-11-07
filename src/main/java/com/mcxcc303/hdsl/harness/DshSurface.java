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
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Application surfaces DeepSeek Harness can boot a profile into.
 */
@NotNullByDefault
public enum DshSurface {
	WEB("web", "dsh-web-app", List.of("--no-open")),
	HEADLESS("headless", "dsh-headless", List.of()),
	ACP("acp", "dsh-acp-app", List.of()),
	SDK("sdk", "dsh-sdk-app", List.of()),
	OTHER("", "", List.of());

	private final String profileName;
	private final String appBundle;
	private final List<String> arguments;

	DshSurface(String profileName, String appBundle, List<String> arguments) {
		this.profileName = profileName;
		this.appBundle = appBundle;
		this.arguments = arguments;
	}

	/**
	 * Infers the surface from a profile name.
	 *
	 * @param profile the profile name
	 * @return the matching surface, or {@link #OTHER}
	 */
	public static DshSurface ofProfile(String profile) {
		String normalized = profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT);
		for (DshSurface surface : values()) {
			if (!surface.profileName.isEmpty() && surface.profileName.equals(normalized)) {
				return surface;
			}
		}
		return OTHER;
	}

	/**
	 * Infers the surface from the bundles layered by a profile.
	 *
	 * @param bundles the bundle package names, in load order
	 * @return the matching surface, or {@link #OTHER} when none match
	 */
	public static DshSurface ofBundles(@Nullable List<String> bundles) {
		if (bundles == null) {
			return OTHER;
		}
		for (String bundle : bundles) {
			if (bundle == null) {
				continue;
			}
			String name = bundle.substring(bundle.lastIndexOf('/') + 1).trim().toLowerCase(Locale.ROOT);
			for (DshSurface surface : values()) {
				if (!surface.appBundle.isEmpty() && surface.appBundle.equals(name)) {
					return surface;
				}
			}
		}
		return OTHER;
	}

	/**
	 * Infers the surface of an instance's profile.
	 *
	 * @param instance the instance
	 * @return the inferred surface
	 */
	public static DshSurface of(DshInstance instance) {
		try {
			DshSurface byBundles = ofBundles(DshPluginInstaller.readBundles(instance.homeDirectory(),
			                                                                instance.profile()));
			if (byBundles != OTHER) {
				return byBundles;
			}
		} catch (DshException | RuntimeException e) {
			// A profile that cannot be read is not a reason to refuse to start it.
			Logger.LOG.info(
					"Could not read the bundles of profile " + instance.profile() + "; falling back to its name: " +
					e.getMessage());
		}
		return ofProfile(instance.profile());
	}

	/**
	 * Returns the bundle whose presence means a profile boots this surface.
	 *
	 * @return the package name
	 */
	public String appBundle() {
		return appBundle;
	}

	/**
	 * Returns the profile template this surface boots.
	 *
	 * @return the profile name
	 */
	public String profileName() {
		return profileName;
	}

	/**
	 * Returns the command line arguments required for this surface.
	 *
	 * @return the extra arguments
	 */
	public List<String> arguments() {
		return arguments;
	}

	/**
	 * Returns the flags for this surface on a specific port.
	 *
	 * @param port the port to bind, or {@code 0} to let the kernel choose
	 * @return the extra arguments
	 */
	public List<String> arguments(int port) {
		if (this != WEB) {
			return arguments;
		}
		List<String> result = new ArrayList<>(arguments);
		result.add("--port");
		result.add(Integer.toString(Math.max(port, 0)));
		return List.copyOf(result);
	}

	/**
	 * Reports whether this surface serves a local HTTP endpoint.
	 *
	 * @return whether a readiness URL line is expected on standard output
	 */
	public boolean isWeb() {
		return this == WEB;
	}
}
