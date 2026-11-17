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

import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

@NotNullByDefault
public record DshVendor(String id, String displayName, String apiKeyEnv, String api, @Nullable String baseUrl,
		boolean preferred) {

	public static final List<String> APIS = List.of("openai-completions", "openai-responses", "anthropic-messages");
	private static final List<DshVendor> OFFERED = List.of(new DshVendor("deepseek",
			"DeepSeek",
			"DEEPSEEK_API_KEY",
			"openai-completions",
			"https://api.deepseek.com",
			true),
			new DshVendor("openai",
					"OpenAI",
					"OPENAI_API_KEY",
					"openai-responses",
					"https://api.openai.com/v1",
					false),
			new DshVendor("anthropic",
					"Anthropic",
					"ANTHROPIC_API_KEY",
					"anthropic-messages",
					"https://api.anthropic.com",
					false),
			new DshVendor("openrouter",
					"OpenRouter",
					"OPENROUTER_API_KEY",
					"openai-completions",
					"https://openrouter.ai/api/v1",
					false),
			new DshVendor("vercel-ai-gateway",
					"Vercel AI Gateway",
					"AI_GATEWAY_API_KEY",
					"openai-completions",
					"https://ai-gateway.vercel.sh",
					false),
			new DshVendor("moonshotai",
					"Moonshot (Kimi)",
					"MOONSHOT_API_KEY",
					"openai-completions",
					"https://api.moonshot.ai/v1",
					false),
			new DshVendor("moonshotai-cn",
					"Moonshot 中国站",
					"MOONSHOT_API_KEY",
					"openai-completions",
					"https://api.moonshot.cn/v1",
					false),
			new DshVendor("zai",
					"智谱 GLM",
					"ZAI_API_KEY",
					"anthropic-messages",
					"https://api.z.ai/api/coding/paas/v4",
					false),
			new DshVendor("zai-coding-cn",
					"智谱 GLM 中国站",
					"ZAI_CODING_CN_API_KEY",
					"anthropic-messages",
					"https://open.bigmodel.cn/api/coding/paas/v4",
					false),
			new DshVendor("google",
					"Google Gemini",
					"GEMINI_API_KEY",
					"openai-completions",
					"https://generativelanguage.googleapis.com/v1beta",
					false),
			new DshVendor("groq",
					"Groq",
					"GROQ_API_KEY",
					"openai-completions",
					"https://api.groq.com/openai/v1",
					false),
			new DshVendor("mistral",
					"Mistral",
					"MISTRAL_API_KEY",
					"openai-completions",
					"https://api.mistral.ai",
					false),
			new DshVendor("xai",
					"xAI",
					"XAI_API_KEY",
					"openai-completions",
					"https://api.x.ai/v1",
					false));
	private static final @Nullable List<DshVendor> CATALOGUE;

	static {
		CATALOGUE = readCatalogue();
	}

	public static List<DshVendor> offered() {
		return OFFERED;
	}

	public static @Nullable DshVendor byId(@Nullable String id) {
		if (id == null) {
			return null;
		}
		for (DshVendor vendor : offered()) {
			if (vendor.id.equalsIgnoreCase(id.trim())) {
				return vendor;
			}
		}
		return null;
	}

	public static List<DshVendor> catalogue() {
		List<DshVendor> known = CATALOGUE;
		return known != null ? known : List.of();
	}

	public static boolean answersTo(@Nullable String name, Predicate<DshVendor> match) {
		if (name == null || name.isBlank()) {
			return false;
		}
		String trimmed = name.trim();
		for (DshVendor vendor : offered()) {
			if (vendor.id().equalsIgnoreCase(trimmed) && match.test(vendor)) {
				return true;
			}
		}
		for (DshVendor vendor : catalogue()) {
			if (vendor.id().equalsIgnoreCase(trimmed) && match.test(vendor)) {
				return true;
			}
		}
		return false;
	}

	public static @Nullable DshVendor byBaseUrl(@Nullable String url) {
		String host = hostOf(url);
		if (host == null) {
			return null;
		}
		for (DshVendor vendor : catalogue()) {
			if (host.equals(hostOf(vendor.baseUrl))) {
				return vendor;
			}
		}
		for (DshVendor vendor : offered()) {
			if (host.equals(hostOf(vendor.baseUrl))) {
				return vendor;
			}
		}
		return null;
	}

	public static @Nullable String hostOf(@Nullable String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		try {
			String host = URI.create(url.trim()).getHost();
			return host == null ? null : host.toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static DshVendor discovered(String id, String name, String baseUrl) {
		return new DshVendor(id,
				name,
				id.toUpperCase(Locale.ROOT).replace('-', '_') + "_API_KEY",
				APIS.get(0),
				baseUrl,
				false);
	}

	public static boolean isUsableId(@Nullable String id) {
		return id != null && id.matches("[a-z0-9][a-z0-9._-]*");
	}

	private static List<DshVendor> readCatalogue() {
		List<DshVendor> vendors = new ArrayList<>();
		try (InputStream stream = DshVendor.class.getResourceAsStream("/assets/dsh-providers.txt")) {
			if (stream == null) {
				Logger.LOG.warning("No provider catalogue in the jar; only the offered vendors will be known");
				return List.of();
			}
			for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}
				String[] parts = trimmed.split("\\|", -1);
				if (parts.length != 4 || parts[0].isEmpty()) {
					continue;
				}
				vendors.add(new DshVendor(parts[0],
						readableName(parts[0]),
						parts[3],
						parts[2],
						parts[1].isEmpty() ? null : parts[1],
						false));
			}
		} catch (IOException | RuntimeException e) {
			Logger.LOG.warning("Could not read the provider catalogue", e);
			return List.of();
		}
		return List.copyOf(vendors);
	}

	private static String readableName(String id) {
		StringBuilder name = new StringBuilder();
		for (String word : id.split("-")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!name.isEmpty()) {
				name.append(' ');
			}
			name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return name.toString();
	}

	public boolean hasBaseUrl() {
		return baseUrl != null && !baseUrl.isBlank();
	}

	public String label() {
		return displayName + " (" + id + ")";
	}

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