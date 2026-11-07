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
package com.mcxcc303.hdsl.core;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;
import java.util.Set;

/**
 * Serializes string values into safe YAML scalar representations.
 */
@NotNullByDefault
public final class YamlScalar {
	private static final Set<String> RESERVED = Set.of(
			"true", "false", "yes", "no", "on", "off", "y", "n", "null", "~", ""
	);
	private static final String RESERVED_FIRST = "-?:,[]{}#&*!|>'\"%@`";

	private YamlScalar() {
	}

	/**
	 * Formats a string as a YAML scalar, quoting and escaping if needed.
	 *
	 * @param value the raw string value, or {@code null}
	 * @return safe YAML scalar string
	 */
	public static String of(String value) {
		String text = value == null ? "" : value;

		if (isPlainlySafe(text) && !isReserved(text)) {
			return text;
		}

		StringBuilder quoted = new StringBuilder(text.length() + 2);
		quoted.append('"');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '"' -> quoted.append("\\\"");
				case '\\' -> quoted.append("\\\\");
				case '\n' -> quoted.append("\\n");
				case '\r' -> quoted.append("\\r");
				case '\t' -> quoted.append("\\t");
				default -> {
					if (c < 0x20) {
						quoted.append(String.format("\\x%02x", (int) c));
					} else {
						quoted.append(c);
					}
				}
			}
		}
		return quoted.append('"').toString();
	}

	private static boolean isReserved(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		if (RESERVED.contains(lower)) {
			return true;
		}
		return text.matches("[-+]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][-+]?[0-9]+)?");
	}

	private static boolean isPlainlySafe(String text) {
		if (text.isEmpty()) {
			return false;
		}
		if (RESERVED_FIRST.indexOf(text.charAt(0)) >= 0) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' ||
			               c == '_' || c == '.' || c == '/' || c == '@' || c == '+';
			if (!safe) {
				return false;
			}
		}
		return true;
	}
}
