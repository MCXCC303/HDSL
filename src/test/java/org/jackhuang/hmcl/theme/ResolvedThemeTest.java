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
package org.jackhuang.hmcl.theme;

import org.glavo.monetfx.Brightness;
import org.glavo.monetfx.ColorScheme;
import org.glavo.monetfx.ColorSpecVersion;
import org.glavo.monetfx.ColorStyle;
import org.glavo.monetfx.Contrast;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The colour style somebody chose is the one the palette is generated from.
///
/// This pins a defect reported as "rainbow mode does not work". The style was read, stored and
/// handed to MonetFX correctly — and then thrown away by the colour specification the scheme was
/// asked for, because the library **does not refuse a style it has no definition for in that
/// specification: it quietly generates a different one.** Measured on MonetFX 0.4.0, `RAINBOW`
/// asked for as `SPEC_2025` comes back as the `FIDELITY` palette, while `getColorStyle()` still
/// answers `Rainbow` — so every surface that reports which style is in force agreed with the
/// person, and the colours on screen were another style's.
///
/// The assertion is made on the pair of values `ResolvedTheme` decides between, and on the whole
/// of the generated stylesheet rather than on one colour, because "the style was ignored" is a
/// statement about the palette and not about any single role in it.
class ResolvedThemeTest {
    /// A colour seed of the launcher's own, so the assertion is about the style and not the seed.
    private static final ThemeColor SEED = ThemeColor.DEFAULT;

    /// Builds the theme a person gets for a style.
    ///
    /// @param style the style
    /// @return the resolved theme
    private static ResolvedTheme theme(ColorStyle style) {
        return new ResolvedTheme(SEED, Brightness.DEFAULT, style, Contrast.DEFAULT);
    }

    /// The specification is the one the style is defined in, whichever that is.
    @Test
    void aStyleIsAskedForUnderASpecificationItIsDefinedIn() {
        for (ColorStyle style : ColorStyle.values()) {
            if (style == ColorStyle.DEFAULT) {
                // Not a style a palette is generated from; the library resolves it itself.
                continue;
            }
            ColorSpecVersion spec = ResolvedTheme.specVersionFor(style);
            assertTrue(style.isSupported(spec),
                    style + " was asked for under " + spec + ", where it is not defined");
        }
    }

    /// Rainbow, which is the style that was reported, is generated as rainbow.
    ///
    /// Two things are asserted, and both of them were false before: the specification asked for
    /// is one Rainbow exists in, and the palette is not the one Fidelity generates for the same
    /// seed. The second is what a person sees; the first is what makes it true.
    @Test
    void rainbowIsNotSilentlyDrawnAsFidelity() {
        ResolvedTheme rainbow = theme(ColorStyle.RAINBOW);
        ResolvedTheme fidelity = theme(ColorStyle.FIDELITY);

        assertEquals(ColorSpecVersion.SPEC_2021, ResolvedTheme.specVersionFor(ColorStyle.RAINBOW),
                "Rainbow is not defined in SPEC_2025, so asking for it there asked for another style");

        ColorScheme rainbowScheme = rainbow.toColorScheme();
        ColorScheme fidelityScheme = fidelity.toColorScheme();

        assertEquals(ColorStyle.RAINBOW, rainbowScheme.getColorStyle(),
                "the generated scheme has to be the style that was asked for");
        assertNotEquals(rainbowScheme.toStyleSheet(), fidelityScheme.toStyleSheet(),
                "a scheme generated from Rainbow is not the one generated from Fidelity");
    }

    /// A style that exists in the newer specification is still drawn in it.
    ///
    /// The other half of the same rule: falling back for everything would have made the fix a
    /// regression for the styles that are defined in both, and `TONAL_SPOT` is one — its two
    /// palettes differ, so which one the launcher asks for is visible.
    @Test
    void aStyleThatExistsInTheNewerSpecificationKeepsIt() {
        assertEquals(ColorSpecVersion.SPEC_2025, ResolvedTheme.specVersionFor(ColorStyle.TONAL_SPOT));

        ColorScheme requested = theme(ColorStyle.TONAL_SPOT).toColorScheme();
        assertEquals(ColorSpecVersion.SPEC_2025, requested.getSpecVersion());

        // And the choice is not a formality for this style: the older specification would have
        // drawn a different palette, so a policy that always fell back would be a change.
        ColorScheme older = ColorScheme.newBuilder()
                .setPrimaryColorSeed(SEED.color())
                .setColorStyle(ColorStyle.TONAL_SPOT)
                .setBrightness(Brightness.DEFAULT)
                .setSpecVersion(ColorSpecVersion.SPEC_2021)
                .setContrast(Contrast.DEFAULT)
                .build();
        assertNotEquals(older.toStyleSheet(), requested.toStyleSheet(),
                "Tonal Spot is drawn differently under the two specifications");
        assertFalse(requested.toStyleSheet().isBlank());
    }
}
