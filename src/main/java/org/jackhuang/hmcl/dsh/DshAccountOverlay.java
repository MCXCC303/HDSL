/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/// The account an instance is launched with, as a patch overlay.
///
/// The harness asks the user to configure a model supplier before it will answer anything. The
/// launcher can spare them that: it holds a key already — that is what an account is — so it hands
/// the harness a route for it at launch.
///
/// It does so through `--patch`, an overlay applied over the composed profile tree, rather than by
/// writing the harness's settings file. That choice is the whole design:
///
/// - **Nothing of the user's is touched.** The harness rewrites `settings.yaml` while it runs, and
///   it does so under a cross-process lock with a leaf-level diff that preserves comments. An
///   outside writer that took a copy and put it back afterwards would silently discard every change
///   the user made through the interface during the run — and could race the harness's own write.
///   An overlay file the launcher owns cannot do that.
/// - **The key is not on disk.** The overlay names an environment variable; the value travels in
///   the child's environment, which is the highest-precedence source and leaves no trace.
/// - **Undoing it is deleting a file.** The overlay lives in the launcher's own cache directory
///   under a name only this launch knows, and is removed when the instance stops. If the launcher
///   dies first, the file is a few hundred bytes in a temporary directory.
///
/// The key is passed in the environment rather than written into the overlay because an overlay is
/// a file: a key in it would be a key on disk, in a place the user never chose.
@NotNullByDefault
public final class DshAccountOverlay {
    /// The variable the overlay tells the harness to read the key from.
    ///
    /// Named for this launcher rather than for the vendor, because the vendor's own variable may
    /// already be set in the user's environment with a different key — and an inherited variable
    /// outranks everything, so a route pointing at the vendor's name would silently use the wrong
    /// key. This one is set for this child only.
    public static final String KEY_ENVIRONMENT_VARIABLE = "HDSL_LAUNCH_API_KEY";

    /// Where overlays are written, inside the launcher's data directory.
    private static final String DIRECTORY = "launch-overlays";

    private DshAccountOverlay() {
    }

    /// Writes the overlay for an account.
    ///
    /// @param instance the instance being launched
    /// @param account  the account, or `null` for none
    /// @return the file to pass to `--patch`, or empty when there is nothing to add
    /// @throws DshException when the file cannot be written
    public static Optional<Path> write(DshInstance instance, @Nullable DshAccount account)
            throws DshException {
        if (account == null || account.apiKey() == null || account.apiKey().isBlank()) {
            return Optional.empty();
        }
        DshVendor vendor = account.vendor();
        String api = vendor == null ? "openai-completions" : vendor.api();
        String endpoint = account.endpoint();

        // The tree the harness composes: a list of layers, each naming a plugin by id and giving the
        // section it wants configured. A route needs all three of protocol, address and models —
        // a hand-written route that omits any of them fails registration, which fails the launch.
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Written by Hello DeepSeek Launcher for one launch; removed when it ends.\n");
        yaml.append("# It carries no key: the key travels in the environment as ")
                .append(KEY_ENVIRONMENT_VARIABLE).append(".\n");
        yaml.append("- id: llm-pi-ai\n");
        yaml.append("  config:\n");
        yaml.append("    providers:\n");
        yaml.append("      ").append(quote(account.vendorId())).append(":\n");
        yaml.append("        apiKeyEnv: ").append(KEY_ENVIRONMENT_VARIABLE).append("\n");
        yaml.append("        api: ").append(api).append("\n");
        if (endpoint != null && !endpoint.isBlank()) {
            yaml.append("        baseURL: ").append(quote(endpoint.trim())).append("\n");
        }
        yaml.append("        models:\n");
        // One placeholder model. Which models a vendor serves is its own answer, and the harness
        // discovers them from the catalogue for a vendor it knows; this entry is what makes the
        // route registrable when it does not.
        yaml.append("          - id: default\n");
        yaml.append("            name: ").append(quote(account.displayName())).append("\n");

        Path directory = directory();
        Path file = directory.resolve("account-" + instance.id() + "-"
                + Long.toHexString(System.nanoTime()) + ".yml");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, yaml.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DshException("Failed to write the account overlay " + file, e);
        }
        return Optional.of(file);
    }

    /// Removes an overlay.
    ///
    /// Failure is logged and ignored: the file is in the launcher's own directory and a few hundred
    /// bytes, so leaving one behind is untidy rather than harmful, and refusing to stop an instance
    /// because a temporary file could not be deleted would be worse.
    ///
    /// @param overlay the file, or `null`
    public static void remove(@Nullable Path overlay) {
        if (overlay == null) {
            return;
        }
        try {
            Files.deleteIfExists(overlay);
        } catch (IOException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.info(
                    "Could not remove the account overlay " + overlay, e);
        }
    }

    /// Returns where overlays are written.
    ///
    /// @return the directory, which may not exist
    private static Path directory() {
        return org.jackhuang.hmcl.Metadata.HMCL_USER_HOME.resolve(DIRECTORY);
    }

    /// Quotes a YAML scalar when it needs it.
    ///
    /// Values here are ids, names and addresses chosen by the user, so they can contain the
    /// characters that mean something to YAML — a colon in a URL, a hash in a name. Quoting
    /// whenever the value is not plainly safe keeps a route name from changing the document's shape.
    ///
    /// @param value the value
    /// @return the scalar as YAML
    private static String quote(String value) {
        String text = value == null ? "" : value;
        if (text.matches("[A-Za-z0-9._/-]+")) {
            return text;
        }
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
