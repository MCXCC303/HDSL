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

import com.mcxcc303.hdsl.core.DshException;
import javafx.scene.image.Image;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for reading and normalising skin textures.
 */
class DshSkinTest {
	private static void writePng(Path file, int width, int height, int argb) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setRGB(x, y, argb);
			}
		}
		ImageIO.write(image, "png", file.toFile());
	}

	private static NormalizedSkin normalize(Path file) throws Exception {
		Image image = new Image(Files.newInputStream(file));
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
		Path file = Files.createTempFile("skin", ".png");
		writePng(file, 64, 32, 0xFF884422);

		NormalizedSkin skin = normalize(file);

		assertTrue(skin.isOldFormat(), "64x32 is the old format");
		assertEquals(64, (int) skin.getNormalizedTexture().getWidth());
		assertEquals(64,
		             (int) skin.getNormalizedTexture().getHeight(),
		             "the old format is grown to the current height");
		Files.deleteIfExists(file);
	}

	@Test
	void aScaledSkinIsAcceptedAndItsScaleReported() throws Exception {
		Path file = Files.createTempFile("skin", ".png");
		writePng(file, 128, 128, 0xFF112233);

		NormalizedSkin skin = normalize(file);

		assertEquals(2, skin.getScale());
		assertEquals(128, (int) skin.getNormalizedTexture().getWidth());
		Files.deleteIfExists(file);
	}

	@Test
	void somethingThatIsNotASkinIsRefused() throws Exception {
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
		Path file = Files.createTempFile("skin", ".png");
		writePng(file, 64, 64, 0xFF445566);

		Image image = new Image(Files.newInputStream(file));

		assertEquals(0, (int) image.getRequestedWidth(), "a skin must be decoded without a requested size");
		assertEquals(0, (int) image.getRequestedHeight());
		Files.deleteIfExists(file);
	}

	@Test
	void theBundledSkinsArePresentAndReadable() throws Exception {
		for (DefaultSkin skin : DefaultSkin.values()) {
			for (boolean slim : new boolean[]{false, true}) {
				String path = "/assets/img/skin/" + (slim ? "slim/" : "wide/") + skin.name().toLowerCase(Locale.ROOT) +
				              ".png";
				try (InputStream stream = DefaultSkin.class.getResourceAsStream(path)) {
					assertNotNull(stream, "the bundled skin " + path + " must be in the jar");
				}
			}
		}
	}

	@Test
	void everyAccountHasItsOwnDefaultSkin() {
		Set<Object> seen = new HashSet<>();
		boolean sawSlim = false;
		boolean sawWide = false;

		for (String key : List.of("official|MCXCC",
		                          "third-party|alpha",
		                          "third-party|beta",
		                          "offline|gamma",
		                          "offline|delta",
		                          "official|",
		                          "x",
		                          "")) {
			UUID uuid = DshSkin.uuidOf(key);
			DefaultSkin.Chosen chosen = DefaultSkin.forUuid(uuid);

			assertNotNull(chosen.image(), "the account " + key + " must have a default skin");
			assertEquals(64, (int) chosen.image().getWidth());
			assertEquals(64, (int) chosen.image().getHeight());
			assertSame(chosen.image(), DefaultSkin.forUuid(uuid).image(), "the same account must keep the same face");

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
		Image bundled = DefaultSkin.STEVE.image(false);
		assertNotNull(bundled, "the bundled skins must be readable");
		assertEquals(0, (int) bundled.getRequestedWidth(), "a bundled skin must reach the model as it was read");

		assertEquals(0,
		             (int) DshSkin.defaultImage("offline|somebody").getRequestedWidth(),
		             "an account's default skin must reach the model as it was read");

		Path file = Files.createTempFile("skin", ".png");
		writePng(file, 64, 64, 0xFF445566);
		Image normalised = new NormalizedSkin(new Image(Files.newInputStream(file))).getNormalizedTexture();
		assertNotEquals(0,
		                normalised.getRequestedWidth(),
		                0.0,
		                "a normalised atlas reports a size, which is why the model must not be given one");
		Files.deleteIfExists(file);
	}

	@Test
	void whatIsNotAnImageIsRefusedBeforeItIsKept() throws Exception {
		Path notAnImage = Files.createTempFile("skin", ".png");
		Files.writeString(notAnImage, "this is not a PNG");

		assertThrows(DshException.class, () -> DshSkin.check(notAnImage));
		Files.deleteIfExists(notAnImage);
	}
}
