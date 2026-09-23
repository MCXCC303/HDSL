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

import java.util.List;
import java.util.Locale;

/// A model supplier this launcher can be told about.
///
/// DeepSeek Harness knows a long list of them — thirty-nine, from the catalogue that ships with the
/// `pi-ai` provider bundle — and each is addressed by an id, reads its key from an environment
/// variable of its own, and talks one of three wire protocols. The three things this launcher needs
/// to know about each are exactly those: what to call it, which variable the harness reads, and
/// which protocol to declare when the route is written out by hand.
///
/// Only a few are offered. A list of thirty-nine in a dropdown is a list nobody reads, and the
/// others can be reached by typing a route of one's own — which is what [DshAccount] allows. The
/// ones here are the ones people actually start with: the harness's own vendor, the two largest
/// general-purpose ones, the two aggregators that resell everything else, and the coding-plan
/// endpoints that a Chinese network can reach without a proxy.
@NotNullByDefault
public record DshVendor(
        /// The id `dsh` addresses this vendor by, which is also the route name.
        String id,

        /// What the interface calls it.
        String displayName,

        /// The environment variable the harness reads this vendor's key from.
        String apiKeyEnv,

        /// The wire protocol its endpoint speaks.
        String api,

        /// The endpoint, or `null` for a vendor whose address is per account.
        @Nullable String baseUrl,

        /// Whether this is the harness's own vendor, which the interface leads with.
        boolean preferred) {

    /// The protocols a hand-written route may declare.
    ///
    /// The harness accepts exactly these three; anything else is rejected when the route is
    /// registered, which fails the launch rather than the request.
    public static final List<String> APIS = List.of(
            "openai-completions", "openai-responses", "anthropic-messages");

    /// The vendors offered, in the order the interface shows them.
    ///
    /// @return the list
    public static List<DshVendor> offered() {
        return OFFERED;
    }

    private static final List<DshVendor> OFFERED = List.of(
            new DshVendor("deepseek", "DeepSeek", "DEEPSEEK_API_KEY",
                    "openai-completions", "https://api.deepseek.com", true),
            new DshVendor("openai", "OpenAI", "OPENAI_API_KEY",
                    "openai-responses", "https://api.openai.com/v1", false),
            new DshVendor("anthropic", "Anthropic", "ANTHROPIC_API_KEY",
                    "anthropic-messages", "https://api.anthropic.com", false),
            new DshVendor("openrouter", "OpenRouter", "OPENROUTER_API_KEY",
                    "openai-completions", "https://openrouter.ai/api/v1", false),
            new DshVendor("vercel-ai-gateway", "Vercel AI Gateway", "AI_GATEWAY_API_KEY",
                    "openai-completions", "https://ai-gateway.vercel.sh", false),
            new DshVendor("moonshotai", "Moonshot (Kimi)", "MOONSHOT_API_KEY",
                    "openai-completions", "https://api.moonshot.ai/v1", false),
            new DshVendor("moonshotai-cn", "Moonshot 中国站", "MOONSHOT_API_KEY",
                    "openai-completions", "https://api.moonshot.cn/v1", false),
            new DshVendor("zai", "智谱 GLM", "ZAI_API_KEY",
                    "anthropic-messages", "https://api.z.ai/api/coding/paas/v4", false),
            new DshVendor("zai-coding-cn", "智谱 GLM 中国站", "ZAI_CODING_CN_API_KEY",
                    "anthropic-messages", "https://open.bigmodel.cn/api/coding/paas/v4", false),
            new DshVendor("google", "Google Gemini", "GEMINI_API_KEY",
                    "openai-completions", "https://generativelanguage.googleapis.com/v1beta", false),
            new DshVendor("groq", "Groq", "GROQ_API_KEY",
                    "openai-completions", "https://api.groq.com/openai/v1", false),
            new DshVendor("mistral", "Mistral", "MISTRAL_API_KEY",
                    "openai-completions", "https://api.mistral.ai", false),
            new DshVendor("xai", "xAI", "XAI_API_KEY",
                    "openai-completions", "https://api.x.ai/v1", false));

    /// Finds a vendor by its id.
    ///
    /// @param id the id, or `null`
    /// @return the vendor, or `null` when it is not one this launcher offers
    public static @Nullable DshVendor byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (DshVendor vendor : OFFERED) {
            if (vendor.id.equalsIgnoreCase(id.trim())) {
                return vendor;
            }
        }
        return null;
    }

    /// Reports whether the endpoint is one a key can be checked against.
    ///
    /// A vendor whose address depends on the account cannot be checked without being told the
    /// address, which is what the account's own base URL is for.
    ///
    /// @return whether this vendor states its own address
    public boolean hasBaseUrl() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /// Returns what the interface shows for this vendor.
    ///
    /// @return the name and the id, because the id is what a settings file will hold
    public String label() {
        return displayName + " (" + id + ")";
    }

    /// Reports whether a key looks like one for this vendor.
    ///
    /// Not a validation — only the vendor can say that — but a key pasted with the wrong vendor
    /// chosen is the commonest mistake, and the prefixes differ enough to catch it: the harness's
    /// own keys begin `sk-`, Anthropic's `sk-ant-`, OpenRouter's `sk-or-`. A key that matches no
    /// known shape is accepted, because this is a guess and refusing on a guess is worse than
    /// letting the vendor answer.
    ///
    /// @param key the key
    /// @return whether it is shaped like one of this vendor's
    public boolean looksLikeItsKey(String key) {
        String trimmed = key == null ? "" : key.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "anthropic" -> trimmed.startsWith("sk-ant-") || trimmed.startsWith("sk-");
            case "openrouter" -> trimmed.startsWith("sk-or-") || trimmed.startsWith("sk-");
            default -> true;
        };
    }
}
