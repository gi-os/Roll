package com.gios.lightcamera

import com.gios.lightcamera.camera.PanelPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelPressureTest {

    @Test
    fun `with shrinking off a frame is whole or refused, never smaller`() {
        for (depth in 0 until PanelPressure.FULL_ONLY_DEPTH) {
            assertEquals("depth $depth", 1, PanelPressure.divisor(depth, shrinkUnderPressure = false))
        }
        assertNull(PanelPressure.divisor(PanelPressure.FULL_ONLY_DEPTH, shrinkUnderPressure = false))
        assertNull(PanelPressure.divisor(100, shrinkUnderPressure = false))
    }

    @Test
    fun `with shrinking on the ladder is what it was`() {
        assertEquals(1, PanelPressure.divisor(0, true))
        assertEquals(1, PanelPressure.divisor(PanelPressure.FULL_DEPTH - 1, true))
        assertEquals(2, PanelPressure.divisor(PanelPressure.FULL_DEPTH, true))
        assertEquals(2, PanelPressure.divisor(PanelPressure.HALF_DEPTH - 1, true))
        assertEquals(4, PanelPressure.divisor(PanelPressure.HALF_DEPTH, true))
        assertEquals(4, PanelPressure.divisor(PanelPressure.MAX_DEPTH - 1, true))
        assertNull(PanelPressure.divisor(PanelPressure.MAX_DEPTH, true))
    }

    @Test
    fun `the full-only cap stays inside the ladder's heap budget`() {
        // A panel frame is 1080 x 1240 ARGB; the ladder was sized to about 45MB at the worst.
        val frameBytes = 1080L * 1240L * 4L
        val budget = 45L * 1024L * 1024L
        assertTrue(PanelPressure.FULL_ONLY_DEPTH * frameBytes <= budget)
    }
}
