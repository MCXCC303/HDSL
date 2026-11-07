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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.launch.DshSession;
import com.mcxcc303.hdsl.launch.DshSessions;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.platform.SystemUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Archives and restores conversation sessions and attachments as portable {@code .sspack} files.
 */
@NotNullByDefault
public final class DshSessionPacks {
	public static final String FORMAT = "hdsl-session-pack";
	public static final String FILE_EXTENSION = ".sspack";
	public static final List<String> ACCEPTED_EXTENSIONS = List.of(".sspack", ".zip");
	public static final int FORMAT_VERSION = 1;
	public static final String MANIFEST = "manifest.json";

	private static final String SESSIONS = "sessions/";
	private static final String ATTACHMENTS = "attachments/";
	private static final String PROJCACHE = "projcache/";
	private static final Pattern OBJECT_ID = Pattern.compile("\\b[0-9a-f]{64}\\b");
	private static final List<String> STORES = List.of("objects", "file-objects", "request-images");
	private static int copiedAttachments;

	private DshSessionPacks() {
	}

	/**
	 * Writes the given sessions into a pack.
	 *
	 * @param instance the instance whose sessions they are
	 * @param sessions the sessions to write
	 * @param target   the archive to create
	 * @param onStage  receives progress lines, or null
	 * @return export summary
	 * @throws DshException when a session cannot be read or the pack cannot be written
	 */
	public static ExportResult export(DshInstance instance,
	                                  List<DshSession> sessions,
	                                  Path target,
	                                  @Nullable Consumer<String> onStage) throws DshException {
		Path home = instance.homeDirectory();
		if (sessions.isEmpty()) {
			throw new DshException("There are no sessions to write into a pack");
		}

		List<DshSession> ordered = sessions.stream().sorted(Comparator.comparing(DshSession::id)).toList();

		Set<String> referenced = referencedAttachments(home, ordered);
		List<Path> attachments = referenced == null
		                         ? allAttachments(home)
		                         : referenced.stream()
		                                     .map(id -> findAttachment(home, id))
		                                     .filter(Objects::nonNull)
		                                     .toList();

		report(onStage, "Writing " + ordered.size() + " session(s)");
		Path parent = target.toAbsolutePath().getParent();
		try {
			if (parent != null) {
				Files.createDirectories(parent);
			}
		} catch (IOException e) {
			throw new DshException("Failed to prepare " + target, e);
		}

		List<Entry> entries = new ArrayList<>();
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
			entries.addAll(writeInto(zip, home, ordered, onStage));
			for (Path attachment : attachments) {
				copyInto(zip, attachment, ATTACHMENTS + relativeToStore(home, attachment));
			}

			Manifest manifest = new Manifest(FORMAT,
			                                 FORMAT_VERSION,
			                                 Instant.now().toString(),
			                                 instance.id(),
			                                 instance.version(),
			                                 commonWorkingDirectory(entries),
			                                 List.copyOf(entries),
			                                 referenced == null ? "all" : "referenced",
			                                 attachments.size());
			zip.putNextEntry(new ZipEntry(MANIFEST));
			zip.write(JsonUtils.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		} catch (IOException e) {
			throw new DshException("Failed to write " + target, e);
		}

		long size = sizeOf(target);
		report(onStage,
		       "Wrote " + entries.size() + " session(s) and " + attachments.size() + " attachment(s), " +
		       (size / 1024 / 1024) + " MiB");
		LOG.info("Wrote a session pack of " + entries.size() + " session(s) to " + target);
		return new ExportResult(entries.size(), attachments.size(), size);
	}

	/**
	 * Writes sessions into an open archive stream.
	 *
	 * @param zip      the output archive
	 * @param home     the DSH_HOME path
	 * @param sessions sessions to write
	 * @param onStage  receives progress lines, or null
	 * @return entries describing what was written
	 * @throws DshException when a session holds no log
	 * @throws IOException  when the archive cannot be written
	 */
	public static List<Entry> writeInto(ZipOutputStream zip,
	                                    Path home,
	                                    List<DshSession> sessions,
	                                    @Nullable Consumer<String> onStage) throws DshException, IOException {
		List<Entry> entries = new ArrayList<>();
		for (DshSession session : sessions) {
			Path slugDirectory = session.directory().getParent();
			String slug = slugDirectory.getFileName().toString();
			long size = 0;
			for (Path log : logsOf(session.directory())) {
				String name = SESSIONS + slug + "/" + session.id() + "/" + log.getFileName();
				size += copyInto(zip, log, name);
			}
			if (size == 0) {
				throw new DshException("Session " + session.id() + " holds no log to write");
			}

			Path cache = cacheFile(home, session.id());
			if (cache != null) {
				copyInto(zip, cache, PROJCACHE + "sessions/" + session.id() + ".json");
			}

			entries.add(new Entry(session.id(),
			                      slug,
			                      session.workingDirectory(),
			                      session.formatVersion(),
			                      session.title(),
			                      size));
		}
		report(onStage, "Wrote " + entries.size() + " session(s)");
		return entries;
	}

	/**
	 * Writes session attachments into an open archive stream.
	 *
	 * @param zip      the archive
	 * @param home     the DSH_HOME path
	 * @param sessions the sessions
	 * @param onStage  receives progress lines, or null
	 * @return count of attachment objects written
	 * @throws IOException when the archive cannot be written
	 */
	public static int writeAttachmentsInto(ZipOutputStream zip,
	                                       Path home,
	                                       List<DshSession> sessions,
	                                       @Nullable Consumer<String> onStage) throws IOException {
		Set<String> referenced = referencedAttachments(home, sessions);
		List<Path> attachments = referenced == null
		                         ? allAttachments(home)
		                         : referenced.stream()
		                                     .map(id -> findAttachment(home, id))
		                                     .filter(Objects::nonNull)
		                                     .toList();
		for (Path attachment : attachments) {
			copyInto(zip, attachment, ATTACHMENTS + relativeToStore(home, attachment));
		}
		report(onStage, "Wrote " + attachments.size() + " attachment(s)");
		return attachments.size();
	}

	/**
	 * Reads a session pack into an instance home.
	 *
	 * @param home    the DSH_HOME path
	 * @param pack    the archive path
	 * @param onStage receives progress lines, or null
	 * @return import summary
	 * @throws DshException when the pack cannot be read or validated
	 */
	public static ImportResult importFrom(Path home, Path pack, @Nullable Consumer<String> onStage)
	throws DshException {
		Manifest manifest = readManifest(pack);
		if (!FORMAT.equals(manifest.format())) {
			throw new DshException("This file is not a session pack (" + manifest.format() + ")");
		}
		if (manifest.version() > FORMAT_VERSION) {
			throw new DshException("This pack was written by a newer launcher (format " + manifest.version() +
			                       "), which this one cannot read");
		}

		warnAboutWorkingDirectory(manifest);

		Set<String> held = new LinkedHashSet<>(DshSessions.list(home).stream().map(DshSession::id).toList());
		List<Entry> wanted = manifest.sessions().stream().filter(entry -> !held.contains(entry.id())).toList();

		report(onStage, "Reading " + wanted.size() + " session(s)");
		int imported;
		int attachments;
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
			imported = copyEntries(zip, home, wanted);
			attachments = copiedAttachments;
		} catch (IOException e) {
			throw new DshException("Failed to read " + pack, e);
		}

		if (imported > 0) {
			DshSessions.regroup(home);
		}

		report(onStage,
		       "Imported " + imported + " session(s), skipped " + (manifest.sessions().size() - imported) +
		       " already present");
		LOG.info("Imported " + imported + " session(s) from " + pack);
		return new ImportResult(imported, manifest.sessions().size() - imported, attachments, manifest);
	}

	/**
	 * Restores session entries from an archive into an instance home.
	 *
	 * @param archive the archive
	 * @param home    the DSH_HOME path
	 * @param onStage receives progress lines, or null
	 * @return number of restored sessions
	 * @throws DshException when the archive cannot be read
	 */
	public static int restoreInto(Path archive, Path home, @Nullable Consumer<String> onStage) throws DshException {
		int imported;
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
			imported = copyEntries(zip, home, null);
		} catch (IOException e) {
			throw new DshException("Failed to read " + archive, e);
		}
		if (imported > 0) {
			DshSessions.regroup(home);
		}
		report(onStage, "Restored " + imported + " session(s) from the pack");
		return imported;
	}

	private static int copyEntries(ZipInputStream zip, Path home, @Nullable List<Entry> wanted) throws IOException {
		Set<String> wantedIds =
				wanted == null ? Set.of() : new LinkedHashSet<>(wanted.stream().map(Entry::id).toList());
		int imported = 0;
		copiedAttachments = 0;

		ZipEntry entry;
		while ((entry = zip.getNextEntry()) != null) {
			String name = entry.getName();
			if (entry.isDirectory()) {
				continue;
			}

			if (name.startsWith(SESSIONS)) {
				String[] parts = name.substring(SESSIONS.length()).split("/");
				if (parts.length != 3 || !safeRelative(parts[0]) || !safeRelative(parts[1]) ||
				    !safeRelative(parts[2])) {
					LOG.warning("Skipping " + name + " in a session pack: unsafe path");
					continue;
				}
				if (wanted != null) {
					if (!wantedIds.contains(parts[1])) {
						continue;
					}
					String recorded = slugOf(wanted, parts[1]);
					if (recorded == null || !recorded.equals(parts[0])) {
						LOG.warning("Skipping " + name + " in a session pack: the manifest puts session " + parts[1] +
						            " in " + recorded + ", not " + parts[0]);
						continue;
					}
				}
				if (!DshSessions.isLogName(parts[2])) {
					LOG.warning("Skipping " + name + " in a session pack: not a session log name");
					continue;
				}
				if (!matchesCompression(home, parts[2])) {
					LOG.warning("Skipping " + name + " in a session pack: this home reads " +
					            (expectsCompressed(home) ? "compressed" : "uncompressed") + " logs");
					continue;
				}
				Path target = home.resolve("sessions").resolve(parts[0]).resolve(parts[1]).resolve(parts[2]);
				boolean fresh = !Files.exists(target);
				Files.createDirectories(target.getParent());
				write(zip, target);
				if (fresh) {
					imported++;
				}
			} else if (name.startsWith(PROJCACHE)) {
				String file = name.substring(PROJCACHE.length());
				if (!file.startsWith("sessions/") || !file.endsWith(".json")) {
					continue;
				}
				String id = file.substring("sessions/".length(), file.length() - ".json".length());
				if (!safeRelative(id)) {
					LOG.warning("Skipping " + name + " in a session pack: unsafe path");
					continue;
				}
				if (!wantedIds.isEmpty() && !wantedIds.contains(id)) {
					continue;
				}
				Path target = home.resolve("storages").resolve("session_projcache").resolve(file);
				Files.createDirectories(target.getParent());
				write(zip, target);
			} else if (name.startsWith(ATTACHMENTS)) {
				String relative = name.substring(ATTACHMENTS.length());
				if (!safeRelative(relative)) {
					LOG.warning("Skipping " + name + " in a session pack: unsafe path");
					continue;
				}
				Path target = home.resolve("attachments").resolve(relative);
				if (Files.exists(target)) {
					continue;
				}
				Files.createDirectories(target.getParent());
				write(zip, target);
				copiedAttachments++;
			}
		}
		return imported;
	}

	/**
	 * Reads and parses the manifest of a session pack.
	 *
	 * @param pack the archive path
	 * @return parsed manifest
	 * @throws DshException when the archive holds no manifest
	 */
	public static Manifest readManifest(Path pack) throws DshException {
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pack))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (MANIFEST.equals(entry.getName())) {
					String body = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
					JsonElement parsed = JsonParser.parseString(body);
					if (!parsed.isJsonObject()) {
						throw new DshException("The pack's manifest is not an object");
					}
					return manifestOf(parsed.getAsJsonObject());
				}
			}
		} catch (IOException e) {
			throw new DshException("Failed to read " + pack, e);
		}
		throw new DshException("This file holds no session pack manifest");
	}

	private static Manifest manifestOf(JsonObject root) {
		List<Entry> entries = new ArrayList<>();
		JsonElement sessions = root.get("sessions");
		if (sessions != null && sessions.isJsonArray()) {
			for (JsonElement element : sessions.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject object = element.getAsJsonObject();
				String id = string(object, "id");
				String slug = string(object, "slug");
				if (id == null || slug == null) {
					continue;
				}
				entries.add(new Entry(id,
				                      slug,
				                      string(object, "cwd"),
				                      integer(object, "formatVersion"),
				                      string(object, "title"),
				                      integer(object, "sizeBytes")));
			}
		}

		return new Manifest(string(root, "format") == null ? "" : string(root, "format"),
		                    integer(root, "version"),
		                    string(root, "createdAt") == null ? "" : string(root, "createdAt"),
		                    string(root, "instanceId") == null ? "" : string(root, "instanceId"),
		                    string(root, "dshVersion") == null ? "" : string(root, "dshVersion"),
		                    string(root, "workingDirectory"),
		                    List.copyOf(entries),
		                    string(root, "attachments") == null ? "unknown" : string(root, "attachments"),
		                    integer(root, "attachmentCount"));
	}

	private static void warnAboutWorkingDirectory(Manifest manifest) {
		String cwd = manifest.workingDirectory();
		if (cwd == null || cwd.isBlank()) {
			return;
		}
		if (!Files.isDirectory(Path.of(cwd))) {
			LOG.warning("The pack's sessions were recorded in " + cwd +
			            ", which does not exist here: they will list ungrouped and cannot be resumed");
		}
	}

	private static @Nullable Set<String> referencedAttachments(Path home, List<DshSession> sessions) {
		Path zstd = SystemUtils.which("zstd");
		if (zstd == null) {
			LOG.info("zstd is not installed, so a session pack carries the whole attachment store");
			return null;
		}

		Set<String> ids = new LinkedHashSet<>();
		for (DshSession session : sessions) {
			for (Path log : logsOf(session.directory())) {
				boolean compressed = log.getFileName().toString().endsWith(".zstd");
				try {
					String text;
					if (compressed) {
						List<String> output = new ArrayList<>();
						int exit = DshCommand.run(List.of(zstd.toString(), "-d", "-c", log.toString()),
						                          null,
						                          output::add).exitCode();
						if (exit != 0) {
							LOG.warning("zstd could not read " + log + ", so the whole store is carried");
							return null;
						}
						text = String.join("\n", output);
					} else {
						text = Files.readString(log, StandardCharsets.UTF_8);
					}
					Matcher matcher = OBJECT_ID.matcher(text);
					while (matcher.find()) {
						ids.add(matcher.group());
					}
				} catch (IOException e) {
					LOG.warning("Failed to read " + log + ", so the whole store is carried", e);
					return null;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return null;
				}
			}
		}
		return ids;
	}

	private static List<Path> allAttachments(Path home) {
		Path store = home.resolve("attachments");
		if (!Files.isDirectory(store)) {
			return List.of();
		}
		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(store)) {
			walk.filter(Files::isRegularFile)
			    .filter(path -> OBJECT_ID.matcher(path.getFileName().toString()).matches())
			    .forEach(files::add);
		} catch (IOException e) {
			LOG.warning("Failed to list the attachment store", e);
		}
		return files;
	}

	private static @Nullable Path findAttachment(Path home, String id) {
		for (String store : STORES) {
			Path file = home.resolve("attachments")
			                .resolve("v1")
			                .resolve(store)
			                .resolve(id.substring(0, 2))
			                .resolve(id);
			if (Files.isRegularFile(file)) {
				return file;
			}
		}
		return null;
	}

	private static String relativeToStore(Path home, Path file) {
		Path store = home.resolve("attachments");
		String relative = store.relativize(file).toString();
		return relative.replace('\\', '/');
	}

	private static boolean expectsCompressed(Path home) {
		Path root = home.resolve("sessions");
		if (!Files.isDirectory(root)) {
			return true;
		}
		try (Stream<Path> walk = Files.walk(root, 3)) {
			for (Path file : walk.filter(Files::isRegularFile).toList()) {
				String name = file.getFileName().toString();
				if (DshSessions.isLogName(name)) {
					return name.endsWith(".zstd");
				}
			}
		} catch (IOException e) {
			LOG.warning("Failed to look at the existing sessions of " + home, e);
		}
		return true;
	}

	private static boolean matchesCompression(Path home, String fileName) {
		boolean compressed = fileName.endsWith(".zstd");
		return compressed == expectsCompressed(home);
	}

	private static @Nullable String slugOf(List<Entry> entries, String id) {
		for (Entry entry : entries) {
			if (entry.id().equals(id)) {
				return entry.slug();
			}
		}
		return null;
	}

	private static @Nullable Path cacheFile(Path home, String id) {
		Path file = home.resolve("storages").resolve("session_projcache").resolve("sessions").resolve(id + ".json");
		return Files.isRegularFile(file) ? file : null;
	}

	private static List<Path> logsOf(Path directory) {
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(Files::isRegularFile)
			            .filter(path -> DshSessions.isLogName(path.getFileName().toString()))
			            .sorted()
			            .toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static @Nullable String commonWorkingDirectory(List<Entry> entries) {
		String common = null;
		for (Entry entry : entries) {
			String cwd = entry.cwd();
			if (cwd == null) {
				return null;
			}
			if (common == null) {
				common = cwd;
			} else if (!common.equals(cwd)) {
				return null;
			}
		}
		return common;
	}

	private static long copyInto(ZipOutputStream zip, Path file, String name) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		try (InputStream input = Files.newInputStream(file)) {
			input.transferTo(zip);
		}
		zip.closeEntry();
		return Files.size(file);
	}

	private static void write(ZipInputStream zip, Path target) throws IOException {
		Path staging = target.resolveSibling(target.getFileName() + ".importing");
		try (OutputStream output = Files.newOutputStream(staging)) {
			zip.transferTo(output);
		}
		Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
	}

	private static boolean safeRelative(String relative) {
		if (relative.isBlank() || relative.startsWith("/") || relative.contains("\\")) {
			return false;
		}
		if (relative.equals(".") || relative.equals("..")) {
			return false;
		}
		for (String part : relative.split("/")) {
			if (part.isEmpty() || part.equals(".") || part.equals("..")) {
				return false;
			}
		}
		return true;
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

	private static @Nullable String string(JsonObject object, String name) {
		JsonElement element = object.get(name);
		return element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
		       ? null
		       : element.getAsString();
	}

	private static int integer(JsonObject object, String name) {
		JsonElement element = object.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return 0;
		}
		return element.getAsInt();
	}

	/**
	 * One conversation session entry in a pack.
	 *
	 * @param id            session identifier
	 * @param slug          workspace project slug directory name
	 * @param cwd           working directory where session was recorded, or null
	 * @param formatVersion log format version
	 * @param title         session title, or null
	 * @param sizeBytes     size of session log files in bytes
	 */
	public record Entry(String id, String slug, @Nullable String cwd, int formatVersion, @Nullable String title,
	                    long sizeBytes) {
	}

	/**
	 * Session pack manifest contents.
	 *
	 * @param format           format identifier
	 * @param version          format version
	 * @param createdAt        ISO creation timestamp
	 * @param instanceId       source instance ID
	 * @param dshVersion       DeepSeek Harness version
	 * @param workingDirectory common working directory, or null
	 * @param sessions         session entries included
	 * @param attachments      attachment coverage indicator ("all" or "referenced")
	 * @param attachmentCount  number of attachment files included
	 */
	public record Manifest(String format, int version, String createdAt, String instanceId, String dshVersion,
	                       @Nullable String workingDirectory, List<Entry> sessions, String attachments,
	                       int attachmentCount) {
	}

	/**
	 * Summary of a session pack export.
	 *
	 * @param sessions    number of sessions exported
	 * @param attachments number of attachment objects exported
	 * @param bytes       archive file size in bytes
	 */
	public record ExportResult(int sessions, int attachments, long bytes) {
	}

	/**
	 * Summary of a session pack import.
	 *
	 * @param imported    number of sessions added
	 * @param skipped     number of sessions skipped because they were already present
	 * @param attachments number of attachment objects imported
	 * @param manifest    imported pack manifest
	 */
	public record ImportResult(int imported, int skipped, int attachments, Manifest manifest) {
	}
}
