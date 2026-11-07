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

import com.mcxcc303.hdsl.core.DshException;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.skin.DshSkin;
import com.mcxcc303.hdsl.skin.DshSkinChoice;
import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * Publishes metadata describing the active account and instance into the process environment
 * and {@code .env} contract.
 */
@NotNullByDefault
public final class DshAccountContract {
	/**
	 * Contract version property name.
	 */
	public static final String CONTRACT = "HDSL_ACCOUNT_CONTRACT";

	/**
	 * Current contract version.
	 */
	public static final String VERSION = "1";

	/**
	 * Account display name property.
	 */
	public static final String ACCOUNT_NAME = "HDSL_ACCOUNT_NAME";

	/**
	 * Account route name property.
	 */
	public static final String ACCOUNT_ROUTE = "HDSL_ACCOUNT_ROUTE";

	/**
	 * Account vendor identifier property.
	 */
	public static final String ACCOUNT_VENDOR = "HDSL_ACCOUNT_VENDOR";

	/**
	 * Account kind property ({@code official}, {@code third-party}, or {@code offline}).
	 */
	public static final String ACCOUNT_KIND = "HDSL_ACCOUNT_KIND";

	/**
	 * Account endpoint URL property.
	 */
	public static final String ACCOUNT_ENDPOINT = "HDSL_ACCOUNT_ENDPOINT";

	/**
	 * Default model property.
	 */
	public static final String ACCOUNT_MODEL = "HDSL_ACCOUNT_MODEL";

	/**
	 * Skin type property.
	 */
	public static final String ACCOUNT_SKIN = "HDSL_ACCOUNT_SKIN";

	/**
	 * Skin body model property ({@code default} or {@code slim}).
	 */
	public static final String ACCOUNT_SKIN_MODEL = "HDSL_ACCOUNT_SKIN_MODEL";

	/**
	 * Absolute path to local skin file.
	 */
	public static final String ACCOUNT_SKIN_FILE = "HDSL_ACCOUNT_SKIN_FILE";

	/**
	 * Absolute path to local cape file.
	 */
	public static final String ACCOUNT_CAPE_FILE = "HDSL_ACCOUNT_CAPE_FILE";

	/**
	 * Instance identifier property.
	 */
	public static final String INSTANCE_ID = "HDSL_INSTANCE_ID";

	/**
	 * Pinned harness version property.
	 */
	public static final String INSTANCE_VERSION = "HDSL_INSTANCE_VERSION";

	/**
	 * Instance profile name property.
	 */
	public static final String INSTANCE_PROFILE = "HDSL_INSTANCE_PROFILE";

	/**
	 * Instance workspace path property.
	 */
	public static final String INSTANCE_WORKSPACE = "HDSL_INSTANCE_WORKSPACE";

	/**
	 * Launcher version property.
	 */
	public static final String LAUNCHER_VERSION = "HDSL_LAUNCHER_VERSION";

	private static final List<String> REFUSED_PREFIXES = List.of("DSH_", "XDG_", "DYLD_", "BASH_FUNC_");

	private static final Set<String> REFUSED_NAMES = Set.of("PATH",
	                                                        "HOME",
	                                                        "USERPROFILE",
	                                                        "SHELL",
	                                                        "NODE_OPTIONS",
	                                                        "NODE_PATH",
	                                                        "NODE_EXTRA_CA_CERTS",
	                                                        "LD_PRELOAD",
	                                                        "LD_LIBRARY_PATH",
	                                                        "LD_AUDIT");

	private static final String START = "# >>> HDSL account contract: written by the launcher, rewritten on every launch";
	private static final String END = "# <<< HDSL account contract";
	private static final String FILE = ".env";
	private static final String STAGING = ".hdsl-contract";
	private static final String SAFE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ" + "0123456789_./:@+-";

	private DshAccountContract() {
	}

	/**
	 * Builds the ordered key-value map for the account contract.
	 *
	 * @param instance the launching instance
	 * @param account  the account used for launch, or {@code null}
	 * @return unmodifiable map of contract keys and values
	 * @throws DshException if workspace paths cannot be resolved
	 */
	public static @Unmodifiable Map<String, String> values(DshInstance instance, @Nullable DshAccount account)
	throws DshException {
		Map<String, String> values = new LinkedHashMap<>();
		values.put(CONTRACT, VERSION);
		values.put(LAUNCHER_VERSION, Metadata.VERSION);
		values.put(INSTANCE_ID, instance.id());
		values.put(INSTANCE_VERSION, instance.version());
		values.put(INSTANCE_PROFILE, instance.profile());
		values.put(INSTANCE_WORKSPACE, instance.workspacePath().toString());

		if (account != null) {
			values.put(ACCOUNT_NAME, account.displayName());
			values.put(ACCOUNT_ROUTE, account.displayName());
			values.put(ACCOUNT_VENDOR, account.vendorId());
			values.put(ACCOUNT_KIND, kindOf(account));
			String endpoint = account.endpoint();
			if (endpoint != null && !endpoint.isBlank()) {
				values.put(ACCOUNT_ENDPOINT, endpoint.trim());
			}
			String model = account.modelOrDefault();
			if (!model.isBlank()) {
				values.put(ACCOUNT_MODEL, model);
			}

			// Export skin metadata and file paths if configured.
			DshSkinChoice worn = account.skinOrDefault();
			values.put(ACCOUNT_SKIN, skinName(worn.type()));
			values.put(ACCOUNT_SKIN_MODEL, worn.model().modelName);
			if (DshSkin.isSet(account.key())) {
				values.put(ACCOUNT_SKIN_FILE, DshSkin.file(account.key()).toString());
			}
			if (Files.isRegularFile(DshSkin.capeFile(account.key()))) {
				values.put(ACCOUNT_CAPE_FILE, DshSkin.capeFile(account.key()).toString());
			}
		}
		// Preserve deterministic key ordering for clean diffs.
		return Collections.unmodifiableMap(values);
	}

	private static String skinName(DshSkinChoice.Type type) {
		return switch (type) {
			case DEFAULT -> "default";
			case STEVE -> "steve";
			case ALEX -> "alex";
			case LOCAL_FILE -> "local";
		};
	}

	private static String kindOf(DshAccount account) {
		return switch (account.kind()) {
			case OFFICIAL -> "official";
			case THIRD_PARTY -> "third-party";
			case OFFLINE -> "offline";
		};
	}

	/**
	 * Writes the account contract block into the instance home's {@code .env} file.
	 *
	 * @param instance the launching instance
	 * @param account  the account used for launch, or {@code null}
	 * @throws DshException if reading or writing fails
	 */
	public static void publish(DshInstance instance, @Nullable DshAccount account) throws DshException {
		Path file = fileOf(instance);
		String existing;
		try {
			existing = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
		} catch (IOException e) {
			throw new DshException("Failed to read " + file, e);
		}

		String updated = withBlock(existing, values(instance, account));
		Path staging = file.resolveSibling(".env" + STAGING);
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			// Write via staging file and replace atomically.
			Files.writeString(staging, updated, StandardCharsets.UTF_8);
			try {
				Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new DshException("Failed to write " + file, e);
		}
	}

	/**
	 * Replaces or inserts the contract block in {@code .env} content, preserving surrounding lines.
	 *
	 * @param text   existing file text
	 * @param values contract values to insert
	 * @return updated file content
	 */
	static String withBlock(String text, Map<String, String> values) {
		StringBuilder kept = new StringBuilder();
		boolean inside = false;
		for (String line : text.split("\n", -1)) {
			if (line.equals(START)) {
				inside = true;
				continue;
			}
			if (inside) {
				if (line.equals(END)) {
					inside = false;
				}
				continue;
			}
			kept.append(line).append('\n');
		}

		StringBuilder out = new StringBuilder(kept.toString().stripTrailing());
		if (out.length() > 0) {
			out.append("\n\n");
		}
		out.append(START).append('\n');
		for (Map.Entry<String, String> value : values.entrySet()) {
			if (refused(value.getKey())) {
				LOG.warning("Not writing " + value.getKey() + " to .env: the harness refuses it");
				continue;
			}
			out.append(value.getKey()).append('=').append(quoted(value.getValue())).append('\n');
		}
		out.append(END).append('\n');
		return out.toString();
	}

	/**
	 * Reports whether an environment variable name is disallowed in {@code .env}.
	 *
	 * @param name variable name
	 * @return true if the variable name is refused
	 */
	static boolean refused(String name) {
		String upper = name.toUpperCase(Locale.ROOT);
		return REFUSED_NAMES.contains(upper) || REFUSED_PREFIXES.stream().anyMatch(upper::startsWith);
	}

	/**
	 * Quotes a value for {@code .env} file output if it contains special characters.
	 *
	 * @param value value to format
	 * @return quoted or bare string
	 */
	static String quoted(String value) {
		boolean bare = !value.isEmpty();
		for (int i = 0; bare && i < value.length(); i++) {
			bare = SAFE.indexOf(value.charAt(i)) >= 0;
		}
		if (bare) {
			return value;
		}
		return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

	/**
	 * Returns the path to the {@code .env} file for an instance.
	 *
	 * @param instance target instance
	 * @return path to {@code .env}
	 * @throws DshException if the instance home cannot be resolved
	 */
	static Path fileOf(DshInstance instance) throws DshException {
		return instance.homeDirectory().resolve(FILE);
	}
}
