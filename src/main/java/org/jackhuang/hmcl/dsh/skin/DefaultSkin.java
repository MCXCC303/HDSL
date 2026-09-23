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
import java.util.Locale;
import java.util.UUID;

/// A skin that ships with the launcher.
///
/// The original bundles nine of them, and — this is the part that is easy to miss — does not simply
/// draw the first one when nothing has been chosen. It picks one from the account's own identity:
///
/// ```java
/// private static final String[] DEFAULT_SKINS = {"alex", "ari", "efe", "kai", "makena",
///                                                "noor", "steve", "sunny", "zuri"};
///
/// public static LoadedTexture getDefaultSkin(UUID uuid) {
///     int idx = Math.floorMod(uuid.hashCode(), DEFAULT_SKINS.length * 2);
///     if (idx < DEFAULT_SKINS.length) {
///         model = TextureModel.SLIM;
///         skin = newBuiltinImage("/assets/img/skin/slim/" + DEFAULT_SKINS[idx] + ".png");
///     } else {
///         model = TextureModel.WIDE;
///         skin = newBuiltinImage("/assets/img/skin/wide/" + DEFAULT_SKINS[idx - DEFAULT_SKINS.length] + ".png");
///     }
/// }
/// ```
///
/// So an account with no skin has *its own* face, the same one every time it starts, and two
/// accounts do not look alike. That is [#forUuid] and the whole reason this is an enum of nine
/// rather than a pair of constants: "nothing chosen" is a choice the launcher makes, and it makes it
/// the way the original does.
///
/// The declaration order is the original's array order, and it has to stay that way: [#forUuid]
/// indexes into [#values], so reordering these silently changes which skin every account wears.
@NotNullByDefault
public enum DefaultSkin {
    /// The slim-armed model with Alex's face.
    ALEX("alex"),

    /// The slim-armed model with Ari's face.
    ARI("ari"),

    /// The slim-armed model with Efe's face.
    EFE("efe"),

    /// The slim-armed model with Kai's face.
    KAI("kai"),

    /// The slim-armed model with Makena's face.
    MAKENA("makena"),

    /// The slim-armed model with Noor's face.
    NOOR("noor"),

    /// The wide-armed model with Steve's face.
    STEVE("steve"),

    /// The slim-armed model with Sunny's face.
    SUNNY("sunny"),

    /// The slim-armed model with Zuri's face.
    ZURI("zuri");

    /// Where the pictures live inside the jar.
    private static final String DIRECTORY = "/assets/img/skin/";

    /// The file's base name.
    private final String baseName;

    /// The picture for the wide body, read once.
    private @Nullable Image wide;

    /// The picture for the slim body, read once.
    private @Nullable Image slim;

    DefaultSkin(String baseName) {
        this.baseName = baseName;
    }

    /// A skin chosen for an account, with the body it is drawn on.
    ///
    /// The two travel together because they are one answer in the original: the choice is not "which
    /// picture" but "which picture, on which body", and a caller that took only the picture would
    /// have to guess the body and would sometimes guess wrong.
    ///
    /// @param image the picture, or `null` when the bundled file could not be read
    /// @param slim  whether it is drawn on the slim body
    public record Chosen(@Nullable Image image, boolean slim) {
    }

    /// Returns the skin the original would draw for an account.
    ///
    /// The account's identity decides, so the answer is stable for an account and different between
    /// accounts. Nine pictures are offered across two bodies, so eighteen outcomes are spread over
    /// the hash — the original's own arithmetic, kept exactly, because a different sum would give
    /// every account a different face from the one the original gives it.
    ///
    /// @param uuid the account's identity
    /// @return the skin and its body
    public static Chosen forUuid(UUID uuid) {
        DefaultSkin[] skins = values();
        int index = Math.floorMod(uuid.hashCode(), skins.length * 2);
        if (index < skins.length) {
            return new Chosen(skins[index].image(true), true);
        }
        return new Chosen(skins[index - skins.length].image(false), false);
    }

    /// Returns what the interface calls this skin.
    ///
    /// @return the name
    public String displayName() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
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
