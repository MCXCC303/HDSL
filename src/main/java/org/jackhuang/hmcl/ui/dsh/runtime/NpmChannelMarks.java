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
package org.jackhuang.hmcl.ui.dsh.runtime;

import com.mcxcc303.hdsl.runtime.DshNpmChannel;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

@NotNullByDefault
public final class NpmChannelMarks {
	private NpmChannelMarks() {
	}

	public static @Nullable SVG mark(DshNpmChannel channel) {
		return switch (channel) {
			case STABLE -> SVG.RELEASE_CIRCLE;
			case RC, BETA -> SVG.BETA_CIRCLE;
			case ALPHA -> SVG.ALPHA_CIRCLE;
			case OTHER -> null;
		};
	}

	public static String style(DshNpmChannel channel) {
		return switch (channel) {
			case STABLE -> "release";
			case RC, BETA -> "beta";
			case ALPHA -> "alpha";
			case OTHER -> "";
		};
	}

	public static String label(DshNpmChannel channel) {
		return switch (channel) {
			case STABLE -> "download.type.stable";
			case RC -> "download.type.rc";
			case BETA -> "download.type.beta";
			case ALPHA -> "download.type.alpha";
			case OTHER -> "download.type.other";
		};
	}
}
