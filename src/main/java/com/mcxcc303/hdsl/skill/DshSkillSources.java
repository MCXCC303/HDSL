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
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

@NotNullByDefault
public enum DshSkillSources {
	// The community's index
	SKILLS_SH("skills-sh", "skills.sh", new DshSkillSource.SkillsSh()),

	// The marketplace
	SKILLS_MP("skillsmp", "SkillsMP", new DshSkillSource.SkillsMp());

	private final String id;
	private final String label;
	private final DshSkillCatalog catalog;

	DshSkillSources(String id, String label, DshSkillCatalog catalog) {
		this.id = id;
		this.label = label;
		this.catalog = catalog;
	}

	/**
	 * Returns the source a stored id names.
	 *
	 * @param id the stored id, possibly {@code null} or of the wrong case
	 * @return the source
	 */
	public static DshSkillSources of(@Nullable String id) {
		if (id == null) return SKILLS_SH;

		String wanted = id.trim().toLowerCase(Locale.ROOT);
		for (DshSkillSources source : values()) {
			if (source.id.equals(wanted)) return source;
		}
		return SKILLS_SH;
	}

	public String id() {
		return id;
	}

	public String label() {
		return label;
	}

	public DshSkillCatalog catalog() {
		return catalog;
	}
}
