package com.gios.lightcamera

import com.gios.lightcamera.camera.Exposure
import com.gios.lightcamera.camera.ExposureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exposure arithmetic is the kind of code that is wrong quietly. A rebalance off by a stop does
 * not throw — it hands back a photograph a stop dark, which you find out about at home.
 */
class ExposureTest {

    private val shutterRange = 1_000L..30_000_000_000L
    private val isoRange = 50..3200

    /* ---------------- labels ---------------- */

    @Test
    fun `fast shutters read as fractions`() {
        assertEquals("1/125", Exposure.shutterLabel(Exposure.stopToNanos(125)))
        assertEquals("1/8000", Exposure.shutterLabel(Exposure.stopToNanos(8000)))
    }

    @Test
    fun `slow shutters read in seconds`() {
        assertEquals("1.0\"", Exposure.shutterLabel(Exposure.NANOS_PER_SECOND))
        assertEquals("2.0\"", Exposure.shutterLabel(2 * Exposure.NANOS_PER_SECOND))
        assertEquals("30\"", Exposure.shutterLabel(30 * Exposure.NANOS_PER_SECOND))
    }

    @Test
    fun `a nonsense exposure reads as nothing rather than dividing by zero`() {
        assertEquals("—", Exposure.shutterLabel(0))
        assertEquals("—", Exposure.shutterLabel(-1))
    }

    /* ---------------- the dial ---------------- */

    @Test
    fun `the dial clamps instead of wrapping`() {
        // The wheel reports several notches for one flick, so this is the difference between a
        // stop too far and a full second where 1/8000 was.
        assertEquals(0, Exposure.stepIndex(Exposure.SHUTTER_STOPS.size, 0, -6))
        assertEquals(
            Exposure.SHUTTER_STOPS.lastIndex,
            Exposure.stepIndex(Exposure.SHUTTER_STOPS.size, Exposure.SHUTTER_STOPS.lastIndex, 9),
        )
    }

    @Test
    fun `an empty or stale index cannot throw`() {
        assertEquals(0, Exposure.stepIndex(0, 5, 2))
        assertTrue(Exposure.shutterAt(9999) > 0)
        assertTrue(Exposure.isoAt(-3) > 0)
    }

    @Test
    fun `leaving auto starts at the stop nearest the meter`() {
        val metered = Exposure.stopToNanos(60)
        assertEquals(Exposure.stopToNanos(60), Exposure.shutterAt(Exposure.nearestShutterIndex(metered)))
        assertEquals(400, Exposure.isoAt(Exposure.nearestIsoIndex(390)))
    }

    /* ---------------- ISO past the ceiling ---------------- */

    @Test
    fun `iso within the sensor asks for no boost`() {
        assertEquals(1600 to 100, Exposure.splitIso(1600, 3200))
    }

    @Test
    fun `iso past the sensor becomes gain after the readout`() {
        // 6400 on a 3200 sensor is 3200 with the rest applied as post-raw boost, not a request for
        // a sensitivity the hardware does not have — which is refused outright and reads on the
        // phone as a shutter that did nothing.
        assertEquals(3200 to 200, Exposure.splitIso(6400, 3200))
    }

    @Test
    fun `the boost has the API's own ceiling`() {
        val (iso, boost) = Exposure.splitIso(1_000_000, 100)
        assertEquals(100, iso)
        assertTrue(boost <= 3199)
    }

    /* ---------------- priority modes ---------------- */

    @Test
    fun `holding the shutter open lets the meter drop the iso`() {
        // Metered at 1/60 and ISO 800. Hold the shutter a stop slower and the same light needs
        // half the sensitivity.
        val (shutter, iso) = Exposure.rebalance(
            meteredShutterNanos = Exposure.stopToNanos(60),
            meteredIso = 800,
            heldShutterNanos = Exposure.stopToNanos(30),
            heldIso = null,
            shutterRange = shutterRange,
            isoRange = isoRange,
        )
        assertEquals(Exposure.stopToNanos(30), shutter)
        assertEquals(400, iso)
    }

    @Test
    fun `holding the iso down lets the meter slow the shutter`() {
        val (shutter, iso) = Exposure.rebalance(
            meteredShutterNanos = Exposure.stopToNanos(60),
            meteredIso = 800,
            heldShutterNanos = null,
            heldIso = 400,
            shutterRange = shutterRange,
            isoRange = isoRange,
        )
        assertEquals(400, iso)
        // The derived half is continuous rather than snapped to a stop — the camera can take any
        // value in range and finer metering is the point — so this compares what is read off the
        // viewfinder rather than the exact nanosecond.
        assertEquals("1/30", Exposure.shutterLabel(shutter))
    }

    @Test
    fun `full manual takes both as given`() {
        val (shutter, iso) = Exposure.rebalance(
            meteredShutterNanos = Exposure.stopToNanos(60),
            meteredIso = 800,
            heldShutterNanos = Exposure.stopToNanos(1000),
            heldIso = 100,
            shutterRange = shutterRange,
            isoRange = isoRange,
        )
        assertEquals(Exposure.stopToNanos(1000), shutter)
        assertEquals(100, iso)
    }

    @Test
    fun `auto changes nothing`() {
        val (shutter, iso) = Exposure.rebalance(
            meteredShutterNanos = Exposure.stopToNanos(60),
            meteredIso = 800,
            heldShutterNanos = null,
            heldIso = null,
            shutterRange = shutterRange,
            isoRange = isoRange,
        )
        assertEquals(Exposure.stopToNanos(60), shutter)
        assertEquals(800, iso)
    }

    @Test
    fun `the clamp is where the exposure stops being holdable`() {
        // A thirty-second hold in daylight cannot be balanced: the sensitivity floor is reached
        // and the frame will be blown. The pair comes back clamped and withinRange says so.
        val (shutter, iso) = Exposure.rebalance(
            meteredShutterNanos = Exposure.stopToNanos(2000),
            meteredIso = 50,
            heldShutterNanos = 30 * Exposure.NANOS_PER_SECOND,
            heldIso = null,
            shutterRange = shutterRange,
            isoRange = isoRange,
        )
        assertEquals(30 * Exposure.NANOS_PER_SECOND, shutter)
        assertEquals(50, iso)
        assertFalse(
            "an iso below the sensor floor must not be reported as achievable",
            Exposure.withinRange(shutter, 20, shutterRange, isoRange),
        )
    }

    /* ---------------- what each mode holds ---------------- */

    @Test
    fun `only auto leaves the meter in charge`() {
        assertFalse(ExposureMode.Auto.manualAe)
        assertTrue(ExposureMode.Shutter.manualAe)
        assertTrue(ExposureMode.Iso.manualAe)
        assertTrue(ExposureMode.Manual.manualAe)
    }

    @Test
    fun `each mode holds the half it is named for`() {
        assertTrue(ExposureMode.Shutter.holdsShutter)
        assertFalse(ExposureMode.Shutter.holdsIso)
        assertTrue(ExposureMode.Iso.holdsIso)
        assertFalse(ExposureMode.Iso.holdsShutter)
        assertTrue(ExposureMode.Manual.holdsShutter && ExposureMode.Manual.holdsIso)
        assertFalse(ExposureMode.Auto.holdsShutter || ExposureMode.Auto.holdsIso)
    }
}
