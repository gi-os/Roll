package com.gios.lightcamera

import com.gios.lightcamera.camera.FlashMode
import com.gios.lightcamera.camera.PanelFlash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel-grab flash decision.
 *
 * Worth testing rather than eyeballing because every branch here is a thing that is hard to
 * notice on a phone: a flash that does not fire looks like a flash that decided not to, and a
 * flash that fires in daylight looks like a bug in somebody else's app.
 */
class PanelFlashTest {

    @Test
    fun `on fires`() {
        assertTrue(PanelFlash.wanted(FlashMode.On, hasLamp = true, torchHeld = false, meanLuma = 200))
    }

    @Test
    fun `off never fires`() {
        assertFalse(PanelFlash.wanted(FlashMode.Off, hasLamp = true, torchHeld = false, meanLuma = 0))
    }

    @Test
    fun `no lamp never fires`() {
        // The selfie lens on this phone has no flash unit. On means on where there is one.
        assertFalse(PanelFlash.wanted(FlashMode.On, hasLamp = false, torchHeld = false, meanLuma = 0))
        assertFalse(PanelFlash.wanted(FlashMode.Auto, hasLamp = false, torchHeld = false, meanLuma = 0))
    }

    @Test
    fun `a held torch is left alone`() {
        // Not merely "no need to": a lamp cycle ends by switching the light off, which would take
        // the person's torch away as a side effect of taking a photograph.
        assertFalse(PanelFlash.wanted(FlashMode.On, hasLamp = true, torchHeld = true, meanLuma = 0))
        assertFalse(PanelFlash.wanted(FlashMode.Auto, hasLamp = true, torchHeld = true, meanLuma = 0))
    }

    @Test
    fun `auto fires in the dark and declines in the light`() {
        assertTrue(PanelFlash.wanted(FlashMode.Auto, hasLamp = true, torchHeld = false, meanLuma = 20))
        assertFalse(PanelFlash.wanted(FlashMode.Auto, hasLamp = true, torchHeld = false, meanLuma = 140))
    }

    @Test
    fun `auto is exclusive at the threshold`() {
        val at = PanelFlash.DARK_BELOW
        assertTrue(PanelFlash.wanted(FlashMode.Auto, hasLamp = true, torchHeld = false, meanLuma = at - 1))
        assertFalse(PanelFlash.wanted(FlashMode.Auto, hasLamp = true, torchHeld = false, meanLuma = at))
    }

    @Test
    fun `auto with no reading does not fire`() {
        // A grab that could not be measured is not evidence of a dark room, and the failure mode of
        // guessing wrong is a lamp in somebody's face.
        assertFalse(PanelFlash.wanted(FlashMode.Auto, hasLamp = true, torchHeld = false, meanLuma = null))
    }

    @Test
    fun `mean luma reads black and white`() {
        val black = IntArray(64) { 0xFF000000.toInt() }
        val white = IntArray(64) { -1 }
        assertEquals(0, PanelFlash.meanLuma(black, 8, 8))
        assertEquals(255, PanelFlash.meanLuma(white, 8, 8))
    }

    @Test
    fun `mean luma weights green like the eye does`() {
        // Rec. 601: the same weighting the histogram uses, so the meter and the flash cannot
        // disagree about what dark means. Pure green is far brighter than pure blue.
        val green = IntArray(4) { 0xFF00FF00.toInt() }
        val blue = IntArray(4) { 0xFF0000FF.toInt() }
        assertEquals(149, PanelFlash.meanLuma(green, 2, 2))
        assertEquals(29, PanelFlash.meanLuma(blue, 2, 2))
    }

    @Test
    fun `mean luma refuses a frame it cannot read`() {
        assertNull(PanelFlash.meanLuma(IntArray(0), 0, 0))
        // Fewer pixels than the dimensions claim: a short buffer is a failed readback, not a black
        // frame, and reading past it would be an exception inside the shutter.
        assertNull(PanelFlash.meanLuma(IntArray(10), 8, 8))
    }
}
