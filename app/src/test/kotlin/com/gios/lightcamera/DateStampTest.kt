package com.gios.lightcamera

import com.gios.lightcamera.camera.DateStamp
import com.gios.lightcamera.StampStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The stamp's text, which is the one part of it that can be checked without a screen — and the part
 * that was wrong first time round. The order is month, day, apostrophe-year, and the padding is
 * spaces rather than zeroes; both were read off photographs of the real thing.
 */
class DateStampTest {

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `month day apostrophe year`() {
        assertEquals("11  5 '21", DateStamp.format(at(2021, 11, 5)))
    }

    @Test
    fun `single digits are space padded, not zero padded`() {
        // A leading zero is the tell of a stamp somebody typeset rather than remembered.
        assertEquals(" 3  7 '99", DateStamp.format(at(1999, 3, 7)))
    }

    @Test
    fun `two digit days and months keep the same width`() {
        assertEquals("12 25 '26", DateStamp.format(at(2026, 12, 25)))
        // Same string length whatever the date, so the stamp never shifts about the corner.
        assertEquals(9, DateStamp.format(at(2026, 1, 1)).length)
        assertEquals(9, DateStamp.format(at(2026, 12, 31)).length)
    }

    @Test
    fun `the year is two digits and wraps at the century`() {
        assertEquals(" 1  1 '00", DateStamp.format(at(2000, 1, 1)))
        assertEquals(" 1  1 '08", DateStamp.format(at(2008, 1, 1)))
    }

    @Test
    fun `quartz puts the year first and pads with zeroes`() {
        // The film SLR backs did it the other way round from the compacts, and zero-padded.
        assertEquals("'99 12 29", DateStamp.format(at(1999, 12, 29), StampStyle.Quartz))
        assertEquals("'21 11 05", DateStamp.format(at(2021, 11, 5), StampStyle.Quartz))
    }

    @Test
    fun `the camcorder stamp uses slashes and four digits`() {
        assertEquals("08/31/2015", DateStamp.format(at(2015, 8, 31), StampStyle.Outline))
        assertEquals("01/01/2026", DateStamp.format(at(2026, 1, 1), StampStyle.Outline))
    }

    /* ---------------- the palette, which is the other testable half ---------------- */

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun alpha(c: Int) = (c ushr 24) and 0xFF

    /** How far from grey a packed colour is. Zero is neutral. */
    private fun chroma(c: Int) =
        maxOf(red(c), green(c), blue(c)) - minOf(red(c), green(c), blue(c))

    @Test
    fun `in colour every style keeps its own lamp`() {
        // A date back was an amber LED array or an orange-red LCD, and that colour is half of why a
        // stamped photograph reads as 1994. Losing it by accident would be a silent regression.
        StampStyle.entries.forEach { style ->
            val ink = DateStamp.inkFor(style, mono = false)
            assertTrue("$style went grey", chroma(ink.lamp) > 60)
            assertTrue("$style lamp is not warm", red(ink.lamp) > blue(ink.lamp))
        }
    }

    @Test
    fun `on a black and white photograph the lamp is neutral`() {
        // light-reports#25: the stamp prints after the filter, so an amber date landed at full
        // colour on a Mono, Dither BW, 1-Bit or Halftone frame.
        StampStyle.entries.forEach { style ->
            val ink = DateStamp.inkFor(style, mono = true)
            assertEquals("$style still has a hue on a mono frame", 0, chroma(ink.lamp))
            assertEquals("$style halo is not neutral", 0, chroma(ink.halo))
        }
    }

    @Test
    fun `the mono stamp reads on white as well as on black`() {
        // The part that is not just desaturation. With no hue left, contrast is the only channel the
        // stamp has — and a 1-Bit frame is nothing but white and black, so a light-grey date would
        // vanish over half of it. The bloom inverts to near-black under near-white lamps, which
        // leaves a dark keyline: on white the keyline reads the digits, on black the lamps do.
        StampStyle.entries.forEach { style ->
            val ink = DateStamp.inkFor(style, mono = true)
            assertTrue("$style lamp is not light", red(ink.lamp) > 200)
            assertTrue("$style halo is not dark", red(ink.halo) < 40)
            assertTrue("$style halo is too faint to hold a highlight", alpha(ink.halo) >= 100)
        }
    }
}
