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
        String vendorId,
        String apiKey,
        @Nullable String baseUrl,
        @Nullable String label) {

    /// How long a key check is given.
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /// The vendor this account is for.
    ///
    /// @return the vendor, or `null` when the id names one this launcher does not offer
    public @Nullable DshVendor vendor() {
        return DshVendor.byId(vendorId);
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
