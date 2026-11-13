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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

@NotNullByDefault
public final class DshJson {
	private DshJson() {}

	/**
	 * Parses the first JSON value in {@code text}.
	 *
	 * @return the parsed element, or {@code null} when no value is present
	 */
	public static @Nullable JsonElement parseFirstValue(String text) {
		if (text.isBlank()) {
			return null;
		}
		List<String> lines = text.lines().toList();
		// npm prints warnings and the JSON output on one stream.
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i).stripLeading();
			if (line.isEmpty() || (line.charAt(0) != '{' && line.charAt(0) != '[')) {
				continue;
			}
			try {
				return JsonParser.parseString(String.join("\n", lines.subList(i, lines.size())));
			} catch (RuntimeException e) {
				LOG.info("Command output from line " + (i + 1) + " did not parse as JSON; trying the next");
			}
		}
		LOG.warning("No JSON value in the command's output: " + text.substring(0, Math.min(200, text.length())));
		return null;
	}
}
