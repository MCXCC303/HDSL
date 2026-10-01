/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.theme;

import org.glavo.monetfx.*;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.*;

/// Concrete launcher theme values resolved for MonetFX.
///
/// @param primaryColorSeed Color seed used to generate the MonetFX color scheme.
/// @param brightness       Brightness used by the generated color scheme.
/// @param colorStyle       MonetFX color style used by the generated color scheme.
/// @param contrast         MonetFX contrast level used by the generated color scheme.
@NotNullByDefault
public record ResolvedTheme(ThemeColor primaryColorSeed,
                            Brightness brightness,
                            ColorStyle colorStyle,
                            Contrast contrast) {

    /// Default launcher theme used when no custom theme values are configured.
    public static final ResolvedTheme DEFAULT = new ResolvedTheme(ThemeColor.DEFAULT, Brightness.DEFAULT, ColorStyle.FIDELITY, Contrast.DEFAULT);

    /// The colour specification the launcher asks for when the style allows it.
    ///
    /// The newer of the two, and the one a style that exists in both is drawn in — which is what
    /// the original asks for.
    private static final ColorSpecVersion PREFERRED_SPEC = ColorSpecVersion.SPEC_2025;

    /// The colour specification a colour style must be generated under.
    ///
    /// Not every style is defined in every specification, and the library does not refuse the
    /// combination: given a style that does not exist in the specification asked for it
    /// **silently generates another one** — measured on MonetFX 0.4.0, `RAINBOW` asked for as
    /// `SPEC_2025` comes back as the `FIDELITY` palette, with `getColorStyle()` still answering
    /// `Rainbow`. So a person choosing Rainbow got Fidelity's colours and a name that said
    /// otherwise, and nothing anywhere said why: the whole of "Rainbow does not work" is this
    /// one line asking for a specification the style is not defined in.
    ///
    /// The answer is asked of the library rather than listed here, because the list is the
    /// library's to change: a style it adds in a future release then works without this class
    /// having to know its name.
    ///
    /// @param style the style to generate
    /// @return the specification to ask for
    static ColorSpecVersion specVersionFor(ColorStyle style) {
        return style.isSupported(PREFERRED_SPEC) ? PREFERRED_SPEC : ColorSpecVersion.SPEC_2021;
    }

    /// Converts these resolved values to a MonetFX color scheme.
    ///
    /// @return the generated color scheme
    public ColorScheme toColorScheme() {
        return ColorScheme.newBuilder()
                .setPrimaryColorSeed(primaryColorSeed.color())
                .setColorStyle(colorStyle)
                .setBrightness(brightness)
                .setSpecVersion(specVersionFor(colorStyle))
                .setContrast(contrast)
                .build();
    }

}
