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

import org.jetbrains.annotations.NotNullByDefault;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/// The community's plugin catalogue, and one entry in it.
///
/// DeepSeek Harness itself has no plugin directory: \`dsh plugin\` forwards its arguments to pnpm,
/// and the only way to find a plugin is to know its name.  The catalogue the marketplace reads is
/// the community's answer — a plain JSON document on a plain HTTPS host.  What it carries per
/// plugin is what the page shows: the npm name when there is one, the repository, a category,
/// localised descriptions, stars and a download count.
@NotNullByDefault
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

	/// One plugin the catalogue lists.
	///
	/// @param name          the plugin's own name
	/// @param owner         the repository owner
	/// @param url           the repository URL
	/// @param category      the catalogue's category
	/// @param description   the description in English
	/// @param descriptionZh the description in Chinese, or `null`
	/// @param npm           the npm package name, or `null` for a repository-only plugin
	/// @param version       the version the catalogue last saw, or `null`
	/// @param stars         the repository's stars
	/// @param downloads     the package's recent downloads, zero when it has none
	/// @param tarball       a prebuilt release archive, or `null`
	/// @param added         the date the entry joined the catalogue, or `null`
	public record Plugin(String name, String owner, String url, String category, @Nullable String description,
	                     @Nullable String descriptionZh, @Nullable String npm, @Nullable String version, int stars,
	                     int downloads, @Nullable String tarball, @Nullable String added) {

		/// Returns the plugin's name without its owner.
		///
		/// The catalogue carries both, and the name alone is what a list row has
		/// room for; the repository is what tells two plugins of one name apart.
		///
		/// @return the display name
		public String displayName() {
			return name;
		}

		/**
		 * Returns where the repository owner's picture is.
		 *
		 * <p>The catalogue lists GitHub repositories, and GitHub serves the owner's picture at a
		 * fixed address.
		 *
		 * @return the address, or {@code null} when the entry names no owner
		 */
		public @Nullable String ownerAvatarUrl() {
			if (owner == null || owner.isBlank()) {
				return null;
			}
			return "https://github.com/" + owner.trim() + ".png?size=64";
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
			return DshPluginSources.REPOSITORY.matcher(DshPluginSources.repoOf(url)).matches();
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
			if (npm != null && CatalogSources.NPM_NAME.matcher(npm).matches()) {
				return version == null || version.isBlank() ? npm : npm + "@" + version;
			}
			String repo = DshPluginSources.repoOf(url);
			if (!DshPluginSources.REPOSITORY.matcher(repo).matches()) {
				return null;
			}
			if (tarball != null && DshPluginSources.tarballBelongsTo(tarball, repo)) {
				return tarball;
			}
			return "github:" + repo + DshPluginSources.subpathSuffix(url);
		}

		/// Returns what the page says about how this plugin is installed.
		///
		/// @return the source description
		public String sourceKind() {
			if (npm != null && CatalogSources.NPM_NAME.matcher(npm).matches()) {
				return "npm";
			}
			return tarball != null && DshPluginSources.tarballBelongsTo(tarball, DshPluginSources.repoOf(url))
			       ? "release"
			       : "github";
		}
	}
}
