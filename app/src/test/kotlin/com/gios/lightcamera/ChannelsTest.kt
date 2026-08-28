package com.gios.lightcamera

import com.gios.lightcamera.camera.ExposureMode
import com.gios.lightcamera.hw.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule this is really testing: **the wheel never offers a position that does nothing.**
 * A physical control that sometimes ignores you is worse than one that is missing, because after
 * the first time every other position is suspect too.
 */
class ChannelsTest {

    @Test
    fun `in auto the wheel holds filters, compensation, focus and zoom`() {
        val out = Channel.available(ExposureMode.Auto, filters = true)
        assertEquals(listOf(Channel.Filter, Channel.Exposure, Channel.Focus, Channel.Zoom), out)
    }

    @Test
    fun `shutter priority puts the shutter on the wheel and takes compensation off`() {
        // Exposure compensation biases a meter. With AE off there is no meter to bias, so leaving
        // it on the dial would be a control that silently does nothing.
        val out = Channel.available(ExposureMode.Shutter, filters = true)
        assertTrue(Channel.Shutter in out)
        assertFalse(Channel.Exposure in out)
        assertFalse(Channel.Iso in out)
    }

    @Test
    fun `iso priority is the same the other way up`() {
        val out = Channel.available(ExposureMode.Iso, filters = true)
        assertTrue(Channel.Iso in out)
        assertFalse(Channel.Shutter in out)
    }

    @Test
    fun `full manual puts both halves on the wheel`() {
        val out = Channel.available(ExposureMode.Manual, filters = true)
        assertTrue(Channel.Shutter in out && Channel.Iso in out)
        assertFalse(Channel.Exposure in out)
    }

    @Test
    fun `focus is always on the dial, because choosing it is the MF switch`() {
        // It used to appear only once zone focus was already on — a wheel you had to leave the
        // viewfinder to make exist. Locking the pick onto FOCUS is now what turns MF on.
        assertTrue(Channel.Focus in Channel.available(ExposureMode.Auto, filters = true))
        assertTrue(Channel.Focus in Channel.available(ExposureMode.Manual, filters = false))
    }

    @Test
    fun `a mode with no filters still has a usable wheel`() {
        // Simple and Video have no filter track. Zoom is always there, so the wheel is never dead.
        val out = Channel.available(ExposureMode.Auto, filters = false)
        assertFalse(Channel.Filter in out)
        assertTrue(out.isNotEmpty())
    }

    @Test
    fun `the click cycles and comes back round`() {
        val all = Channel.available(ExposureMode.Auto, filters = true)
        var at = Channel.Filter
        at = Channel.next(at, all)
        assertEquals(Channel.Exposure, at)
        at = Channel.next(at, all)
        assertEquals(Channel.Focus, at)
        at = Channel.next(at, all)
        assertEquals(Channel.Zoom, at)
        assertEquals(Channel.Filter, Channel.next(at, all))
    }

    @Test
    fun `a channel that has just disappeared falls to the first available`() {
        // Leave shutter priority while the wheel is holding the shutter and that channel is gone.
        // Landing nowhere would leave the wheel inert with a label still naming the dead channel.
        val afterLeaving = Channel.available(ExposureMode.Auto, filters = true)
        assertEquals(Channel.Filter, Channel.next(Channel.Shutter, afterLeaving))
    }

    @Test
    fun `an empty list cannot make the wheel throw`() {
        assertEquals(Channel.Filter, Channel.next(Channel.Filter, emptyList()))
    }
}
