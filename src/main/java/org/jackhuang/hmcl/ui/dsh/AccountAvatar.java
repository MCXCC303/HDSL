/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
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

/// Draws an account's face.
///
/// The original's own drawing, arithmetic and all
/// (`TexturesLoader.drawAvatar(Canvas, Image)`):
///
/// ```java
/// int size = (int) canvas.getWidth();
/// int scale = (int) skin.getWidth() / 64;
/// int faceOffset = (int) Math.round(size / 18.0);
///
/// g.setImageSmoothing(false);
/// g.drawImage(skin, 8*scale, 8*scale, 8*scale, 8*scale,
///         faceOffset, faceOffset, size - 2*faceOffset, size - 2*faceOffset);
/// g.drawImage(skin, 40*scale, 8*scale, 8*scale, 8*scale, 0, 0, size, size);
/// ```
///
/// Three things in that are worth not simplifying away:
///
/// - **Two draws, not one.** A skin's head is two layers: the face at `(8,8)` and the hat or hair
///   that goes over it at `(40,8)`. Drawing only the first loses every hat and every hairstyle,
///   which most skins have.
/// - **`faceOffset`, not zero.** The face is inset by a eighteenth of the box before being scaled up.
///   Drawn edge to edge instead — which is what this did — the head fills the frame and looks
///   subtly wrong beside the original without anything being obviously broken.
/// - **`scale`.** A skin may be stored enlarged. Reading the head out at `8*scale` is what keeps a
///   large one from drawing its top-left corner instead of its face.
@NotNullByDefault
public final class AccountAvatar {
    private AccountAvatar() {
    }

    /// Draws one skin's head, filling the canvas.
    ///
    /// @param canvas where to draw
    /// @param skin   a skin atlas, at any of the sizes the format allows
    public static void draw(Canvas canvas, Image skin) {
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        int size = (int) canvas.getWidth();
        int scale = (int) skin.getWidth() / 64;
        int faceOffset = (int) Math.round(size / 18.0);

        graphics.setImageSmoothing(false);
        graphics.drawImage(skin,
                8 * scale, 8 * scale, 8 * scale, 8 * scale,
                faceOffset, faceOffset, size - 2 * faceOffset, size - 2 * faceOffset);
        graphics.drawImage(skin,
                40 * scale, 8 * scale, 8 * scale, 8 * scale,
                0, 0, size, size);
    }
}
