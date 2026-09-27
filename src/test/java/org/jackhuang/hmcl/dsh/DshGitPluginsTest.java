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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the specification an installed-from-a-repository plugin is written as.
///
/// This is the string the profile ends up carrying, so it is what decides whether a plugin
/// installed from a fork of a branch is still there on the next boot — and whether it is the
/// same plugin. The refusals matter as much as the readings: an address this cannot name
/// correctly has to say so, because the alternative is a package manager quietly installing
/// the default branch (or, for a bare `https://` address, a tarball) instead.
class DshGitPluginsTest {
    @Test
    void aGitHubLinkBecomesTheSpecificationTheProfileWrites() throws Exception {
        assertEquals("github:dsh-market/dsh-market#main",
                DshGitPlugins.specOf("https://github.com/dsh-market/dsh-market", "main"));
        assertEquals("github:dsh-market/dsh-market#180c3144da8eb4229cacb843aced229ea55912fc",
                DshGitPlugins.specOf("https://github.com/dsh-market/dsh-market",
                        "180c3144da8eb4229cacb843aced229ea55912fc"),
                "a commit is a revision like any other, and is what pins an installation to one");
    }

    @Test
    void aLinkThatCarriesItsRevisionIsTakenAsItIs() throws Exception {
        // Which is how a link is copied out of a browser, so it has to work with the revision
        // field left alone.
        assertEquals("github:dsh-market/dsh-market#main",
                DshGitPlugins.specOf("https://github.com/dsh-market/dsh-market#main", ""));
        assertEquals("github:dsh-market/dsh-market#main",
                DshGitPlugins.specOf("https://github.com/dsh-market/dsh-market#main", null));
    }

    @Test
    void whatTheRevisionFieldSaysWinsOverTheLink() throws Exception {
        assertEquals("github:owner/name#v1.2.3", DshGitPlugins.specOf("https://github.com/owner/name#main", "v1.2.3"),
                "somebody who fills the field in has just said which revision they mean");
    }

    @Test
    void aBranchNamedInALinkToItIsRead() throws Exception {
        assertEquals("github:owner/name#main", DshGitPlugins.specOf("https://github.com/owner/name/tree/main", ""));
    }

    @Test
    void aRepositoryWithNoRevisionFollowsItsDefaultBranch() throws Exception {
        assertEquals("github:owner/name", DshGitPlugins.specOf("https://github.com/owner/name", ""));
        assertEquals("github:owner/name", DshGitPlugins.specOf("https://github.com/owner/name", "   "));
        assertEquals("github:owner/name", DshGitPlugins.specOf("https://github.com/owner/name/", null));
        assertEquals("github:owner/name", DshGitPlugins.specOf("https://github.com/owner/name.git", null));
        assertEquals("github:owner/name", DshGitPlugins.specOf("github:owner/name", null),
                "the shorthand the marketplace writes is one of the addresses this takes");
        assertEquals("github:owner/name", DshGitPlugins.specOf("  owner/name  ", null),
                "and so is the bare owner/name npm reads as GitHub");
    }

    @Test
    void aRepositoryOnAnotherHostIsInstalledAsGit() throws Exception {
        // The `git+` prefix is not decoration: npm reads a bare https:// address as a tarball.
        assertEquals("git+https://gitlab.com/owner/name#main",
                DshGitPlugins.specOf("https://gitlab.com/owner/name", "main"));
        assertEquals("git+https://git.example.com/owner/name",
                DshGitPlugins.specOf("git+https://git.example.com/owner/name", ""));
        assertEquals("git+ssh://git@example.com/owner/name.git#v1",
                DshGitPlugins.specOf("ssh://git@example.com/owner/name.git", "v1"));
        assertEquals("git+https://notgithub.com/owner/name",
                DshGitPlugins.specOf("https://notgithub.com/owner/name", ""),
                "a host whose name merely contains github.com is not GitHub");
    }

    @Test
    void whatCannotBeInstalledFromIsRefused() {
        assertThrows(DshException.class, () -> DshGitPlugins.specOf(null, "main"));
        assertThrows(DshException.class, () -> DshGitPlugins.specOf("", ""));
        assertThrows(DshException.class, () -> DshGitPlugins.specOf("   ", ""));
        assertThrows(DshException.class, () -> DshGitPlugins.specOf("not an address", ""));
        assertThrows(DshException.class, () -> DshGitPlugins.specOf("ftp://example.com/owner/name", ""),
                "a repository is fetched over http, https, ssh or git");
        assertThrows(DshException.class, () -> DshGitPlugins.specOf("https://github.com/owner", ""),
                "a link to an account is not a repository");
        assertThrows(DshException.class, () -> DshGitPlugins.specOf("https://github.com/owner/name/tree/main/packages/thing", ""),
                "a link that points inside a repository names a directory, not a plugin to install");
        assertThrows(DshException.class, () -> DshGitPlugins.specOf("#feat/thing", ""),
                "a revision with no repository in front of it names nothing");
    }

    @Test
    void aRevisionThatCannotBeWrittenIntoASpecificationIsRefused() {
        assertThrows(DshException.class, () -> DshGitPlugins.specOf("https://github.com/owner/name", "feat thing"),
                "a revision with a space in it is a typo, not a branch");
        assertThrows(DshException.class, () -> DshGitPlugins.specOf("https://github.com/owner/name", "feat#thing"),
                "the '#' is what separates the revision from the repository, so it cannot be inside one");
        assertThrows(DshException.class, () -> DshGitPlugins.specOf("https://github.com/owner/name", "--depth=1"),
                "a leading hyphen is read as an option by the package manager, which would install something else");
    }

    @Test
    void aRevisionWrittenWithItsOwnHashIsRead() throws Exception {
        assertEquals("github:owner/name#main", DshGitPlugins.specOf("https://github.com/owner/name", "#main"));
        assertEquals("github:owner/name#v1.0.0", DshGitPlugins.specOf("https://github.com/owner/name", "  v1.0.0  "),
                "what was pasted with spaces around it is the revision that was meant");
    }

    @Test
    void whatTheDialogTellsSomebodyWhileTheyTypeIsTheSameRule() {
        // The field is checked as it is typed rather than when the button is pressed, so this is
        // what "the revision is usable" means — including the empty one, which is the default
        // branch and the only one of the two fields that may be left alone.
        assertTrue(DshGitPlugins.isUsableReference(null));
        assertTrue(DshGitPlugins.isUsableReference(""));
        assertTrue(DshGitPlugins.isUsableReference("   "));
        assertTrue(DshGitPlugins.isUsableReference("#main"));
        assertTrue(DshGitPlugins.isUsableReference("feat/claude-flavor-bundle"));
        assertTrue(DshGitPlugins.isUsableReference("180c3144da8eb4229cacb843aced229ea55912fc"));

        assertFalse(DshGitPlugins.isUsableReference("feat thing"));
        assertFalse(DshGitPlugins.isUsableReference("feat#thing"));
        assertFalse(DshGitPlugins.isUsableReference("--depth=1"));
    }
}
