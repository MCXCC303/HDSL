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
import com.google.gson.JsonParser;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Installs local plugin packages (.tgz) into an instance's local directory and inspects package contents.
 */
@NotNullByDefault
public final class DshLocalPlugins {
	/** Directory within an instance where local plugin archives are stored. */
	public static final String DIRECTORY = "plugins";

	private DshLocalPlugins() {
	}

	/**
	 * Inspects a plugin archive without installing it.
	 *
	 * @param file the archive
	 * @return the package
	 * @throws DshException when the file is not a readable package
	 */
	public static Package inspect(Path file) throws DshException {
		if (!Files.isRegularFile(file)) {
			throw new DshException("There is no file at " + file);
		}
		if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tgz") &&
		    !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tar.gz")) {
			throw new DshException(file.getFileName() + " is not a packed plugin (.tgz)");
		}

		String manifest = readPackedManifest(file);
		if (manifest == null) {
			throw new DshException(file.getFileName() + " holds no package.json, so it is not a package");
		}
		return packageOf(manifest, sizeOf(file), file.getFileName().toString());
	}

	/**
	 * Copies a plugin file into an instance and installs it from there.
	 *
	 * @param instance the instance to install into
	 * @param file     the archive the user has
	 * @param onLine   receives progress lines, or {@code null}
	 * @return what was installed
	 * @throws DshException when the file is not a plugin, or the installation fails
	 */
	public static Result install(DshInstance instance, Path file, @Nullable Consumer<String> onLine)
	throws DshException {
		Package pkg = inspect(file);

		Path directory = instance.instanceDirectory().resolve(DIRECTORY);
		String fileName = safeFileName(pkg.name()) + "-" + safeFileName(pkg.version()) + ".tgz";
		Path target = directory.resolve(fileName);
		try {
			Files.createDirectories(directory);
			if (Files.exists(target) && Files.mismatch(target, file) == -1) {
				report(onLine, "Already holding " + fileName);
			} else {
				report(onLine, "Copying " + file.getFileName() + " into the instance");
				Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new DshException("Failed to copy " + file + " into " + directory, e);
		}

		if (!pkg.isBundle()) {
			report(onLine, "Note: " + pkg.name() + " declares no dsh.bundle, so it will be installed but not active");
		}

		String spec = target.toAbsolutePath().toString();
		report(onLine, "Installing " + pkg.name() + " " + pkg.version() + " from the instance's own copy");
		DshPluginInstaller.installSpecs(instance, List.of(spec), onLine);

		LOG.info("Installed the local plugin " + pkg.name() + " into " + instance.id() + " from " + target);
		return new Result(pkg, target, spec);
	}

	/**
	 * Removes a local plugin file an instance no longer needs.
	 *
	 * @param instance the instance
	 * @param pkg      the package that was installed from it
	 * @return whether a file was removed
	 */
	public static boolean discard(DshInstance instance, Package pkg) {
		Path target;
		try {
			target = instance.instanceDirectory()
			                 .resolve(DIRECTORY)
			                 .resolve(safeFileName(pkg.name()) + "-" + safeFileName(pkg.version()) + ".tgz");
		} catch (DshException e) {
			LOG.warning("Failed to locate the instance directory of " + instance.id(), e);
			return false;
		}
		try {
			return Files.deleteIfExists(target);
		} catch (IOException e) {
			LOG.warning("Failed to remove " + target, e);
			return false;
		}
	}

	/**
	 * Reads {@code package.json} from a gzipped tar archive.
	 *
	 * @param file the archive
	 * @return the manifest's text, or {@code null} when the archive holds none
	 * @throws DshException when the archive cannot be read
	 */
	static @Nullable String readPackedManifest(Path file) throws DshException {
		try (InputStream raw = Files.newInputStream(file);
		     InputStream gzip = new GZIPInputStream(raw)) {
			byte[] header = new byte[512];
			while (true) {
				int read = gzip.readNBytes(header, 0, 512);
				if (read < 512) {
					return null;
				}
				if (isZero(header)) {
					return null;
				}

				String name = string(header, 0, 100);
				long size = octal(header, 124, 12);
				int type = header[156] & 0xff;
				String prefix = string(header, 345, 155);
				String full = prefix.isEmpty() ? name : prefix + "/" + name;

				if (type == '0' || type == 0) {
					if (full.equals("package/package.json") || full.equals("package.json")) {
						return new String(gzip.readNBytes((int) size), StandardCharsets.UTF_8);
					}
				}
				long remaining = size;
				while (remaining > 0) {
					long skipped = gzip.skip(remaining);
					if (skipped <= 0) {
						return null;
					}
					remaining -= skipped;
				}
				long padding = (512 - (size % 512)) % 512;
				while (padding > 0) {
					long skipped = gzip.skip(padding);
					if (skipped <= 0) {
						break;
					}
					padding -= skipped;
				}
			}
		} catch (IOException e) {
			throw new DshException("Failed to read " + file, e);
		}
	}

	/**
	 * Parses package metadata from a manifest string.
	 *
	 * @param body     the manifest's text
	 * @param size     the file's size
	 * @param fileName the file's name, for messages
	 * @return the package
	 * @throws DshException when the manifest names no package
	 */
	static Package packageOf(String body, long size, String fileName) throws DshException {
		JsonObject root;
		try {
			JsonElement parsed = JsonParser.parseString(body);
			if (!parsed.isJsonObject()) {
				throw new DshException(fileName + " holds a package.json that is not an object");
			}
			root = parsed.getAsJsonObject();
		} catch (RuntimeException e) {
			throw new DshException(fileName + " holds a package.json that cannot be read", e);
		}

		String name = asString(root, "name");
		if (name == null) {
			throw new DshException(fileName + " holds a package with no name");
		}

		String patch = null;
		JsonElement dsh = root.get("dsh");
		if (dsh != null && dsh.isJsonObject()) {
			JsonElement bundle = dsh.getAsJsonObject().get("bundle");
			if (bundle != null && bundle.isJsonObject()) {
				patch = asString(bundle.getAsJsonObject(), "patch");
			}
		}

		return new Package(name, asString(root, "version") == null ? "0.0.0" : asString(root, "version"), patch, size);
	}

	private static @Nullable String asString(JsonObject object, String name) {
		JsonElement element = object.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return null;
		}
		String value = element.getAsString();
		return value.isBlank() ? null : value;
	}

	private static String safeFileName(String value) {
		return value.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	/**
	 * Sanitizes a string for safe use in file names.
	 *
	 * @param value the package name or version
	 * @return the sanitised value
	 */
	static String safeName(String value) {
		return safeFileName(value);
	}

	private static boolean isZero(byte[] block) {
		for (byte b : block) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	private static String string(byte[] block, int offset, int length) {
		int end = offset;
		while (end < offset + length && block[end] != 0) {
			end++;
		}
		return new String(block, offset, end - offset, StandardCharsets.UTF_8).trim();
	}

	private static long octal(byte[] block, int offset, int length) {
		String text = string(block, offset, length).trim();
		if (text.isEmpty()) {
			return 0;
		}
		try {
			return Long.parseLong(text, 8);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static long sizeOf(Path file) {
		try {
			return Files.size(file);
		} catch (IOException e) {
			return 0;
		}
	}

	private static void report(@Nullable Consumer<String> onStage, String message) {
		if (onStage != null) {
			onStage.accept(message);
		}
	}

	/**
	 * What a plugin file holds.
	 *
	 * @param name        the package name
	 * @param version     the package version
	 * @param bundlePatch the patch file the package declares, or {@code null}
	 * @param sizeBytes   the file's size
	 */
	public record Package(String name, String version, @Nullable String bundlePatch, long sizeBytes) {
		/**
		 * Returns whether the package would be an active plugin.
		 *
		 * @return whether it declares a bundle patch
		 */
		public boolean isBundle() {
			return bundlePatch != null && !bundlePatch.isBlank();
		}
	}

	/**
	 * What an installation did.
	 *
	 * @param pkg       what the file held
	 * @param installed the file inside the instance
	 * @param spec      the specification the profile recorded
	 */
	public record Result(Package pkg, Path installed, String spec) {
	}
}

