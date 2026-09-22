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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The community's plugin catalogue, and what it takes to install from it.
///
/// DeepSeek Harness itself has no plugin directory: `dsh plugin` forwards its
/// arguments to pnpm, and the only way to find a plugin is to know its name. The
/// catalogue the marketplace plugin reads is the community's answer, and it is a
/// plain JSON document on a plain HTTPS host, needing no key and answering with
/// `access-control-allow-origin: *`. Reading it here rather than driving the
/// marketplace keeps this launcher's plugin page working on an instance that has
/// no marketplace installed, which is the state every new instance starts in.
///
/// What the document carries per plugin is what the page shows: the npm name when
/// there is one, the repository, a category, localised descriptions, stars and a
/// download count. Just under half the entries have no npm name and can only be
/// installed from their repository, which is the slow path; the page says which
/// is which rather than hiding the difference.
@NotNullByDefault
public final class DshPluginCatalog {
    /// Where the catalogue is published.
    public static final String CATALOG_URL = "https://awesome-dsh-plugin.com/plugins.json";

    /// The address actually read, which the `hdsl.pluginCatalog` property may
    /// point at a mirror of the catalogue. The marketplace's own deployment reads
    /// `DSHM_REGISTRY_URL` for the same reason: one host serving one JSON document
    /// is a single point of failure, and a mirror of it should be usable without
    /// rebuilding.
    ///
    /// @return the address to read
    public static String catalogUrl() {
        // Three sources, most specific first: the property is for a developer who wants one
        // run to read somewhere else, the setting is for a network that cannot reach the
        // community host, and the constant is what the ecosystem publishes.
        String property = System.getProperty("hdsl.pluginCatalog");
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        try {
            String setting = org.jackhuang.hmcl.setting.SettingsManager.settings()
                    .pluginCatalogUrlProperty().get();
            if (setting != null && !setting.isBlank()) {
                return setting.trim();
            }
        } catch (RuntimeException e) {
            LOG.warning("Could not read the catalogue address from the settings", e);
        }
        return CATALOG_URL;
    }

    /// The hosts a catalogue URL may name for a prebuilt release archive.
    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");

    /// The npm names a plugin entry may name.
    private static final Pattern NPM_NAME = Pattern.compile("^(@[a-z0-9-~][a-z0-9-._~]*/)?[a-z0-9-~][a-z0-9-._~]*$");

    private DshPluginCatalog() {
    }

    /// One plugin the catalogue lists.
    ///
    /// @param name        the plugin's own name
    /// @param owner       the repository owner
    /// @param url         the repository URL
    /// @param category    the catalogue's category
    /// @param description the description in English
    /// @param descriptionZh the description in Chinese, or `null`
    /// @param npm         the npm package name, or `null` for a repository-only plugin
    /// @param version     the version the catalogue last saw, or `null`
    /// @param stars       the repository's stars
    /// @param downloads   the package's recent downloads, zero when it has none
    /// @param tarball     a prebuilt release archive, or `null`
    /// @param added       the date the entry joined the catalogue, or `null`
    public record Plugin(
            String name,
            String owner,
            String url,
            String category,
            @Nullable String description,
            @Nullable String descriptionZh,
            @Nullable String npm,
            @Nullable String version,
            int stars,
            int downloads,
            @Nullable String tarball,
            @Nullable String added) {

        /// Returns the plugin's name without its owner.
        ///
        /// The catalogue carries both, and the name alone is what a list row has
        /// room for; the repository is what tells two plugins of one name apart.
        ///
        /// @return the display name
        public String displayName() {
            return name;
        }

        /// Returns the description in the language the launcher is showing.
        ///
        /// @return the description, or `null` when the catalogue has none
        public @Nullable String localizedDescription() {
            boolean chinese = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh");
            if (chinese && descriptionZh != null && !descriptionZh.isBlank()) {
                return descriptionZh;
            }
            return description == null || description.isBlank() ? descriptionZh : description;
        }

        /// Returns whether the plugin has a repository to link to.
        ///
        /// @return whether the entry names a usable repository
        public boolean hasRepository() {
            return REPO.matcher(repoOf(url)).matches();
        }

        /// Returns the specification to hand to `dsh plugin add`, if any.
        ///
        /// The marketplace resolves an entry the same way, and for the same
        /// reasons: the published package first, because a registry install is
        /// the only one that can be verified and updated by version; then a
        /// prebuilt archive attached to the repository's own release, which is
        /// faster than building; and finally the repository itself for the two
        /// thousand entries that publish nothing else.
        ///
        /// @return the specification, or `null` when nothing can be installed
        public @Nullable String installSpec() {
            if (npm != null && NPM_NAME.matcher(npm).matches()) {
                return version == null || version.isBlank() ? npm : npm + "@" + version;
            }
            String repo = repoOf(url);
            if (!REPO.matcher(repo).matches()) {
                return null;
            }
            if (tarball != null && tarballBelongsTo(tarball, repo)) {
                return tarball;
            }
            return "github:" + repo + subpathSuffix(url);
        }

        /// Returns what the page says about how this plugin is installed.
        ///
        /// @return the source description
        public String sourceKind() {
            if (npm != null && NPM_NAME.matcher(npm).matches()) {
                return "npm";
            }
            return tarball != null && tarballBelongsTo(tarball, repoOf(url)) ? "release" : "github";
        }
    }

    /// A catalogue as one document.
    ///
    /// @param updated    the date the catalogue was generated, or `null`
    /// @param plugins    the plugins, in the order the catalogue lists them
    /// @param categories the categories, in alphabetical order
    public record Catalog(@Nullable String updated, List<Plugin> plugins, List<String> categories) {
        /// Returns the plugins whose category is one of the given ones.
        ///
        /// The page asks with the empty string for every category, which is what
        /// the original's category picker does with its own "all" entry.
        ///
        /// @param category the category to keep, or an empty string for all
        /// @return the matching plugins
        public List<Plugin> inCategory(String category) {
            if (category == null || category.isEmpty()) {
                return plugins;
            }
            return plugins.stream().filter(plugin -> category.equals(plugin.category())).toList();
        }
    }

    /// The catalogue the launcher last read, if it read one.
    private static volatile @Nullable Catalog lastCatalog;

    /// Looks a package up in the catalogue the launcher last read.
    ///
    /// The catalogue is already held for the market page, so a page that wants to
    /// describe one plugin can ask for it rather than fetching four megabytes of
    /// entries again.
    ///
    /// @param packageName the npm package name
    /// @return the entry, or empty when the catalogue does not hold it
    public static java.util.Optional<Plugin> find(String packageName) {
        Catalog catalog = lastCatalog;
        if (catalog == null || packageName == null || packageName.isBlank()) {
            return java.util.Optional.empty();
        }
        for (Plugin plugin : catalog.plugins()) {
            if (packageName.equals(plugin.npm()) || packageName.equals(plugin.name())) {
                return java.util.Optional.of(plugin);
            }
        }
        return java.util.Optional.empty();
    }

    /// Reads the catalogue.
    ///
    /// @return the catalogue
    /// @throws DshException when it cannot be read or does not parse
    public static Catalog fetch() throws DshException {
        String url = catalogUrl();
        Path cached = cachedFile(url);
        String body;
        try {
            body = NetworkUtils.doGet(URI.create(url));
            // Kept, so that the next fetch has something to fall back on.
            try {
                Files.createDirectories(cached.getParent());
                Files.writeString(cached, body);
            } catch (IOException e) {
                LOG.warning("Could not keep a copy of the plugin catalogue", e);
            }
        } catch (IOException | RuntimeException e) {
            // The address is in the message for the same reason it is in the Node index's: a
            // network that reaches one host may not reach another, and which host failed is the
            // one thing that says so. But the last copy is worth more than nothing: a launcher
            // that cannot reach the catalogue can still show what it showed yesterday.
            String kept = readCached(cached);
            if (kept == null) {
                throw new DshException("Failed to read the plugin catalogue from " + url
                        + " (" + e.getMessage() + ")", e);
            }
            LOG.info("The plugin catalogue could not be fetched; using the copy kept at " + cached);
            body = kept;
        }
        try {
            Catalog catalog = parse(body);
            lastCatalog = catalog;
            return catalog;
        } catch (RuntimeException e) {
            throw new DshException("The plugin catalogue had an unexpected shape", e);
        }
    }

    /// Returns the directory the launcher keeps fetched catalogues in.
    ///
    /// @return the directory, which may not exist
    public static Path cacheDirectory() {
        try {
            String configured = org.jackhuang.hmcl.setting.SettingsManager.settings()
                    .cacheDirectoryProperty().get();
            if (configured != null && !configured.isBlank()) {
                return Path.of(configured.trim());
            }
        } catch (RuntimeException e) {
            LOG.warning("Could not read the cache directory", e);
        }
        return DshPaths.CATALOG;
    }

    /// Returns the file one address's copy is kept in.
    ///
    /// The name comes from the address, so pointing the launcher at another catalogue does not
    /// overwrite the copy of the first one.
    ///
    /// @param url the address
    /// @return the file, which may not exist
    static Path cachedFile(String url) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder name = new StringBuilder();
            for (byte value : digest.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                name.append(String.format("%02x", value));
            }
            return cacheDirectory().resolve(name.substring(0, 16) + ".json");
        } catch (java.security.NoSuchAlgorithmException e) {
            return cacheDirectory().resolve("catalog.json");
        }
    }

    /// Reads a kept copy.
    ///
    /// @param file the file
    /// @return its text, or `null` when it is not there or cannot be read
    private static @org.jetbrains.annotations.Nullable String readCached(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /// Removes every kept copy.
    ///
    /// @return how many files were removed
    public static int clearCache() {
        Path directory = cacheDirectory();
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int removed = 0;
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                try {
                    if (Files.isDirectory(file)) {
                        continue;
                    }
                    Files.delete(file);
                    removed++;
                } catch (IOException e) {
                    LOG.warning("Could not remove " + file, e);
                }
            }
        } catch (IOException e) {
            LOG.warning("Could not list " + directory, e);
        }
        return removed;
    }

    /// Parses a catalogue document.
    ///
    /// @param body the JSON document
    /// @return the catalogue
    public static Catalog parse(String body) {
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("the catalogue is not an object");
        }
        JsonObject root = parsed.getAsJsonObject();

        List<Plugin> plugins = new ArrayList<>();
        JsonElement entries = root.get("plugins");
        if (entries != null && entries.isJsonArray()) {
            for (JsonElement element : entries.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                Plugin plugin = readPlugin(element.getAsJsonObject());
                if (plugin != null) {
                    plugins.add(plugin);
                }
            }
        }

        // Categories come from the entries rather than from the document's own
        // list, so a category the document forgot is still reachable.
        Map<String, String> byKey = new TreeMap<>();
        for (Plugin plugin : plugins) {
            byKey.putIfAbsent(plugin.category().toLowerCase(Locale.ROOT), plugin.category());
        }

        return new Catalog(asString(root, "updated"), List.copyOf(plugins),
                List.copyOf(new LinkedHashSet<>(byKey.values())));
    }

    /// Sorts plugins the way the original's sort picker does.
    ///
    /// @param plugins the plugins
    /// @param sort    the sort: `downloads`, `stars` or `added`
    /// @return the sorted list
    public static List<Plugin> sorted(List<Plugin> plugins, String sort) {
        Comparator<Plugin> comparator = switch (sort == null ? "" : sort) {
            case "stars" -> Comparator.<Plugin>comparingInt(Plugin::stars).reversed();
            case "added" -> Comparator.comparing(Plugin::added, Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.<Plugin>comparingInt(Plugin::downloads).reversed();
        };
        return plugins.stream().sorted(comparator.thenComparing(Plugin::name)).toList();
    }

    /// Reads one entry.
    ///
    /// @param object the entry
    /// @return the plugin, or `null` when the entry names nothing installable
    private static @Nullable Plugin readPlugin(JsonObject object) {
        String name = asString(object, "name");
        String url = asString(object, "url");
        if (name == null || name.isBlank() || url == null || url.isBlank()) {
            return null;
        }

        String description = null;
        String descriptionZh = null;
        JsonElement descriptions = object.get("description");
        if (descriptions != null && descriptions.isJsonObject()) {
            JsonObject localized = descriptions.getAsJsonObject();
            description = asString(localized, "en");
            descriptionZh = asString(localized, "zh");
        }

        return new Plugin(
                name,
                orEmpty(asString(object, "owner")),
                url,
                orEmpty(asString(object, "category")),
                description,
                descriptionZh,
                asString(object, "npm"),
                asString(object, "version"),
                asInt(object, "stars"),
                asInt(object, "downloads"),
                asString(object, "tarball"),
                asString(object, "added"));
    }

    /// Returns the `owner/name` a repository URL names.
    ///
    /// @param url the URL
    /// @return the repository, or the URL unchanged when it names no repository
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

    /// Returns the subpath a repository URL names, as a pnpm suffix.
    ///
    /// A monorepo can hold several plugins, and the catalogue points at the one it
    /// means with a path: `…/owner/repo/tree/<branch>/packages/plugin`, which pnpm
    /// takes as `#path:/packages/plugin`. Some four hundred of the four thousand
    /// entries are named that way, so getting this wrong would install the wrong
    /// package for a tenth of the catalogue.
    ///
    /// @param url the entry's URL
    /// @return the suffix, or an empty string when the URL names the repository root
    static String subpathSuffix(String url) {
        String rest = pathAfterRepository(url);
        if (rest.isEmpty()) {
            return "";
        }
        String[] parts = rest.split("/");
        // `tree`/`blob` name a branch before the path; a plain path is taken as is,
        // because a URL that already points into the repository means that path.
        String subpath = switch (parts[0]) {
            case "tree", "blob" -> parts.length > 2 ? String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length)) : "";
            case "packages", "plugins", "src" -> rest;
            default -> rest;
        };
        return subpath.isEmpty() ? "" : "#path:/" + subpath;
    }

    /// Returns the path a repository URL carries after `owner/name`.
    ///
    /// @param url the URL
    /// @return the path, or an empty string
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
        return parts.length <= 2 ? "" : String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length));
    }

    /// Reports whether a prebuilt archive belongs to the repository it is
    /// attached to.
    ///
    /// The check is the point of the rule rather than a formality: without it an
    /// entry could name a trusted repository and hand out an archive built
    /// somewhere else. The original refuses exactly this, and so does this.
    ///
    /// @param tarball the archive URL
    /// @param repo    the entry's repository
    /// @return whether the archive is the repository's own
    static boolean tarballBelongsTo(String tarball, String repo) {
        if (!REPO.matcher(repo).matches()) {
            return false;
        }
        String lower = tarball.toLowerCase(Locale.ROOT);
        return lower.contains("github.com/" + repo.toLowerCase(Locale.ROOT) + "/")
                || lower.contains("githubusercontent.com/" + repo.toLowerCase(Locale.ROOT) + "/");
    }

    /// Returns a string member, or `null`.
    ///
    /// @param object the object
    /// @param name   the member's name
    /// @return the string, or `null`
    private static @Nullable String asString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString();
        return value.isBlank() ? null : value;
    }

    /// Returns an integer member, or zero.
    ///
    /// @param object the object
    /// @param name   the member's name
    /// @return the number, or zero when it is absent or not a number
    private static int asInt(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            LOG.warning("Ignoring a " + name + " that is not an integer", e);
            return 0;
        }
    }

    /// Returns a string member that must have a value.
    ///
    /// @param value the value, or `null`
    /// @return the value, or an empty string
    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
