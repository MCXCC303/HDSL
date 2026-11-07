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
package com.mcxcc303.hdsl.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Persisted DeepSeek Harness session metadata on disk.
 *
 * @param id               the session id
 * @param workspaceSlug    the directory slug grouping sessions by workspace
 * @param directory        the session directory
 * @param formatVersion    the log format version
 * @param workingDirectory the working directory the session was created in, when known
 * @param title            the session title, when a projection cache row exists
 * @param sizeBytes        the size of the session directory's contents
 * @param modifiedAt       last modification timestamp in epoch milliseconds
 * @param locked           whether a lock file is present
 */
@NotNullByDefault
public record DshSession(String id, String workspaceSlug, Path directory, int formatVersion,
                         @Nullable String workingDirectory, @Nullable String title, long sizeBytes, long modifiedAt,
                         boolean locked) {

	/**
	 * Returns the display label for the session.
	 *
	 * @return the title, working directory name, or truncated id
	 */
	public String label() {
		if (title != null && !title.isBlank()) {
			return title;
		}
		if (workingDirectory != null && !workingDirectory.isBlank()) {
			Path path = Path.of(workingDirectory);
			Path name = path.getFileName();
			if (name != null && !name.toString().isBlank()) {
				return name.toString();
			}
		}
		return id.length() > 8 ? id.substring(0, 8) : id;
	}

	/**
	 * Returns the log file name expected for this format version.
	 *
	 * @return expected log file name
	 */
	public String logFileName() {
		return "session.v" + formatVersion + ".jsonl.zstd";
	}
}
