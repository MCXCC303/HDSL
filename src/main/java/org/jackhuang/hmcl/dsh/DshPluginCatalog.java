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

    /// The npm package the same catalogue is also published as.
    ///
    /// The community host is GitHub Pages, and a network that cannot reach GitHub can still reach
    /// an npm mirror — which is exactly the network this launcher is most likely to run on, since
    /// it installs everything else through npm. The package carries the same `plugins.json`, so it
    /// is the catalogue rather than a copy of it, and reading it goes through whichever registry
    /// the machine's `npm` is already configured to use.
    public static final String NPM_CATALOG_PACKAGE = "dsh-plugin-catalog";

    /// The address actually read first, which the `hdsl.pluginCatalog` property may
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

    /// Returns every place the catalogue is read from, in the order they are tried.
    ///
    /// One catalogue is one document on one host, and a host is a single point of
    /// failure: a mirror is what a network that cannot reach the community host
    /// needs, and a second community catalogue is what somebody who wants more
    /// plugins than one list holds needs. So what is configured is a *list*, and
    /// every entry in it that answers contributes its entries — see [#fetch].
    ///
    /// An entry is an address (`https://…/plugins.json`) or the name of an npm
    /// package that publishes the same document (`dsh-plugin-catalog`), which is
    /// the classification [#isPackageSource] makes.
    ///
    /// The npm package that publishes the community catalogue is not added here.
    /// It is [#fallbackSource], read when nothing above answered: it is the one
    /// network path this launcher can be sure of — it goes through whichever
    /// registry this machine's npm is configured for — and reading it *as well*
    /// as a source that answered would be reading the same document twice.
    ///
    /// @return the sources, in the order they are read
    public static List<String> sources() {
        String property = System.getProperty("hdsl.pluginCatalog");
        if (property != null && !property.isBlank()) {
            // A developer asking one run to read somewhere else means somewhere
            // else, not somewhere else as well.
            return List.of(property.trim());
        }

        List<String> configured = new ArrayList<>();
        try {
            org.jackhuang.hmcl.setting.LauncherSettings settings =
                    org.jackhuang.hmcl.setting.SettingsManager.settings();
            for (String source : settings.pluginCatalogSourcesProperty()) {
                if (source != null && !source.isBlank()) {
                    configured.add(source.trim());
                }
            }
            if (configured.isEmpty()) {
                // The single address this launcher had before sources were a list.
                // Kept working rather than migrated: it is one string, and a
                // settings file that carries it is a settings file somebody is
                // using.
                String legacy = settings.pluginCatalogUrlProperty().get();
                if (legacy != null && !legacy.isBlank()) {
                    configured.add(legacy.trim());
                }
            }
        } catch (RuntimeException e) {
            LOG.warning("Could not read the catalogue sources from the settings", e);
        }
        if (configured.isEmpty()) {
            configured.add(CATALOG_URL);
        }
        return List.copyOf(configured.stream().distinct().toList());
    }

    /// Returns the source read when every configured one has failed.
    ///
    /// The community catalogue is published as an npm package as well as on the
    /// community host, and the host is GitHub Pages: a network that cannot reach
    /// GitHub can still reach an npm mirror, which is the network this launcher is
    /// most likely to run on, since it installs everything else through npm. The
    /// package carries the same `plugins.json`, so it is the catalogue rather than
    /// a copy of it.
    ///
    /// @return the source to fall back on
    public static String fallbackSource() {
        return NPM_CATALOG_PACKAGE;
    }

    /// Reports whether a source names an npm package rather than an address.
    ///
    /// @param source the source
    /// @return whether it is a package for the registry to resolve
    public static boolean isPackageSource(String source) {
        String trimmed = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        return !trimmed.startsWith("http://") && !trimmed.startsWith("https://")
                && !trimmed.startsWith("file:");
    }

    /// Reports whether a source can be read at all.
    ///
    /// A source is an address or a package name, and anything else is neither:
    /// refusing it here names the entry the user typed rather than failing later
    /// with a message about a URL that was never one.
    ///
    /// @param source the source
    /// @return whether it is usable
    public static boolean isUsableSource(@Nullable String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String trimmed = source.trim();
        if (isPackageSource(trimmed)) {
            return NPM_NAME.matcher(trimmed).matches();
        }
        try {
            java.net.URI uri = java.net.URI.create(trimmed);
            return uri.getScheme() != null && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /// Returns the sources the user arranged, without the ones the launcher adds.
    ///
    /// What the settings hold, which is what an editor shows: the built-in pair is
    /// added when this is empty, and a list that showed them would be a list whose
    /// entries cannot all be removed.
    ///
    /// @return the configured sources, empty when the launcher's own pair is used
    public static List<String> configuredSources() {
        try {
            return List.copyOf(org.jackhuang.hmcl.setting.SettingsManager.settings()
                    .pluginCatalogSourcesProperty());
        } catch (RuntimeException e) {
            LOG.warning("Could not read the catalogue sources from the settings", e);
            return List.of();
        }
    }

    /// Adds a source, unless it is already one.
    ///
    /// @param source an address or an npm package name
    /// @throws DshException when it is neither
    public static void addSource(String source) throws DshException {
        String trimmed = source == null ? "" : source.trim();
        if (!isUsableSource(trimmed)) {
            throw new DshException("\"" + source + "\" is neither a catalogue address nor a package name"
                    + " (an address looks like https://host/plugins.json, a package like "
                    + NPM_CATALOG_PACKAGE + ")");
        }
        List<String> updated = new ArrayList<>(configuredSources());
        if (updated.stream().anyMatch(existing -> existing.equalsIgnoreCase(trimmed))) {
            throw new DshException(trimmed + " is already a catalogue source");
        }
        updated.add(trimmed);
        setConfiguredSources(updated);
    }

    /// Removes a source.
    ///
    /// Removing the last one goes back to the pair the ecosystem publishes rather
    /// than leaving the launcher with none: a launcher with no source is a
    /// launcher whose plugin page shows nothing and cannot say why, and "stop
    /// using my list" is what removing the last entry means.
    ///
    /// @param source the source to remove
    /// @throws DshException when it is not a source
    public static void removeSource(String source) throws DshException {
        String trimmed = source == null ? "" : source.trim();
        List<String> updated = new ArrayList<>(configuredSources());
        if (!updated.removeIf(existing -> existing.equalsIgnoreCase(trimmed))) {
            throw new DshException(trimmed + " is not a catalogue source");
        }
        if (updated.isEmpty()) {
            resetSources();
            return;
        }
        setConfiguredSources(updated);
    }

    /// Puts the catalogue sources back to the pair the ecosystem publishes.
    public static void resetSources() {
        setConfiguredSources(List.of());
        try {
            org.jackhuang.hmcl.setting.LauncherSettings settings =
                    org.jackhuang.hmcl.setting.SettingsManager.settings();
            settings.pluginCatalogUrlProperty().set("");
            org.jackhuang.hmcl.setting.SettingsManager.save();
        } catch (RuntimeException e) {
            LOG.warning("Could not put the catalogue address back", e);
        }
    }

    /// Writes the configured sources down.
    ///
    /// @param sources the sources
    private static void setConfiguredSources(List<String> sources) {
        try {
            org.jackhuang.hmcl.setting.LauncherSettings settings =
                    org.jackhuang.hmcl.setting.SettingsManager.settings();
            settings.pluginCatalogSourcesProperty().setAll(sources);
            // The single address this launcher had before sources were a list is
            // cleared as soon as a list is written: leaving it set would make the
            // first source the one the list no longer names.
            settings.pluginCatalogUrlProperty().set("");
            org.jackhuang.hmcl.setting.SettingsManager.save();
        } catch (RuntimeException e) {
            LOG.warning("Could not write the catalogue sources", e);
        }
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
    /// @param unreadable the sources that could not be read, empty when every one answered
    public record Catalog(@Nullable String updated, List<Plugin> plugins, List<String> categories,
                          List<String> unreadable) {

        /// Creates a catalogue that came from one source that answered.
        ///
        /// @param updated    the date the catalogue was generated, or `null`
        /// @param plugins    the plugins
        /// @param categories the categories
        public Catalog(@Nullable String updated, List<Plugin> plugins, List<String> categories) {
            this(updated, plugins, categories, List.of());
        }

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

        /// Reports whether something the user asked to read did not answer.
        ///
        /// A catalogue built from several places is built from the ones that
        /// answered: a source that is down — one host serving one JSON document
        /// is a single point of failure, which is why more than one can be
        /// configured — must not take the sources that are up with it. What it
        /// must do is say so, which is what this is for.
        ///
        /// @return whether any source failed
        public boolean isPartial() {
            return !unreadable.isEmpty();
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

    /// Reads the catalogue from every source that answers.
    ///
    /// @return the catalogue
    /// @throws DshException when no source could be read and no copy was kept
    public static Catalog fetch() throws DshException {
        return fetch(sources());
    }

    /// Reads the catalogue from the given sources, in the given order.
    ///
    /// Every source that answers contributes its entries, and one that does not
    /// is reported in [Catalog#unreadable] rather than failing the read: a
    /// catalogue made of several places is worth having precisely when one of
    /// them is down. Each source's own copy is kept as it arrives, so an
    /// unreachable source can still be shown from the last time it answered —
    /// which is what makes the *third* read of a mutli-source catalogue as good
    /// as the first on a network that has gone away.
    ///
    /// The order is the tie-break: two sources listing the same plugin are one
    /// plugin, and the one that comes first is the one kept. The list the user
    /// arranged is therefore the order of authority, and putting a mirror first
    /// is how somebody says "prefer this one".
    ///
    /// @param sources the sources to read
    /// @return the catalogue
    /// @throws DshException when no source could be read and no copy was kept
    public static Catalog fetch(List<String> sources) throws DshException {
        List<Catalog> answered = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        for (String source : sources) {
            Path cached = cachedFile(source);
            try {
                String body = readSource(source);
                keepCopy(cached, body);
                answered.add(parse(body));
                continue;
            } catch (IOException | RuntimeException direct) {
                String kept = readCached(cached);
                if (kept != null) {
                    // Worth more than nothing, and worth saying: a launcher that
                    // cannot reach a catalogue can still show what it showed
                    // yesterday, and the person can see that is what happened.
                    LOG.info("The plugin catalogue at " + source + " could not be read ("
                            + direct.getMessage() + "); using the copy kept at " + cached);
                    try {
                        answered.add(parse(kept));
                        unreadable.add(source);
                        continue;
                    } catch (RuntimeException stale) {
                        // The copy is not a catalogue any more. Fall through to
                        // the failure it stands for.
                    }
                }
                LOG.info("The plugin catalogue at " + source + " could not be read: "
                        + direct.getMessage());
                unreadable.add(source);
                reasons.add(source + " (" + direct.getMessage() + ")");
            }
        }

        if (answered.isEmpty()) {
            // Nothing answered, so the rescue is tried: the same document, through
            // a registry rather than a host. It is read *only* here — reading it
            // beside a source that answered would be reading the same catalogue
            // twice, and every source costs a couple of megabytes.
            String rescue = fallbackSource();
            if (sources.stream().noneMatch(rescue::equalsIgnoreCase)) {
                Path cached = cachedFile(rescue);
                try {
                    String body = readSource(rescue);
                    keepCopy(cached, body);
                    LOG.info("The plugin catalogue was read from the npm package " + rescue);
                    Catalog catalog = merge(List.of(parse(body)), unreadable);
                    lastCatalog = catalog;
                    return catalog;
                } catch (IOException | RuntimeException viaNpm) {
                    String kept = readCached(cached);
                    if (kept != null) {
                        try {
                            Catalog catalog = merge(List.of(parse(kept)), unreadable);
                            lastCatalog = catalog;
                            return catalog;
                        } catch (RuntimeException stale) {
                            // The copy is not a catalogue any more.
                        }
                    }
                    reasons.add(rescue + " (" + viaNpm.getMessage() + ")");
                }
            }
            throw new DshException("Failed to read the plugin catalogue from "
                    + String.join(", ", reasons.isEmpty() ? sources : reasons));
        }

        Catalog catalog = merge(answered, unreadable);
        lastCatalog = catalog;
        return catalog;
    }

    /// Reads one source's document.
    ///
    /// @param source an address or an npm package name
    /// @return the catalogue document
    /// @throws IOException when it cannot be read
    private static String readSource(String source) throws IOException {
        if (isPackageSource(source)) {
            return fetchFromNpm(source);
        }
        return NetworkUtils.doGet(URI.create(source.trim()));
    }

    /// Combines what the sources answered into one catalogue.
    ///
    /// A plugin is the same plugin across sources when it names the same npm
    /// package, and repository-only entries are the same when they name the same
    /// repository. The first occurrence wins, so the order of the sources decides
    /// which description, version and download count are shown for a plugin that
    /// two of them list — which is what makes the order the user arranged mean
    /// something.
    ///
    /// @param parts      the catalogues that answered, in source order
    /// @param unreadable the sources that did not
    /// @return the merged catalogue
    static Catalog merge(List<Catalog> parts, List<String> unreadable) {
        List<Plugin> plugins = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        Map<String, String> categories = new TreeMap<>();
        String updated = null;

        for (Catalog part : parts) {
            if (updated == null) {
                updated = part.updated();
            }
            for (Plugin plugin : part.plugins()) {
                if (seen.add(identityOf(plugin))) {
                    plugins.add(plugin);
                }
            }
            for (String category : part.categories()) {
                categories.putIfAbsent(category.toLowerCase(Locale.ROOT), category);
            }
        }

        return new Catalog(updated, List.copyOf(plugins),
                List.copyOf(categories.values()), List.copyOf(unreadable));
    }

    /// Returns the name two entries share when they are the same plugin.
    ///
    /// @param plugin the entry
    /// @return the identity
    private static String identityOf(Plugin plugin) {
        String npm = plugin.npm();
        if (npm != null && !npm.isBlank()) {
            return "npm:" + npm.trim().toLowerCase(Locale.ROOT);
        }
        return "repo:" + repoOf(plugin.url()).toLowerCase(Locale.ROOT);
    }

    /// Keeps a copy of the catalogue so a later fetch has something to fall back on.
    ///
    /// @param cached where to keep it
    /// @param body   the catalogue
    private static void keepCopy(Path cached, String body) {
        try {
            Files.createDirectories(cached.getParent());
            Files.writeString(cached, body);
        } catch (IOException e) {
            LOG.warning("Could not keep a copy of the plugin catalogue", e);
        }
    }

    /// Reads the catalogue out of an npm package that publishes one.
    ///
    /// Two requests: the package's metadata names the archive, and the archive holds the same
    /// `plugins.json` the community host serves. Both go to the registry the machine's own `npm`
    /// is configured to use, which is why this works on a network where the community host does
    /// not.
    ///
    /// @param packageName the package to read, which is [NPM_CATALOG_PACKAGE] unless a source named
    ///                    another one
    /// @return the catalogue
    /// @throws IOException when either request fails or the archive holds no catalogue
    private static String fetchFromNpm(String packageName) throws IOException {
        String registry = npmRegistry();
        String metadataUrl = registry + "/" + packageName + "/latest";
        String metadata = NetworkUtils.doGet(URI.create(metadataUrl));
        JsonObject object = JsonParser.parseString(metadata).getAsJsonObject();
        JsonElement tarball = object.getAsJsonObject("dist") == null
                ? null : object.getAsJsonObject("dist").get("tarball");
        if (tarball == null || !tarball.isJsonPrimitive()) {
            throw new IOException("the registry's answer for " + packageName
                    + " names no archive");
        }

        byte[] archive = downloadBytes(URI.create(tarball.getAsString()));

        // The tar reader wants the whole archive rather than a stream, so the gzip layer is taken
        // off first. The catalogue is about a megabyte compressed, which is a fine size to hold.
        byte[] tar;
        try (java.io.InputStream unzipped =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(archive))) {
            tar = unzipped.readAllBytes();
        }
        try (kala.compress.archivers.tar.TarArchiveReader reader =
                     new kala.compress.archivers.tar.TarArchiveReader(tar)) {
            for (kala.compress.archivers.tar.TarArchiveEntry entry : reader.getEntries()) {
                if (entry.getName().endsWith("plugins.json")) {
                    try (java.io.InputStream content = reader.getInputStream(entry)) {
                        return new String(content.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        }
        throw new IOException("the archive holds no plugins.json");
    }

    /// Downloads an address into memory.
    ///
    /// The catalogue's archive is small and is parsed from a byte array, so it is read whole
    /// rather than spilled to a file that would then have to be cleaned up.
    ///
    /// @param uri the address
    /// @return its body
    /// @throws IOException when it cannot be read
    private static byte[] downloadBytes(URI uri) throws IOException {
        java.net.URLConnection connection = NetworkUtils.createConnection(uri);
        if (connection instanceof java.net.HttpURLConnection http) {
            connection = NetworkUtils.resolveConnection(http);
        }
        try (java.io.InputStream input = connection.getInputStream()) {
            return input.readAllBytes();
        }
    }

    /// Returns the registry the machine's npm is configured to use.
    ///
    /// Read from `npm config get registry` rather than from a file, because that is the command
    /// whose answers npm itself acts on: it accounts for the project's `.npmrc`, the user's, the
    /// global one and the built-in default, in the order npm does. Falling back to the public
    /// registry keeps this working when npm is missing.
    ///
    /// @return the registry's base address, without a trailing slash
    static String npmRegistry() {
        try {
            DshNodeRuntime runtime = DshNodeRuntime.detect().orElse(null);
            if (runtime != null && runtime.npm() != null) {
                DshCommand.Result result = DshCommand.run(
                        List.of(runtime.npm().toString(), "config", "get", "registry"));
                if (result.isSuccess()) {
                    String configured = result.text().strip();
                    // npm prints a warning on the same stream when it dislikes something, so the
                    // answer is the last line that looks like an address rather than the whole
                    // output.
                    for (String line : configured.lines().toList().reversed()) {
                        String candidate = line.strip();
                        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                            return candidate.endsWith("/")
                                    ? candidate.substring(0, candidate.length() - 1) : candidate;
                        }
                    }
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.info("Could not read npm's registry; using the public one", e);
        }
        return "https://registry.npmjs.org";
    }

    /// Returns the directory the launcher keeps fetched catalogues in.
    ///
    /// @return the directory, which may not exist
    public static Path cacheDirectory() {
        try {
            org.jackhuang.hmcl.setting.LauncherSettings settings =
                    org.jackhuang.hmcl.setting.SettingsManager.settings();
            String configured = settings.cacheDirectoryProperty().get();
            if (settings.cacheDirectoryCustomProperty().get() && configured != null && !configured.isBlank()) {
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
