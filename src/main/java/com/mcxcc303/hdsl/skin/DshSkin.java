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
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skin and cape texture management, storage, normalization, and caching.
 */
@NotNullByDefault
public final class DshSkin {
	private static final String DIRECTORY = "skins";
	private static final String SUFFIX = ".png";
	private static final Map<String, Loaded> LOADED = new ConcurrentHashMap<>();
	private static final Map<String, Loaded> DEFAULTS = new ConcurrentHashMap<>();
	private static final Map<String, Optional<Image>> CAPES = new ConcurrentHashMap<>();
	private static final String CAPE_SUFFIX = "-cape.png";
	private static @Nullable Image blank;

	private DshSkin() {
	}

	/**
	 * Returns the path to the account's skin file.
	 *
	 * @param accountKey the account's key
	 * @return the path, which may not exist
	 */
	public static Path file(String accountKey) {
		return Metadata.HMCL_USER_HOME.resolve(DIRECTORY).resolve(hashedName(accountKey) + SUFFIX);
	}

	/**
	 * Returns the path to the account's cape file.
	 *
	 * @param accountKey the account's key
	 * @return the path, which may not exist
	 */
	public static Path capeFile(String accountKey) {
		return Metadata.HMCL_USER_HOME.resolve(DIRECTORY).resolve(hashedName(accountKey) + CAPE_SUFFIX);
	}

	/**
	 * Returns a deterministic UUID derived from the account's key.
	 *
	 * @param accountKey the account's key
	 * @return the derived UUID
	 */
	public static UUID uuidOf(String accountKey) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + accountKey).getBytes(StandardCharsets.UTF_8));
	}

	private static String hashedName(String accountKey) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(accountKey.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (int i = 0; i < 16; i++) {
				hex.append(String.format("%02x", digest[i]));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			return "key-" + Integer.toHexString(accountKey.hashCode());
		}
	}

	/**
	 * Returns the normalized 64x64 texture atlas used to extract an account head preview.
	 *
	 * @param accountKey the account's key
	 * @return the normalized atlas
	 */
	public static Image headImage(String accountKey) {
		return load(accountKey).normalized();
	}

	/**
	 * Returns the raw decoded image for rendering in the 3D canvas model.
	 *
	 * @param accountKey the account's key
	 * @return the image to draw
	 */
	public static Image canvasImage(String accountKey) {
		Loaded skin = load(accountKey);
		return skin.source() != null ? skin.source() : skin.normalized();
	}

	/**
	 * Reads the skin image, falling back to the default skin if unconfigured.
	 *
	 * @param accountKey the account's key
	 * @return the image to draw
	 */
	public static Image imageOrFallback(String accountKey) {
		return canvasImage(accountKey);
	}

	/**
	 * Returns the default bundled skin image for an account.
	 *
	 * @param accountKey the account's key
	 * @return the default image
	 */
	public static Image defaultImage(String accountKey) {
		Loaded skin = defaultSkin(accountKey);
		return skin.source() != null ? skin.source() : skin.normalized();
	}

	/**
	 * Reports whether an account's default skin uses the slim model.
	 *
	 * @param accountKey the account's key
	 * @return true if the default skin is slim
	 */
	public static boolean defaultSlim(String accountKey) {
		return defaultSkin(accountKey).slim();
	}

	/**
	 * Returns the cape image for an account, or {@code null} if none is set or readable.
	 *
	 * @param accountKey the account's key
	 * @return the cape image, or {@code null}
	 */
	public static @Nullable Image cape(String accountKey) {
		Optional<Image> cached = CAPES.get(accountKey);
		if (cached != null) {
			return cached.orElse(null);
		}
		Optional<Image> read = Optional.ofNullable(readCape(accountKey));
		CAPES.put(accountKey, read);
		return read.orElse(null);
	}

	private static @Nullable Image readCape(String accountKey) {
		Path file = capeFile(accountKey);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			Image candidate = new Image(Files.newInputStream(file));
			if (candidate.isError() || candidate.getWidth() <= 0) {
				Logger.LOG.warning("The cape at " + file + " could not be read");
				return null;
			}
			return candidate;
		} catch (IOException | RuntimeException e) {
			Logger.LOG.warning("The cape at " + file + " is not usable", e);
			return null;
		}
	}

	/**
	 * Copies a file in as the chosen cape, or clears it if source is {@code null}.
	 *
	 * @param accountKey the account's key
	 * @param source     the cape image file, or {@code null} to clear
	 * @throws DshException if the image cannot be read or written
	 */
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
				Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new DshException("Could not write " + target, e);
		}

		CAPES.put(accountKey, Optional.of(candidate));
	}

	/**
	 * Removes the chosen cape file and clears the cache for the account.
	 *
	 * @param accountKey the account's key
	 * @throws DshException if the file cannot be removed
	 */
	public static void clearCape(String accountKey) throws DshException {
		try {
			Files.deleteIfExists(capeFile(accountKey));
		} catch (IOException e) {
			throw new DshException("Could not remove " + capeFile(accountKey), e);
		}
		CAPES.put(accountKey, Optional.empty());
	}

	/**
	 * Reports whether a custom skin has been saved on disk for this account.
	 *
	 * @param accountKey the account's key
	 * @return true if a skin file is present on disk
	 */
	public static boolean isSet(String accountKey) {
		return load(accountKey).fromDisk();
	}

	/**
	 * Reports whether the account's active skin uses the slim model.
	 *
	 * @param accountKey the account's key
	 * @return true if the skin uses the slim model
	 */
	public static boolean isSlim(String accountKey) {
		return load(accountKey).slim();
	}

	/**
	 * Determines whether a skin image uses the slim body model.
	 *
	 * @param picture the skin image
	 * @return true if the image uses the slim model
	 */
	public static boolean slimOf(Image picture) {
		try {
			return new NormalizedSkin(picture).isSlim();
		} catch (InvalidSkinException e) {
			return false;
		}
	}

	/**
	 * Reads an image from disk without validating or caching it.
	 *
	 * @param source the image file
	 * @return the decoded image, or {@code null} if unreadable
	 */
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

	/**
	 * Checks whether a file is a valid skin image.
	 *
	 * @param source the image file to check
	 * @throws DshException if the image is missing or invalid
	 */
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

	/**
	 * Saves a skin image to disk and updates the cache.
	 *
	 * @param accountKey the account's key
	 * @param picture    the skin image
	 * @param slim       whether the slim body model is requested
	 * @throws DshException if the image is invalid or cannot be written
	 */
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

		Path target = file(accountKey);
		Image atlas = normalized.getNormalizedTexture();
		try {
			Files.createDirectories(target.getParent());
			ImageIO.write(toBufferedImage(atlas), "png", target.toFile());
		} catch (IOException | RuntimeException e) {
			throw new DshException("Could not write " + target, e);
		}

		LOADED.put(accountKey, new Loaded(atlas, picture, slim, true));
	}

	private static BufferedImage toBufferedImage(Image image) {
		int width = (int) image.getWidth();
		int height = (int) image.getHeight();
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		PixelReader reader = image.getPixelReader();
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				out.setRGB(x, y, reader.getArgb(x, y));
			}
		}
		return out;
	}

	/**
	 * Validates a skin file, copies it into storage, and updates the cache.
	 *
	 * @param accountKey the account's key
	 * @param source     the skin image file
	 * @throws DshException if the image is invalid or cannot be saved
	 */
	public static void setFrom(String accountKey, Path source) throws DshException {
		Image candidate;
		try {
			candidate = new Image(Files.newInputStream(source));
		} catch (IOException | RuntimeException e) {
			throw new DshException("Could not read " + source, e);
		}
		if (candidate.isError() || candidate.getWidth() <= 0) {
			throw new DshException("Could not read " + source + (candidate.getException() == null
			                                                     ? ""
			                                                     : ": " + candidate.getException().getMessage()));
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
			Path staging = target.resolveSibling(target.getFileName() + ".hdsl-writing");
			Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new DshException("Could not write " + target, e);
		}

		LOADED.put(accountKey, new Loaded(normalized.getNormalizedTexture(), candidate, normalized.isSlim(), true));
	}

	/**
	 * Deletes the custom skin file and evicts it from the cache.
	 *
	 * @param accountKey the account's key
	 * @throws DshException if the file cannot be deleted
	 */
	public static void clear(String accountKey) throws DshException {
		try {
			Files.deleteIfExists(file(accountKey));
		} catch (IOException e) {
			throw new DshException("Could not remove " + file(accountKey), e);
		}
		forget(accountKey);
	}

	private static Loaded load(String accountKey) {
		Loaded cached = LOADED.get(accountKey);
		if (cached != null) {
			return cached;
		}
		Loaded skin = readFromDisk(accountKey);
		LOADED.put(accountKey, skin);
		return skin;
	}

	private static Loaded readFromDisk(String accountKey) {
		Path file = file(accountKey);
		if (!Files.isRegularFile(file)) {
			return defaultSkin(accountKey);
		}
		try {
			Image candidate = new Image(Files.newInputStream(file));
			if (candidate.isError() || candidate.getWidth() <= 0) {
				Logger.LOG.warning("The skin at " + file + " could not be read; using this account's default");
				return defaultSkin(accountKey);
			}
			NormalizedSkin normalized = new NormalizedSkin(candidate);
			return new Loaded(normalized.getNormalizedTexture(), candidate, normalized.isSlim(), true);
		} catch (IOException | InvalidSkinException | RuntimeException e) {
			Logger.LOG.warning("The skin at " + file + " is not usable; using this account's default", e);
			return defaultSkin(accountKey);
		}
	}

	private static Loaded defaultSkin(String accountKey) {
		return DEFAULTS.computeIfAbsent(accountKey, DshSkin::chooseDefault);
	}

	private static Loaded chooseDefault(String accountKey) {
		DefaultSkin.Chosen chosen = DefaultSkin.forUuid(uuidOf(accountKey));
		Image picture = chosen.image();
		if (picture == null) {
			return new Loaded(blank(), null, false, false);
		}
		try {
			return new Loaded(new NormalizedSkin(picture).getNormalizedTexture(), picture, chosen.slim(), false);
		} catch (InvalidSkinException e) {
			Logger.LOG.warning("A bundled skin is not usable", e);
			return new Loaded(blank(), null, false, false);
		}
	}

	private static void forget(String accountKey) {
		LOADED.remove(accountKey);
	}

	private static Image blank() {
		if (blank == null) {
			WritableImage image = new WritableImage(64, 64);
			PixelWriter writer = image.getPixelWriter();
			Color skin = Color.web("#8d8d8d");
			for (int y = 0; y < 64; y++) {
				for (int x = 0; x < 64; x++) {
					writer.setColor(x, y, skin);
				}
			}
			blank = image;
		}
		return blank;
	}

	private static String i18n(String key) {
		return I18n.i18n(key);
	}

	/**
	 * Reports whether a file name has a supported image extension.
	 *
	 * @param name the file name
	 * @return true if the file name ends with {@code .png}
	 */
	public static boolean looksLikeAnImage(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.endsWith(".png");
	}

	private record Loaded(Image normalized, @Nullable Image source, boolean slim, boolean fromDisk) {
	}
}
