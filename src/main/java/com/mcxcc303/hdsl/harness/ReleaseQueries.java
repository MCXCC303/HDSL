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
package com.mcxcc303.hdsl.harness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcxcc303.hdsl.core.DshCommand;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.DshJson;
import com.mcxcc303.hdsl.runtime.node.DshNodeRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * The releases npm publishes: the harness versions, one package's versions, their publish times,
 * and the {@code dist-tags} that point at them.
 * <p>
 * Queries run through the npm of the Node.js runtime.
 */
@NotNullByDefault
public final class ReleaseQueries {
	private ReleaseQueries() {
	}

	/**
	 * Queries the npm registry for every published DeepSeek Harness version.
	 * <p>
	 * Dist-tags are fetched in a second call and merged into the releases.
	 *
	 * @return the published releases, newest first
	 * @throws DshException when no usable npm is available or the query fails
	 */
	public static List<DshRelease> fetchReleases() throws DshException {
		DshNodeRuntime runtime = HarnessInstaller.requireRuntime();
		if (!runtime.canInstall()) {
			throw new DshException("npm was not found on PATH; reading the version list requires it");
		}

		Set<String> versions = queryVersions(runtime.npm());
		JsonObject distTags = queryDistTags(runtime.npm());
		JsonObject times = queryTimes(runtime.npm());

		List<DshRelease> releases = new ArrayList<>(versions.size());
		for (String version : versions) {
			Set<String> tags = new LinkedHashSet<>();
			for (var entry : distTags.entrySet()) {
				JsonElement value = entry.getValue();
				if (value != null && value.isJsonPrimitive() && version.equals(value.getAsString())) {
					tags.add(entry.getKey());
				}
			}
			JsonElement published = times.get(version);
			releases.add(new DshRelease(version,
			                            Set.copyOf(tags),
			                            published != null && published.isJsonPrimitive()
			                            ? published.getAsString()
			                            : null));
		}

		releases.sort(Comparator.comparing(DshRelease::version, VersionOrder::compareVersions).reversed());
		return releases;
	}

	/**
	 * Reads the harness version list with a given npm.
	 *
	 * @param npm the npm executable
	 * @return the published version strings
	 * @throws DshException when the query fails or returns unexpected data
	 */
	static Set<String> queryVersions(Path npm) throws DshException {
		return queryVersions(npm, HarnessInstaller.PACKAGE_NAME);
	}

	/**
	 * Reads a package's published versions with the launcher's runtime.
	 *
	 * @param pkg the package to read
	 * @return the published version strings, newest first
	 * @throws DshException when the query fails or returns unexpected data
	 */
	public static List<String> fetchPackageVersions(String pkg) throws DshException {
		return fetchPackageVersions(HarnessInstaller.requireRuntime(), pkg);
	}

	/**
	 * Reads a package's published versions with a runtime the caller resolved.
	 *
	 * @param runtime the runtime to read with
	 * @param pkg     the package name
	 * @return the versions, newest first
	 * @throws DshException when npm is missing or the registry cannot be reached
	 */
	public static List<String> fetchPackageVersions(DshNodeRuntime runtime, String pkg) throws DshException {
		if (!runtime.canInstall()) {
			throw new DshException(
					"npm was not found for this instance's Node runtime;" + " reading the registry requires it");
		}
		List<String> versions = new ArrayList<>(queryVersions(runtime.npm(), pkg));
		versions.sort((left, right) -> VersionOrder.compareVersions(right, left));
		return List.copyOf(versions);
	}

	/**
	 * Returns the last few output lines, for an error message.
	 *
	 * @param lines the captured output
	 * @return the trailing lines joined by newlines
	 */
	static String tail(List<String> lines) {
		int from = Math.max(0, lines.size() - 12);
		return String.join("\n", lines.subList(from, lines.size()));
	}

	/**
	 * Reads a package's version list with a given npm.
	 *
	 * @param npm the npm executable
	 * @param pkg the package to read
	 * @return the published version strings
	 * @throws DshException when the query fails or returns unexpected data
	 */
	static Set<String> queryVersions(Path npm, String pkg) throws DshException {
		DshCommand.Result result = runNpmView(npm, pkg, "versions");
		JsonElement parsed = parseJson(result.text());
		if (parsed == null) {
			throw new DshException("npm returned no version list; check the network and the npm registry configuration");
		}
		Set<String> versions = new TreeSet<>();
		if (parsed.isJsonArray()) {
			for (JsonElement element : parsed.getAsJsonArray()) {
				if (element.isJsonPrimitive()) {
					versions.add(element.getAsString());
				}
			}
		} else if (parsed.isJsonPrimitive()) {
			// A package with exactly one published version answers with a bare string.
			versions.add(parsed.getAsString());
		}
		return versions;
	}

	/**
	 * Reads when each version was published.
	 * <p>
	 * A failure is not fatal: a version is listed without its date.
	 *
	 * @param npm the npm executable
	 * @return the version to publication-time map, empty when unavailable
	 */
	static JsonObject queryTimes(Path npm) {
		try {
			return asObject(parseJson(runNpmView(npm, "time").text()));
		} catch (DshException e) {
			LOG.warning("Failed to read npm publication times", e);
			return new JsonObject();
		}
	}

	/**
	 * Returns the object npm printed.
	 * <p>
	 * Handles both shapes npm uses: a bare object, and an array holding one object.
	 *
	 * @param parsed the parsed value, possibly {@code null}
	 * @return the object, or an empty one when there is none
	 */
	static JsonObject asObject(@Nullable JsonElement parsed) {
		if (parsed == null) {
			return new JsonObject();
		}
		if (parsed.isJsonArray()) {
			for (JsonElement element : parsed.getAsJsonArray()) {
				if (element.isJsonObject()) {
					return element.getAsJsonObject();
				}
			}
			return new JsonObject();
		}
		return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
	}

	/**
	 * Reads the dist-tag table from the registry.
	 *
	 * @param npm the npm executable
	 * @return the tag-to-version mapping, empty when the query fails
	 */
	static JsonObject queryDistTags(Path npm) {
		try {
			return asObject(parseJson(runNpmView(npm, "dist-tags").text()));
		} catch (DshException e) {
			LOG.warning("Failed to read npm dist-tags", e);
			return new JsonObject();
		}
	}

	/**
	 * Runs {@code npm view <package> <field> --json}.
	 *
	 * @param npm   the npm executable
	 * @param field the registry field to read
	 * @return the command result
	 * @throws DshException when npm cannot be run
	 */
	static DshCommand.Result runNpmView(Path npm, String field) throws DshException {
		return runNpmView(npm, HarnessInstaller.PACKAGE_NAME, field);
	}

	/**
	 * Runs {@code npm view <package> <field> --json}.
	 *
	 * @param npm   the npm executable
	 * @param pkg   the package to read
	 * @param field the field to read
	 * @return the command result
	 * @throws DshException when the command cannot be run
	 */
	static DshCommand.Result runNpmView(Path npm, String pkg, String field) throws DshException {
		List<String> command = List.of(npm.toString(), "view", pkg, field, "--json");
		try {
			DshCommand.Result result = DshCommand.run(command);
			if (!result.isSuccess()) {
				throw new DshException(
						"npm view " + field + " exited with code " + result.exitCode() + ": " + result.text());
			}
			return result;
		} catch (IOException e) {
			throw new DshException("Failed to run pnpm", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DshException("npm view was interrupted", e);
		}
	}

	/**
	 * Parses the first JSON value in {@code text}.
	 *
	 * @param text the command's output
	 * @return the parsed element, or {@code null} when there is no value in it
	 */
	static @Nullable JsonElement parseJson(String text) {
		return DshJson.parseFirstValue(text);
	}
}
