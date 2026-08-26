package com.gios.lightcamera

import com.gios.lightcamera.ui.ColorMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the two daltonizer settings mean read together — the predicate behind
 * `ColorMode.phoneIsColour`, which decides whether the app tells you to go and run an adb line.
 *
 * The whole point is that `accessibility_display_daltonizer_enabled` is not the answer on its
 * own. The daltonizer's off is mode **-1**; mode **0** is simulate monochromacy, and enabled 1
 * with mode 0 is the pair LightOS pins. Anything that holds the phone in colour by moving the
 * mode instead of the flag leaves enabled at 1, and the old check read that as grey.
 */
class ColorModeTest {

    @Test
    fun `the pair LightOS pins is the only grey`() {
        assertFalse(ColorMode.isColour(enabled = 1, mode = 0))
    }

    @Test
    fun `the correction switched off is colour, whatever mode was left behind`() {
        assertTrue(ColorMode.isColour(enabled = 0, mode = 0))
        assertTrue(ColorMode.isColour(enabled = 0, mode = -1))
        assertTrue(ColorMode.isColour(enabled = 0, mode = 12))
    }

    @Test
    fun `enabled with the mode moved off monochromacy is a colour screen`() {
        // This is the case report #54 was filed from: an overrider leaves the flag alone and
        // changes the mode, so the panel is in real colour with enabled still 1.
        assertTrue(ColorMode.isColour(enabled = 1, mode = -1))
        // Deuteranomaly, protanomaly, tritanomaly — corrections, not monochromacy. They tint
        // the screen; they do not take the colour out of it.
        assertTrue(ColorMode.isColour(enabled = 1, mode = 11))
        assertTrue(ColorMode.isColour(enabled = 1, mode = 12))
        assertTrue(ColorMode.isColour(enabled = 1, mode = 13))
    }

    @Test
    fun `an unset flag reads as colour rather than nagging`() {
        // A phone that never had the setting written reads back the 0 default. Not the LPIII,
        // but the failure it must not have is a false "run adb" on a colour screen.
        assertTrue(ColorMode.isColour(enabled = 0, mode = 0))
    }
}
