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
package com.mcxcc303.hdsl.skin;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Record of how an account's skin was chosen.
 *
 * @param type          how the skin was chosen
 * @param textureModel  which body model the skin is drawn on
 * @param localSkinPath file path for {@link Type#LOCAL_FILE}
 * @param localCapePath file path for local cape image, if any
 */
@NotNullByDefault
public record DshSkinChoice(Type type, TextureModel textureModel, @Nullable String localSkinPath,
                            @Nullable String localCapePath) {

	public static final DshSkinChoice DEFAULT = new DshSkinChoice(Type.DEFAULT, TextureModel.WIDE, null, null);

	/**
	 * Returns the body texture model, falling back to wide.
	 *
	 * @return the texture model
	 */
	public TextureModel model() {
		return textureModel == null ? TextureModel.WIDE : textureModel;
	}

	/**
	 * Reports whether this is the default skin choice.
	 *
	 * @return true if using the default skin choice
	 */
	public boolean isDefault() {
		return type == Type.DEFAULT;
	}

	/**
	 * How an account skin was chosen.
	 */
	public enum Type {
		DEFAULT,
		STEVE,
		ALEX,
		LOCAL_FILE;

		/**
		 * Parses a stored skin choice type name.
		 *
		 * @param stored the stored name
		 * @return the type, or {@code null} if unrecognized
		 */
		public static @Nullable Type fromStorage(@Nullable String stored) {
			if (stored == null) {
				return null;
			}
			for (Type type : values()) {
				if (type.name().toLowerCase(Locale.ROOT).equals(stored.toLowerCase(Locale.ROOT))) {
					return type;
				}
			}
			return null;
		}
	}

	/**
	 * Body model geometry for rendering a skin.
	 */
	public enum TextureModel {
		WIDE("default"),
		SLIM("slim");

		public final String modelName;

		TextureModel(String modelName) {
			this.modelName = modelName;
		}

		/**
		 * Parses a stored texture model name.
		 *
		 * @param stored the stored name
		 * @return the texture model, defaulting to wide
		 */
		public static TextureModel fromStorage(@Nullable String stored) {
			return SLIM.modelName.equalsIgnoreCase(stored) ? SLIM : WIDE;
		}
	}
}
