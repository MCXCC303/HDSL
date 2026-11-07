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
package com.mcxcc303.hdsl.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.core.YamlScalar;
import com.mcxcc303.hdsl.instance.DshInstance;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Tracks, injects, and cleans up temporary route and model configurations in {@code settings.yaml}.
 */
@NotNullByDefault
public final class DshInjectedSettings {
	private static final String FILE = "settings.yaml";
	private static final String DEFAULT_MODEL = "agent-default-model";
	private static final String NOTE = ".hdsl-injected.json";
	private static final String LEDGER = ".hdsl-injected-routes.json";
	private static final String PROVIDERS = "llm-pi-ai";
	private static final String PROVIDERS_KEY = "providers";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private DshInjectedSettings() {
	}

	/**
	 * Saves a snapshot of existing settings before injecting launch configuration.
	 *
	 * @param instance     the launching instance
	 * @param route        the supplier route being injected
	 * @param account      the account key, or {@code null}
	 * @param patchProfile the target profile name
	 * @throws DshException if writing the stash file fails
	 */
	public static void capture(DshInstance instance, String route, @Nullable String account, String patchProfile)
	throws DshException {
		Path settings = settingsOf(instance);
		String text = read(settings);
		Stash stash = new Stash(instance.id(),
		                        route,
		                        account,
		                        sectionBlock(text, DEFAULT_MODEL),
		                        routeBlock(text, PROVIDERS, PROVIDERS_KEY, route),
		                        patchProfile,
		                        Instant.now().toString());
		write(noteOf(instance), GSON.toJson(stash));

		LinkedHashSet<String> routes = new LinkedHashSet<>(routesOf(instance));
		if (routes.add(route)) {
			write(ledgerOf(instance), GSON.toJson(new Ledger(List.copyOf(routes))));
		}
	}

	/**
	 * Removes injected routes and resets default model settings across launches.
	 *
	 * @param instance      the instance being prepared for launch
	 * @param patchProfile  the profile name
	 * @param accountRoutes known account route names to clean
	 * @param injecting     the route currently being injected, or {@code null}
	 * @return true if any files were modified
	 * @throws DshException if reading or writing fails
	 */
	public static boolean clean(DshInstance instance,
	                            String patchProfile,
	                            Collection<String> accountRoutes,
	                            @Nullable String injecting) throws DshException {
		boolean changed = false;
		for (String route : routesOf(instance)) {
			if (!route.equals(injecting)) {
				changed |= takeRouteBack(instance, patchProfile, route);
			}
		}

		LinkedHashSet<String> ours = new LinkedHashSet<>(routesOf(instance));
		ours.addAll(accountRoutes);
		if (injecting != null) {
			// Retain the route being injected by the current launch.
			ours.add(injecting);
		}
		ours.removeIf(route -> route == null || route.isBlank());
		Path settings = settingsOf(instance);
		String text = read(settings);
		if (ours.isEmpty() || text.isEmpty()) {
			return changed;
		}
		String updated = text;
		for (String route : ours) {
			updated = withRouteBlock(updated, PROVIDERS, PROVIDERS_KEY, route, null);
		}
		String current = sectionBlock(updated, DEFAULT_MODEL);
		String named = current == null ? null : scalarOf(current, "provider");
		if (named != null && ours.contains(named) && !named.equals(injecting)) {
			updated = withSectionBlock(updated, DEFAULT_MODEL, null);
		}
		if (!updated.equals(text)) {
			write(settings, updated);
			changed = true;
		}
		return changed;
	}

	private static boolean takeRouteBack(DshInstance instance, String profile, String route) throws DshException {
		return DshAccountRoute.remove(instance.homeDirectory(), profile, route);
	}

	private static List<String> routesOf(DshInstance instance) throws DshException {
		Path ledger = ledgerOf(instance);
		if (!Files.isRegularFile(ledger)) {
			return List.of();
		}
		try {
			Ledger read = GSON.fromJson(Files.readString(ledger, StandardCharsets.UTF_8), Ledger.class);
			return read == null || read.routes() == null ? List.of() : read.routes();
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not read " + ledger, e);
			return List.of();
		}
	}

	private static Path ledgerOf(DshInstance instance) throws DshException {
		return settingsOf(instance).resolveSibling(LEDGER);
	}

	/**
	 * Injects the provider reference and environment variable into {@code settings.yaml}.
	 *
	 * @param instance  the launching instance
	 * @param route     the route name
	 * @param apiKeyEnv the environment variable name carrying the API key
	 * @return true if the settings were modified
	 * @throws DshException if reading or writing fails
	 */
	public static boolean publish(DshInstance instance, String route, String apiKeyEnv) throws DshException {
		Path settings = settingsOf(instance);
		String text = read(settings);
		String block = YamlScalar.of(route) + ":\n  apiKeyEnv: " + YamlScalar.of(apiKeyEnv) + "\n";
		String updated = withRouteBlock(text, PROVIDERS, PROVIDERS_KEY, route, block);
		if (updated.equals(text)) {
			return false;
		}
		write(settings, updated);
		return true;
	}

	/**
	 * Restores previous settings and deletes the stash note after a launch finishes.
	 *
	 * @param instance the instance being settled
	 * @return true if settings were restored
	 * @throws DshException if reading or writing fails
	 */
	public static boolean settle(DshInstance instance) throws DshException {
		Path note = noteOf(instance);
		if (!Files.isRegularFile(note)) {
			return false;
		}
		Stash stash = readStash(note);
		if (stash == null) {
			delete(note);
			return false;
		}

		// Remove route from profile patch layer.
		boolean changed = stash.patchProfile() != null && takeRouteBack(instance, stash.patchProfile(), stash.route());

		Path settings = settingsOf(instance);
		String text = read(settings);
		if (text.isEmpty()) {
			delete(note);
			return changed;
		}
		String updated = text;

		// Remove the injected route block if it did not exist prior to launch.
		if (stash.provider() == null) {
			updated = withRouteBlock(updated, PROVIDERS, PROVIDERS_KEY, stash.route(), null);
		}

		// Restore original default model if still pointing to this launch's route.
		String current = sectionBlock(updated, DEFAULT_MODEL);
		if (current != null && Objects.equals(scalarOf(current, "provider"), stash.route())) {
			updated = withSectionBlock(updated, DEFAULT_MODEL, stash.defaultModel());
		}

		if (!updated.equals(text)) {
			write(settings, updated);
			changed = true;
		}
		delete(note);
		return changed;
	}

	private static Path noteOf(DshInstance instance) throws DshException {
		return settingsOf(instance).resolveSibling(NOTE);
	}

	private static Path settingsOf(DshInstance instance) throws DshException {
		return instance.homeDirectory().resolve(FILE);
	}

	private static @Nullable Stash readStash(Path note) {
		try {
			JsonObject json = JsonParser.parseString(Files.readString(note, StandardCharsets.UTF_8)).getAsJsonObject();
			return new Stash(string(json, "instance"),
			                 string(json, "route"),
			                 nullableString(json, "account"),
			                 nullableString(json, "defaultModel"),
			                 nullableString(json, "provider"),
			                 nullableString(json, "patchProfile"),
			                 string(json, "writtenAt"));
		} catch (IOException | RuntimeException e) {
			LOG.warning("Could not read " + note, e);
			return null;
		}
	}

	private static String string(JsonObject json, String key) {
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
	}

	private static @Nullable String nullableString(JsonObject json, String key) {
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
	}

	private static String read(Path file) throws DshException {
		if (!Files.isRegularFile(file)) {
			return "";
		}
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new DshException("Could not read " + file, e);
		}
	}

	private static void write(Path file, String text) throws DshException {
		try {
			Files.createDirectories(file.getParent());
			Path staging = file.resolveSibling(file.getFileName() + ".hdsl-injecting");
			Files.writeString(staging, text, StandardCharsets.UTF_8);
			try {
				Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new DshException("Could not write " + file, e);
		}
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			LOG.info("Could not remove " + file, e);
		}
	}

	/**
	 * Extracts the lines of a top-level YAML section.
	 *
	 * @param text the YAML text
	 * @param name the section key name
	 * @return the section block lines, or {@code null} if not found
	 */
	static @Nullable String sectionBlock(String text, String name) {
		List<String> lines = lines(text);
		for (int i = 0; i < lines.size(); i++) {
			if (indentOf(lines.get(i)) == 0 && isKey(lines.get(i), name)) {
				return join(lines, i, endOfBlock(lines, i));
			}
		}
		return null;
	}

	/**
	 * Replaces or removes a top-level YAML section.
	 *
	 * @param text  the YAML text
	 * @param name  the section key name
	 * @param block replacement lines, or {@code null} to remove
	 * @return updated YAML text
	 */
	static String withSectionBlock(String text, String name, @Nullable String block) {
		List<String> lines = lines(text);
		for (int i = 0; i < lines.size(); i++) {
			if (indentOf(lines.get(i)) == 0 && isKey(lines.get(i), name)) {
				return splice(lines, i, endOfBlock(lines, i), block);
			}
		}
		if (block == null) {
			return text;
		}
		String base = text.isEmpty() || text.endsWith("\n") ? text : text + "\n";
		return base + block;
	}

	/**
	 * Extracts the YAML block for a nested provider route.
	 *
	 * @param text  the YAML text
	 * @param owner the top-level section name
	 * @param key   the nested container key
	 * @param route the route name
	 * @return the route block lines, or {@code null} if not found
	 */
	static @Nullable String routeBlock(String text, String owner, String key, String route) {
		List<String> lines = lines(text);
		int ownerAt = keyAt(lines, 0, lines.size(), 0, owner);
		if (ownerAt < 0) {
			return null;
		}
		int ownerEnd = endOfBlock(lines, ownerAt);
		int keyAt = nestedKeyAt(lines, ownerAt + 1, ownerEnd, indentOf(lines.get(ownerAt)), key);
		if (keyAt < 0) {
			return null;
		}
		int keyEnd = endOfBlock(lines, keyAt);
		int routeAt = nestedKeyAt(lines, keyAt + 1, keyEnd, indentOf(lines.get(keyAt)), route);
		return routeAt < 0 ? null : join(lines, routeAt, endOfBlock(lines, routeAt));
	}

	/**
	 * Replaces, inserts, or removes a nested provider route block.
	 *
	 * @param text  the YAML text
	 * @param owner the top-level section name
	 * @param key   the nested container key
	 * @param route the route name
	 * @param block replacement lines, or {@code null} to remove
	 * @return updated YAML text
	 */
	static String withRouteBlock(String text, String owner, String key, String route, @Nullable String block) {
		List<String> lines = lines(text);
		int ownerAt = keyAt(lines, 0, lines.size(), 0, owner);
		if (ownerAt < 0) {
			if (block == null) {
				return text;
			}
			String base = text.isEmpty() || text.endsWith("\n") ? text : text + "\n";
			return base + owner + ":\n  " + key + ":\n" + indent(block, 4);
		}
		int ownerIndent = indentOf(lines.get(ownerAt));
		int ownerEnd = endOfBlock(lines, ownerAt);
		int keyAt = nestedKeyAt(lines, ownerAt + 1, ownerEnd, ownerIndent, key);
		if (keyAt < 0) {
			if (block == null) {
				return text;
			}
			String grown = " ".repeat(ownerIndent + 2) + key + ":\n" + indent(block, ownerIndent + 4);
			return String.join("\n", insertAfter(lines, ownerAt, grown));
		}
		int keyEnd = endOfBlock(lines, keyAt);
		int routeAt = nestedKeyAt(lines, keyAt + 1, keyEnd, indentOf(lines.get(keyAt)), route);
		if (routeAt < 0) {
			return block == null
			       ? text
			       : String.join("\n", insertAfter(lines, keyAt, indent(block, indentOf(lines.get(keyAt)) + 2)));
		}
		return splice(lines, routeAt, endOfBlock(lines, routeAt), block);
	}

	private static List<String> insertAfter(List<String> lines, int after, String block) {
		List<String> result = new ArrayList<>(lines.subList(0, after + 1));
		List<String> inserted = lines(block);
		if (!inserted.isEmpty() && inserted.get(inserted.size() - 1).isEmpty()) {
			inserted.remove(inserted.size() - 1);
		}
		result.addAll(inserted);
		result.addAll(lines.subList(after + 1, lines.size()));
		return result;
	}

	private static String indent(String block, int spaces) {
		String padding = " ".repeat(spaces);
		StringBuilder indented = new StringBuilder();
		for (String line : lines(block)) {
			indented.append(line.isBlank() ? line : padding + line).append('\n');
		}
		return indented.toString();
	}

	/**
	 * Extracts the unquoted scalar value for a key in a YAML block.
	 *
	 * @param block the YAML block text
	 * @param name  the key name
	 * @return the unquoted scalar value, or {@code null} if not found
	 */
	static @Nullable String scalarOf(String block, String name) {
		for (String line : lines(block)) {
			if (isKey(line.trim(), name)) {
				String value = line.trim().substring(name.length() + 1).trim();
				int comment = commentAt(value);
				if (comment >= 0) {
					value = value.substring(0, comment).trim();
				}
				if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'') &&
				    value.charAt(value.length() - 1) == value.charAt(0)) {
					value = value.substring(1, value.length() - 1);
				}
				return value.isEmpty() ? null : value;
			}
		}
		return null;
	}

	private static int keyAt(List<String> lines, int from, int to, int indent, String name) {
		for (int i = from; i < to && i < lines.size(); i++) {
			if (!lines.get(i).isBlank() && indentOf(lines.get(i)) == indent && isKey(lines.get(i).trim(), name)) {
				return i;
			}
		}
		return -1;
	}

	private static int nestedKeyAt(List<String> lines, int from, int to, int parentIndent, String name) {
		for (int i = from; i < to && i < lines.size(); i++) {
			if (!lines.get(i).isBlank() && indentOf(lines.get(i)) > parentIndent && isKey(lines.get(i).trim(), name)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean isKey(String trimmed, String name) {
		if (!trimmed.startsWith(name + ":")) {
			return false;
		}
		return trimmed.length() == name.length() + 1 || Character.isWhitespace(trimmed.charAt(name.length() + 1));
	}

	private static int commentAt(String value) {
		char quote = 0;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (quote != 0) {
				if (c == quote) {
					quote = 0;
				}
				continue;
			}
			if (c == '"' || c == '\'') {
				quote = c;
			} else if (c == '#' && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) {
				return i;
			}
		}
		return -1;
	}

	private static int endOfBlock(List<String> lines, int start) {
		int indent = indentOf(lines.get(start));
		int i = start + 1;
		for (; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.isBlank()) {
				continue;
			}
			if (indentOf(line) <= indent) {
				break;
			}
		}
		return i;
	}

	private static int indentOf(String line) {
		int i = 0;
		while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
			i++;
		}
		return i;
	}

	private static List<String> lines(String text) {
		return new ArrayList<>(List.of(text.split("\n", -1)));
	}

	private static String join(List<String> lines, int from, int to) {
		StringBuilder block = new StringBuilder();
		for (int i = from; i < to && i < lines.size(); i++) {
			block.append(lines.get(i)).append('\n');
		}
		return block.toString();
	}

	private static String splice(List<String> lines, int from, int to, @Nullable String block) {
		List<String> result = new ArrayList<>(lines.subList(0, from));
		if (block != null) {
			result.addAll(lines(block));
			if (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) {
				result.remove(result.size() - 1);
			}
		}
		result.addAll(lines.subList(Math.min(to, lines.size()), lines.size()));
		if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty() &&
		    (result.isEmpty() || !result.get(result.size() - 1).isEmpty())) {
			result.add("");
		}
		return String.join("\n", result);
	}

	private record Ledger(List<String> routes) {
	}

	/**
	 * Snapshot of pre-launch settings state preserved for cleanup.
	 *
	 * @param instance     target instance identifier
	 * @param route        injected route name
	 * @param account      associated account key, or {@code null}
	 * @param defaultModel original {@code agent-default-model} block, or {@code null}
	 * @param provider     original provider block, or {@code null}
	 * @param patchProfile target profile name
	 * @param writtenAt    timestamp when captured
	 */
	public record Stash(String instance, String route, @Nullable String account, @Nullable String defaultModel,
	                    @Nullable String provider, @Nullable String patchProfile, String writtenAt) {
	}

}
