package com.gios.lightcamera.video

import com.gios.lightcamera.filter.Adjust
import com.gios.lightcamera.filter.Filters
import com.gios.lightcamera.filter.Grade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLooksTest {

    @Test
    fun `plain is first, free, and the fallback`() {
        assertSame(VideoLooks.plain, VideoLooks.all.first())
        assertTrue(VideoLooks.plain.plain)
        assertSame(VideoLooks.plain, VideoLooks.byId("no such look"))
        assertSame(VideoLooks.plain, VideoLooks.byId(null))
    }

    @Test
    fun `ids are unique and every other look has a shader`() {
        val ids = VideoLooks.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        VideoLooks.all.drop(1).forEach { assertFalse("${it.id} is empty", it.plain) }
    }

    @Test
    fun `the video-only looks are all there`() {
        val ids = VideoLooks.all.map { it.id }.toSet()
        listOf("super8", "vhs", "trails", "stopmotion", "cctv", "motion", "slitscan", "mosh").forEach {
            assertTrue("$it missing", it in ids)
        }
    }

    @Test
    fun `datamosh sits far from preset in both directions`() {
        // The photo dial's lesson (light-reports#27): the look that wrecks the picture must not be
        // one overshoot away from the plain one on a dial that wraps.
        val n = VideoLooks.all.size
        val at = VideoLooks.all.indexOfFirst { it.id == "mosh" }
        assertTrue("datamosh is $at of $n", minOf(at, n - at) >= n / 3)
    }

    @Test
    fun `purikura stays a photograph`() {
        assertFalse(VideoLooks.all.any { it.id == "purikura" })
    }

    @Test
    fun `switching a photo filter off takes its port off the video dial, never a native look`() {
        val off = setOf("gbcolor", "gameboy", "none")
        val dial = VideoLooks.dial(off)
        assertFalse(dial.any { it.id == "gbcolor" })
        assertFalse(dial.any { it.id == "gameboy" })
        assertTrue(dial.first().id == VideoLooks.plain.id)
        assertTrue(dial.any { it.id == "vhs" })
        assertEquals(VideoLooks.all.size - 2, dial.size)
    }

    @Test
    fun `the wheel wraps both ways`() {
        val all = VideoLooks.all
        assertSame(all.last(), VideoLooks.step(all.first(), -1))
        assertSame(all.first(), VideoLooks.step(all.last(), 1))
        assertSame(all[1], VideoLooks.step(all.first(), 1))
    }

    @Test
    fun `a look not on the dial steps onto its end`() {
        val dial = VideoLooks.dial(setOf("gameboy"))
        val gb = VideoLooks.byId("gameboy")
        assertSame(dial.first(), VideoLooks.step(gb, 1, dial))
        assertSame(dial.last(), VideoLooks.step(gb, -1, dial))
    }

    @Test
    fun `preset wears the photo grade and nothing else does`() {
        val warm = Grade().with(Adjust.Warmth, 3)
        val graded = VideoLooks.forGrade(VideoLooks.plain, warm)
        assertTrue(graded.adjustable)
        assertFalse(graded.plain)
        assertEquals(warm, graded.grade)
        assertSame(VideoLooks.plain, VideoLooks.forGrade(VideoLooks.plain, Grade.NEUTRAL))
        val vhs = VideoLooks.byId("vhs")
        assertSame(vhs, VideoLooks.forGrade(vhs, warm))
    }

    @Test
    fun `a ported look is the photo filter's own shader`() {
        val gb = VideoLooks.byId("gameboy")
        assertEquals("gameboy", gb.photoId)
        assertEquals(GlslPort.port(Filters.byId("gameboy").source!!), gb.glsl)
    }

    @Test
    fun `the only photo filters on the video dial are the two Game Boys`() {
        val ported = VideoLooks.all.mapNotNull { it.photoId }.toSet()
        assertEquals(setOf("gameboy", "gbcolor"), ported)
    }

    @Test
    fun `only the looks that need memory ask for it`() {
        VideoLooks.all.forEach { look ->
            if (look.id == "slitscan") assertTrue(look.history > 1) else assertEquals(look.id, 0, look.history)
            if (look.id == "mosh") assertTrue(look.prePass != null) else assertEquals(look.id, null, look.prePass)
        }
    }
}
