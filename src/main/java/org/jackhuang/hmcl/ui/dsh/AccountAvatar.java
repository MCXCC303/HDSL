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
 * Utility to render player skin face avatars onto a canvas with deterministic 2D skin viewport and upscaling math.
 * <p>
 * Rendering follows the Minecraft skin atlas format:
 * <ul>
 *   <li><b>Two-layer rendering:</b> The head consists of a base face at atlas coordinate {@code (8,8)}
 *       and an outer hat/hair overlay at {@code (40,8)}. Both layers must be rendered to avoid losing hairstyles and accessories.</li>
 *   <li><b>Face inset offset:</b> The base face is inset by 1/18th of the canvas size ({@code Math.round(size / 18.0)}),
 *       while the overlay layer is drawn edge-to-edge. Drawing the base edge-to-edge makes the head fill the frame and appear distorted.</li>
 *   <li><b>Resolution scaling:</b> Atlas coordinate offsets are multiplied by {@code scale = skin.getWidth() / 64}
 *       to support high-resolution skins (e.g. 128x128 or larger) without cropping to the top-left corner.</li>
 *   <li><b>Nearest-neighbor scaling:</b> {@link GraphicsContext#setImageSmoothing(boolean)} is disabled to preserve crisp pixel art.</li>
 * </ul>
 */
@NotNullByDefault
public final class AccountAvatar {
	private AccountAvatar() {
	}

	/**
	 * Draws one skin's head, filling the canvas with base and overlay layers.
	 *
	 * @param canvas canvas to render onto
	 * @param skin   skin texture image at any valid scale
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
