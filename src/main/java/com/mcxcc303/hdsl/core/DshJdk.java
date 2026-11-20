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

import com.sun.management.HotSpotDiagnosticMXBean;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Locale;

@NotNullByDefault
public final class DshJdk {
	private DshJdk() {}

	public static String version() {
		return System.getProperty("java.version", "");
	}

	public static boolean isBase64IntrinsicSupported() {
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		return arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x86-64");
	}

	public static boolean hasVulnerableBase64Intrinsic(String version) {
		int[] components = components(version);
		return switch (components[0]) {
			case 17 -> compare(components, 17, 0, 6) >= 0 && compare(components, 17, 0, 21, 1) < 0;
			case 18, 19, 20, 22, 23, 24, 26 -> true;
			case 21 -> compare(components, 21, 0, 13, 1) < 0;
			case 25 -> compare(components, 25, 0, 5, 1) < 0;
			case 27 -> compare(components, 27, 0, 1, 1) < 0;
			default -> false;
		};
	}

	public static @Nullable String base64IntrinsicFixedVersion(String version) {
		return switch (components(version)[0]) {
			case 17 -> "17.0.21.1";
			case 21 -> "21.0.13.1";
			case 25 -> "25.0.5.1";
			case 27 -> "27.0.1.1";
			default -> null;
		};
	}

	public static boolean isBase64IntrinsicDisabled() {
		try {
			HotSpotDiagnosticMXBean bean = ManagementFactory.newPlatformMXBeanProxy(
					ManagementFactory.getPlatformMBeanServer(),
					"com.sun.management:type=HotSpotDiagnostic",
					HotSpotDiagnosticMXBean.class);
			return "false".equals(bean.getVMOption("UseBASE64Intrinsics").getValue());
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static int[] components(String version) {
		String core = version;
		for (int i = 0; i < core.length(); i++) {
			char c = core.charAt(i);
			if (c == '+' || c == '-' || c == '_') {
				core = core.substring(0, i);
				break;
			}
		}

		String[] parts = core.split("\\.");
		int[] components = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			components[i] = number(parts[i]);
		}
		return components;
	}

	private static int number(String part) {
		long value = 0;
		for (int i = 0; i < part.length(); i++) {
			char c = part.charAt(i);
			if (c < '0' || c > '9') break;
			value = value * 10 + (c - '0');
			if (value > Integer.MAX_VALUE) return 0;
		}
		return (int) value;
	}

	private static int compare(int[] components, int... expected) {
		for (int i = 0; i < Math.max(components.length, expected.length); i++) {
			int actual = i < components.length ? components[i] : 0;
			int target = i < expected.length ? expected[i] : 0;
			if (actual != target) return actual < target ? -1 : 1;
		}
		return 0;
	}
}
