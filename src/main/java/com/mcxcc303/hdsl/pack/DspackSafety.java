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
package com.mcxcc303.hdsl.pack;

import com.mcxcc303.hdsl.core.DshException;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Security checks and path traversal validations for unpacking {@code .dspack} archives.
 */
@NotNullByDefault
public final class DspackSafety {
	private static final List<String> DENY_NAMES = List.of(".env",
	                                                       ".netrc",
	                                                       ".pypirc",
	                                                       ".npmrc",
	                                                       ".yarnrc",
	                                                       ".yarnrc.yml",
	                                                       "credentials.yaml",
	                                                       ".credentials.yaml",
	                                                       "id_rsa",
	                                                       "id_ed25519",
	                                                       "id_ecdsa",
	                                                       "id_ed448");

	private static final List<String> DENY_EXTENSIONS = List.of(".key", ".pem", ".p12", ".pfx", ".jks", ".keystore");

	private DspackSafety() {
	}

	/**
	 * Returns where a member lands, refusing paths that escape the root.
	 *
	 * @param root the directory the member must stay inside
	 * @param name the member relative path
	 * @return resolved destination path
	 * @throws DshException when the path escapes the root directory
	 */
	static Path resolve(Path root, String name) throws DshException {
		Path base = root.toAbsolutePath().normalize();
		Path target = base.resolve(name).normalize();
		if (!target.startsWith(base)) {
			throw new DshException(
					"That pack tries to write outside the folder it is installing into (" + name + ")", null);
		}
		return target;
	}

	/**
	 * Refuses extraction if the target represents sensitive files, keys, or nested archives.
	 *
	 * @param name   archive entry name
	 * @param target destination path
	 * @throws DshException when the file is prohibited
	 */
	static void refuseIfUnsafe(String name, Path target) throws DshException {
		String file = target.getFileName().toString();
		String lower = file.toLowerCase(Locale.ROOT);
		if (DENY_NAMES.contains(lower) || lower.startsWith("id_rsa") || lower.startsWith("id_ed25519") ||
		    lower.startsWith("id_ecdsa") || lower.startsWith("id_ed448")) {
			throw new DshException(
					"That pack tries to write " + file + ", which holds credentials and is never installed from a pack",
					null);
		}
		for (String extension : DENY_EXTENSIONS) {
			if (lower.endsWith(extension)) {
				throw new DshException(
						"That pack tries to write " + file + ", which is a key file and is never installed from a pack",
						null);
			}
		}
		for (String extension : List.of(".zip", ".tar", ".tgz", ".gz", ".bz2", ".xz", ".7z", ".rar")) {
			if (lower.endsWith(extension) && !name.startsWith("overrides/plugins/")) {
				throw new DshException(
						"That pack holds a nested archive (" + name + "), which is never installed from a pack", null);
			}
		}
	}

	/**
	 * Checks whether a file name is a root machine configuration file.
	 *
	 * @param name entry name
	 * @return true if the entry is a recognized machine configuration file
	 */
	static boolean isMachineFile(String name) {
		return name.equals("package.json") || name.equals("pnpm-lock.yaml") || name.equals("pnpm-workspace.yaml");
	}
}
