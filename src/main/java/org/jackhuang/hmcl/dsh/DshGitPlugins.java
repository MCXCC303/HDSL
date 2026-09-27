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
package org.jackhuang.hmcl.dsh;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Installs a plugin from a Git repository.
///
/// A plugin published to a registry is installed by name and version, which is what the plugin
/// market does. One that lives only in a repository — a fork, a branch somebody is working on,
/// a package nobody ever published — is installed from the repository and a revision instead.
/// pnpm takes that as one specification, so the work here is turning the two things a person
/// has into that specification, and handing it to the installer that already exists
/// ([DshPluginInstaller#installSpecs]).
///
/// Two addresses are understood, and they are the two npm understands:
///
/// - **GitHub**, written as `owner/name`, `github:owner/name`, or any
///   `https://github.com/owner/name` link, becomes `github:owner/name`. The shorthand is what
///   the ecosystem writes into a profile, so a plugin installed here reads the same way as one
///   the market installed.
/// - **Any other host** becomes `git+<address>`. The prefix is not decoration: to npm a bare
///   `https://` address is a **tarball**, not a repository, and installing a repository as
///   though it were one fails in a way that only shows up much later.
///
/// The revision is optional and may be a branch, a tag or a commit — pnpm resolves all three —
/// and it may be written where a browser writes it, as `.../tree/<branch>` in the address or
/// `#<revision>` after it. What the revision field says wins over what the address says, on the
/// grounds that somebody who fills the field in has just said which revision they mean.
@NotNullByDefault
public final class DshGitPlugins {
    /// The shape of a GitHub `owner/name`.
    private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");

    /// The schemes a repository is fetched over.
    private static final List<String> SCHEMES = List.of("http", "https", "ssh", "git");

    private DshGitPlugins() {
    }

    /// Installs the plugin a repository and a revision name.
    ///
    /// @param instance   the instance to install into
    /// @param repository the repository the plugin is in
    /// @param reference  the branch, tag or commit, or `null`/blank for the default branch
    /// @param onLine     receives everything the package manager prints, or `null`
    /// @return the specification that was installed, which is what the profile recorded
    /// @throws DshException when the address or the revision cannot be written as a
    ///                      specification, or the installation fails
    public static String install(DshInstance instance, @Nullable String repository, @Nullable String reference,
                                 @Nullable Consumer<String> onLine) throws DshException {
        String spec = specOf(repository, reference);
        DshPluginInstaller.installSpecs(instance, List.of(spec), onLine);
        LOG.info("Installed " + spec + " into " + instance.id());
        return spec;
    }

    /// Builds the specification `dsh plugin add` is handed.
    ///
    /// @param repository the repository the plugin is in
    /// @param reference  the branch, tag or commit, or `null`/blank for the default branch
    /// @return the specification, for example `github:owner/name#feat/thing`
    /// @throws DshException when the address or the revision cannot be written as one
    static String specOf(@Nullable String repository, @Nullable String reference) throws DshException {
        String address = repository == null ? "" : repository.trim();
        if (address.isEmpty()) {
            throw new DshException("No repository was given for the plugin");
        }

        String base = repositoryOf(address);

        String revision = referenceOf(reference);
        if (revision.isEmpty()) {
            // Nothing was filled in, so whatever the address itself names stands: a link to a
            // branch is a revision somebody chose, and taking the default branch instead would
            // install a different commit than the one the link shows.
            revision = referenceOf(revisionInLink(address));
        }
        return revision.isEmpty() ? base : base + "#" + revision;
    }

    /// Returns the repository as the part of a specification pnpm takes.
    ///
    /// @param address the address, as it was typed
    /// @return the repository, for example `github:owner/name` or `git+https://host/name`
    /// @throws DshException when the address names no repository this can install from
    static String repositoryOf(String address) throws DshException {
        String text = withoutRevision(address);
        if (text.isEmpty()) {
            throw new DshException("The address names no repository");
        }

        if (isGitHubLink(text)) {
            String subpath = DshPluginCatalog.subpathSuffix(text);
            if (!subpath.isEmpty()) {
                // A link to a directory inside a repository is not a repository, and taking the
                // repository root instead would install something other than what the link names.
                throw new DshException("That address points inside a repository ("
                        + subpath.substring("#path:/".length()) + "); give the repository's own address");
            }
            return githubSpec(DshPluginCatalog.repoOf(text));
        }
        if (text.startsWith("github:")) {
            return githubSpec(text.substring("github:".length()));
        }
        if (text.startsWith("git+")) {
            return gitSpec(text.substring("git+".length()));
        }
        if (REPOSITORY.matcher(text).matches()) {
            // `owner/name` on its own is npm's GitHub shorthand, and the marketplace writes it
            // that way too, so it is read as one rather than refused.
            return githubSpec(text);
        }
        return gitSpec(text);
    }

    /// Reads the revision field, refusing what cannot be written into a specification.
    ///
    /// @param reference the revision, as it was typed
    /// @return the revision, or an empty string when none was given
    /// @throws DshException when it is written in a way a specification cannot carry
    static String referenceOf(@Nullable String reference) throws DshException {
        String text = reference == null ? "" : reference.trim();
        if (text.startsWith("#")) {
            text = text.substring(1).trim();
        }
        if (text.isEmpty()) {
            return "";
        }

        if (text.startsWith("-")) {
            // A package manager reads a leading hyphen as an option, so this would install
            // something else entirely rather than fail.
            throw new DshException("A revision cannot begin with a hyphen: " + text);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                throw new DshException("A revision cannot contain a space: " + text);
            }
            if (c == '#') {
                throw new DshException("A revision cannot contain '#', which is what separates it"
                        + " from the repository: " + text);
            }
        }
        return text;
    }

    /// Reports whether a revision can be written into a specification.
    ///
    /// @param reference the revision, as it was typed
    /// @return whether it is one the specification can carry
    public static boolean isUsableReference(@Nullable String reference) {
        try {
            referenceOf(reference);
            return true;
        } catch (DshException e) {
            return false;
        }
    }

    /// Returns the revision an address names, or an empty string when it names none.
    ///
    /// @param address the address
    /// @return the revision
    static String revisionInLink(String address) {
        String text = address.trim();

        int hash = text.indexOf('#');
        if (hash >= 0) {
            String fragment = text.substring(hash + 1).trim();
            // `#path:/packages/thing` names a directory rather than a revision.
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
        // A path after the branch names a directory inside the repository, which is refused
        // when the address is read, so nothing is taken from it here.
        return rest.contains("/") ? "" : rest;
    }

    /// Returns an address without the revision written after it.
    ///
    /// @param address the address
    /// @return the address
    private static String withoutRevision(String address) {
        String text = address.trim();
        int hash = text.indexOf('#');
        return (hash < 0 ? text : text.substring(0, hash)).trim();
    }

    /// Builds the specification for a GitHub repository.
    ///
    /// @param repository the `owner/name`
    /// @return the specification
    /// @throws DshException when the text is not an `owner/name`
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

    /// Builds the specification for a repository on any other host.
    ///
    /// @param address the address
    /// @return the specification
    /// @throws DshException when the address names no repository this can fetch
    private static String gitSpec(String address) throws DshException {
        String text = address.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }

        if (!text.contains("://")) {
            // The `git@host:owner/name` form would need rewriting into a URL, and a line with no
            // scheme at all is a typo rather than a repository.
            throw new DshException(text + " is not a repository address, and this installs from"
                    + " https://, ssh:// or git:// ones");
        }
        String scheme = text.substring(0, text.indexOf("://")).toLowerCase(Locale.ROOT);
        if (!SCHEMES.contains(scheme)) {
            throw new DshException("A repository is fetched over " + String.join(", ", SCHEMES)
                    + ", and " + text + " is not one of them");
        }
        return "git+" + text;
    }

    /// Reports whether an address is a link to GitHub itself.
    ///
    /// The host is read rather than searched for in the text: `https://notgithub.com/owner/name`
    /// contains `github.com/` and is not GitHub, and taking it as GitHub would install from the
    /// wrong host.
    ///
    /// @param address the address
    /// @return whether it is a GitHub link
    private static boolean isGitHubLink(String address) {
        if (!address.contains("://")) {
            return false;
        }
        try {
            String host = java.net.URI.create(address).getHost();
            return host != null
                    && (host.equalsIgnoreCase("github.com") || host.equalsIgnoreCase("www.github.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
