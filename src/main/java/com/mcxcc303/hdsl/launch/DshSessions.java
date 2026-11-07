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
package com.mcxcc303.hdsl.launch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshWorkspace;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Reads, inspects, and migrates session histories stored in an instance home.
 */
@NotNullByDefault
public final class DshSessions {
	private static final Pattern LOG_FILE = Pattern.compile("^session(?:\\.v([1-9][0-9]*))?\\.jsonl(?:\\.zstd)?$");

	private DshSessions() {
	}

	/**
	 * Checks whether a file name matches the session log naming pattern.
	 *
	 * @param fileName the file name to check
	 * @return true if the file name is recognized as a session log
	 */
	public static boolean isLogName(String fileName) {
		return LOG_FILE.matcher(fileName).matches();
	}

	/**
	 * Returns the sessions stored in a home, newest first.
	 *
	 * @param home the DSH_HOME path
	 * @return list of sessions sorted newest first
	 * @throws DshException when the home cannot be read
	 */
	public static @Unmodifiable List<DshSession> list(Path home) throws DshException {
		Path root = home.resolve("sessions");
		if (!Files.isDirectory(root)) {
			return List.of();
		}

		List<DshSession> sessions = new ArrayList<>();
		try (Stream<Path> slugs = Files.list(root)) {
			for (Path slugDirectory : slugs.filter(Files::isDirectory).toList()) {
				try (Stream<Path> children = Files.list(slugDirectory)) {
					for (Path sessionDirectory : children.filter(Files::isDirectory).toList()) {
						DshSession session = read(slugDirectory, sessionDirectory);
						if (session != null) {
							sessions.add(session);
						}
					}
				}
			}
		} catch (IOException e) {
			throw new DshException("Failed to read the sessions in " + root, e);
		}

		sessions.sort(Comparator.comparingLong(DshSession::modifiedAt).reversed());
		return List.copyOf(sessions);
	}

	/**
	 * Groups a home's sessions by the workspace they were recorded in.
	 *
	 * @param home the DSH_HOME path
	 * @return list of workspaces, newest activity first
	 * @throws DshException when the home cannot be read
	 */
	public static @Unmodifiable List<DshWorkspace> workspaces(Path home) throws DshException {
		Map<String, List<DshSession>> bySlug = new LinkedHashMap<>();
		for (DshSession session : list(home)) {
			bySlug.computeIfAbsent(session.workspaceSlug(), slug -> new ArrayList<>()).add(session);
		}

		List<DshWorkspace> workspaces = new ArrayList<>();
		for (Map.Entry<String, List<DshSession>> entry : bySlug.entrySet()) {
			String path = null;
			for (DshSession session : entry.getValue()) {
				if (session.workingDirectory() != null && !session.workingDirectory().isBlank()) {
					path = session.workingDirectory();
					break;
				}
			}
			workspaces.add(new DshWorkspace(entry.getKey(),
			                                path,
			                                DshWorkspace.titleOf(path, entry.getKey()),
			                                List.copyOf(entry.getValue())));
		}
		workspaces.sort(Comparator.comparingLong(DshWorkspace::modifiedAt).reversed());
		return List.copyOf(workspaces);
	}

	private static @Nullable DshSession read(Path slugDirectory, Path sessionDirectory) {
		Path log = findLog(sessionDirectory);
		if (log == null) {
			return null;
		}

		Matcher matcher = LOG_FILE.matcher(log.getFileName().toString());
		if (!matcher.matches()) {
			return null;
		}

		int formatVersion;
		try {
			formatVersion = matcher.group(1) == null ? 0 : Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException e) {
			return null;
		}

		String id = sessionDirectory.getFileName().toString();
		CacheRow cache = readCache(slugDirectory.getParent().getParent(), id);
		long modifiedAt = cache == null
		                  ? lastModified(sessionDirectory)
		                  : Math.max(cache.createdAt(), lastModified(sessionDirectory));

		return new DshSession(id,
		                      slugDirectory.getFileName().toString(),
		                      sessionDirectory,
		                      formatVersion,
		                      cache == null ? null : cache.cwd(),
		                      cache == null ? null : cache.title(),
		                      directorySize(sessionDirectory),
		                      modifiedAt,
		                      isHeld(sessionDirectory.resolve("session.lock")));
	}

	private static boolean isHeld(Path lockFile) {
		if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
			return false;
		}
		if (!Files.exists(lockFile)) {
			return false;
		}
		try {
			Process process = new ProcessBuilder("flock", "-n", lockFile.toString(), "true").redirectErrorStream(true)
			                                                                                .redirectOutput(
					                                                                                ProcessBuilder.Redirect.DISCARD)
			                                                                                .start();
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return false;
			}
			return process.exitValue() != 0;
		} catch (IOException e) {
			LOG.info("flock(1) is unavailable, so session leases cannot be observed: " + e);
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static @Nullable Path findLog(Path sessionDirectory) {
		try (Stream<Path> files = Files.list(sessionDirectory)) {
			return files.filter(Files::isRegularFile)
			            .filter(path -> LOG_FILE.matcher(path.getFileName().toString()).matches())
			            .max(Comparator.comparingInt(DshSessions::generationOf)
			                           .thenComparing(path -> path.getFileName().toString().endsWith(".zstd")))
			            .orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	private static int generationOf(Path log) {
		Matcher matcher = LOG_FILE.matcher(log.getFileName().toString());
		if (!matcher.matches() || matcher.group(1) == null) {
			return 0;
		}
		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static long lastModified(Path directory) {
		try {
			return Files.getLastModifiedTime(directory).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}

	private static long directorySize(Path directory) {
		long[] total = {0L};
		try {
			Files.walkFileTree(directory, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
					total[0] += attributes.size();
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException e) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			LOG.warning("Failed to size " + directory, e);
		}
		return total[0];
	}

	/**
	 * Returns the format versions already present in a home.
	 *
	 * @param home the DSH_HOME to scan
	 * @return set of format versions
	 * @throws DshException when the home cannot be read
	 */
	public static @Unmodifiable Set<Integer> formatVersions(Path home) throws DshException {
		Set<Integer> versions = new LinkedHashSet<>();
		for (DshSession session : list(home)) {
			versions.add(session.formatVersion());
		}
		return Collections.unmodifiableSet(versions);
	}

	/**
	 * Explains why a session cannot be migrated into a home, if it cannot.
	 *
	 * @param sourceInstance the source instance
	 * @param session        the session to migrate
	 * @param targetInstance the destination instance
	 * @return refusal reason, or null if permitted
	 * @throws DshException when the target home cannot be read
	 */
	public static @Nullable String refusalReason(DshInstance sourceInstance,
	                                             DshSession session,
	                                             DshInstance targetInstance) throws DshException {
		if (sourceInstance.id().equals(targetInstance.id())) {
			return "A session cannot be migrated into its own instance";
		}
		if (session.locked()) {
			return "This session is open; close it in DeepSeek Harness before migrating it";
		}

		Path targetHome = targetInstance.homeDirectory();
		Path existing = targetHome.resolve("sessions").resolve(session.workspaceSlug()).resolve(session.id());
		if (Files.exists(existing)) {
			return "The target instance already has a session with this id";
		}

		Set<Integer> targetVersions = formatVersions(targetHome);
		if (!targetVersions.isEmpty()) {
			if (!targetVersions.contains(session.formatVersion())) {
				return "The target instance uses session format " + describe(targetVersions) +
				       ", but this session is format v" + session.formatVersion();
			}
			return null;
		}

		if (!sourceInstance.version().equals(targetInstance.version())) {
			return "The target instance has no sessions yet, so its format is unknown; it runs DeepSeek Harness " +
			       targetInstance.version() + " while this session was written by " + sourceInstance.version();
		}
		return null;
	}

	private static String describe(Set<Integer> versions) {
		StringBuilder builder = new StringBuilder();
		for (Integer version : versions) {
			if (!builder.isEmpty()) {
				builder.append(", ");
			}
			builder.append('v').append(version);
		}
		return builder.toString();
	}

	/**
	 * Migrates a session between two instances' homes.
	 *
	 * @param sourceInstance the instance the session belongs to
	 * @param session        the session to migrate
	 * @param targetInstance the instance receiving it
	 * @param move           true to delete original after copying
	 * @throws DshException when migration is refused or fails
	 */
	public static void migrate(DshInstance sourceInstance, DshSession session, DshInstance targetInstance, boolean move)
	throws DshException {
		String refusal = refusalReason(sourceInstance, session, targetInstance);
		if (refusal != null) {
			throw new DshException(refusal);
		}

		copyInto(sourceInstance.homeDirectory(), session, targetInstance, move);
	}

	/**
	 * Copies a session from an external home into an instance.
	 *
	 * @param sourceHome external home path
	 * @param session    session to copy
	 * @param target     receiving instance
	 * @throws DshException when copying fails or session is locked
	 */
	public static void importFrom(Path sourceHome, DshSession session, DshInstance target) throws DshException {
		if (session.locked()) {
			throw new DshException("This session is open; close it in DeepSeek Harness before importing it");
		}
		Path existing = target.homeDirectory()
		                      .resolve("sessions")
		                      .resolve(session.workspaceSlug())
		                      .resolve(session.id());
		if (Files.exists(existing)) {
			throw new DshException("The instance already has a session with this id");
		}
		copyInto(sourceHome, session, target, false);
	}

	/**
	 * Reads sessions from an unmanaged external home directory.
	 *
	 * @param home the home to read
	 * @return sessions found, newest first
	 * @throws DshException when reading fails
	 */
	public static @Unmodifiable List<DshSession> readForeignHome(Path home) throws DshException {
		return list(home);
	}

	private static void copyInto(Path sourceHome, DshSession session, DshInstance target, boolean move)
	throws DshException {
		Path targetHome = target.homeDirectory();

		Path targetSessionDirectory = targetHome.resolve("sessions")
		                                        .resolve(session.workspaceSlug())
		                                        .resolve(session.id());

		try {
			copyTree(session.directory(), targetSessionDirectory);

			Files.deleteIfExists(targetSessionDirectory.resolve("session.lock"));

			Path sourceCache = cacheFile(sourceHome, session.id());
			if (Files.isRegularFile(sourceCache)) {
				Path targetCache = cacheFile(targetHome, session.id());
				Files.createDirectories(targetCache.getParent());
				Files.copy(sourceCache, targetCache, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			try {
				deleteTree(targetSessionDirectory);
				Files.deleteIfExists(cacheFile(targetHome, session.id()));
			} catch (IOException cleanupFailure) {
				LOG.warning("Failed to clean up after a failed migration", cleanupFailure);
			}
			throw new DshException("Failed to migrate the session: " + e.getMessage(), e);
		}

		if (move) {
			try {
				deleteTree(session.directory());
				Files.deleteIfExists(cacheFile(sourceHome, session.id()));
			} catch (IOException e) {
				throw new DshException(
						"The session was copied, but the original could not be removed: " + e.getMessage(), e);
			}
		}
	}

	/**
	 * Deletes a session and its projection cache row.
	 *
	 * @param instance the instance owning the session
	 * @param session  the session to delete
	 * @throws DshException when removal fails or session is locked
	 */
	public static void delete(DshInstance instance, DshSession session) throws DshException {
		if (session.locked()) {
			throw new DshException("This session is open; close it in DeepSeek Harness before deleting it");
		}
		try {
			deleteTree(session.directory());
			Files.deleteIfExists(cacheFile(instance.homeDirectory(), session.id()));
			Path slug = session.directory().getParent();
			if (slug != null && Files.isDirectory(slug)) {
				try (var children = Files.list(slug)) {
					if (children.findAny().isEmpty()) {
						Files.deleteIfExists(slug);
					}
				}
			}
		} catch (IOException e) {
			throw new DshException("Failed to delete the session: " + e.getMessage(), e);
		}
	}

	/**
	 * Marks the workspace registry uninitialized so the harness re-indexes session groupings.
	 *
	 * @param home the DSH_HOME path
	 * @throws DshException when registry cannot be read or written
	 */
	public static void regroup(Path home) throws DshException {
		Path registryFile = workspaceFile(home);
		if (!Files.isRegularFile(registryFile)) {
			return;
		}

		JsonObject registry;
		try {
			registry = JsonUtils.fromJsonFile(registryFile, JsonObject.class);
		} catch (Exception e) {
			throw new DshException("Failed to read the workspace list of " + home, e);
		}
		if (registry == null) {
			return;
		}

		JsonObject global = member(registry, "global") instanceof JsonObject existing ? existing : new JsonObject();
		if (!registry.has("global")) {
			registry.add("global", global);
		}
		if (global.has("initialized") && global.get("initialized").isJsonPrimitive() &&
		    !global.get("initialized").getAsBoolean()) {
			return;
		}
		global.addProperty("initialized", false);

		try {
			Files.createDirectories(registryFile.getParent());
			JsonUtils.writeToJsonFile(registryFile, registry);
		} catch (IOException e) {
			throw new DshException("Failed to write the workspace list of " + home, e);
		}
		LOG.info("Asked for the sessions of " + home + " to be grouped again");
	}

	private static Path workspaceFile(Path home) {
		return home.resolve("storages").resolve("workspace.json");
	}

	private static @Nullable JsonElement member(@Nullable JsonElement object, String name) {
		if (object == null || !object.isJsonObject()) {
			return null;
		}
		JsonObject holder = object.getAsJsonObject();
		return holder.has(name) ? holder.get(name) : null;
	}

	private static Path cacheFile(Path home, String sessionId) {
		return home.resolve("storages").resolve("session_projcache").resolve("sessions").resolve(sessionId + ".json");
	}

	private static void copyTree(Path source, Path destination) throws IOException {
		Files.createDirectories(destination);
		Files.walkFileTree(source, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
			throws IOException {
				Files.createDirectories(destination.resolve(source.relativize(directory).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				Files.copy(file,
				           destination.resolve(source.relativize(file).toString()),
				           StandardCopyOption.REPLACE_EXISTING,
				           StandardCopyOption.COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Deletes a directory tree recursively.
	 *
	 * @param directory directory to delete
	 * @throws IOException if deletion fails
	 */
	static void deleteTree(Path directory) throws IOException {
		if (!Files.exists(directory)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static @Nullable CacheRow readCache(Path home, String sessionId) {
		Path file = cacheFile(home, sessionId);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(file));
			if (!parsed.isJsonObject()) {
				return null;
			}
			JsonObject record = parsed.getAsJsonObject().getAsJsonObject("record");
			if (record == null) {
				return null;
			}

			String cwd = null;
			long createdAt = 0L;
			JsonObject identity = record.getAsJsonObject("identity");
			if (identity != null) {
				JsonElement cwdElement = identity.get("cwd");
				if (cwdElement != null && cwdElement.isJsonPrimitive()) {
					cwd = cwdElement.getAsString();
				}
				JsonElement createdAtElement = identity.get("createdAt");
				if (createdAtElement != null && createdAtElement.isJsonPrimitive()) {
					createdAt = createdAtElement.getAsLong();
				}
			}

			return new CacheRow(cwd, titleOf(record), createdAt);
		} catch (IOException | RuntimeException e) {
			LOG.info("Unreadable session cache row for " + sessionId + ": " + e);
			return null;
		}
	}

	private static @Nullable String titleOf(JsonObject record) {
		JsonObject rows = record.getAsJsonObject("rows");
		if (rows == null) {
			return null;
		}
		JsonObject title = rows.getAsJsonObject("title");
		if (title == null) {
			return null;
		}
		JsonElement value = title.get("val");
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			return null;
		}
		String text = value.getAsString().trim();
		return text.isEmpty() ? null : text;
	}

	/**
	 * Formats a byte count into a human-readable size string.
	 *
	 * @param bytes byte count
	 * @return formatted size string
	 */
	public static String formatSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
		}
		return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
	}

	private record CacheRow(@Nullable String cwd, @Nullable String title, long createdAt) {
	}
}
