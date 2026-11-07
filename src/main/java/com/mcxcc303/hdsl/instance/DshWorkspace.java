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
package com.mcxcc303.hdsl.instance;

import com.mcxcc303.hdsl.launch.DshSession;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Working directory sessions grouped by workspace slug.
 *
 * @param slug     the on-disk grouping slug shared by sessions in this workspace
 * @param path     the working directory path, or {@code null} when unknown
 * @param title    the display title for the workspace
 * @param sessions the workspace sessions, newest first
 */
@NotNullByDefault
public record DshWorkspace(String slug, @Nullable String path, String title, List<DshSession> sessions) {

	/**
	 * Returns the display title for a working directory path.
	 *
	 * @param path the working directory path, or {@code null}
	 * @param slug the fallback on-disk grouping slug
	 * @return the display title
	 */
	public static String titleOf(@Nullable String path, String slug) {
		if (path != null && !path.isBlank()) {
			Path name = Path.of(path).getFileName();
			if (name != null && !name.toString().isBlank()) {
				return name.toString();
			}
			return path;
		}
		return slug;
	}

	/**
	 * Returns the total size of the workspace's sessions.
	 *
	 * @return the total size in bytes
	 */
	public long sizeBytes() {
		long total = 0L;
		for (DshSession session : sessions) {
			total += session.sizeBytes();
		}
		return total;
	}

	/**
	 * Returns the timestamp of the most recent activity in this workspace.
	 *
	 * @return the newest session modification time in epoch milliseconds, or {@code 0}
	 */
	public long modifiedAt() {
		long newest = 0L;
		for (DshSession session : sessions) {
			newest = Math.max(newest, session.modifiedAt());
		}
		return newest;
	}

	/**
	 * Reports whether a session belongs to this workspace.
	 *
	 * @param session the session to check
	 * @return whether the session belongs to this workspace
	 */
	public boolean contains(DshSession session) {
		return slug.equals(session.workspaceSlug());
	}
}
