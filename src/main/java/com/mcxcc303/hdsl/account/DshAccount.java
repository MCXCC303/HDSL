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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.mcxcc303.hdsl.instance.DshInstance;
import com.mcxcc303.hdsl.instance.DshInstanceSettings;
import com.mcxcc303.hdsl.skin.DshSkinChoice;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * Model supplier account definition, credentials, and verification routines.
 *
 * @param kind     the account kind (official, third-party, or offline)
 * @param vendorId the vendor identifier and harness route name
 * @param apiKey   the API key credential
 * @param baseUrl  custom endpoint URL, or {@code null}
 * @param label    user-defined display label, or {@code null}
 * @param model    default model identifier, or {@code null}
 * @param skin     custom skin choice, or {@code null}
 */
public record DshAccount(
		AccountKind kind, String vendorId, String apiKey, @Nullable String baseUrl, @Nullable String label,
		@Nullable String model,
		@Nullable DshSkinChoice skin) {

	private static final Duration TIMEOUT = Duration.ofSeconds(20);
	private static final String USER_AGENT =
			Metadata.NAME + "/" + Metadata.VERSION + " (+" + Metadata.HOMEPAGE_URL + ")";
	private static final String ANTHROPIC_API = "anthropic-messages";
	private static final int ANTHROPIC_MODEL_LIMIT = 1000;

	/**
	 * Creates an official or third-party account without a configured default model.
	 *
	 * @param vendorId the vendor identifier
	 * @param apiKey   the API key
	 * @param baseUrl  the endpoint URL, or {@code null}
	 * @param label    display label, or {@code null}
	 */
	public DshAccount(String vendorId, String apiKey, @Nullable String baseUrl, @Nullable String label) {
		this(vendorId.equals(DshVendor.offered().get(0).id()) ? AccountKind.OFFICIAL : AccountKind.THIRD_PARTY,
		     vendorId,
		     apiKey,
		     baseUrl,
		     label,
		     null,
		     null);
	}

	/**
	 * Creates an account of a specified kind without a configured default model.
	 *
	 * @param kind     the account kind
	 * @param vendorId the vendor identifier
	 * @param apiKey   the API key
	 * @param baseUrl  the endpoint URL, or {@code null}
	 * @param label    display label, or {@code null}
	 */
	public DshAccount(AccountKind kind,
	                  String vendorId,
	                  String apiKey,
	                  @Nullable String baseUrl,
	                  @Nullable String label) {
		this(kind, vendorId, apiKey, baseUrl, label, null, null);
	}

	/**
	 * Creates an offline account with no API key or remote endpoints.
	 *
	 * @param label the account label
	 * @return the offline account
	 */
	public static DshAccount offline(String label) {
		return new DshAccount(AccountKind.OFFLINE, "offline", "", null, label, null, null);
	}

	/**
	 * Reports whether a name can be used as a harness route identifier.
	 *
	 * @param name the candidate name
	 * @return true if valid as a route identifier
	 */
	public static boolean isUsableName(@Nullable String name) {
		return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
	}

	/**
	 * Queries an endpoint's model listing without providing an API key.
	 *
	 * @param baseUrl the base endpoint URL
	 * @return the response answer
	 */
	public static Answer ask(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return new Answer(-1, false);
		}
		String url = listingUrl(baseUrl.trim(), "openai-completions");
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			                                 .timeout(TIMEOUT)
			                                 .header("Accept", "application/json")
			                                 .header("User-Agent", USER_AGENT)
			                                 .GET()
			                                 .build();
			try (HttpClient client = HttpClient.newBuilder()
			                                   .connectTimeout(TIMEOUT)
			                                   .followRedirects(HttpClient.Redirect.NORMAL)
			                                   .build()) {
				HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
				return new Answer(response.statusCode(), looksLikeAListing(response.body()));
			}
		} catch (IOException e) {
			String why =
					e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
			Logger.LOG.info("Nothing answered at " + url + ": " + why);
			return new Answer(-1, false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Answer(-1, false);
		} catch (RuntimeException e) {
			Logger.LOG.info("Not an address: " + baseUrl);
			return new Answer(-1, false);
		}
	}

	/**
	 * Reports whether a response body parses as a JSON model listing.
	 *
	 * @param body the response body
	 * @return true if containing models array or object
	 */
	static boolean looksLikeAListing(@Nullable String body) {
		if (body == null || body.isBlank()) {
			return false;
		}
		try {
			JsonElement parsed = JsonParser.parseString(body);
			if (!parsed.isJsonObject()) {
				return false;
			}
			JsonObject root = parsed.getAsJsonObject();
			JsonElement data = root.get("data");
			if (data != null && data.isJsonArray()) {
				return true;
			}
			JsonElement models = root.get("models");
			return models != null && models.isJsonObject();
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * Constructs the full models listing URL for an address and protocol.
	 *
	 * @param address the endpoint URL
	 * @param api     the wire protocol name
	 * @return the models listing URL
	 */
	static String listingUrl(String address, String api) {
		String base = address.replaceAll("/+$", "");
		if (!ANTHROPIC_API.equals(api)) {
			return base + "/models";
		}
		String root = base.endsWith("/v1") ? base.substring(0, base.length() - "/v1".length()) : base;
		return root + "/v1/models?limit=" + ANTHROPIC_MODEL_LIMIT;
	}

	/**
	 * Extracts an error message from a refused response body.
	 *
	 * @param body the response body
	 * @return the extracted message, or {@code null}
	 */
	static @Nullable String saidIn(@Nullable String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonElement parsed = JsonParser.parseString(body);
			if (!parsed.isJsonObject()) {
				return null;
			}
			JsonObject root = parsed.getAsJsonObject();
			String said = saidInValue(root.get("message"));
			if (said == null) {
				JsonElement error = root.get("error");
				said = error != null && error.isJsonObject()
				       ? saidInValue(error.getAsJsonObject().get("message"))
				       : saidInValue(error);
			}
			if (said == null) {
				return null;
			}
			String oneLine = said.replaceAll("\\s+", " ").trim();
			return oneLine.isEmpty() ? null : oneLine;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static @Nullable String saidInValue(@Nullable JsonElement element) {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return null;
		}
		String value = element.getAsString();
		return value.isBlank() ? null : value;
	}

	/**
	 * Extracts model identifiers from a response body.
	 *
	 * @param body the response body
	 * @return list of model identifiers
	 */
	static List<String> readModelIds(@Nullable String body) {
		if (body == null || body.isBlank()) {
			return List.of();
		}
		try {
			JsonElement parsed = JsonParser.parseString(body);
			if (!parsed.isJsonObject()) {
				return List.of();
			}
			JsonObject root = parsed.getAsJsonObject();
			List<String> ids = new ArrayList<>();
			JsonElement data = root.get("data");
			if (data != null && data.isJsonArray()) {
				for (JsonElement element : data.getAsJsonArray()) {
					if (element.isJsonObject()) {
						addModelId(ids, idOf(element.getAsJsonObject()));
					}
				}
			} else {
				JsonElement models = root.get("models");
				if (models != null && models.isJsonObject()) {
					for (Map.Entry<String, JsonElement> entry : models.getAsJsonObject().entrySet()) {
						JsonElement value = entry.getValue();
						if (value == null || !value.isJsonObject()) {
							continue;
						}
						String key = entry.getKey();
						addModelId(ids, key != null && !key.isBlank() ? key : idOf(value.getAsJsonObject()));
					}
				}
			}
			return List.copyOf(ids);
		} catch (RuntimeException e) {
			return List.of();
		}
	}

	private static @Nullable String idOf(JsonObject entry) {
		JsonElement id = entry.get("id");
		if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
			return null;
		}
		String text = id.getAsString();
		return text.isBlank() ? null : text;
	}

	private static void addModelId(List<String> ids, @Nullable String id) {
		if (id != null && !id.isBlank() && !ids.contains(id)) {
			ids.add(id);
		}
	}

	/**
	 * Resolves the account configured for an instance launch.
	 *
	 * @param instance the target instance
	 * @return the account to launch with, or {@code null}
	 */
	public static @Nullable DshAccount forInstance(DshInstance instance) {
		try {
			List<DshAccount> accounts = SettingsManager.settings().getAccounts();
			if (accounts.isEmpty()) {
				return null;
			}
			String chosen = DshInstanceSettings.accountKey(instance);
			if (chosen != null) {
				for (DshAccount account : accounts) {
					if (account.matchesKey(chosen)) {
						return account;
					}
				}
				Logger.LOG.warning("Instance " + instance.id() + " names an account that no longer exists: " + chosen);
				return null;
			}
			DshAccount selected = SettingsManager.settings().activeAccount();
			return selected != null ? selected : accounts.get(0);
		} catch (RuntimeException e) {
			Logger.LOG.warning("Could not read the accounts", e);
			return null;
		}
	}

	/**
	 * Returns the configured skin choice, or the default choice if unset.
	 *
	 * @return the skin choice
	 */
	public DshSkinChoice skinOrDefault() {
		return skin == null ? DshSkinChoice.DEFAULT : skin;
	}

	/**
	 * Returns a copy of this account with an updated skin choice.
	 *
	 * @param newSkin the new skin choice
	 * @return updated account copy
	 */
	public DshAccount withSkin(DshSkinChoice newSkin) {
		return new DshAccount(kind, vendorId, apiKey, baseUrl, label, model, newSkin);
	}

	/**
	 * Resolves the vendor definition for this account.
	 *
	 * @return the vendor, or {@code null} if unknown
	 */
	public @Nullable DshVendor vendor() {
		return DshVendor.byId(vendorId);
	}

	/**
	 * Reports whether this account carries a usable API key credential.
	 *
	 * @return true if non-offline and API key is present
	 */
	public boolean carriesAKey() {
		return kind != AccountKind.OFFLINE && apiKey != null && !apiKey.isBlank();
	}

	/**
	 * Returns the unique key identifying this account in settings.
	 *
	 * @return compound key string
	 */
	public String key() {
		return vendorId + (label == null || label.isBlank() ? "" : "|" + label.trim());
	}

	/**
	 * Returns the configured model identifier, or an empty string if unset.
	 *
	 * @return the model identifier
	 */
	public String modelOrDefault() {
		return model == null ? "" : model.trim();
	}

	/**
	 * Reports whether this account matches the specified storage key.
	 *
	 * @param key the candidate key
	 * @return true if matching
	 */
	public boolean matchesKey(@Nullable String key) {
		return key != null && key.equals(key());
	}

	/**
	 * Returns the resolved endpoint URL for this account.
	 *
	 * @return endpoint URL, or {@code null}
	 */
	public @Nullable String endpoint() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			return baseUrl.trim();
		}
		DshVendor vendor = vendor();
		if (vendor != null && vendor.hasBaseUrl()) {
			return vendor.baseUrl();
		}
		for (DshVendor added : SettingsManager.settings().getCustomVendors()) {
			if (added.hasBaseUrl() && added.id().equalsIgnoreCase(vendorId)) {
				return added.baseUrl();
			}
		}
		return null;
	}

	/**
	 * Returns the display name shown in the user interface.
	 *
	 * @return display name
	 */
	public String displayName() {
		if (label != null && !label.isBlank()) {
			return label.trim();
		}
		DshVendor vendor = vendor();
		return vendor == null ? vendorId : vendor.displayName();
	}

	/**
	 * Returns a masked representation of the API key for display.
	 *
	 * @return masked key string
	 */
	public String maskedKey() {
		String key = apiKey == null ? "" : apiKey.trim();
		if (key.length() <= 8) {
			return "••••";
		}
		return key.substring(0, 4) + "••••" + key.substring(key.length() - 4);
	}

	/**
	 * Validates the API key by querying the vendor's models listing endpoint.
	 *
	 * @return the validation result
	 */
	public Check check() {
		String address = endpoint();
		if (address == null || address.isBlank()) {
			return new Check(Outcome.UNREACHABLE, i18n("dsh.account.check.no_endpoint", vendorId));
		}
		DshVendor vendor = vendor();
		String api = vendor == null ? "openai-completions" : vendor.api();
		String url = listingUrl(address, api);

		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
			                                         .timeout(TIMEOUT)
			                                         .header("Accept", "application/json")
			                                         .header("User-Agent", USER_AGENT)
			                                         .GET();
			if ("anthropic-messages".equals(api)) {
				request.header("x-api-key", apiKey == null ? "" : apiKey.trim());
				request.header("anthropic-version", "2023-06-01");
			} else {
				request.header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey.trim()));
			}

			try (HttpClient client = HttpClient.newBuilder()
			                                   .connectTimeout(TIMEOUT)
			                                   .followRedirects(HttpClient.Redirect.NORMAL)
			                                   .build()) {
				HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
				int status = response.statusCode();
				if (status >= 200 && status < 300) {
					return looksLikeAListing(response.body())
					       ? new Check(Outcome.VALID,
					                   i18n("dsh.account.check.valid"))
					       : new Check(Outcome.UNKNOWN,
					                   i18n("dsh.account.check.no_listing", String.valueOf(status), url));
				}
				if (status == 401 || status == 403) {
					String said = saidIn(response.body());
					return new Check(Outcome.REJECTED,
					                 i18n("dsh.account.check.not_accepted",
					                      String.valueOf(status),
					                      said == null ? "" : said));
				}
				if (status == 404 || status == 400 || status == 405) {
					return new Check(Outcome.UNKNOWN,
					                 i18n("dsh.account.check.no_listing", String.valueOf(status), url));
				}
				return new Check(Outcome.UNREACHABLE, i18n("dsh.account.check.http", String.valueOf(status)));
			}
		} catch (IOException e) {
			String why =
					e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
			return new Check(Outcome.UNREACHABLE, i18n("dsh.account.check.unreachable", url, why));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Check(Outcome.UNREACHABLE, i18n("dsh.account.check.interrupted"));
		} catch (RuntimeException e) {
			return new Check(Outcome.UNREACHABLE,
			                 i18n("dsh.account.check.bad_address", String.valueOf(e.getMessage())));
		}
	}

	/**
	 * Queries the remote vendor for available model identifiers.
	 *
	 * @return list of model identifiers served by the vendor
	 */
	public List<String> fetchModels() {
		String address = endpoint();
		if (address == null || address.isBlank()) {
			return List.of();
		}
		DshVendor vendor = vendor();
		String api = vendor == null ? "openai-completions" : vendor.api();
		String url = listingUrl(address, api);

		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
			                                         .timeout(TIMEOUT)
			                                         .header("Accept", "application/json")
			                                         .header("User-Agent", USER_AGENT)
			                                         .GET();
			if ("anthropic-messages".equals(api)) {
				request.header("x-api-key", apiKey == null ? "" : apiKey.trim());
				request.header("anthropic-version", "2023-06-01");
			} else {
				request.header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey.trim()));
			}

			try (HttpClient client = HttpClient.newBuilder()
			                                   .connectTimeout(TIMEOUT)
			                                   .followRedirects(HttpClient.Redirect.NORMAL)
			                                   .build()) {
				HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() < 200 || response.statusCode() >= 300) {
					return List.of();
				}
				return readModelIds(response.body());
			}
		} catch (IOException | InterruptedException | RuntimeException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			Logger.LOG.info("Could not read the model list of " + url + ": " + e.getMessage());
			return List.of();
		}
	}

	/**
	 * Account kind classification.
	 */
	public enum AccountKind {
		@SerializedName("official")
		OFFICIAL,

		@SerializedName("third-party")
		THIRD_PARTY,

		@SerializedName("offline")
		OFFLINE
	}

	/**
	 * Key validation check outcome.
	 */
	public enum Outcome {
		VALID,
		REJECTED,
		UNREACHABLE,
		UNKNOWN
	}

	/**
	 * Response from a model listing probe.
	 *
	 * @param status  HTTP status code, or -1 on network failure
	 * @param listing whether the response body resembles a model listing
	 */
	public record Answer(int status, boolean listing) {

		/**
		 * Reports whether the endpoint appears to host a model API.
		 *
		 * @return true if the response was a listing or auth challenge
		 */
		public boolean listsModels() {
			return listing || status == 401 || status == 403;
		}
	}

	/**
	 * Outcome and human-readable message from an account credential check.
	 *
	 * @param outcome check outcome status
	 * @param message localized result message
	 */
	public record Check(Outcome outcome, String message) {
	}
}
