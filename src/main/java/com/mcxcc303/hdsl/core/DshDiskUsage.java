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

import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

@NotNullByDefault
public final class DshDiskUsage {
	private static final long CLUSTER = 4096L;

	private DshDiskUsage() {
	}

	public static long usageBytes(Path path) {
		if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			return clusters(FileUtils.size(path));
		}

		long[] total = {0L};
		Set<Object> counted = new HashSet<>();
		try {
			Files.walkFileTree(path, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
					if (attributes.isSymbolicLink()) {
						return FileVisitResult.CONTINUE;
					}
					Object key = attributes.fileKey();
					if (key != null && !counted.add(key)) {
						return FileVisitResult.CONTINUE;
					}
					total[0] += clusters(attributes.size());
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException e) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			LOG.warning("Failed to size " + path, e);
		}
		return total[0];
	}

	public static long sizeBytes(Path path) {
		if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			return FileUtils.size(path);
		}

		long[] total = {0L};
		try {
			Files.walkFileTree(path, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
					if (!attributes.isSymbolicLink()) {
						total[0] += attributes.size();
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException e) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			LOG.warning("Failed to size " + path, e);
		}
		return total[0];
	}

	public static String formatBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
		}
		if (bytes < 1024L * 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
		}
		if (bytes < 1024L * 1024 * 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
		}
		return String.format(Locale.ROOT, "%.1f TiB", bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0));
	}

	private static long clusters(long size) {
		return (size + CLUSTER - 1) / CLUSTER * CLUSTER;
	}
}
