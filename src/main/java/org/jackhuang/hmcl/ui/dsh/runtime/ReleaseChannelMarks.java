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

import com.mcxcc303.hdsl.runtime.DshReleaseChannel;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Set the style showing on the runtime version list.
 *
 * Including icon, icon color, and basic label.
 */
@NotNullByDefault
public final class ReleaseChannelMarks {
	private ReleaseChannelMarks() {}

	public static @Nullable SVG channelMark(DshReleaseChannel channel) {
		return switch (channel) {
			case STABLE -> SVG.RELEASE_CIRCLE;
			case RC, BETA -> SVG.BETA_CIRCLE;
			case ALPHA -> SVG.ALPHA_CIRCLE;
			case OTHER -> null;
		};
	}

	public static String channelStyle(DshReleaseChannel channel) {
		return switch (channel) {
			case STABLE -> "release";
			case RC, BETA -> "beta";
			case ALPHA -> "alpha";
			case OTHER -> "";
		};
	}

	public static SVGContainer icon(DshReleaseChannel channel, boolean unsupported) {
		SVG mark = unsupported ? SVG.ERROR : channelMark(channel);
		SVGContainer icon = (mark == null ? SVG.PACKAGE2 : mark).createIcon(24);
		icon.getStyleClass().add("addon-channel-icon");
		String style = unsupported ? "unsupported" : channelStyle(channel);
		if (!style.isEmpty()) {
			icon.getStyleClass().add(style);
		}
		return icon;
	}

	public static String label(DshReleaseChannel channel) {
		return switch (channel) {
			case STABLE -> "download.type.stable";
			case RC -> "download.type.rc";
			case BETA -> "download.type.beta";
			case ALPHA -> "download.type.alpha";
			case OTHER -> "download.type.other";
		};
	}
}
