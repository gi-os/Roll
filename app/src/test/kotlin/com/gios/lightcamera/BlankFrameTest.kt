package com.gios.lightcamera

import com.gios.lightcamera.camera.BlankFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Is this a photograph?"
 *
 * The one thing these tests are really holding is the direction to fail in. Accepting a dead frame
 * writes a black file somebody has to go and delete; refusing a real one loses a picture they took
 * and cannot take again, so every ambiguous case here has to come out "not blank".
 */
class BlankFrameTest {

    @Test
    fun `a frame of one repeated value is blank`() {
        assertTrue(BlankFrame.isBlank(IntArray(64) { 0xFF000000.toInt() }))
        // Not only black: a readback can come back as any single value, and none of them are a
        // photograph.
        assertTrue(BlankFrame.isBlank(IntArray(64) { -1 }))
        assertTrue(BlankFrame.isBlank(IntArray(64) { 0 }))
    }

    @Test
    fun `one pixel of difference is a photograph`() {
        val samples = IntArray(64) { 0xFF000000.toInt() }
        samples[37] = 0xFF000001.toInt()
        assertFalse(BlankFrame.isBlank(samples))
    }

    @Test
    fun `too few samples is never blank`() {
        // No evidence either way in one number, and the safe reading of no evidence is that the
        // frame is real.
        assertFalse(BlankFrame.isBlank(IntArray(0)))
        assertFalse(BlankFrame.isBlank(IntArray(1)))
    }

    @Test
    fun `the grid covers the frame and stays inside it`() {
        val width = 1080
        val height = 1440
        val points = BlankFrame.points(width, height)
        assertEquals(BlankFrame.SAMPLES * 2, points.size)
        for (i in 0 until BlankFrame.SAMPLES) {
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            assertTrue("x $x out of range", x in 0 until width)
            assertTrue("y $y out of range", y in 0 until height)
        }
    }

    @Test
    fun `the grid takes cell centres, not the edges`() {
        // A ruler from 0 to width-1 puts a quarter of its points on the outermost rows and
        // columns, which on a preview are as often the letterboxing as the picture.
        val points = BlankFrame.points(800, 800)
        val xs = (0 until BlankFrame.SAMPLES).map { points[it * 2] }.toSortedSet()
        assertFalse(xs.contains(0))
        assertFalse(xs.contains(799))
        assertEquals(BlankFrame.GRID, xs.size)
    }

    @Test
    fun `every sample is a distinct point on a frame larger than the grid`() {
        val points = BlankFrame.points(640, 480)
        val seen = HashSet<Long>()
        for (i in 0 until BlankFrame.SAMPLES) {
            seen.add(points[i * 2].toLong() shl 32 or points[i * 2 + 1].toLong())
        }
        assertEquals(BlankFrame.SAMPLES, seen.size)
    }

    @Test
    fun `a frame smaller than the grid still samples inside itself`() {
        // Points collapse onto each other at this size, which is fine — the only thing that must
        // not happen is an index off the end of the bitmap.
        val points = BlankFrame.points(3, 2)
        assertEquals(BlankFrame.SAMPLES * 2, points.size)
        for (i in 0 until BlankFrame.SAMPLES) {
            assertTrue(points[i * 2] in 0..2)
            assertTrue(points[i * 2 + 1] in 0..1)
        }
    }

    @Test
    fun `a frame with no size has no points`() {
        assertEquals(0, BlankFrame.points(0, 0).size)
        assertEquals(0, BlankFrame.points(-4, 100).size)
    }
}
