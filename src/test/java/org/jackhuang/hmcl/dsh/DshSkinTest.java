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
package org.jackhuang.hmcl.dsh;

import org.jackhuang.hmcl.dsh.skin.DshSkin;
import org.jackhuang.hmcl.dsh.skin.InvalidSkinException;
import org.jackhuang.hmcl.dsh.skin.NormalizedSkin;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for reading and normalising a skin.
///
/// The skin format is the part of this feature with rules that are not visible in a picture. A skin
/// is 64x64 **or** the older 64x32, and the older one is not merely smaller: its limbs are stored in
/// one place and have to be mirrored into another before anything can draw them. Getting that wrong
/// looks like a broken skin rather than a broken program, so it is worth pinning.
class DshSkinTest {
    /// Writes a PNG of the given size.
    ///
    /// @param file   where to write it
    /// @param width  the width
    /// @param height the height
    /// @param argb   the colour to fill it with
    private static void writePng(Path file, int width, int height, int argb) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }

    /// Reads a skin from a file the way the launcher does.
    ///
    /// @param file the file
    /// @return the normalised skin
    private static NormalizedSkin normalize(Path file) throws Exception {
        javafx.scene.image.Image image = new javafx.scene.image.Image(Files.newInputStream(file));
        return new NormalizedSkin(image);
    }

    @Test
    void aCurrentSkinIsTakenAsItIs() throws Exception {
        Path file = Files.createTempFile("skin", ".png");
        writePng(file, 64, 64, 0xFF3366CC);

        NormalizedSkin skin = normalize(file);

        assertFalse(skin.isOldFormat(), "64x64 is the current format");
        assertEquals(1, skin.getScale());
        assertEquals(64, (int) skin.getNormalizedTexture().getWidth());
        assertEquals(64, (int) skin.getNormalizedTexture().getHeight());
        Files.deleteIfExists(file);
    }

    @Test
    void anOldSkinIsGrownToTheCurrentFormat() throws Exception {
        // 64x32 is the format before the second layer existed. Its legs and arms are stored once and
        // have to be copied, mirrored, into where the current format keeps them — leaving it at
        // 64x32 would draw the limbs in the wrong places rather than not draw them.
        Path file = Files.createTempFile("skin", ".png");
        writePng(file, 64, 32, 0xFF884422);

        NormalizedSkin skin = normalize(file);

        assertTrue(skin.isOldFormat(), "64x32 is the old format");
        assertEquals(64, (int) skin.getNormalizedTexture().getWidth());
        assertEquals(64, (int) skin.getNormalizedTexture().getHeight(),
                "the old format is grown to the current height");
        Files.deleteIfExists(file);
    }

    @Test
    void aScaledSkinIsAcceptedAndItsScaleReported() throws Exception {
        // Skins are found in multiples of the format, and the original reads the multiple so the
        // coordinates it indexes with stay in the same units.
        Path file = Files.createTempFile("skin", ".png");
        writePng(file, 128, 128, 0xFF112233);

        NormalizedSkin skin = normalize(file);

        assertEquals(2, skin.getScale());
        assertEquals(128, (int) skin.getNormalizedTexture().getWidth());
        Files.deleteIfExists(file);
    }

    @Test
    void somethingThatIsNotASkinIsRefused() throws Exception {
        // The rules are the width being a multiple of 64 and the height being the width or half of
        // it. A picture that keeps neither is refused rather than cropped or stretched: showing a
        // wrongly shaped picture as a skin would hide the fact that the wrong file was picked.
        Path square = Files.createTempFile("skin", ".png");
        writePng(square, 32, 32, 0xFFFFFFFF);
        assertThrows(InvalidSkinException.class, () -> normalize(square));

        Path wrongRatio = Files.createTempFile("skin", ".png");
        writePng(wrongRatio, 64, 48, 0xFFFFFFFF);
        assertThrows(InvalidSkinException.class, () -> normalize(wrongRatio));

        Path notAMultiple = Files.createTempFile("skin", ".png");
        writePng(notAMultiple, 63, 63, 0xFFFFFFFF);
        assertThrows(InvalidSkinException.class, () -> normalize(notAMultiple));

        Files.deleteIfExists(square);
        Files.deleteIfExists(wrongRatio);
        Files.deleteIfExists(notAMultiple);
    }

    @Test
    void aSkinIsAskedForWithoutASize() throws Exception {
        // The 3D renderer refuses an image that was decoded to a requested size, because it cannot
        // tell one from a picture that was assembled. This is the property that decides whether the
        // model is drawn with a texture or left white, so it is checked rather than assumed.
        Path file = Files.createTempFile("skin", ".png");
        writePng(file, 64, 64, 0xFF445566);

        javafx.scene.image.Image image = new javafx.scene.image.Image(Files.newInputStream(file));

        assertEquals(0, (int) image.getRequestedWidth(),
                "a skin must be decoded without a requested size");
        assertEquals(0, (int) image.getRequestedHeight());
        Files.deleteIfExists(file);
    }

    @Test
    void theBundledSkinsArePresentAndReadable() throws Exception {
        // Read without the toolkit: the file has to be in the jar, and a missing one would only
        // show up as a blank model in one dialog. All nine are checked, on both bodies, because all
        // eighteen are reachable — the account's own identity decides which one is drawn.
        for (org.jackhuang.hmcl.dsh.skin.DefaultSkin skin
                : org.jackhuang.hmcl.dsh.skin.DefaultSkin.values()) {
            for (boolean slim : new boolean[]{false, true}) {
                String path = "/assets/img/skin/" + (slim ? "slim/" : "wide/")
                        + skin.name().toLowerCase(java.util.Locale.ROOT) + ".png";
                try (java.io.InputStream stream =
                             org.jackhuang.hmcl.dsh.skin.DefaultSkin.class.getResourceAsStream(path)) {
                    assertTrue(stream != null, "the bundled skin " + path + " must be in the jar");
                }
            }
        }
    }

    @Test
    void everyAccountHasItsOwnDefaultSkin() {
        // The original's rule, and the reason "nothing chosen" is still a face: one of nine skins on
        // one of two bodies, picked by the account's identity. Both halves are checked — that a
        // picture always comes back, and that the two bodies are both reachable, since a rule that
        // only ever returned the wide one would look right for most accounts.
        java.util.Set<Object> seen = new java.util.HashSet<>();
        boolean sawSlim = false;
        boolean sawWide = false;

        for (String key : java.util.List.of(
                "official|MCXCC", "third-party|alpha", "third-party|beta", "offline|gamma",
                "offline|delta", "official|", "x", "")) {
            java.util.UUID uuid = org.jackhuang.hmcl.dsh.skin.DshSkin.uuidOf(key);
            org.jackhuang.hmcl.dsh.skin.DefaultSkin.Chosen chosen =
                    org.jackhuang.hmcl.dsh.skin.DefaultSkin.forUuid(uuid);

            assertTrue(chosen.image() != null, "the account " + key + " must have a default skin");
            assertEquals(64, (int) chosen.image().getWidth());
            assertEquals(64, (int) chosen.image().getHeight());
            assertSame(chosen.image(),
                    org.jackhuang.hmcl.dsh.skin.DefaultSkin.forUuid(uuid).image(),
                    "the same account must keep the same face");

            sawSlim |= chosen.slim();
            sawWide |= !chosen.slim();
            seen.add(chosen.image());
        }

        assertTrue(sawSlim, "some accounts must be given the slim body");
        assertTrue(sawWide, "some accounts must be given the wide body");
        assertTrue(seen.size() > 1, "two accounts must not always look alike");
    }

    @Test
    void theModelIsNeverHandedAPreNormalisedAtlas() throws Exception {
        // `SkinCanvas.updateSkin` does nothing at all unless the picture reports no requested size,
        // which is how the renderer tells a picture that was decoded from one that was assembled.
        // A WritableImage — which is what normalising produces — always reports the size it was
        // made with, so handing the model a normalised atlas draws nothing: the shape is left white
        // and no error is raised anywhere. Both halves are pinned, because the bug is invisible.
        javafx.scene.image.Image bundled =
                org.jackhuang.hmcl.dsh.skin.DefaultSkin.STEVE.image(false);
        assertTrue(bundled != null, "the bundled skins must be readable");
        assertEquals(0, (int) bundled.getRequestedWidth(),
                "a bundled skin must reach the model as it was read");

        assertEquals(0, (int) DshSkin.defaultImage("offline|somebody").getRequestedWidth(),
                "an account's default skin must reach the model as it was read");

        Path file = Files.createTempFile("skin", ".png");
        writePng(file, 64, 64, 0xFF445566);
        javafx.scene.image.Image normalised = new NormalizedSkin(
                new javafx.scene.image.Image(Files.newInputStream(file))).getNormalizedTexture();
        assertFalse(normalised.getRequestedWidth() == 0,
                "a normalised atlas reports a size, which is why the model must not be given one");
        Files.deleteIfExists(file);
    }

    @Test
    void whatIsNotAnImageIsRefusedBeforeItIsKept() throws Exception {
        // `setFrom` reads the file, and a file that is not a PNG must not be copied over a skin that
        // works: a mistyped path would otherwise leave the launcher with no skin at all.
        Path notAnImage = Files.createTempFile("skin", ".png");
        Files.writeString(notAnImage, "this is not a PNG");

        assertThrows(DshException.class, () -> DshSkin.check(notAnImage));
        Files.deleteIfExists(notAnImage);
    }
}
