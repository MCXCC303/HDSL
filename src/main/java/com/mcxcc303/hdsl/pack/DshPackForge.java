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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.harness.DshPackageRegistry;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.plugin.DshPluginBundle;
import com.mcxcc303.hdsl.plugin.DshPluginInstaller;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Exports an instance profile as a community-standard {@code .dspack} container archive.
 * <p>
 * Scans profile files, filters sensitive data and artifacts, writes a v5 manifest,
 * and packages files into a container with root configuration and {@code overrides/}.
 */
@NotNullByDefault
public final class DshPackForge {
	public static final String MARKER = "dspack.json";
	public static final int CONTAINER_VERSION = 3;
	public static final String MANIFEST = "manifest.json";
	public static final String OVERRIDES = "overrides/";
	public static final int MANIFEST_VERSION = 5;

	private static final List<String> DENY_NAMES = List.of("node_modules",
	                                                       "dist",
	                                                       "build",
	                                                       "coverage",
	                                                       ".cache",
	                                                       "cordis.yml",
	                                                       "manifest.json",
	                                                       "package-lock.json",
	                                                       "yarn.lock",
	                                                       ".env",
	                                                       ".npmrc",
	                                                       ".credentials.yaml",
	                                                       ".anonymous-user-id",
	                                                       "settings.yaml",
	                                                       ".dshpkcfg");

	private static final List<String> DENY_EXTENSIONS = List.of(".key", ".pem", ".p12", ".pfx", ".crt", ".der", ".asc");

	private static final List<Pattern> DENY_FILE_NAMES = List.of(Pattern.compile("^\\.env.*"),
	                                                             Pattern.compile("^credentials.*\\.ya?ml$",
	                                                                             Pattern.CASE_INSENSITIVE),
	                                                             Pattern.compile(".*\\.credentials$",
	                                                                             Pattern.CASE_INSENSITIVE),
	                                                             Pattern.compile("^id_rsa.*"),
	                                                             Pattern.compile("^secrets.*\\.(json|ya?ml)$",
	                                                                             Pattern.CASE_INSENSITIVE),
	                                                             Pattern.compile(".*token.*", Pattern.CASE_INSENSITIVE),
	                                                             Pattern.compile(".*api[_-]?key.*",
	                                                                             Pattern.CASE_INSENSITIVE));

	private static final Pattern DENY_ARCHIVE = Pattern.compile(".*\\.(tgz|tar\\.gz|zip|dspack)$",
	                                                            Pattern.CASE_INSENSITIVE);

	private static final List<String> DENY_PREFIXES = List.of("attachments/",
	                                                          "profiles/web/",
	                                                          "profiles/headless/",
	                                                          "skills/.system/");

	private DshPackForge() {
	}

	/**
	 * Scans a profile directory for files eligible to be included in a pack.
	 *
	 * @param profile the profile directory
	 * @return scan results containing included and excluded files
	 * @throws DshException when the directory cannot be read
	 */
	public static Scan scan(Path profile) throws DshException {
		List<Entry> files = new ArrayList<>();
		Map<String, String> excluded = new LinkedHashMap<>();

		try {
			Files.walkFileTree(profile, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
					if (directory.equals(profile)) {
						return FileVisitResult.CONTINUE;
					}
					if (Files.isSymbolicLink(directory)) {
						excluded.put(relative(profile, directory), "symlink");
						return FileVisitResult.SKIP_SUBTREE;
					}
					String rel = relative(profile, directory) + "/";
					String reason = exclusionOf(rel);
					if (reason != null) {
						excluded.put(rel, reason);
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
					String rel = relative(profile, file);
					if (Files.isSymbolicLink(file)) {
						excluded.put(rel, "symlink");
						return FileVisitResult.CONTINUE;
					}
					String reason = exclusionOf(rel);
					if (reason != null) {
						excluded.put(rel, reason);
						return FileVisitResult.CONTINUE;
					}
					files.add(new Entry(rel, attributes.size()));
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException e) {
					excluded.put(relative(profile, file), "unreadable");
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			throw new DshException("Failed to scan " + profile, e);
		}

		files.sort((left, right) -> left.relative().compareTo(right.relative()));
		return new Scan(List.copyOf(files), Map.copyOf(excluded));
	}

	/**
	 * Determines the exclusion reason for a path, or null if eligible for export.
	 *
	 * @param relative profile-relative path
	 * @return exclusion reason code, or null if included
	 */
	static @Nullable String exclusionOf(String relative) {
		String path = relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
		String[] segments = path.split("/");
		for (String segment : segments) {
			if (DENY_NAMES.contains(segment)) {
				return "name";
			}
		}

		String name = segments.length == 0 ? path : segments[segments.length - 1];
		for (String extension : DENY_EXTENSIONS) {
			if (name.toLowerCase(Locale.ROOT).endsWith(extension)) {
				return "extension";
			}
		}
		for (Pattern pattern : DENY_FILE_NAMES) {
			if (pattern.matcher(name).matches()) {
				return "credentials";
			}
		}
		if (DENY_ARCHIVE.matcher(path).matches()) {
			return "archive";
		}
		for (String prefix : DENY_PREFIXES) {
			if ((path + "/").startsWith(prefix)) {
				return "runtime";
			}
		}
		return null;
	}

	/**
	 * Exports an instance profile as a {@code .dspack} archive.
	 *
	 * @param instance the source instance
	 * @param target   the output archive path
	 * @param options  pack metadata options
	 * @param onStage  consumer for progress messages, or null
	 * @return export result statistics
	 * @throws DshException when the profile cannot be read or written
	 */
	public static Result export(DshInstance instance, Path target, Options options, @Nullable Consumer<String> onStage)
	throws DshException {
		Path profile = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
		if (!Files.isDirectory(profile)) {
			throw new DshException("The instance has no profile at " + profile);
		}

		report(onStage, "Scanning " + profile.getFileName());
		Scan scan = scan(profile);
		report(onStage, scan.files().size() + " file(s) travel, " + scan.excluded().size() + " left out");

		JsonObject manifest = buildManifest(instance, options, onStage);

		Path parent = target.toAbsolutePath().getParent();
		try {
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
				put(zip, MARKER, "{\"format\":\"dspack\",\"version\":" + CONTAINER_VERSION + "}");
				put(zip, MANIFEST, JsonUtils.GSON.toJson(manifest));
				for (Entry entry : scan.files()) {
					copy(zip,
					     profile.resolve(entry.relative()),
					     DspackSafety.isMachineFile(entry.relative())
					     ? entry.relative()
					     : OVERRIDES + entry.relative());
				}
			}
		} catch (IOException e) {
			throw new DshException("Failed to write " + target, e);
		}

		long size = sizeOf(target);
		String digest = sha256(target);
		report(onStage,
		       "Wrote " + target.getFileName() + " (" + (size / 1024) + " KiB, sha256 " + digest.substring(0, 12) +
		       "…)");
		LOG.info("Wrote a DSH-PackForge pack of " + instance.id() + " to " + target);
		return new Result(scan.files().size(), scan.excluded().size(), size, digest);
	}

	/**
	 * Builds a v5 pack manifest for an instance.
	 *
	 * @param instance the instance
	 * @param options  metadata options
	 * @param onStage  consumer for progress messages, or null
	 * @return JSON manifest object
	 * @throws DshException when a dependency cannot be pinned
	 */
	static JsonObject buildManifest(DshInstance instance, Options options, @Nullable Consumer<String> onStage)
	throws DshException {
		JsonObject manifest = new JsonObject();
		manifest.addProperty("manifestVersion", MANIFEST_VERSION);
		manifest.addProperty("type", "profile");
		manifest.addProperty("name", Options.kebab(options.name()));
		manifest.addProperty("version",
		                     options.version() == null || options.version().isBlank()
		                     ? "1.0.0"
		                     : options.version().trim());
		if (options.displayName() != null && !options.displayName().isBlank()) {
			manifest.addProperty("displayName", options.displayName().trim());
		}
		if (options.description() != null && !options.description().isBlank()) {
			manifest.addProperty("description", options.description().trim());
		}
		if (options.author() != null && !options.author().isBlank()) {
			manifest.addProperty("author", options.author().trim());
		}
		manifest.addProperty("dshVersion", instance.version());
		manifest.addProperty("profileName", instance.profile());

		JsonArray bundles = new JsonArray();
		DshPluginInstaller.readBundles(instance.homeDirectory(), instance.profile()).forEach(bundles::add);
		manifest.add("bundles", bundles);

		JsonObject dependencies = new JsonObject();
		for (Map.Entry<String, String> entry : DshPluginInstaller.readDependencies(instance.homeDirectory(),
		                                                                           instance.profile()).entrySet()) {
			if (PackManifest.isLocalSpec(entry.getValue())) {
				dependencies.addProperty(entry.getKey(),
				                         publishedVersionOf(instance, entry.getKey(), entry.getValue()));
				report(onStage,
				       entry.getKey() + " was installed from a file and is published, so the" +
				       " pack names its published version");
				continue;
			}
			dependencies.addProperty(entry.getKey(), pin(entry.getKey(), entry.getValue(), onStage));
		}
		manifest.add("dependencies", dependencies);
		return manifest;
	}

	private static String publishedVersionOf(DshInstance instance, String name, String declared) throws DshException {
		Path profileDirectory = instance.homeDirectory().resolve("profiles").resolve(instance.profile());
		DshPluginBundle.Payload payload = DshPluginBundle.locate(profileDirectory, name, declared);
		if (payload != null &&
		    DshPackageRegistry.availability(name, payload.version()) == DshPackageRegistry.Availability.PUBLISHED) {
			return payload.version();
		}
		throw new DshException("The plugin " + name + " was installed from a file this instance keeps (" + declared +
		                       "), and the registry does not publish that version, so a pack of published" +
		                       " sources cannot name it. Export a pack this launcher reads (.hdslp) to carry the files" +
		                       " themselves, or install a published version first.");
	}

	/**
	 * Resolves a plugin dependency declaration to a pinned version or commit hash.
	 *
	 * @param name     package name
	 * @param declared declared version or git URL
	 * @param onStage  consumer for progress messages, or null
	 * @return pinned version or commit hash
	 * @throws DshException when resolution fails
	 */
	static String pin(String name, String declared, @Nullable Consumer<String> onStage) throws DshException {
		if (declared == null || declared.isBlank()) {
			throw new DshException("The plugin " + name + " has no version to pin");
		}
		if (!declared.startsWith("github:") && !declared.startsWith("git+") && !declared.contains("github.com/")) {
			return declared.trim();
		}

		String repository = declared.trim();
		if (repository.startsWith("github:")) {
			repository = "https://github.com/" + repository.substring("github:".length());
		}
		report(onStage, "Resolving " + name + " to a commit");
		try {
			DshCommand.Result result = DshCommand.run(List.of("git", "ls-remote", repository), null, null);
			if (result.exitCode() != 0) {
				throw new DshException("git could not list " + repository + ": " + String.join("\n", result.output()));
			}
			for (String line : result.output()) {
				String[] parts = line.trim().split("\\s+");
				if (parts.length == 2 && parts[0].length() >= 40) {
					return parts[0];
				}
			}
			throw new DshException("No branch or tag was found at " + repository);
		} catch (IOException e) {
			throw new DshException("Failed to run git for " + name, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DshException("Resolving " + name + " was interrupted", e);
		}
	}

	private static String relative(Path root, Path file) {
		return root.relativize(file).toString().replace('\\', '/');
	}

	private static void put(ZipOutputStream zip, String name, String body) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(body.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	private static void copy(ZipOutputStream zip, Path file, String name) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		try (InputStream input = Files.newInputStream(file)) {
			input.transferTo(zip);
		}
		zip.closeEntry();
	}

	private static long sizeOf(Path file) {
		try {
			return Files.size(file);
		} catch (IOException e) {
			return 0;
		}
	}

	/**
	 * Computes the SHA-256 hash of a file as a hex string.
	 *
	 * @param file the file to hash
	 * @return hex encoded SHA-256 digest
	 * @throws DshException when the file cannot be read
	 */
	static String sha256(Path file) throws DshException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(file)) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = input.read(buffer)) > 0) {
					digest.update(buffer, 0, read);
				}
			}
			StringBuilder text = new StringBuilder();
			for (byte value : digest.digest()) {
				text.append(String.format("%02x", value));
			}
			return text.toString();
		} catch (IOException | NoSuchAlgorithmException e) {
			throw new DshException("Failed to hash " + file, e);
		}
	}

	private static void report(@Nullable Consumer<String> onStage, String message) {
		if (onStage != null) {
			onStage.accept(message);
		}
	}

	/**
	 * Pack metadata configuration options.
	 *
	 * @param name        pack identifier (kebab-case)
	 * @param version     pack version string
	 * @param displayName user-facing display name
	 * @param description pack description
	 * @param author      pack author
	 */
	public record Options(String name, String version, String displayName, String description, String author) {

		/**
		 * Creates default options describing an instance.
		 *
		 * @param instance the instance
		 * @return default options
		 */
		public static Options of(DshInstance instance) {
			String name = kebab(instance.id());
			return new Options(name, "1.0.0", instance.id(), "", "");
		}

		/**
		 * Converts arbitrary text into a valid kebab-case identifier.
		 *
		 * @param text input text
		 * @return sanitized kebab-case identifier
		 */
		public static String kebab(String text) {
			String cleaned = text == null
			                 ? ""
			                 : text.trim()
			                       .toLowerCase(Locale.ROOT)
			                       .replaceAll("[^a-z0-9]+", "-")
			                       .replaceAll("(^-+)|(-+$)", "");
			return cleaned.isEmpty() ? "pack" : cleaned;
		}
	}

	/**
	 * File entry in a profile scan.
	 *
	 * @param relative relative path with forward slashes
	 * @param size     size in bytes
	 */
	public record Entry(String relative, long size) {
	}

	/**
	 * Result of scanning a profile directory.
	 *
	 * @param files    included files sorted by path
	 * @param excluded excluded paths mapped to reason codes
	 */
	public record Scan(List<Entry> files, Map<String, String> excluded) {
	}

	/**
	 * Result of exporting a pack archive.
	 *
	 * @param files    number of files included
	 * @param excluded number of files excluded
	 * @param bytes    output file size in bytes
	 * @param sha256   hex SHA-256 digest of the archive
	 */
	public record Result(int files, int excluded, long bytes, String sha256) {
	}
}
