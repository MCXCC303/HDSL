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

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Resolves repository URLs, tarballs, and local directory paths into installable package specifications.
 */
@NotNullByDefault
public final class DshPluginSources {
	/** Pattern matching GitHub {@code owner/name} format. */
	static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
	/** Pattern matching tarball archive extensions. */
	static final Pattern ARCHIVE = Pattern.compile(".*\\.(?:tgz|tar\\.gz)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern SHORTHAND = Pattern.compile("^(?:github|gitlab|bitbucket|gist):",
	                                                         Pattern.CASE_INSENSITIVE);
	private static final Pattern SCP = Pattern.compile("^[^@/\\s]+@[^:/\\s]+:.+$");
	private static final List<String> SCHEMES = List.of("http", "https", "ssh", "git");

	private DshPluginSources() {
	}

	/**
	 * Installs the plugin an address and a revision name.
	 *
	 * @param instance  the instance to install into
	 * @param address   the repository, the tarball, or the directory the plugin is in
	 * @param reference the branch, tag or commit, or {@code null}/blank for the default branch
	 * @param onLine    receives everything the package manager prints, or {@code null}
	 * @return the specification that was installed, which is what the profile recorded
	 * @throws DshException when the address or the revision cannot be written as a
	 *                      specification, the directory holds no package, or the installation fails
	 */
	public static String install(DshInstance instance,
	                             @Nullable String address,
	                             @Nullable String reference,
	                             @Nullable Consumer<String> onLine) throws DshException {
		String spec = specOf(address, reference);
		requirePackage(spec);
		DshPluginInstaller.installSpecs(instance, List.of(spec), onLine);
		LOG.info("Installed " + spec + " into " + instance.id());
		return spec;
	}

	/**
	 * Builds the specification {@code dsh plugin add} is handed.
	 *
	 * @param address   the repository, the tarball, or the directory the plugin is in
	 * @param reference the branch, tag or commit, or {@code null}/blank for the default branch
	 * @return the specification, for example {@code github:owner/name#feat/thing} or {@code link:/src/thing}
	 * @throws DshException when the address or the revision cannot be written as one
	 */
	static String specOf(@Nullable String address, @Nullable String reference) throws DshException {
		String text = address == null ? "" : address.trim();
		if (text.isEmpty()) {
			throw new DshException("No address was given for the plugin");
		}

		if (isLocalPath(text)) {
			String wanted = referenceOf(reference);
			if (!wanted.isEmpty()) {
				throw new DshException(
						"A local directory is installed as it stands, so it takes no" + " branch, tag or commit: " +
						wanted);
			}
			return localSpec(text);
		}

		String base = sourceOf(text);

		String revision = referenceOf(reference);
		if (revision.isEmpty()) {
			// Use revision from address if not explicitly specified
			revision = referenceOf(revisionInLink(text));
		}
		return revision.isEmpty() ? base : base + "#" + revision;
	}

	/**
	 * Reports whether an address names something on this machine rather than something to fetch.
	 *
	 * @param address the address, as it was typed
	 * @return whether it is a local path
	 */
	static boolean isLocalPath(@Nullable String address) {
		String text = address == null ? "" : address.trim();
		return text.startsWith("file:") || text.startsWith("link:") || text.startsWith("/") || text.startsWith("./") ||
		       text.startsWith("../");
	}

	/**
	 * Reports whether a revision can be written into a specification.
	 *
	 * @param reference the revision, as it was typed
	 * @return whether it is one the specification can carry
	 */
	public static boolean isUsableReference(@Nullable String reference) {
		try {
			referenceOf(reference);
			return true;
		} catch (DshException e) {
			return false;
		}
	}

	/**
	 * Returns the source as the part of a specification pnpm takes, without any revision.
	 *
	 * @param address the address, as it was typed
	 * @return the source, for example {@code github:owner/name} or {@code git+https://host/name}
	 * @throws DshException when the address names no source this can install from
	 */
	static String sourceOf(String address) throws DshException {
		String text = withoutRevision(address);
		if (text.isEmpty()) {
			throw new DshException("The address names nothing to install from");
		}

		if (isGitHubLink(text)) {
			String subpath = DshPluginSources.subpathSuffix(text);
			if (!subpath.isEmpty()) {
				// Addresses pointing inside a repository are rejected
				throw new DshException(
						"That address points inside a repository (" + subpath.substring("#path:/".length()) +
						"); give the repository's own address");
			}
			return githubSpec(DshPluginSources.repoOf(text));
		}
		if (text.toLowerCase(Locale.ROOT).startsWith("github:")) {
			return githubSpec(text.substring("github:".length()));
		}
		if (SHORTHAND.matcher(text).find()) {
			// Shorthand schemes pass through directly
			return text;
		}
		if (text.regionMatches(true, 0, "git+", 0, "git+".length())) {
			// git+ prefix already present
			return fetchSpec(text.substring("git+".length()));
		}
		if (SCP.matcher(text).matches()) {
			return text;
		}
		if (REPOSITORY.matcher(text).matches()) {
			// owner/name shorthand for GitHub
			return githubSpec(text);
		}
		return fetchSpec(text);
	}

	/**
	 * Reads the revision field, refusing what cannot be written into a specification.
	 *
	 * @param reference the revision, as it was typed
	 * @return the revision, or an empty string when none was given
	 * @throws DshException when it is written in a way a specification cannot carry
	 */
	static String referenceOf(@Nullable String reference) throws DshException {
		String text = reference == null ? "" : reference.trim();
		if (text.startsWith("#")) {
			text = text.substring(1).trim();
		}
		if (text.isEmpty()) {
			return "";
		}

		if (text.startsWith("-")) {
			throw new DshException("A revision cannot begin with a hyphen: " + text);
		}
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c)) {
				throw new DshException("A revision cannot contain a space: " + text);
			}
			if (c == '#') {
				throw new DshException(
						"A revision cannot contain '#', which is what separates it" + " from the repository: " + text);
			}
		}
		return text;
	}

	/**
	 * Returns the revision an address names, or an empty string when it names none.
	 *
	 * @param address the address
	 * @return the revision
	 */
	static String revisionInLink(String address) {
		String text = address.trim();

		int hash = text.indexOf('#');
		if (hash >= 0) {
			String fragment = text.substring(hash + 1).trim();
			return fragment.startsWith("path:") ? "" : fragment;
		}

		int tree = text.indexOf("/tree/");
		if (tree < 0) {
			return "";
		}
		String rest = text.substring(tree + "/tree/".length()).trim();
		while (rest.endsWith("/")) {
			rest = rest.substring(0, rest.length() - 1);
		}
		return rest.contains("/") ? "" : rest;
	}

	private static String withoutRevision(String address) {
		String text = address.trim();
		int hash = text.indexOf('#');
		return (hash < 0 ? text : text.substring(0, hash)).trim();
	}

	private static String localSpec(String address) throws DshException {
		boolean linked = address.startsWith("link:");
		// Normalize leading slashes
		String path = address.replaceFirst("^(?:file|link):", "").trim().replaceFirst("^/+", "/");

		if (!path.startsWith("/")) {
			throw new DshException("A local path must be absolute, and " + path + " is not");
		}
		if (ARCHIVE.matcher(path).matches()) {
			throw new DshException("A packed plugin is installed with the file button, which copies it" +
			                       " into the instance; this installs a directory: " + path);
		}
		return (linked ? "link:" : "file:") + path;
	}

	private static void requirePackage(String spec) throws DshException {
		if (!spec.startsWith("file:") && !spec.startsWith("link:")) {
			return;
		}
		String path = spec.replaceFirst("^(?:file|link):", "");
		Path directory = Path.of(path);
		if (!Files.isDirectory(directory)) {
			throw new DshException("There is no directory at " + path);
		}
		if (!Files.isRegularFile(directory.resolve("package.json"))) {
			throw new DshException("There is no package.json in " + path + ", so it is not a plugin");
		}
	}

	private static String fetchSpec(String address) throws DshException {
		String text = address.trim();
		while (text.endsWith("/")) {
			text = text.substring(0, text.length() - 1);
		}

		if (ARCHIVE.matcher(text).matches() && text.toLowerCase(Locale.ROOT).startsWith("http")) {
			return text;
		}
		if (!text.contains("://")) {
			throw new DshException(text + " is not an address this installs from: give a repository," +
			                       " an https:// tarball, or the absolute path of a directory");
		}
		String scheme = text.substring(0, text.indexOf("://")).toLowerCase(Locale.ROOT);
		if (!SCHEMES.contains(scheme)) {
			throw new DshException("A repository is fetched over " + String.join(", ", SCHEMES) + ", and " + text +
			                       " is not one of them");
		}
		return "git+" + text;
	}

	private static String githubSpec(String repository) throws DshException {
		String name = repository.trim();
		while (name.endsWith("/")) {
			name = name.substring(0, name.length() - 1);
		}
		if (name.endsWith(".git")) {
			name = name.substring(0, name.length() - ".git".length());
		}
		if (!REPOSITORY.matcher(name).matches()) {
			throw new DshException(name + " is not the owner/name of a GitHub repository");
		}
		return "github:" + name;
	}

	private static boolean isGitHubLink(String address) {
		if (!address.contains("://")) {
			return false;
		}
		try {
			String host = URI.create(address).getHost();
			return host != null && (host.equalsIgnoreCase("github.com") || host.equalsIgnoreCase("www.github.com"));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * Extracts the {@code owner/name} from a repository URL.
	 *
	 * @param url the repository URL
	 * @return the repository name
	 */
	static String repoOf(String url) {
		String trimmed = url.trim();
		int index = trimmed.indexOf("github.com/");
		if (index < 0) {
			return trimmed;
		}
		String rest = trimmed.substring(index + "github.com/".length());
		while (rest.endsWith("/")) {
			rest = rest.substring(0, rest.length() - 1);
		}
		if (rest.endsWith(".git")) {
			rest = rest.substring(0, rest.length() - ".git".length());
		}
		String[] parts = rest.split("/");
		return parts.length < 2 ? rest : parts[0] + "/" + parts[1];
	}

	/**
	 * Returns the monorepo subpath from a URL as a pnpm path suffix.
	 *
	 * @param url the URL
	 * @return the {@code #path:/...} suffix, or empty string
	 */
	static String subpathSuffix(String url) {
		String rest = pathAfterRepository(url);
		if (rest.isEmpty()) {
			return "";
		}
		String[] parts = rest.split("/");
		String subpath = switch (parts[0]) {
			case "tree", "blob" -> parts.length > 2 ? String.join("/", Arrays.copyOfRange(parts, 2, parts.length)) : "";
			case "packages", "plugins", "src" -> rest;
			default -> rest;
		};
		return subpath.isEmpty() ? "" : "#path:/" + subpath;
	}

	private static String pathAfterRepository(String url) {
		String trimmed = url.trim();
		int index = trimmed.indexOf("github.com/");
		if (index < 0) {
			return "";
		}
		String rest = trimmed.substring(index + "github.com/".length());
		if (rest.endsWith(".git")) {
			rest = rest.substring(0, rest.length() - ".git".length());
		}
		while (rest.endsWith("/")) {
			rest = rest.substring(0, rest.length() - 1);
		}
		int hash = rest.indexOf("#path:/");
		if (hash >= 0) {
			return rest.substring(hash + "#path:/".length());
		}
		String[] parts = rest.split("/");
		return parts.length <= 2 ? "" : String.join("/", Arrays.copyOfRange(parts, 2, parts.length));
	}

	/**
	 * Reports whether a prebuilt archive belongs to the repository it is attached to.
	 *
	 * @param tarball the archive URL
	 * @param repo    the entry's repository
	 * @return whether the archive is the repository's own
	 */
	static boolean tarballBelongsTo(String tarball, String repo) {
		if (!REPOSITORY.matcher(repo).matches()) {
			return false;
		}
		String lower = tarball.toLowerCase(Locale.ROOT);
		return lower.contains("github.com/" + repo.toLowerCase(Locale.ROOT) + "/") ||
		       lower.contains("githubusercontent.com/" + repo.toLowerCase(Locale.ROOT) + "/");
	}
}
