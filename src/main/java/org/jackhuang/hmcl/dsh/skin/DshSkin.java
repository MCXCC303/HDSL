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
package org.jackhuang.hmcl.dsh.skin;

import javafx.scene.image.Image;
import org.jackhuang.hmcl.dsh.DshException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/// The skin this launcher wears.
///
/// Ported from the original's texture handling, minus the half of it that has nothing to do with
/// drawing. That half arranged uploading a skin to Mojang and fetching one back for a signed-in
/// account; there is no login here, so there is nothing for it to talk to. What is left is the part
/// that is actually about pictures, and it is the whole of what a preview needs:
///
/// - read a PNG from a file,
/// - normalise it, because a skin is one of **two** shapes and only one of them is the current one,
/// - decide whether it is the slim model, which is a property of the picture rather than a setting,
/// - keep the result, and hand it out without reading the file again.
///
/// Storage is one file in the launcher's own directory. A skin belongs to the person using the
/// launcher, not to an instance: it is what the launcher draws beside a name, and two instances do
/// not have two faces.
@NotNullByDefault
public final class DshSkin {
    /// Where the chosen skin is kept, inside the launcher's data directory.
    private static final String FILE_NAME = "skin.png";

    /// A skin that is all one colour, used when nothing has been chosen.
    ///
    /// Not an error state and not an empty square: the original ships the two default skins for the
    /// same reason — a preview that shows nothing tells a person nothing about whether the preview
    /// works. This is `null` until asked for, because building it needs JavaFX to be up.
    private static @Nullable Image fallback;

    /// The skin currently loaded, or `null` before the first read.
    ///
    /// The normalized texture: a 64x64 tile atlas, which is what drawing a head or a body part out
    /// of it needs. It is **not** what the 3D renderer wants — see [#previewImage()].
    private static @Nullable Image loaded;

    /// The skin as read, before normalising, or `null` before the first read.
    ///
    /// Kept because the two consumers want different things and the difference is not cosmetic. The
    /// 3D renderer's entry point refuses an image that was decoded to a requested size
    /// (`SkinHelper.isNoRequest`), and a `WritableImage` always reports one — it is a picture that
    /// was assembled rather than decoded. Handing it the normalized texture is what made the model
    /// draw white: the call returned without binding any material, so the geometry was there and the
    /// picture was not.
    private static @Nullable Image source;

    /// Whether the loaded skin is the slim model.
    private static boolean loadedSlim;

    /// Whether the loaded skin is the one on disk, as opposed to the fallback.
    private static boolean loadedFromDisk;

    private DshSkin() {
    }

    /// Returns the file the chosen skin is kept in.
    ///
    /// @return the path, which may not exist
    public static Path file() {
        return org.jackhuang.hmcl.Metadata.HMCL_USER_HOME.resolve(FILE_NAME);
    }

    /// Reads the skin, from cache when it has already been read.
    ///
    /// @return the normalized skin, or `null` when none has been chosen
    public static @Nullable Image image() {
        requireLoaded();
        return loadedFromDisk ? loaded : null;
    }

    /// Returns the skin as read, for the 3D renderer.
    ///
    /// @return the decoded image, or `null` when none has been chosen
    public static @Nullable Image previewImage() {
        requireLoaded();
        return loadedFromDisk ? source : null;
    }

    /// Reads the skin, falling back to the built-in one.
    ///
    /// This is what a preview draws: the original shows its default skin rather than an empty frame
    /// when an account has none, and the same reasoning holds — a skin chooser that starts blank
    /// cannot be told apart from one that failed.
    ///
    /// @return the skin to draw, never `null`
    public static Image imageOrFallback() {
        requireLoaded();
        return loaded != null ? loaded : fallback();
    }

    /// Reports whether a skin has been chosen.
    ///
    /// @return whether the file is there
    public static boolean isSet() {
        requireLoaded();
        return loadedFromDisk;
    }

    /// Reports whether the chosen skin is the slim model.
    ///
    /// Read off the picture rather than asked for. The original does the same: whether a skin is
    /// slim is recorded in its transparent pixels, so a person importing a skin does not have to be
    /// asked a question the file already answers.
    ///
    /// @return whether the arms are three pixels wide
    public static boolean isSlim() {
        requireLoaded();
        return loadedSlim;
    }

    /// Reads a picture from a file without keeping it.
    ///
    /// For a dialog that has to show what a file looks like before the person has agreed to it:
    /// nothing is written until they confirm, so nothing they did not choose can replace what they
    /// had.
    ///
    /// @param source the image file
    /// @return the picture, or `null` when it is not a skin
    public static @Nullable Image read(Path source) {
        try {
            Image candidate = new Image(Files.newInputStream(source));
            if (candidate.isError() || candidate.getWidth() <= 0) {
                return null;
            }
            return candidate;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /// Reports whether a file is a usable skin, without keeping it.
    ///
    /// @param source the file
    /// @throws DshException when it is not
    public static void check(Path source) throws DshException {
        Image candidate = read(source);
        if (candidate == null) {
            throw new DshException(i18n("dsh.skin.invalid"), null);
        }
        try {
            new NormalizedSkin(candidate);
        } catch (InvalidSkinException e) {
            throw new DshException(i18n("dsh.skin.invalid"), e);
        }
    }

    /// Keeps an already-read picture as the chosen skin.
    ///
    /// The body type is stated rather than read off the picture. For an imported file the picture
    /// decides — that is what makes importing a slim skin switch the arms without being asked — but
    /// for one of the launcher's own skins the person has just chosen the body, and the two bundled
    /// pictures are drawn for their own bodies, so the choice is the answer rather than the picture.
    ///
    /// @param picture the picture
    /// @param slim    whether the slim body is wanted
    /// @throws DshException when the picture is not a skin, or cannot be written
    public static void setFromImage(@Nullable Image picture, boolean slim) throws DshException {
        if (picture == null) {
            throw new DshException(i18n("dsh.skin.invalid"), null);
        }
        NormalizedSkin normalized;
        try {
            normalized = new NormalizedSkin(picture);
        } catch (InvalidSkinException e) {
            throw new DshException(i18n("dsh.skin.invalid"), e);
        }

        // The file is written from the normalised atlas, so what is stored is always the current
        // format whether the picture came from a bundled pair, an old 64x32 file, or anywhere else.
        Path target = file();
        Image atlas = normalized.getNormalizedTexture();
        try {
            Files.createDirectories(target.getParent());
            javax.imageio.ImageIO.write(toBufferedImage(atlas), "png", target.toFile());
        } catch (IOException | RuntimeException e) {
            throw new DshException("Could not write " + target, e);
        }

        loaded = atlas;
        DshSkin.source = picture;
        // For a bundled pair the body is what was chosen; the picture is drawn for that body, so
        // reading it back would agree — but the choice is the answer, not a guess at it.
        loadedSlim = slim;
        loadedFromDisk = true;
    }

    /// Copies an image out of JavaFX so it can be written.
    ///
    /// JavaFX can read a PNG and cannot write one, and the launcher has no image encoder of its own;
    /// the JDK's is reached through `ImageIO`, which needs an `AWT` image. The conversion is a copy
    /// of the pixels and nothing else — no scaling, no colour management — so a 64-pixel skin stays
    /// 64 pixels exactly.
    ///
    /// @param image the image
    /// @return the same pixels as an AWT image
    private static java.awt.image.BufferedImage toBufferedImage(Image image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(width, height,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return out;
    }

    /// Copies a file in as the chosen skin, after checking that it is one.
    ///
    /// The check happens before the copy: a file that is not a skin must not replace a good one, or
    /// a mistyped path would leave the launcher with no skin and no way back except choosing again.
    ///
    /// @param source the image file
    /// @throws DshException when the file is not a skin, or cannot be read or written
    public static void setFrom(Path source) throws DshException {
        Image candidate;
        try {
            // Loaded with its own size requested so the picture is not scaled to a default: the
            // normaliser reads the dimensions to decide which of the two formats this is.
            candidate = new Image(Files.newInputStream(source));
        } catch (IOException | RuntimeException e) {
            throw new DshException("Could not read " + source, e);
        }
        if (candidate.isError() || candidate.getWidth() <= 0) {
            throw new DshException("Could not read " + source
                    + (candidate.getException() == null ? "" : ": " + candidate.getException().getMessage()));
        }

        NormalizedSkin normalized;
        try {
            normalized = new NormalizedSkin(candidate);
        } catch (InvalidSkinException e) {
            throw new DshException(i18n("dsh.skin.invalid"), e);
        }

        Path target = file();
        try {
            Files.createDirectories(target.getParent());
            // Written beside the target and moved over it, so an interrupted copy cannot leave half
            // a picture where the launcher expects a whole one.
            Path staging = target.resolveSibling(FILE_NAME + ".hdsl-writing");
            Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new DshException("Could not write " + target, e);
        }

        loaded = normalized.getNormalizedTexture();
        DshSkin.source = candidate;
        loadedSlim = normalized.isSlim();
        loadedFromDisk = true;
    }

    /// Forgets the chosen skin.
    ///
    /// @throws DshException when the file cannot be removed
    public static void clear() throws DshException {
        try {
            Files.deleteIfExists(file());
        } catch (IOException e) {
            throw new DshException("Could not remove " + file(), e);
        }
        loaded = null;
        source = null;
        loadedSlim = false;
        loadedFromDisk = false;
    }

    /// Reads the file if it has not been read yet.
    ///
    /// A failure is not thrown at the caller: a skin is decoration, and a launcher that refuses to
    /// draw because a picture is unreadable would be trading the whole interface for an ornament.
    private static void requireLoaded() {
        if (loaded != null || loadedFromDisk) {
            return;
        }
        Path source = file();
        if (!Files.isRegularFile(source)) {
            return;
        }
        try {
            Image candidate = new Image(Files.newInputStream(source));
            if (candidate.isError() || candidate.getWidth() <= 0) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                        "The skin at " + source + " could not be read; using the built-in one");
                return;
            }
            NormalizedSkin normalized = new NormalizedSkin(candidate);
            loaded = normalized.getNormalizedTexture();
            DshSkin.source = candidate;
            loadedSlim = normalized.isSlim();
            loadedFromDisk = true;
        } catch (IOException | InvalidSkinException | RuntimeException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "The skin at " + source + " is not usable; using the built-in one", e);
        }
    }

    /// Builds the skin drawn when none has been chosen.
    ///
    /// A flat picture the size of a skin, in the surface colour the interface already uses, so it
    /// cannot be mistaken for somebody's skin and cannot clash with the theme. Drawing one pixel at
    /// a time is the cheapest way to make a valid skin without shipping a binary asset.
    ///
    /// @return the built-in skin
    private static Image fallback() {
        if (fallback == null) {
            javafx.scene.image.WritableImage image =
                    new javafx.scene.image.WritableImage(64, 64);
            javafx.scene.image.PixelWriter writer = image.getPixelWriter();
            javafx.scene.paint.Color skin = javafx.scene.paint.Color.web("#8d8d8d");
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    writer.setColor(x, y, skin);
                }
            }
            fallback = image;
        }
        return fallback;
    }

    /// Returns a translated string.
    ///
    /// @param key the key
    /// @return the text
    private static String i18n(String key) {
        return org.jackhuang.hmcl.util.i18n.I18n.i18n(key);
    }

    /// Reports whether a file looks like a skin by its name.
    ///
    /// @param name the file name
    /// @return whether it is worth opening
    public static boolean looksLikeAnImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png");
    }
}
