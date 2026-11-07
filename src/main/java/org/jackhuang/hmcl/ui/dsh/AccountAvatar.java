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
package org.jackhuang.hmcl.ui.dsh;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Utility to render player skin face avatars onto a canvas.
 */
@NotNullByDefault
public final class AccountAvatar {
	private AccountAvatar() {
	}

	/**
	 * Draws a skin's head onto the given canvas, rendering both base and overlay layers.
	 *
	 * @param canvas canvas to render onto
	 * @param skin   skin texture image
	 */
	public static void draw(Canvas canvas, Image skin) {
		GraphicsContext graphics = canvas.getGraphicsContext2D();
		graphics.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

		int size = (int) canvas.getWidth();
		int scale = (int) skin.getWidth() / 64;
		int faceOffset = (int) Math.round(size / 18.0);

		graphics.setImageSmoothing(false);
		graphics.drawImage(skin,
		                   8 * scale,
		                   8 * scale,
		                   8 * scale,
		                   8 * scale,
		                   faceOffset,
		                   faceOffset,
		                   size - 2 * faceOffset,
		                   size - 2 * faceOffset);
		graphics.drawImage(skin, 40 * scale, 8 * scale, 8 * scale, 8 * scale, 0, 0, size, size);
	}
}
