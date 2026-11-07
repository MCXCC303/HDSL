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

/**
 * Verifies color scheme generation and specification version mapping for theme styles.
 */
class ResolvedThemeTest {
    private static final ThemeColor SEED = ThemeColor.DEFAULT;

    private static ResolvedTheme theme(ColorStyle style) {
        return new ResolvedTheme(SEED, Brightness.DEFAULT, style, Contrast.DEFAULT);
    }

    @Test
    void aStyleIsAskedForUnderASpecificationItIsDefinedIn() {
        for (ColorStyle style : ColorStyle.values()) {
            if (style == ColorStyle.DEFAULT) {
                continue;
            }
            ColorSpecVersion spec = ResolvedTheme.specVersionFor(style);
            assertTrue(style.isSupported(spec),
                    style + " was asked for under " + spec + ", where it is not defined");
        }
    }

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

    @Test
    void aStyleThatExistsInTheNewerSpecificationKeepsIt() {
        assertEquals(ColorSpecVersion.SPEC_2025, ResolvedTheme.specVersionFor(ColorStyle.TONAL_SPOT));

        ColorScheme requested = theme(ColorStyle.TONAL_SPOT).toColorScheme();
        assertEquals(ColorSpecVersion.SPEC_2025, requested.getSpecVersion());

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
