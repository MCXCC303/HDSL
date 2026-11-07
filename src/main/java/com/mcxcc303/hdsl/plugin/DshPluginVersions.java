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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads published plugin versions from npm packument data and groups them by harness compatibility.
 */
@NotNullByDefault
public final class DshPluginVersions {
	private static final String HARNESS_PREFIX = "@deepseek-ai/dsh";
	private static final Pattern CLAIMED = Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?");

	private DshPluginVersions() {
	}

	/**
	 * Reads a registry document into the versions it describes, newest first.
	 *
	 * @param packument the document
	 * @return the versions, or an empty list when the document describes none
	 */
	public static List<Published> read(JsonObject packument) {
		JsonObject versions = object(packument, "versions");
		if (versions == null) {
			return List.of();
		}
		JsonObject times = object(packument, "time");
		JsonObject tags = object(packument, "dist-tags");

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
			JsonObject peers = harnessPeers(object(manifest, "peerDependencies"));
			published.add(new Published(version,
			                            channelOf(version, tags),
			                            moment(times, version),
			                            peers,
			                            claimedOf(peers)));
		}

		published.sort(Comparator.comparing(Published::published, Comparator.nullsLast(Comparator.reverseOrder())));
		return List.copyOf(published);
	}

	/**
	 * Arranges versions into the groups the page draws.
	 *
	 * @param versions       the versions
	 * @param harnessVersion the harness version the page is for, or {@code null} when there is none
	 * @return the groups, which are never empty unless nothing was published at all
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
	 * Reports whether a version declares compatibility with the given harness version.
	 *
	 * @param version        the published plugin version
	 * @param harnessVersion the harness version
	 * @return true if compatible
	 */
	static boolean admits(Published version, String harnessVersion) {
		for (Map.Entry<String, JsonElement> entry : version.harnessPeers().entrySet()) {
			if (!DshPluginRequirements.satisfies(entry.getValue().getAsString(), harnessVersion)) {
				return false;
			}
		}
		return !version.harnessPeers().isEmpty();
	}

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
			panel.add(0, harnessVersion);
		}
		return panel;
	}

	/**
	 * Reports compatibility of a published version against the given harness or core package versions.
	 *
	 * @param version        the version
	 * @param harnessVersion the harness version, or {@code null} when there is none to judge against
	 * @param harnessCore    the harness packages that version holds, or {@code null} when they could not be read
	 * @return what it says
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

	/**
	 * Returns the channel a version belongs to.
	 *
	 * @param version the version
	 * @param tags    the package's dist-tags, or {@code null}
	 * @return the channel
	 */
	public static Channel channelOf(String version, @Nullable JsonObject tags) {
		int dash = version.indexOf('-');
		if (dash >= 0) {
			String pre = version.substring(dash + 1).toLowerCase(Locale.ROOT);
			if (pre.startsWith("rc")) {
				return Channel.RC;
			}
			if (pre.startsWith("beta")) {
				return Channel.BETA;
			}
			if (pre.startsWith("alpha")) {
				return Channel.ALPHA;
			}
			return Channel.OTHER;
		}
		if (tags != null) {
			for (Map.Entry<String, JsonElement> tag : tags.entrySet()) {
				if (!tag.getValue().isJsonPrimitive() || !version.equals(tag.getValue().getAsString())) {
					continue;
				}
				switch (tag.getKey().toLowerCase(Locale.ROOT)) {
					case "rc" -> {
						return Channel.RC;
					}
					case "beta" -> {
						return Channel.BETA;
					}
					case "alpha" -> {
						return Channel.ALPHA;
					}
					case "dev", "next", "canary", "snapshot" -> {
						return Channel.OTHER;
					}
					default -> {
					}
				}
			}
		}
		return Channel.STABLE;
	}

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

	private static @Nullable JsonObject object(@Nullable JsonObject object, String name) {
		if (object == null || !object.has(name) || !object.get(name).isJsonObject()) {
			return null;
		}
		return object.getAsJsonObject(name);
	}

	private static @Nullable Instant moment(@Nullable JsonObject times, String version) {
		if (times == null || !times.has(version) || !times.get(version).isJsonPrimitive()) {
			return null;
		}
		try {
			return Instant.parse(times.get(version).getAsString());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/**
	 * The channel a published version belongs to.
	 */
	public enum Channel {
		/** No pre-release suffix, release line. */
		STABLE,

		/** A release candidate. */
		RC,

		/** A beta. */
		BETA,

		/** An alpha. */
		ALPHA,

		/** A nightly or other pre-release channel. */
		OTHER
	}

	/**
	 * What one version says about the harness an instance runs.
	 */
	public enum Fit {
		/** Declares the harness's packages, and everything is satisfied. */
		FITS,

		/** Declares the harness's packages, and something is not satisfied. */
		NOT_FITS,

		/** Declares nothing about the harness. */
		UNCLAIMED
	}

	/**
	 * One published version.
	 *
	 * @param version      the version
	 * @param channel      the channel it belongs to
	 * @param published    when it was published, or {@code null} when not available
	 * @param harnessPeers what it declares about the harness's own packages
	 * @param claimed      the harness versions the declaration names, newest first
	 */
	public record Published(String version, Channel channel, @Nullable Instant published, JsonObject harnessPeers,
	                        List<String> claimed) {
	}

	/**
	 * A run of versions shown under one heading.
	 *
	 * @param harnessVersion the harness version the group is about, or {@code null}
	 * @param recommended    whether this is the group that fits the instance the page is for
	 * @param versions       the versions, newest first
	 */
	public record Group(@Nullable String harnessVersion, boolean recommended, List<Published> versions) {
	}
}

