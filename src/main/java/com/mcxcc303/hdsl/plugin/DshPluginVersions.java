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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcxcc303.hdsl.harness.VersionOrder;
import com.mcxcc303.hdsl.runtime.DshNpmDocument;
import com.mcxcc303.hdsl.runtime.DshReleaseChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Published plugin versions, arranged for display.
 * <p>
 * Groups are keyed by the DeepSeek Harness version a plugin version supports.
 * Support comes from {@code @deepseek-ai/dsh*} peer dependencies.
 * A version that declares no harness peer is placed in a separate group and is not counted as fitting.
 */
@NotNullByDefault
public final class DshPluginVersions {
	private static final String HARNESS_PREFIX = "@deepseek-ai/dsh";

	// Version literal inside a range, for example ^0.1.0-rc.7.
	private static final Pattern CLAIMED = Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?");

	private DshPluginVersions() {}

	/**
	 * Reads a registry document into published versions, newest first.
	 *
	 * @param packument the registry document
	 * @return the versions, or an empty list when none are described
	 */
	public static List<Published> read(JsonObject packument) {
		JsonObject versions = DshNpmDocument.object(packument, "versions");
		if (versions == null) {
			return List.of();
		}
		JsonObject times = DshNpmDocument.object(packument, "time");
		JsonObject tags = DshNpmDocument.object(packument, "dist-tags");

		List<Published> published = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : versions.entrySet()) {
			if (!entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject manifest = entry.getValue().getAsJsonObject();
			String version =
					manifest.has("version") && manifest.get("version").isJsonPrimitive()
					? manifest.get("version")
					          .getAsString()
					: entry.getKey();
			if (version.isBlank()) {
				continue;
			}
			JsonObject peers = harnessPeers(DshNpmDocument.object(manifest, "peerDependencies"));
			published.add(new Published(version,
			                            DshNpmDocument.channelOf(version, tags),
			                            DshNpmDocument.moment(times, version),
			                            peers,
			                            claimedOf(peers)));
		}

		published.sort(Comparator.comparing(Published::published, Comparator.nullsLast(Comparator.reverseOrder())));
		return List.copyOf(published);
	}

	/**
	 * Groups versions by the harness versions they declare.
	 * <p>
	 * A version is listed under every harness version it admits.
	 * The current harness version is placed first.
	 * Versions that declare no harness peer, or admit no listed version, go in a final null group.
	 *
	 * @param versions       the published versions
	 * @param harnessVersion the current harness version, or null
	 * @return the groups
	 */
	public static List<Group> group(List<Published> versions, @Nullable String harnessVersion) {
		List<String> panel = harnessVersions(versions, harnessVersion);
		Map<String, List<Published>> byHarness = new LinkedHashMap<>();
		for (String target : panel) {
			byHarness.put(target, new ArrayList<>());
		}
		List<Published> unclaimed = new ArrayList<>();

		for (Published version : versions) {
			if (version.harnessPeers().isEmpty()) {
				unclaimed.add(version);
				continue;
			}
			boolean listed = false;
			for (String target : panel) {
				if (admits(version, target)) {
					byHarness.get(target).add(version);
					listed = true;
				}
			}
			if (!listed) {
				// Declares harness peers but admits no listed harness version.
				unclaimed.add(version);
			}
		}

		List<Group> groups = new ArrayList<>();
		for (String target : panel) {
			List<Published> members = byHarness.get(target);
			if (members.isEmpty()) {
				continue;
			}
			groups.add(new Group(target, target.equals(harnessVersion), List.copyOf(members)));
		}
		if (!unclaimed.isEmpty()) {
			groups.add(new Group(null, false, List.copyOf(unclaimed)));
		}
		return List.copyOf(groups);
	}

	/**
	 * Returns whether a version admits a harness version.
	 * <p>
	 * All declared harness peers must satisfy {@code harnessVersion}.
	 * Returns false when no harness peer is declared.
	 */
	static boolean admits(Published version, String harnessVersion) {
		for (Map.Entry<String, JsonElement> entry : version.harnessPeers().entrySet()) {
			if (!DshPluginRequirements.satisfies(entry.getValue().getAsString(), harnessVersion)) {
				return false;
			}
		}
		return !version.harnessPeers().isEmpty();
	}

	// Harness versions to group by, current version first.
	private static List<String> harnessVersions(List<Published> versions, @Nullable String harnessVersion) {
		Set<String> named = new LinkedHashSet<>();
		for (Published version : versions) {
			named.addAll(version.claimed());
		}
		boolean asked = harnessVersion != null && !harnessVersion.isBlank();
		if (asked) {
			named.add(harnessVersion);
		}

		List<String> panel = new ArrayList<>(named);
		panel.sort((left, right) -> VersionOrder.compareVersions(right, left));
		if (asked) {
			panel.remove(harnessVersion);
			panel.addFirst(harnessVersion);
		}
		return panel;
	}

	/**
	 * Returns how a version fits the instance's harness.
	 * <p>
	 * No harness peers -> {@link Fit#UNCLAIMED}.
	 * If {@code harnessCore} is given, uses {@link DshPluginRequirements#fits}.
	 * Otherwise, no harness version -> {@link Fit#UNCLAIMED}; else uses {@link admits}.
	 */
	public static Fit fitOf(Published version,
	                        @Nullable String harnessVersion,
	                        @Nullable Map<String, String> harnessCore) {
		if (version.harnessPeers().isEmpty()) {
			return Fit.UNCLAIMED;
		}
		if (harnessCore != null) {
			return DshPluginRequirements.fits(version.harnessPeers(), harnessCore) ? Fit.FITS : Fit.NOT_FITS;
		}
		if (harnessVersion == null || harnessVersion.isBlank()) {
			return Fit.UNCLAIMED;
		}
		return admits(version, harnessVersion) ? Fit.FITS : Fit.NOT_FITS;
	}

	// Keeps names equal to {@code @deepseek-ai/dsh} or starting with {@code @deepseek-ai/dsh-}.
	private static JsonObject harnessPeers(@Nullable JsonObject peers) {
		JsonObject harness = new JsonObject();
		if (peers == null) {
			return harness;
		}
		for (Map.Entry<String, JsonElement> entry : peers.entrySet()) {
			if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
				continue;
			}
			String name = entry.getKey();
			if (name.equals(HARNESS_PREFIX) || name.startsWith(HARNESS_PREFIX + "-")) {
				harness.addProperty(name, entry.getValue().getAsString());
			}
		}
		return harness;
	}

	// Harness version literals found in peer ranges, newest first.
	private static List<String> claimedOf(JsonObject peers) {
		Set<String> claimed = new LinkedHashSet<>();
		for (Map.Entry<String, JsonElement> entry : peers.entrySet()) {
			Matcher matcher = CLAIMED.matcher(entry.getValue().getAsString());
			while (matcher.find()) {
				claimed.add(matcher.group());
			}
		}
		List<String> versions = new ArrayList<>(claimed);
		versions.sort((left, right) -> VersionOrder.compareVersions(right, left));
		return List.copyOf(versions);
	}

	public enum Fit {
		FITS, NOT_FITS, UNCLAIMED
	}

	/**
	 * One published plugin version.
	 *
	 * @param published    publish time, or null
	 * @param harnessPeers declared harness peers
	 * @param claimed      harness versions named by harnessPeers, newest first
	 */
	public record Published(String version, DshReleaseChannel channel, @Nullable Instant published, JsonObject harnessPeers,
	                        List<String> claimed) {}

	/**
	 * Versions shown under one heading.
	 *
	 * @param harnessVersion the harness version for this group, or null for undeclared versions
	 * @param recommended    whether this group is for the current instance
	 * @param versions       the versions, newest first
	 */
	public record Group(@Nullable String harnessVersion, boolean recommended, List<Published> versions) {}
}
