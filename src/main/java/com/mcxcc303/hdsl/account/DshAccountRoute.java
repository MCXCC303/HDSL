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
import com.mcxcc303.hdsl.core.YamlScalar;
import com.mcxcc303.hdsl.plugin.DshPluginPatch;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

/**
 * Generates and applies model provider routes in a profile's patch layer during launch.
 */
@NotNullByDefault
public final class DshAccountRoute {
	/**
	 * Prefix for the environment variable that carries the route's API key.
	 */
	public static final String KEY_ENVIRONMENT_VARIABLE = "HDSL_LAUNCH_API_KEY";

	/**
	 * Environment variable read by the harness's built-in DeepSeek web search.
	 */
	public static final String WEB_SEARCH_ENVIRONMENT_VARIABLE = "DEEPSEEK_API_KEY";

	private static final String ENTRY = "llm-pi-ai";
	private static final int READABLE_LIMIT = 24;
	private static final Set<String> IMAGE_MODELS = Set.of("deepseek-flash", "deepseek-v4-flash-vision-exp");
	private static final int DEEPSEEK_CONTEXT_WINDOW = 1_000_000;
	private static final int DEEPSEEK_MAX_TOKENS = 256_000;

	private DshAccountRoute() {
	}

	/**
	 * Computes the environment variable name used to deliver the API key for a route.
	 *
	 * @param route the route name
	 * @return the environment variable name
	 */
	public static String environmentVariable(String route) {
		String readable = route.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
		if (readable.length() > READABLE_LIMIT) {
			readable = readable.substring(0, READABLE_LIMIT);
		}
		if (readable.isEmpty()) {
			readable = "ROUTE";
		}
		return KEY_ENVIRONMENT_VARIABLE + "_" + readable + "_" + String.format("%08X", route.hashCode());
	}

	/**
	 * Prepares route configuration for an account without querying remote endpoints.
	 *
	 * @param account the account, or {@code null}
	 * @return optional prepared route configuration
	 */
	public static Optional<Prepared> prepare(@Nullable DshAccount account) {
		if (account == null || !account.carriesAKey()) {
			return Optional.empty();
		}
		DshVendor vendor = account.vendor();
		return Optional.of(new Prepared(account.displayName(),
		                                vendor == null ? "openai-completions" : vendor.api(),
		                                account.modelOrDefault(),
		                                account.endpoint(),
		                                isDeepSeek(vendor, account.endpoint())));
	}

	/**
	 * Writes the account route into the profile's patch layer.
	 * <p>
	 * If the file already contains this exact entry, it is left untouched.
	 *
	 * @param home    the instance's {@code DSH_HOME}
	 * @param profile the profile the instance boots
	 * @param route   the described route
	 * @return true if the file was written, false if it already matched
	 * @throws DshException if the file cannot be read or written
	 */
	public static boolean apply(Path home, String profile, Prepared route) throws DshException {
		return DshProfilePatch.putProvider(DshPluginPatch.patchFile(home, profile),
		                                   ENTRY,
		                                   route.route(),
		                                   route.render()).changed();
	}

	/**
	 * Removes an account route from the profile's patch layer.
	 *
	 * @param home    the instance's {@code DSH_HOME}
	 * @param profile the profile name
	 * @param route   the route name
	 * @return true if the file was modified
	 * @throws DshException if reading or writing fails
	 */
	public static boolean remove(Path home, String profile, String route) throws DshException {
		return DshProfilePatch.removeProvider(DshPluginPatch.patchFile(home, profile), ENTRY, route).changed();
	}

	private static boolean isDeepSeek(@Nullable DshVendor vendor, @Nullable String endpoint) {
		if (vendor != null && "deepseek".equals(vendor.id())) {
			return true;
		}
		String host = DshVendor.hostOf(endpoint);
		return host != null && (host.equals("deepseek.com") || host.endsWith(".deepseek.com"));
	}

	/**
	 * Prepared route configuration ready to resolve models and render YAML settings.
	 */
	public static final class Prepared {
		private final String route;
		private final String api;
		private final String fallbackModel;
		private final @Nullable String endpoint;
		private final boolean deepSeek;
		private @Nullable List<String> served;

		private Prepared(String route, String api, String fallbackModel, @Nullable String endpoint, boolean deepSeek) {
			this.route = route;
			this.api = api;
			this.fallbackModel = fallbackModel;
			this.endpoint = endpoint;
			this.deepSeek = deepSeek;
		}

		/**
		 * Returns the route name recognized by the harness.
		 *
		 * @return the route name
		 */
		public String route() {
			return route;
		}

		/**
		 * Returns the environment variable name holding the route's API key.
		 *
		 * @return the environment variable name
		 */
		public String environmentVariable() {
			return DshAccountRoute.environmentVariable(route);
		}

		/**
		 * Reports whether this route targets DeepSeek models.
		 *
		 * @return true if targeting DeepSeek
		 */
		public boolean deepSeek() {
			return deepSeek;
		}

		/**
		 * Queries the remote supplier for its available models.
		 *
		 * @param account the account whose supplier is queried
		 */
		public void resolveModels(DshAccount account) {
			resolveModels(account.fetchModels());
		}

		void resolveModels(List<String> models) {
			this.served = models;
		}

		/**
		 * Renders the route configuration YAML block under {@code config.providers.<route>}.
		 *
		 * @return the YAML string
		 * @throws DshException if no models could be resolved or configured
		 */
		String render() throws DshException {
			String model = fallbackModel;
			StringBuilder yaml = new StringBuilder();
			yaml.append("apiKeyEnv: ").append(environmentVariable()).append("\n");
			yaml.append("api: ").append(api).append("\n");
			if (endpoint != null && !endpoint.isBlank()) {
				yaml.append("baseURL: ").append(YamlScalar.of(endpoint.trim())).append("\n");
			}
			if (deepSeek) {
				// Match dsh-llm-deepseek defaults for context window and max tokens.
				yaml.append("defaultContextWindow: ").append(DEEPSEEK_CONTEXT_WINDOW).append("\n");
				yaml.append("defaultMaxTokens: ").append(DEEPSEEK_MAX_TOKENS).append("\n");
			}
			yaml.append("models:\n");
			// Use fetched models or fallback model from account configuration.
			List<String> models = new ArrayList<>(served == null ? List.of() : served);
			if (models.isEmpty() && !model.isEmpty()) {
				models.add(model);
			}
			if (models.isEmpty()) {
				throw new DshException(
						"Could not read the models of " + endpoint + ", so there is" + " no route to write for " +
						route + ". This route is not one the harness" +
						" ships, so it cannot fill the list in either: check that the address answers" +
						" and that this machine can reach it, or name a model on the account.");
			}
			for (String one : models) {
				yaml.append("  - id: ").append(YamlScalar.of(one)).append("\n");
				yaml.append("    name: ").append(YamlScalar.of(one)).append("\n");
				if (IMAGE_MODELS.contains(one)) {
					// Declare multimodal input capability for supported vision models.
					yaml.append("    input:\n");
					yaml.append("      - text\n");
					yaml.append("      - image\n");
				}
				if (deepSeek) {
					// DeepSeek reasoning effort levels.
					yaml.append("    reasoningEfforts:\n");
					yaml.append("      off: null\n");
					yaml.append("      low: low\n");
					yaml.append("      high: high\n");
					yaml.append("      max: max\n");
				}
			}
			return yaml.toString();
		}
	}
}
