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
import java.util.List;
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

    /// The model ids that accept images, under the names the suppliers publish them as.
    ///
    /// Only ids **known** to be image-capable are here. Guessing the other way is worse than leaving
    /// a model out: `read_image` would then hand an image to a supplier that cannot take one, and the
    /// failure would arrive as a request error rather than as a tool that is simply not offered.
    ///
    /// `deepseek-flash` is the one that matters in practice — the vision-experimental name was routed
    /// onto it, and it is the id the vendor's own model list returns — and `deepseek-v4-flash-vision-exp`
    /// is its catalogue name in the harness. Both are declared image-capable by the harness's own
    /// DeepSeek adapter.
    private static final java.util.Set<String> IMAGE_MODELS = java.util.Set.of(
            "deepseek-flash",
            "deepseek-v4-flash-vision-exp");

    /// The context capacity the harness's own DeepSeek adapter gives every model it serves.
    ///
    /// `dsh-llm-deepseek` declares `DEFAULT_CONTEXT_WINDOW = 1_000_000` and hands it to the models
    /// it ships. A route the launcher writes is not in that adapter's catalogue, so the harness
    /// cannot fill this in for it. Without it `pi-ai` falls back to `DEFAULT_CONTEXT_WINDOW =
    /// 262_144` for a model neither it nor its own catalogue sizes, which is 256 Ki where the same
    /// model gets 1M through the harness's own route.
    private static final int DEEPSEEK_CONTEXT_WINDOW = 1_000_000;

    /// The output cap the harness's own DeepSeek adapter gives every model it serves.
    ///
    /// The same mirror: `dsh-llm-deepseek` declares `DEFAULT_MAX_TOKENS = 256_000`, against
    /// `pi-ai`'s fallback of 32_768.
    private static final int DEEPSEEK_MAX_TOKENS = 256_000;

    /// An account's route, described but not yet written.
    ///
    /// The two halves of writing an overlay take very different amounts of time. Saying what the
    /// supplier is called and where it lives costs nothing; asking it which models it serves is a
    /// request over the network, and is the whole of the wait. Keeping the two apart is what lets a
    /// launch show them as separate steps rather than one long silence.
    public static final class Prepared {
        private final DshInstance instance;
        private final String route;
        private final String api;
        private final String fallbackModel;
        private final @Nullable String endpoint;

        /// Whether this route is the one the harness's own DeepSeek adapter describes.
        ///
        /// A route the launcher writes is not in the harness's catalogue, so the harness cannot
        /// fill the model's reasoning capability or its capacity in itself; the launcher has to say
        /// both. For a vendor the harness does have an adapter for, the launcher says exactly what
        /// that adapter says, so the same model thinks the same way and holds the same context
        /// whichever account it is reached through.
        private final boolean deepSeek;

        private @Nullable List<String> served;

        private Prepared(DshInstance instance, String route, String api, String fallbackModel,
                         @Nullable String endpoint, boolean deepSeek) {
            this.instance = instance;
            this.route = route;
            this.api = api;
            this.fallbackModel = fallbackModel;
            this.endpoint = endpoint;
            this.deepSeek = deepSeek;
        }

        /// The route the harness will know this supplier by.
        public String route() {
            return route;
        }

        /// Asks the supplier which models it serves — the one slow step, and the only thing here
        /// that touches the network.
        ///
        /// **Asked every launch and never remembered.** A list is the vendor's to state and it
        /// changes often: one written down once, by the person or by a launcher that cached it, goes
        /// stale in both directions, showing models that are gone and hiding the ones that arrived.
        /// And a route the launcher writes is not in the harness's catalogue, so the harness will not
        /// fill this in either — while a route with no models cannot be registered at all — so asking
        /// is the only source, not a convenience.
        ///
        /// @param account the account whose supplier is asked
        public void resolveModels(DshAccount account) {
            this.served = account.fetchModels();
        }

        /// Writes the overlay.
        ///
        /// @return the file to pass to `--patch`
        /// @throws DshException when the file cannot be written
        public Path write() throws DshException {
            String yaml = render();
            Path directory = directory();
            Path file = directory.resolve("account-" + instance.id() + "-"
                    + Long.toHexString(System.nanoTime()) + ".yml");
            try {
                Files.createDirectories(directory);
                Files.writeString(file, yaml, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new DshException("Failed to write the account overlay " + file, e);
            }
            return file;
        }

        /// Renders the overlay's text, without writing it.
        ///
        /// Kept apart from [`write`][#write] so a test can read exactly what a launch would put in
        /// the file: the file itself goes to the launcher's own data directory, which is not a
        /// test's to touch.
        ///
        /// @return the YAML to pass through `--patch`
        String render() {
            String model = fallbackModel;
            StringBuilder yaml = new StringBuilder();
            yaml.append("# Written by Hello DeepSeek! Launcher for one launch; removed when it ends.\n");
            yaml.append("# It carries no key: the key travels in the environment as ")
                    .append(KEY_ENVIRONMENT_VARIABLE).append(".\n");
            yaml.append("- id: llm-pi-ai\n");
            yaml.append("  config:\n");
            yaml.append("    providers:\n");
            yaml.append("      ").append(YamlScalar.of(route)).append(":\n");
            yaml.append("        apiKeyEnv: ").append(KEY_ENVIRONMENT_VARIABLE).append("\n");
            yaml.append("        api: ").append(api).append("\n");
            if (endpoint != null && !endpoint.isBlank()) {
                yaml.append("        baseURL: ").append(YamlScalar.of(endpoint.trim())).append("\n");
            }
            if (deepSeek) {
                // Provider-level, not per model. `dsh-llm-deepseek` states a `defaultContextWindow`
                // and a `defaultMaxTokens` for the whole route, and every model it does not
                // otherwise size takes those. `pi-ai` sizes a model from its entry, then its own
                // catalogue, then this pair — its own fallbacks being 262_144 and 32_768. So this
                // is what makes the same model hold the same context and answer in the same length
                // through either route.
                yaml.append("        defaultContextWindow: ")
                        .append(DEEPSEEK_CONTEXT_WINDOW).append("\n");
                yaml.append("        defaultMaxTokens: ")
                        .append(DEEPSEEK_MAX_TOKENS).append("\n");
            }
            yaml.append("        models:\n");
            // Which models this route serves, **asked of the vendor every launch and never remembered**.
            //
            // Both halves of that matter. A route the launcher writes is not in the harness's catalogue,
            // so the harness will not fill this in the way it does for a vendor it knows — and a route
            // with no models cannot be registered at all — so asking is the only source, not a
            // convenience. And a list is the vendor's to state and it changes often: one written down
            // once, by the person or by a launcher that cached it, goes stale in both directions, showing
            // models that are gone and hiding the ones that arrived.
            //
            // What this replaced was inventing a model called `default` whenever no model had been named
            // — which was every official account, since the form does not ask those for one. The result
            // was a supplier whose only model was called `default`: not a model any vendor serves, and
            // not one a request can be made against.
            //
            // A model stored on the account survives as a fallback for a vendor that cannot be reached at
            // this moment — something has to be written, and a name this account used before guesses
            // better than `default` does. It is a fallback, not a source; nothing asks for it any more.
            List<String> models = new java.util.ArrayList<>(served == null ? List.of() : served);
            if (models.isEmpty() && !model.isEmpty()) {
                models.add(model);
            }
            if (models.isEmpty()) {
                models.add("default");
            }
            for (String one : models) {
                yaml.append("          - id: ").append(YamlScalar.of(one)).append("\n");
                yaml.append("            name: ").append(YamlScalar.of(one)).append("\n");
                if (IMAGE_MODELS.contains(one)) {
                    // A capability the harness cannot fill in for itself, because this route is one it
                    // has never heard of: `input` otherwise falls back to `["text"]`, and the harness's
                    // `read_image` refuses to hand an image to a model that "does not declare image
                    // input". The harness's own DeepSeek adapter declares this same model with
                    // `inputModalities: ["text", "image"]`, so the model reached through a supplier of
                    // the person's own is written the same way — the same model should not answer with
                    // and without eyes depending on which account it was launched with.
                    yaml.append("            input:\n");
                    yaml.append("              - text\n");
                    yaml.append("              - image\n");
                }
                if (deepSeek) {
                    // The harness's DeepSeek adapter offers these four levels for every model it
                    // serves, and reasoningEfforts is how a route it has never heard of says the
                    // same. Without it the model carries no reasoning metadata at all, and the
                    // model picker offers no effort control — only the provider's default. off
                    // carries no wire value because not thinking is the parameter's absence rather
                    // than a value to send; the other three are DeepSeek's own spellings.
                    yaml.append("            reasoningEfforts:\n");
                    yaml.append("              off: null\n");
                    yaml.append("              low: low\n");
                    yaml.append("              high: high\n");
                    yaml.append("              max: max\n");
                }
            }

            return yaml.toString();
        }
    }

    /// Describes an account's route, without asking the supplier anything.
    ///
    /// @param instance the instance being launched
    /// @param account  the account, or `null` for none
    /// @return what to write, or empty when there is nothing to add
    public static Optional<Prepared> prepare(DshInstance instance, @Nullable DshAccount account) {
        if (account == null || !account.carriesAKey()) {
            return Optional.empty();
        }
        DshVendor vendor = account.vendor();

        // The route is named after the **account**, not after the vendor it borrows its settings
        // from. That is the whole shape of this: the harness is handed a supplier of the person's
        // own, named what they called it, speaking the protocol and living at the address of the
        // vendor they picked. Opening the harness then shows one supplier — their supplier — with
        // that vendor's models behind it, rather than a generic "deepseek" that could be anybody's.
        //
        // Which is also why the person is asked for a name that can be a route: a route name is an
        // identifier, and a name with a space in it would arrive in the harness as something it
        // cannot address. The dialog checks that before it gets here.
        return Optional.of(new Prepared(instance, account.displayName(),
                vendor == null ? "openai-completions" : vendor.api(),
                account.modelOrDefault(), account.endpoint(), isDeepSeek(vendor, account.endpoint())));
    }

    /// Reports whether this route is the one the harness's own DeepSeek adapter describes.
    ///
    /// Only the DeepSeek adapter is mirrored, because that is the one the launcher's account
    /// plumbing is built around and the one whose models are known to think. The test is the
    /// harness's own: the vendor id, or an address under deepseek.com, which is how pi-ai decides
    /// to speak DeepSeek's dialect. A route neither test recognises is left alone — declaring
    /// reasoning levels for a model that has none would offer a control that does not work, and
    /// claiming a capacity it does not have could let a request overrun it.
    ///
    /// @param vendor   the route's vendor, or null
    /// @param endpoint the route's address, or null
    /// @return whether to mirror the adapter's reasoning levels and capacities
    private static boolean isDeepSeek(@Nullable DshVendor vendor, @Nullable String endpoint) {
        if (vendor != null && "deepseek".equals(vendor.id())) {
            return true;
        }
        String host = DshVendor.hostOf(endpoint);
        return host != null && (host.equals("deepseek.com") || host.endsWith(".deepseek.com"));
    }

    /// Writes the overlay for an account, asking its supplier for the models on the way.
    ///
    /// @param instance the instance being launched
    /// @param account  the account, or `null` for none
    /// @return the file to pass to `--patch`, or empty when there is nothing to add
    /// @throws DshException when the file cannot be written
    public static Optional<Path> write(DshInstance instance, @Nullable DshAccount account)
            throws DshException {
        Optional<Prepared> prepared = prepare(instance, account);
        if (prepared.isEmpty()) {
            return Optional.empty();
        }
        Prepared overlay = prepared.get();
        overlay.resolveModels(account);
        return Optional.of(overlay.write());
    }

    /// Removes overlays a previous launch of an instance left behind.
    ///
    /// An overlay is removed when its process ends, which is every ending except one: a launcher
    /// killed outright never runs the listener that does it. What is left names no secret and
    /// nothing reads it again — a launch writes a file with a new name — so this is tidiness. It is
    /// done anyway, because the file names the person's supplier and an account is not something to
    /// keep lying about in a directory nobody looks at.
    ///
    /// @param instanceId the instance about to be launched
    public static void removeStale(String instanceId) {
        Path directory = directory();
        if (!Files.isDirectory(directory)) {
            return;
        }
        String prefix = "account-" + instanceId + "-";
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            files.filter(file -> {
                String name = file.getFileName().toString();
                return name.startsWith(prefix) && name.endsWith(".yml");
            }).forEach(DshAccountOverlay::remove);
        } catch (IOException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.info(
                    "Could not look through " + directory + " for old overlays", e);
        }
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
    static Path directory() {
        return org.jackhuang.hmcl.Metadata.HMCL_USER_HOME.resolve(DIRECTORY);
    }

}
