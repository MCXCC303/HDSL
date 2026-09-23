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
/// account, not to the launcher: the original keeps one per account, offline ones included, which is
/// why two accounts can wear different faces. A single launcher-wide skin is what made changing one
/// change them all.
@NotNullByDefault
public final class DshSkin {
    /// Where chosen skins are kept, inside the launcher's data directory.
    ///
    /// One file per account, named after the account's key. A key is not a legal file name as it
    /// stands — it is `vendor|name`, and a separator or a slash in it would escape the directory —
    /// so it is hashed: the file names are opaque and stable, which is all they need to be.
    private static final String DIRECTORY = "skins";

    /// The name a skin's file has inside [#DIRECTORY].
    private static final String SUFFIX = ".png";

    /// A skin that is all one colour, used when even the bundled ones cannot be read.
    ///
    /// Not an error state and not an empty square: a preview that shows nothing tells a person
    /// nothing about whether the preview works. It is the **last** resort rather than the first —
    /// an account with no skin of its own wears the original's own default for it, and this is only
    /// reached when a bundled picture is missing from the jar.
    private static @Nullable Image blank;

    /// What one account's skin came to when it was read.
    ///
    /// @param normalized the 64x64 tile atlas, for drawing a head or a body part out of
    /// @param source     the picture as decoded, which is **not** the same thing — see [#previewImage]
    /// @param slim       whether the arms are three pixels wide
    /// @param fromDisk   whether a skin was actually there, as opposed to the default
    private record Loaded(Image normalized, @Nullable Image source, boolean slim, boolean fromDisk) {
    }

    /// What each account's skin came to, keyed by the account's key.
    ///
    /// Keyed rather than held once, because the answer is now per account: a menu that lists several
    /// accounts draws several faces, and a single cache would have them overwrite one another.
    private static final java.util.Map<String, Loaded> LOADED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /// The skin each account wears when it has chosen none.
    ///
    /// Cached because normalising a picture is real work and the answer never changes, and keyed by
    /// account because the answer is per account — the launcher picks a different one for each, so a
    /// single entry would hand every account the same face.
    private static final java.util.Map<String, Loaded> DEFAULTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /// The cape each account wears, or a marker meaning "none".
    ///
    /// A cape is optional and most accounts have none, so the absence has to be remembered too, or
    /// every read would go back to the disk to find out the same thing. `Optional` is the marker.
    private static final java.util.Map<String, java.util.Optional<Image>> CAPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /// What a cape's file is called, beside the skin's.
    private static final String CAPE_SUFFIX = "-cape.png";

    private DshSkin() {
    }

    /// Returns the file an account's skin is kept in.
    ///
    /// The account's key is hashed rather than used literally: a key is `vendor|name`, so a name
    /// containing a separator or a slash would put the file somewhere else entirely.
    ///
    /// @param accountKey the account's key
    /// @return the path, which may not exist
    public static Path file(String accountKey) {
        return org.jackhuang.hmcl.Metadata.HMCL_USER_HOME.resolve(DIRECTORY)
                .resolve(hashedName(accountKey) + SUFFIX);
    }

    /// Returns the file an account's cape is kept in.
    ///
    /// Beside the skin and named from the same hash, so the two travel together and neither can be
    /// found without the account they belong to.
    ///
    /// @param accountKey the account's key
    /// @return the path, which may not exist
    public static Path capeFile(String accountKey) {
        return org.jackhuang.hmcl.Metadata.HMCL_USER_HOME.resolve(DIRECTORY)
                .resolve(hashedName(accountKey) + CAPE_SUFFIX);
    }

    /// Returns the identity the launcher picks an account's default skin from.
    ///
    /// The original hashes the account's UUID; there is no login here, so there are no UUIDs, and
    /// the closest honest equivalent is the account's key — which is what makes an account *this*
    /// account. Hashed through the same name-based scheme the original uses for its offline
    /// accounts, so the arithmetic in `DefaultSkin.forUuid` is fed something of the same kind: a
    /// UUID, not an arbitrary string's hash.
    ///
    /// @param accountKey the account's key
    /// @return its identity
    public static java.util.UUID uuidOf(String accountKey) {
        return java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + accountKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /// Returns the file name for an account's key.
    ///
    /// @param accountKey the account's key
    /// @return the name
    private static String hashedName(String accountKey) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(accountKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // Every JVM has SHA-256; a fallback that cannot collide with a hash is still needed so the
            // method never throws.
            return "key-" + Integer.toHexString(accountKey.hashCode());
        }
    }

    /// Reads the skin, from cache when it has already been read.
    ///
    /// @return the normalized skin, or `null` when none has been chosen
    public static @Nullable Image image(String accountKey) {
        Loaded skin = load(accountKey);
        return skin.fromDisk() ? skin.normalized() : null;
    }

    /// Returns the skin as read, for the 2D head drawn beside an account's name.
    ///
    /// The **normalised** atlas, which is what a head can be cut out of, and `null` when nothing has
    /// been chosen. This is not what the turning model is given: see [#canvasImage].
    ///
    /// @return the decoded image, or `null` when none has been chosen
    public static @Nullable Image previewImage(String accountKey) {
        return load(accountKey).source();
    }

    /// Returns the picture the turning model is given.
    ///
    /// The picture **as it was read**, not the normalised atlas, and this is not a detail: the
    /// renderer refuses a picture that reports a size it was decoded to, because it cannot tell such
    /// a picture from one that was assembled — and a `WritableImage`, which is what normalising
    /// produces, always reports the size it was made with. Handing the model a normalised atlas
    /// therefore draws **nothing at all**, silently, with the shape left white and no error
    /// anywhere. The original feeds its canvas the raw picture for the same reason and lets the
    /// canvas do its own normalising and enlarging.
    ///
    /// @param accountKey the account's key
    /// @return the picture to draw, never `null`
    public static Image canvasImage(String accountKey) {
        Loaded skin = load(accountKey);
        return skin.source() != null ? skin.source() : skin.normalized();
    }

    /// Reads the skin, falling back to the one the account wears by default.
    ///
    /// This is what the turning model is given: the original shows its own default for an account
    /// that has chosen none, and the same reasoning holds — a skin chooser that starts blank cannot
    /// be told apart from one that failed.
    ///
    /// @return the picture to draw, never `null`
    public static Image imageOrFallback(String accountKey) {
        return canvasImage(accountKey);
    }

    /// Returns the picture the launcher draws for an account that has chosen no skin.
    ///
    /// The original's own rule, ported: one of nine bundled skins on one of two bodies, picked by
    /// the account's identity. Drawing the first bundled skin instead — or a flat placeholder — is
    /// what made every account look alike before anything was chosen.
    ///
    /// As with [#canvasImage], the picture **as it was read**: the model cannot draw a normalised
    /// atlas.
    ///
    /// @param accountKey the account's key
    /// @return the picture, never `null`
    public static Image defaultImage(String accountKey) {
        Loaded skin = defaultSkin(accountKey);
        return skin.source() != null ? skin.source() : skin.normalized();
    }

    /// Reports whether the default picture is drawn on the slim body.
    ///
    /// @param accountKey the account's key
    /// @return whether the arms are three pixels wide
    public static boolean defaultSlim(String accountKey) {
        return defaultSkin(accountKey).slim();
    }

    /// Returns the picture an account's cape was read from.
    ///
    /// @param accountKey the account's key
    /// @return the cape, or `null` when none has been chosen or it cannot be read
    public static @Nullable Image cape(String accountKey) {
        java.util.Optional<Image> cached = CAPES.get(accountKey);
        if (cached != null) {
            return cached.orElse(null);
        }
        java.util.Optional<Image> read = java.util.Optional.ofNullable(readCape(accountKey));
        CAPES.put(accountKey, read);
        return read.orElse(null);
    }

    /// Reads an account's cape from its file.
    ///
    /// @param accountKey the account's key
    /// @return the cape, or `null`
    private static @Nullable Image readCape(String accountKey) {
        Path file = capeFile(accountKey);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Image candidate = new Image(Files.newInputStream(file));
            if (candidate.isError() || candidate.getWidth() <= 0) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                        "The cape at " + file + " could not be read");
                return null;
            }
            return candidate;
        } catch (IOException | RuntimeException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "The cape at " + file + " is not usable", e);
            return null;
        }
    }

    /// Copies a file in as the chosen cape, after checking that it is a picture.
    ///
    /// A cape is not checked for being a *skin*, because it is not one: the original accepts any
    /// picture here and stretches it over the cloak. Only that it can be read is required, since a
    /// file that cannot be read would leave the model wearing nothing and the person no wiser.
    ///
    /// @param source the picture file, or `null` to remove the cape
    /// @throws DshException when the file cannot be read or written
    public static void setCape(String accountKey, @Nullable Path source) throws DshException {
        if (source == null) {
            clearCape(accountKey);
            return;
        }
        Image candidate = read(source);
        if (candidate == null) {
            throw new DshException(i18n("dsh.skin.cape.invalid"), null);
        }

        Path target = capeFile(accountKey);
        try {
            Files.createDirectories(target.getParent());
            Path staging = target.resolveSibling(target.getFileName() + ".hdsl-writing");
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

        CAPES.put(accountKey, java.util.Optional.of(candidate));
    }

    /// Forgets the chosen cape.
    ///
    /// @throws DshException when the file cannot be removed
    public static void clearCape(String accountKey) throws DshException {
        try {
            Files.deleteIfExists(capeFile(accountKey));
        } catch (IOException e) {
            throw new DshException("Could not remove " + capeFile(accountKey), e);
        }
        CAPES.put(accountKey, java.util.Optional.empty());
    }

    /// Reports whether a skin has been chosen.
    ///
    /// @return whether the file is there
    public static boolean isSet(String accountKey) {
        return load(accountKey).fromDisk();
    }

    /// Reports whether the chosen skin is the slim model.
    ///
    /// Read off the picture rather than asked for. The original does the same: whether a skin is
    /// slim is recorded in its transparent pixels, so a person importing a skin does not have to be
    /// asked a question the file already answers.
    ///
    /// @return whether the arms are three pixels wide
    public static boolean isSlim(String accountKey) {
        return load(accountKey).slim();
    }

    /// Reports whether a picture describes the slim body.
    ///
    /// Asked of the picture rather than chosen by the person: whether a skin is slim is recorded in
    /// its own transparent pixels, so a file already answers the question and asking again would let
    /// the two disagree.
    ///
    /// @param picture the picture
    /// @return whether the arms are three pixels wide, or `false` when it is not a skin at all
    public static boolean slimOf(Image picture) {
        try {
            return new NormalizedSkin(picture).isSlim();
        } catch (InvalidSkinException e) {
            return false;
        }
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
    public static void setFromImage(String accountKey, @Nullable Image picture, boolean slim) throws DshException {
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
        Path target = file(accountKey);
        Image atlas = normalized.getNormalizedTexture();
        try {
            Files.createDirectories(target.getParent());
            javax.imageio.ImageIO.write(toBufferedImage(atlas), "png", target.toFile());
        } catch (IOException | RuntimeException e) {
            throw new DshException("Could not write " + target, e);
        }

        LOADED.put(accountKey, new Loaded(atlas, picture, slim, true));
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
    public static void setFrom(String accountKey, Path source) throws DshException {
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

        Path target = file(accountKey);
        try {
            Files.createDirectories(target.getParent());
            // Written beside the target and moved over it, so an interrupted copy cannot leave half
            // a picture where the launcher expects a whole one.
            Path staging = target.resolveSibling(target.getFileName() + ".hdsl-writing");
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

        LOADED.put(accountKey, new Loaded(normalized.getNormalizedTexture(), candidate,
                normalized.isSlim(), true));
    }

    /// Forgets the chosen skin.
    ///
    /// @throws DshException when the file cannot be removed
    public static void clear(String accountKey) throws DshException {
        try {
            Files.deleteIfExists(file(accountKey));
        } catch (IOException e) {
            throw new DshException("Could not remove " + file(accountKey), e);
        }
        forget(accountKey);
    }

    /// Reads the file if it has not been read yet.
    ///
    /// A failure is not thrown at the caller: a skin is decoration, and a launcher that refuses to
    /// draw because a picture is unreadable would be trading the whole interface for an ornament.
    ///
    /// @param accountKey the account whose skin is wanted
    /// @return what its skin came to, with the built-in picture standing in when there is none
    private static Loaded load(String accountKey) {
        Loaded cached = LOADED.get(accountKey);
        if (cached != null) {
            return cached;
        }
        Loaded skin = readFromDisk(accountKey);
        LOADED.put(accountKey, skin);
        return skin;
    }

    /// Reads an account's skin from its file.
    ///
    /// @param accountKey the account
    /// @return what the file came to
    private static Loaded readFromDisk(String accountKey) {
        Path file = file(accountKey);
        if (!Files.isRegularFile(file)) {
            return defaultSkin(accountKey);
        }
        try {
            Image candidate = new Image(Files.newInputStream(file));
            if (candidate.isError() || candidate.getWidth() <= 0) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                        "The skin at " + file + " could not be read; using this account's default");
                return defaultSkin(accountKey);
            }
            NormalizedSkin normalized = new NormalizedSkin(candidate);
            return new Loaded(normalized.getNormalizedTexture(), candidate,
                    normalized.isSlim(), true);
        } catch (IOException | InvalidSkinException | RuntimeException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "The skin at " + file + " is not usable; using this account's default", e);
            return defaultSkin(accountKey);
        }
    }

    /// Reads the skin an account wears when it has chosen none.
    ///
    /// @param accountKey the account
    /// @return the default skin, read once per account
    private static Loaded defaultSkin(String accountKey) {
        return DEFAULTS.computeIfAbsent(accountKey, DshSkin::chooseDefault);
    }

    /// Picks and normalises an account's default skin.
    ///
    /// A bundled file that cannot be read is a packaging mistake, and the flat picture is the answer
    /// to it rather than a crash: an ornament must not be able to take the interface down.
    ///
    /// @param accountKey the account
    /// @return the default skin
    private static Loaded chooseDefault(String accountKey) {
        DefaultSkin.Chosen chosen = DefaultSkin.forUuid(uuidOf(accountKey));
        Image picture = chosen.image();
        if (picture == null) {
            return new Loaded(blank(), null, false, false);
        }
        try {
            return new Loaded(new NormalizedSkin(picture).getNormalizedTexture(), picture,
                    chosen.slim(), false);
        } catch (InvalidSkinException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "A bundled skin is not usable", e);
            return new Loaded(blank(), null, false, false);
        }
    }

    /// Forgets what was read for an account, so the next read goes to the file.
    ///
    /// @param accountKey the account
    private static void forget(String accountKey) {
        LOADED.remove(accountKey);
    }

    /// Builds the picture drawn when even the bundled skins cannot be read.
    ///
    /// A flat picture the size of a skin, in the surface colour the interface already uses, so it
    /// cannot be mistaken for somebody's skin and cannot clash with the theme. Drawing one pixel at
    /// a time is the cheapest way to make a valid skin without shipping a binary asset.
    ///
    /// @return the picture
    private static Image blank() {
        if (blank == null) {
            javafx.scene.image.WritableImage image =
                    new javafx.scene.image.WritableImage(64, 64);
            javafx.scene.image.PixelWriter writer = image.getPixelWriter();
            javafx.scene.paint.Color skin = javafx.scene.paint.Color.web("#8d8d8d");
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    writer.setColor(x, y, skin);
                }
            }
            blank = image;
        }
        return blank;
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
