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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/// An account: a vendor and the key that is allowed to speak for it.
///
/// The original's accounts are logins it performs on the user's behalf; there is no login here,
/// because DeepSeek Harness is not a service with accounts — it is a program that talks to whichever
/// model supplier it is configured with. So the thing that stands in the same place is a key: what
/// the launcher can hold, check, and hand to an instance.
///
/// The key is kept in the launcher's own settings, which already holds this machine's proxy
/// password. It is not written into an instance, a profile or a pack.
///
/// @param vendorId  the vendor's id, which is also the route name in the harness
/// @param apiKey    the key
/// @param baseUrl   the endpoint, for a vendor whose address is per account; empty otherwise
/// @param label     what the person calls this account, for telling two of them apart
public record DshAccount(
        /// Which kind of account this is.
        ///
        /// A kind rather than a flag, because the two answer different questions. An **official** or
        /// **third-party** account is a key: the launcher hands it to the harness so the harness can
        /// talk to a supplier, and it is worth checking that the key still works before a launch. An
        /// **offline** account is a name and a face: nothing is handed to anybody, nothing is
        /// checked, and what it is for is being able to launch the harness the way it was configured
        /// by hand — which is a real arrangement and not an omission, the same way the original's
        /// offline mode lets somebody play without a login.
        AccountKind kind,
        String vendorId,
        String apiKey,
        @Nullable String baseUrl,
        @Nullable String label,
        /// The model the harness should start with on this account's route, or empty.
        ///
        /// Empty is a real answer and the default one: which model a route offers is the vendor's
        /// catalogue to describe, and the harness refuses to start on a default it cannot resolve,
        /// so a name invented here would turn a convenience into a launch that fails.
        @Nullable String model,

        /// How this account's skin was chosen.
        ///
        /// Stored as the *choice* rather than as the picture, as the original does: a picture alone
        /// cannot answer "which of the launcher's own skins is this", and so cannot draw the chooser
        /// in the state it was left in. Empty means the body's own default.
        @Nullable org.jackhuang.hmcl.dsh.skin.DshSkinChoice skin) {

    /// Returns the skin choice, never `null`.
    ///
    /// @return the choice, or the default one
    public org.jackhuang.hmcl.dsh.skin.DshSkinChoice skinOrDefault() {
        return skin == null ? org.jackhuang.hmcl.dsh.skin.DshSkinChoice.DEFAULT : skin;
    }

    /// Returns a copy with a different skin.
    ///
    /// @param newSkin the choice
    /// @return the copy
    public DshAccount withSkin(org.jackhuang.hmcl.dsh.skin.DshSkinChoice newSkin) {
        return new DshAccount(kind, vendorId, apiKey, baseUrl, label, model, newSkin);
    }

    /// Which kind of account this is.
    ///
    /// The names are pinned rather than left to the enum's own spelling, because they are written to
    /// a file: a rename in the code would otherwise be a rename of the stored format, and every
    /// account on disk would stop being readable. `THIRD_PARTY` is `third-party` for the same reason
    /// it is not `thirdParty` — the file is read by people as well as by this program.
    public enum AccountKind {
        /// The vendor the harness itself is built around, added as the leading choice.
        @com.google.gson.annotations.SerializedName("official")
        OFFICIAL,

        /// A supplier somebody asked for by name.
        @com.google.gson.annotations.SerializedName("third-party")
        THIRD_PARTY,

        /// A name and a skin, with nothing handed to the harness and nothing checked.
        @com.google.gson.annotations.SerializedName("offline")
        OFFLINE
    }

    /// Creates an official or third-party account with no model named.
    ///
    /// @param vendorId the vendor's id
    /// @param apiKey   the key
    /// @param baseUrl  the endpoint, or `null`
    /// @param label    what the person calls it, or `null`
    public DshAccount(String vendorId, String apiKey, @Nullable String baseUrl, @Nullable String label) {
        this(vendorId.equals(DshVendor.offered().get(0).id())
                        ? AccountKind.OFFICIAL : AccountKind.THIRD_PARTY,
                vendorId, apiKey, baseUrl, label, null, null);
    }

    /// Creates an account of a stated kind, naming no model.
    ///
    /// @param kind     the kind
    /// @param vendorId the vendor's id
    /// @param apiKey   the key
    /// @param baseUrl  the endpoint, or `null`
    /// @param label    what the person calls it, or `null`
    public DshAccount(AccountKind kind, String vendorId, String apiKey,
                      @Nullable String baseUrl, @Nullable String label) {
        this(kind, vendorId, apiKey, baseUrl, label, null, null);
    }

    /// Creates an offline account: a name, and nothing to check or hand over.
    ///
    /// @param label the name
    /// @return the account
    public static DshAccount offline(String label) {
        return new DshAccount(AccountKind.OFFLINE, "offline", "", null, label, null, null);
    }

    /// How long a key check is given.
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /// The vendor this account is for.
    ///
    /// @return the vendor, or `null` when the id names one this launcher does not offer
    public @Nullable DshVendor vendor() {
        return DshVendor.byId(vendorId);
    }

    /// Reports whether a name can be used as a route name in the harness.
    ///
    /// The name an account is given becomes the name of the supplier the harness is handed, so it
    /// has to be something the harness can address: letters, digits, and the three separators a
    /// route name is made of. A space would arrive as two arguments' worth of something the harness
    /// cannot find, and a colon would end the YAML key early.
    ///
    /// @param name the name
    /// @return whether it may be used
    public static boolean isUsableName(@Nullable String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    /// Reports whether this account has anything to hand to the harness.
    ///
    /// An offline account does not: it is a name and a face, and the launcher must not write a route
    /// for it, must not set a default model for it, and must not put a key in the child's
    /// environment. Everything that touches the harness asks this first.
    ///
    /// @return whether a key travels with this account
    public boolean carriesAKey() {
        return kind != AccountKind.OFFLINE && apiKey != null && !apiKey.isBlank();
    }

    /// Returns the key that names this account.
    ///
    /// The vendor id and the label together: a vendor may be used twice with two keys, and the
    /// label is what tells them apart. Stored on an instance to say which account it launches with,
    /// so it has to survive being written to a file and read back.
    ///
    /// @return the key
    public String key() {
        return vendorId + (label == null || label.isBlank() ? "" : "|" + label.trim());
    }

    /// Returns the model this account names, or empty.
    ///
    /// @return the model id
    public String modelOrDefault() {
        return model == null ? "" : model.trim();
    }

    /// Reports whether this account is the one a key names.
    ///
    /// @param key the key, or `null`
    /// @return whether they are the same account
    public boolean matchesKey(@Nullable String key) {
        return key != null && key.equals(key());
    }

    /// Returns the endpoint to talk to.
    ///
    /// The account's own address wins, so an account can point at a gateway the launcher has never
    /// heard of; otherwise the vendor's published one.
    ///
    /// @return the address, or `null` when neither is known
    public @Nullable String endpoint() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.trim();
        }
        DshVendor vendor = vendor();
        return vendor == null ? null : vendor.baseUrl();
    }

    /// Returns what the interface shows for this account.
    ///
    /// @return the name
    public String displayName() {
        if (label != null && !label.isBlank()) {
            return label.trim();
        }
        DshVendor vendor = vendor();
        return vendor == null ? vendorId : vendor.displayName();
    }

    /// Returns the key with everything but its ends hidden, for showing on a row.
    ///
    /// @return the masked key
    public String maskedKey() {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.length() <= 8) {
            return "••••";
        }
        return key.substring(0, 4) + "••••" + key.substring(key.length() - 4);
    }

    /// Checks the key against the vendor.
    ///
    /// Done here rather than by asking the harness, and that is not a shortcut: the harness answers
    /// out of a local catalogue for a vendor it knows and never makes the request, so asking it
    /// would report success for any string. What actually decides is the vendor's own `/models`
    /// endpoint, which is the smallest call that requires a valid key and does not spend anything.
    ///
    /// The distinction that matters is between "the vendor says no" and "the vendor could not be
    /// reached": only the first means the key is wrong, and a network that cannot reach the endpoint
    /// must not be reported as a bad key.
    ///
    /// @return what the check found
    public Check check() {
        String address = endpoint();
        if (address == null || address.isBlank()) {
            return new Check(Outcome.UNREACHABLE, "这个供应商的地址取决于账号，请填写端点地址");
        }
        DshVendor vendor = vendor();
        String api = vendor == null ? "openai-completions" : vendor.api();
        String url = address.replaceAll("/+$", "") + "/models";

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
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
                HttpResponse<String> response = client.send(request.build(),
                        HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return new Check(Outcome.VALID, "密钥有效");
                }
                if (status == 401 || status == 403) {
                    return new Check(Outcome.REJECTED, "供应商拒绝了这个密钥（HTTP " + status + "）");
                }
                // 404 and 400 are the endpoint answering, which is not the same as the key being
                // wrong: some gateways do not serve a model list at all.
                if (status == 404 || status == 400 || status == 405) {
                    return new Check(Outcome.UNKNOWN,
                            "这个端点不提供模型列表（HTTP " + status + "），无法据此判断密钥");
                }
                return new Check(Outcome.UNREACHABLE, "供应商返回 HTTP " + status);
            }
        } catch (IOException e) {
            // A connection failure often carries no message at all — `ConnectException` from a
            // closed port says nothing — so the exception's own name is the more useful half.
            String why = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            return new Check(Outcome.UNREACHABLE, "无法连接 " + url + "：" + why);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check(Outcome.UNREACHABLE, "检查被中断");
        } catch (RuntimeException e) {
            return new Check(Outcome.UNREACHABLE, "地址无效：" + e.getMessage());
        }
    }

    /// Asks an address whether anything is listening, without a key.
    ///
    /// This is what tells a person who pasted an address that is not in the catalogue whether they
    /// have found a supplier or mistyped a hostname. What is asked is the same `/models` call the key
    /// check makes, minus the key — it is the one endpoint every OpenAI-compatible service has, and
    /// the answer is readable whether or not it is allowed:
    ///
    /// - **200** — a model list came back, so this is a supplier and it is even answering openly.
    /// - **401** or **403** — it answered, and it wants a key. That is the ordinary case, and a
    ///   supplier rather than a mistake.
    /// - **anything else** — it answered, but not as a model service. A 404 means the address is a
    ///   web server with no API under it, and a 5xx means something is there and unwell; neither is
    ///   something to write a route for.
    /// - **no answer at all** — nothing is listening, the name does not resolve, or the network
    ///   cannot reach it. The distinction from the above is the point: an unreachable address has
    ///   not said "no", and reporting it as a bad address would be reporting the network's fault as
    ///   the person's.
    ///
    /// @param baseUrl the address, without `/models`
    /// @return the status it answered with, or `-1` when it did not answer
    public static int probe(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return -1;
        }
        String url = baseUrl.trim().replaceAll("/+$", "") + "/models";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()) {
                return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
            }
        } catch (IOException e) {
            String why = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            org.jackhuang.hmcl.util.logging.Logger.LOG.info("Nothing answered at " + url + ": " + why);
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (RuntimeException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.info("Not an address: " + baseUrl);
            return -1;
        }
    }

    /// Reports whether a status means something is serving a model API there.
    ///
    /// @param status what [#probe] returned
    /// @return whether it is a supplier
    public static boolean statusLooksLikeASupplier(int status) {
        return status == 200 || status == 401 || status == 403;
    }

    /// Asks the vendor which models it serves.
    ///
    /// The same call the key check makes — the one endpoint every OpenAI-compatible service has — but
    /// the answer is read instead of the status. The launcher needs it because a **route it writes
    /// itself is not in the harness's catalogue**, so the harness cannot fill in that route's model
    /// list the way it does for a vendor it knows; a route with no models cannot be registered at
    /// all, so the list has to come from somewhere. Asking the vendor is the same place the harness
    /// would have got it.
    ///
    /// **Failure is not an error here.** A launch must not depend on a supplier answering: a network
    /// that cannot reach it today may reach it tomorrow, and the caller falls back to a name it can
    /// at least register. Only a 2xx with a readable list is an answer; everything else is "no
    /// answer", which is why this returns a list rather than throwing.
    ///
    /// @return the model ids the vendor named, in the order it named them, or empty
    public List<String> fetchModels() {
        String address = endpoint();
        if (address == null || address.isBlank()) {
            return List.of();
        }
        DshVendor vendor = vendor();
        String api = vendor == null ? "openai-completions" : vendor.api();
        String url = address.replaceAll("/+$", "") + "/models";

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
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
                HttpResponse<String> response = client.send(request.build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return List.of();
                }
                return readModelIds(response.body());
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            org.jackhuang.hmcl.util.logging.Logger.LOG.info("Could not read the model list of " + url + ": " + e.getMessage());
            return List.of();
        }
    }

    /// Reads model ids out of what a `/models` endpoint answered.
    ///
    /// The shape is the one OpenAI-compatible services share — `{"data":[{"id":…},…]}` — and it is
    /// read defensively because "compatible" is a claim rather than a promise: a service that answers
    /// something else is a service whose list this cannot use, and saying so by returning nothing is
    /// better than writing a model the harness will fail to find.
    ///
    /// @param body the answer
    /// @return the ids, or empty when the body is not a model list
    static List<String> readModelIds(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return List.of();
            }
            com.google.gson.JsonElement data = parsed.getAsJsonObject().get("data");
            if (data == null || !data.isJsonArray()) {
                return List.of();
            }
            List<String> ids = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement element : data.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                com.google.gson.JsonElement id = element.getAsJsonObject().get("id");
                if (id != null && id.isJsonPrimitive()) {
                    String text = id.getAsString();
                    if (!text.isBlank() && !ids.contains(text)) {
                        ids.add(text);
                    }
                }
            }
            return List.copyOf(ids);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /// Returns the account an instance launches with.
    ///
    /// The instance's own choice wins, and when it has none the first account the launcher holds is
    /// used: a person with one account means it for everything, and making them repeat that per
    /// instance would be asking a question with one answer.
    ///
    /// @param instance the instance
    /// @return the account, or `null` when there is none to use
    public static @Nullable DshAccount forInstance(DshInstance instance) {
        try {
            java.util.List<DshAccount> accounts =
                    org.jackhuang.hmcl.setting.SettingsManager.settings().getAccounts();
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
                // The account it named is gone. Falling back to another would launch with a key
                // nobody chose, so it launches with none and says so.
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                        "Instance " + instance.id() + " names an account that no longer exists: " + chosen);
                return null;
            }
            return accounts.get(0);
        } catch (RuntimeException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Could not read the accounts", e);
            return null;
        }
    }

    /// What a key check found.
    public enum Outcome {
        /// The vendor accepted the key.
        VALID,
        /// The vendor refused the key.
        REJECTED,
        /// The endpoint could not be reached, or cannot answer this question.
        UNREACHABLE,
        /// The endpoint answered but does not offer the call that would answer it.
        UNKNOWN
    }

    /// What a check found, and why.
    ///
    /// @param outcome what happened
    /// @param message a sentence for the person who pressed the button
    public record Check(Outcome outcome, String message) {
    }
}
