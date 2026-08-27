package com.gios.lightcamera

import com.gios.lightcamera.camera.ZoneFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point of this file is that the focus distances are *derived*, so they have to be right.
 * A hyperfocal distance that is wrong by a factor is a street photograph that is soft, and there
 * is no way to notice on a 3.92" panel.
 */
class ZoneFocusTest {

    // A phone main camera in the right ballpark: 5.6mm at f/1.8 on a 7.0 x 5.3mm sensor.
    private val focal = 5.6f
    private val fStop = 1.8f
    private val sensorW = 7.0f
    private val sensorH = 5.3f
    private val hyperfocal = ZoneFocus.hyperfocalMetres(focal, fStop, sensorW, sensorH)

    @Test
    fun `focusing at the hyperfocal distance is sharp from half of it to infinity`() {
        // The defining property of the hyperfocal distance, and the whole reason to focus there.
        val depth = ZoneFocus.depthOfField(hyperfocal, hyperfocal, focal)
        assertEquals(hyperfocal / 2f, depth.near, hyperfocal * 0.02f)
        assertTrue(depth.far.isInfinite())
    }

    @Test
    fun `the hyperfocal distance is a plausible few metres, not centimetres or a kilometre`() {
        assertTrue("got $hyperfocal", hyperfocal > 1f && hyperfocal < 12f)
    }

    @Test
    fun `a wider aperture gives a longer hyperfocal distance`() {
        val wide = ZoneFocus.hyperfocalMetres(focal, 1.8f, sensorW, sensorH)
        val stoppedDown = ZoneFocus.hyperfocalMetres(focal, 8f, sensorW, sensorH)
        assertTrue(wide > stoppedDown)
    }

    @Test
    fun `focusing closer than the hyperfocal distance has a finite far edge`() {
        val depth = ZoneFocus.depthOfField(1f, hyperfocal, focal)
        assertTrue(depth.far.isFinite())
        assertTrue(depth.near < 1f && depth.far > 1f)
    }

    @Test
    fun `a lens that says nothing falls back rather than dividing by zero`() {
        assertEquals(ZoneFocus.DEFAULT_HYPERFOCAL_M, ZoneFocus.hyperfocalMetres(0f, 0f, 0f, 0f), 0.001f)
        val depth = ZoneFocus.depthOfField(0f, 0f, 0f)
        assertEquals(0f, depth.near, 0.001f)
        assertTrue(depth.far.isInfinite())
    }

    /* ---------------- diopters ---------------- */

    @Test
    fun `infinity is zero diopters, both ways`() {
        // The trap in the whole file: LENS_FOCUS_DISTANCE takes 1/metres, so infinity is not a
        // large number, it is zero — and a naive division would produce it from a divide by zero.
        assertEquals(0f, ZoneFocus.metresToDiopters(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(0f, ZoneFocus.metresToDiopters(0f), 0.0001f)
        assertTrue(ZoneFocus.dioptersToMetres(0f).isInfinite())
    }

    @Test
    fun `two metres is half a diopter`() {
        assertEquals(0.5f, ZoneFocus.metresToDiopters(2f), 0.0001f)
        assertEquals(2f, ZoneFocus.dioptersToMetres(0.5f), 0.0001f)
    }

    /* ---------------- the dial ---------------- */

    @Test
    fun `the stops include the hyperfocal distance and infinity, in order`() {
        val stops = ZoneFocus.stops(hyperfocal, closestMetres = 0.1f)
        assertTrue(stops.contains(hyperfocal))
        assertTrue(stops.last().isInfinite())
        val finite = stops.filter { it.isFinite() }
        assertEquals(finite.sorted(), finite)
    }

    @Test
    fun `stops closer than the lens can focus are not offered`() {
        // A dial position the lens physically cannot reach is a control that lies.
        val stops = ZoneFocus.stops(hyperfocal, closestMetres = 1f)
        assertTrue(stops.none { it.isFinite() && it < 1f })
    }

    @Test
    fun `the nearest stop is where switching in starts`() {
        val stops = ZoneFocus.stops(hyperfocal, closestMetres = 0.1f)
        assertEquals(1f, stops[ZoneFocus.nearestStop(stops, 1.1f)], 0.001f)
        assertTrue(stops[ZoneFocus.nearestStop(stops, Float.POSITIVE_INFINITY)].isInfinite())
    }

    @Test
    fun `an empty stop list cannot throw`() {
        assertEquals(0, ZoneFocus.nearestStop(emptyList(), 2f))
    }

    /* ---------------- the readout ---------------- */

    @Test
    fun `the readout names the distance and what is sharp`() {
        val label = ZoneFocus.label(hyperfocal, ZoneFocus.depthOfField(hyperfocal, hyperfocal, focal))
        assertTrue("got $label", label.contains("sharp"))
        assertTrue("got $label", label.contains("∞"))
    }

    @Test
    fun `close distances read in centimetres`() {
        val label = ZoneFocus.label(0.3f, ZoneFocus.depthOfField(0.3f, hyperfocal, focal))
        assertTrue("got $label", label.contains("cm"))
    }
}
