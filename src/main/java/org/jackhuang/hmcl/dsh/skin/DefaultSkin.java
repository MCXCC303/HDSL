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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/// A skin that ships with the launcher.
///
/// The original bundles its defaults and offers them as choices, which is what makes the chooser
/// usable before anything has been imported: a person with no PNG to hand can still see what the
/// feature does, and a person who wants a plain look does not have to draw one.
///
/// The two names are the models the game itself ships — the wide one and the slim one — and each
/// comes in both body types, so the choice of which is not a choice between two pictures but
/// between a picture and a body.
@NotNullByDefault
public enum DefaultSkin {
    /// The wide-armed model.
    STEVE("steve"),

    /// The slim-armed model.
    ALEX("alex");

    /// Where the pictures live inside the jar.
    private static final String DIRECTORY = "/assets/img/skin/";

    /// The file's base name.
    private final String baseName;

    /// The picture, read once.
    private @Nullable Image wide;

    /// The slim picture, read once.
    private @Nullable Image slim;

    DefaultSkin(String baseName) {
        this.baseName = baseName;
    }

    /// The skins offered, in the order the interface shows them.
    ///
    /// @return the list
    public static List<DefaultSkin> offered() {
        return List.of(values());
    }

    /// Returns what the interface calls this skin.
    ///
    /// @return the name
    public String displayName() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }

    /// Returns the i18n key for this skin's name.
    ///
    /// @return the key
    public String i18nKey() {
        return "dsh.skin.default." + baseName;
    }

    /// Reads the picture for a body type.
    ///
    /// The file is opened from the running jar, so a missing one is a packaging mistake rather than
    /// anything the person did; it is reported by returning `null` and the caller draws the
    /// fallback, which keeps a broken resource from taking the interface down with it.
    ///
    /// @param slim whether the slim body is wanted
    /// @return the picture, or `null` when it cannot be read
    public @Nullable Image image(boolean slim) {
        if (slim ? this.slim != null : wide != null) {
            return slim ? this.slim : wide;
        }

        String path = DIRECTORY + (slim ? "slim/" : "wide/") + baseName + ".png";
        try (InputStream stream = DefaultSkin.class.getResourceAsStream(path)) {
            if (stream == null) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning("No bundled skin at " + path);
                return null;
            }
            // Read without a requested size: the 3D renderer refuses an image that was decoded to
            // one, because it cannot tell such a picture from one that was assembled.
            Image image = new Image(stream);
            if (image.isError() || image.getWidth() <= 0) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning("The bundled skin " + path
                        + " could not be read");
                return null;
            }
            if (slim) {
                this.slim = image;
            } else {
                wide = image;
            }
            return image;
        } catch (java.io.IOException | RuntimeException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "The bundled skin " + path + " could not be read", e);
            return null;
        }
    }
}
