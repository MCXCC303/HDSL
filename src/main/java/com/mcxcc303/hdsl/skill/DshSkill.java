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
package com.mcxcc303.hdsl.skill;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/**
 * A skill pack in an instance's DSH home.
 *
 * @param entry          the entry on disk: the bundle directory, or the flat file
 * @param instructions   the file whose frontmatter describes the skill
 * @param name           the frontmatter {@code name}, or the entry's own name when it has none
 * @param description    the frontmatter {@code description}, or an empty string
 * @param bundle         whether this is a directory bundle rather than a flat file
 * @param modelInvocable whether the agent may invoke it: the harness hides a skill
 *                       whose frontmatter says {@code disable-model-invocation: true}
 * @param sizeBytes      what the pack occupies on disk
 * @param modifiedAt     when the instructions were last written, in epoch milliseconds
 */
@NotNullByDefault
public record DshSkill(Path entry, Path instructions, String name, String description, boolean bundle,
                       boolean modelInvocable, long sizeBytes, long modifiedAt) {
	/**
	 * Returns whether the agent can see and invoke this skill.
	 *
	 * @return whether the frontmatter leaves {@code disable-model-invocation} unset or false
	 */
	public boolean enabled() {
		return modelInvocable;
	}

	/**
	 * Returns the entry's own name, which is what a file manager shows.
	 *
	 * @return the last segment of {@link #entry}
	 */
	public String fileName() {
		return entry.getFileName().toString();
	}
}
