package com.gios.lightcamera

import com.gios.lightcamera.camera.Ring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring holds decoded frames, so the thing most worth testing is not which frame comes back —
 * it is that every frame that does not come back is handed to the caller to release. A leak here
 * is an out-of-memory two shots later, somewhere else entirely.
 */
class RingTest {

    private fun ring(capacity: Int, freed: MutableList<Int>) =
        Ring<Int>(capacity) { freed += it }

    @Test
    fun `the oldest frame is evicted and released when the ring is full`() {
        val freed = mutableListOf<Int>()
        val r = ring(3, freed)
        (1..5).forEach { r.add(it, it * 10L) }
        assertEquals(3, r.size)
        assertEquals(listOf(1, 2), freed)
    }

    @Test
    fun `a ring of nothing releases immediately rather than holding a frame for ever`() {
        val freed = mutableListOf<Int>()
        val r = ring(0, freed)
        r.add(7, 0L)
        assertEquals(0, r.size)
        assertEquals(listOf(7), freed)
    }

    @Test
    fun `clearing releases everything`() {
        val freed = mutableListOf<Int>()
        val r = ring(4, freed)
        (1..3).forEach { r.add(it, it.toLong()) }
        r.clear()
        assertEquals(0, r.size)
        assertEquals(listOf(1, 2, 3), freed)
    }

    /* ---------------- reaching backwards ---------------- */

    @Test
    fun `pre-roll picks the frame nearest that moment`() {
        val r = ring(8, mutableListOf())
        // Frames every 33ms, press at 200.
        listOf(100L, 133L, 166L, 200L).forEach { r.add(it.toInt(), it) }
        assertEquals(133, r.nearest(pressedAtMs = 200, preRollMs = 66))
    }

    @Test
    fun `a pre-roll longer than the ring gives the oldest frame, not nothing`() {
        // A pre-roll that occasionally returns no photograph is one nobody leaves switched on.
        val r = ring(8, mutableListOf())
        listOf(180L, 200L).forEach { r.add(it.toInt(), it) }
        assertEquals(180, r.nearest(pressedAtMs = 200, preRollMs = 5_000))
    }

    @Test
    fun `no pre-roll is the newest frame`() {
        val r = ring(8, mutableListOf())
        listOf(100L, 200L).forEach { r.add(it.toInt(), it) }
        assertEquals(200, r.nearest(pressedAtMs = 200, preRollMs = 0))
        assertEquals(200, r.newest())
    }

    @Test
    fun `an empty ring hands back nothing rather than throwing`() {
        val r = ring(8, mutableListOf())
        assertNull(r.nearest(1_000, 100))
        assertNull(r.newest())
        assertNull(r.takeBest { 1f })
    }

    @Test
    fun `taking the nearest frame never recycles what it returns`() {
        // **The bug this guards against shipped.** nearest() then clear() handed the caller a
        // frame that clear() had just recycled — a crash on the next draw, with Reach back on and
        // burst off, which is the setting's default pairing.
        val freed = mutableListOf<Int>()
        val r = ring(8, freed)
        listOf(100L, 133L, 166L, 200L).forEach { r.add(it.toInt(), it) }
        val taken = r.takeNearest(pressedAtMs = 200, preRollMs = 66)
        assertEquals(133, taken)
        assertTrue("the returned frame must never be evicted", 133 !in freed)
        assertEquals(listOf(100, 166, 200), freed.sorted())
        assertEquals(0, r.size)
    }

    @Test
    fun `taking from an empty ring is nothing, not a crash`() {
        assertNull(ring(8, mutableListOf()).takeNearest(1_000, 100))
    }

    /* ---------------- picking ---------------- */

    @Test
    fun `the highest score wins and every loser is released`() {
        val freed = mutableListOf<Int>()
        val r = ring(8, freed)
        listOf(3, 9, 5).forEach { r.add(it, it.toLong()) }
        assertEquals(9, r.takeBest { it.toFloat() })
        // The winner must not be released; everything else must.
        assertEquals(listOf(3, 5), freed.sorted())
        assertEquals(0, r.size)
    }

    @Test
    fun `the ring is emptied by a pick, so the next press starts clean`() {
        val freed = mutableListOf<Int>()
        val r = ring(8, freed)
        listOf(1, 2).forEach { r.add(it, it.toLong()) }
        r.takeBest { it.toFloat() }
        assertEquals(0, r.size)
        assertTrue(r.entries().isEmpty())
    }

    @Test
    fun `ties keep the first, so a still scene does not jitter between frames`() {
        val r = ring(8, mutableListOf())
        listOf(4, 4, 4).forEach { r.add(it, it.toLong()) }
        assertEquals(4, r.takeBest { it.toFloat() })
    }
}
