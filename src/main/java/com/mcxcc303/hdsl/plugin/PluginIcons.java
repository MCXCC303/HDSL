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
package com.mcxcc303.hdsl.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * The picture a plugin package declares for itself.
 *
 * <p>The harness reads a plugin's icon from its manifest, and the rules here are the harness's.
 */
@NotNullByDefault
public final class PluginIcons {

	/** The largest icon file that is read */
	private static final long MAX_ICON_BYTES = 256 * 1024;

	/** The formats an icon may be in */
	private static final Set<String> FORMATS = Set.of("svg", "png", "jpg", "jpeg", "webp");

	/** A scheme at the front of a path, which is an address rather than a file. */
	private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z\\d+.-]*:");

	private PluginIcons() {
	}

	/**
	 * Returns the icon file a package declares.
	 *
	 * @param packageDirectory the package directory
	 * @return the icon file, or {@code null} when the package declares none that can be read
	 */
	public static @Nullable Path iconFile(Path packageDirectory) {
		JsonObject manifest = DshPluginBundle.readManifest(packageDirectory);
		if (manifest == null) {
			return null;
		}

		JsonElement declared = manifest.get("icon");
		if (declared == null || !declared.isJsonPrimitive() || !declared.getAsJsonPrimitive().isString()) {
			return null;
		}

		String icon = declared.getAsString().trim();
		if (icon.isEmpty()) {
			return null;
		}

		Path relative;
		try {
			relative = Path.of(icon);
		} catch (RuntimeException e) {
			LOG.warning("The icon of " + packageDirectory + " is not a path: " + icon);
			return null;
		}
		if (relative.isAbsolute() || SCHEME.matcher(icon).find()) {
			LOG.warning("The icon of " + packageDirectory + " is not a path inside the package: " + icon);
			return null;
		}
		if (!FORMATS.contains(extensionOf(icon))) {
			LOG.warning("The icon of " + packageDirectory + " is not an SVG, PNG, JPEG or WebP: " + icon);
			return null;
		}

		Path root;
		Path file;
		try {
			root = packageDirectory.toRealPath();
			file = root.resolve(relative).normalize();
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not resolve the icon of " + packageDirectory, e);
			return null;
		}
		if (!file.startsWith(root)) {
			LOG.warning("The icon of " + packageDirectory + " points outside the package: " + icon);
			return null;
		}

		try {
			file = file.toRealPath();
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not read the icon of " + packageDirectory + ": " + icon, e);
			return null;
		}
		if (!file.startsWith(root) || !Files.isRegularFile(file)) {
			LOG.warning("The icon of " + packageDirectory + " is not a file inside the package: " + icon);
			return null;
		}
		try {
			if (Files.size(file) > MAX_ICON_BYTES) {
				LOG.warning("The icon of " + packageDirectory + " is larger than "
				            + (MAX_ICON_BYTES / 1024) + " KiB: " + icon);
				return null;
			}
		} catch (IOException e) {
			LOG.warning("Could not read the icon of " + packageDirectory + ": " + icon, e);
			return null;
		}
		return file;
	}

	/**
	 * Returns the extension of a path in lower case, or an empty string when it has none.
	 */
	private static String extensionOf(String path) {
		int dot = path.lastIndexOf('.');
		int slash = path.lastIndexOf('/');
		if (dot < 0 || dot < slash || dot == slash + 1) {
			return "";
		}
		return path.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
