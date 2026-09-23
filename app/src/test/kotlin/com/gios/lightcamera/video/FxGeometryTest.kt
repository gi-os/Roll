package com.gios.lightcamera.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The turn into the upright frame, corner by corner.
 *
 * In GL's convention (origin bottom-left). A buffer that needs a quarter turn clockwise has its
 * top-left corner end up at the upright frame's top-right — the one fact every other case here is
 * derived from.
 */
class FxGeometryTest {

    private fun assertMaps(m: FxGeometry.Affine, from: Pair<Float, Float>, to: Pair<Float, Float>) {
        val (x, y) = m.apply(from.first, from.second)
        assertEquals("x of $from", to.first, x, 1e-5f)
        assertEquals("y of $from", to.second, y, 1e-5f)
    }

    @Test
    fun `no turn is the identity`() {
        val m = FxGeometry.uprightToBuffer(0)
        assertMaps(m, 0f to 0f, 0f to 0f)
        assertMaps(m, 1f to 1f, 1f to 1f)
    }

    @Test
    fun `a quarter clockwise puts the buffer's top-left at the upright top-right`() {
        val m = FxGeometry.uprightToBuffer(90)
        // Upright top-right (GL 1,1) samples buffer top-left (GL 0,1).
        assertMaps(m, 1f to 1f, 0f to 1f)
        // Upright top-left (0,1) samples buffer bottom-left (0,0).
        assertMaps(m, 0f to 1f, 0f to 0f)
        // Upright bottom-left (0,0) samples buffer bottom-right (1,0).
        assertMaps(m, 0f to 0f, 1f to 0f)
        assertMaps(m, 0.5f to 0.5f, 0.5f to 0.5f)
    }

    @Test
    fun `three quarters is the other way round`() {
        val m = FxGeometry.uprightToBuffer(270)
        // Upright top-left (0,1) samples buffer top-right... of a counter-clockwise turn:
        // buffer top-left (0,1) lands at upright bottom-left (0,0).
        assertMaps(m, 0f to 0f, 0f to 1f)
        assertMaps(m, 1f to 1f, 1f to 0f)
    }

    @Test
    fun `half a turn flips both axes`() {
        val m = FxGeometry.uprightToBuffer(180)
        assertMaps(m, 0f to 0f, 1f to 1f)
        assertMaps(m, 0.25f to 0.75f, 0.75f to 0.25f)
    }

    @Test
    fun `negative and over-a-turn angles normalise`() {
        assertEquals(FxGeometry.uprightToBuffer(90), FxGeometry.uprightToBuffer(-270))
        assertEquals(FxGeometry.uprightToBuffer(90), FxGeometry.uprightToBuffer(450))
    }

    @Test
    fun `the way out undoes the way in, whatever CameraX's own matrix is`() {
        // A SurfaceTexture transform is typically a y-flip with a hair of crop.
        val st = FxGeometry.Affine(1f, 0f, 0f, -0.98f, 0f, 0.99f)
        for (deg in listOf(0, 90, 180, 270)) {
            val upright = st * FxGeometry.uprightToBuffer(deg)
            val back = FxGeometry.invert(upright)
            val round = back * upright
            for (p in listOf(0f to 0f, 1f to 0f, 0.3f to 0.8f)) assertMaps(round, p, p)
        }
    }

    @Test
    fun `the round trip means a look cannot turn the clip`() {
        // What drawLook does: outputs sample fx through back * out, and fx holds the frame sampled
        // through upright. Composed, the output sees `out` alone — exactly what a straight draw
        // would — for any sensor rotation, right or wrong.
        val st = FxGeometry.Affine(1f, 0f, 0f, -1f, 0f, 1f)
        val out = FxGeometry.Affine(0f, 1f, -1f, 0f, 1f, 0f) * st
        for (deg in listOf(0, 90, 180, 270)) {
            val upright = st * FxGeometry.uprightToBuffer(deg)
            val composed = upright * (FxGeometry.invert(upright) * out)
            for (p in listOf(0f to 0f, 1f to 1f, 0.2f to 0.6f)) {
                val a = composed.apply(p.first, p.second)
                val b = out.apply(p.first, p.second)
                assertEquals(b.first, a.first, 1e-5f)
                assertEquals(b.second, a.second, 1e-5f)
            }
        }
    }

    @Test
    fun `gl layout is column major`() {
        val m = FxGeometry.Affine(1f, 2f, 3f, 4f, 5f, 6f).toGl()
        assertEquals(1f, m[0]); assertEquals(2f, m[1]); assertEquals(3f, m[4]); assertEquals(4f, m[5])
        assertEquals(5f, m[12]); assertEquals(6f, m[13]); assertEquals(1f, m[15])
        assertEquals(FxGeometry.Affine(1f, 2f, 3f, 4f, 5f, 6f), FxGeometry.Affine.fromGl(m))
    }

    @Test
    fun `working size turns with the buffer and holds the long edge`() {
        assertEquals(1080 to 1440, FxGeometry.workingSize(1440, 1080, 90))
        assertEquals(1080 to 1440, FxGeometry.workingSize(1440, 1080, 270))
        assertEquals(1440 to 1080, FxGeometry.workingSize(1440, 1080, 0))
        // 1920x1080 turned is 1080x1920, held to 1440 on the long edge.
        assertEquals(810 to 1440, FxGeometry.workingSize(1920, 1080, 90))
        val (w, h) = FxGeometry.workingSize(1923, 1081, 90)
        assertEquals(0, w % 2); assertEquals(0, h % 2)
        assertEquals(2 to 2, FxGeometry.workingSize(0, 0, 90))
    }

    @Test
    fun `history ring fills then wraps`() {
        var head = -1; var len = 0
        repeat(3) { val (h, l) = FxGeometry.pushHistory(head, len, 3); head = h; len = l }
        assertEquals(2, head); assertEquals(3, len)
        val (h, l) = FxGeometry.pushHistory(head, len, 3)
        assertEquals(0, h); assertEquals(3, l)
        assertEquals(-1 to 0, FxGeometry.pushHistory(5, 5, 0))
        assertEquals(480 to 640, FxGeometry.historySize(1080, 1440, 640))
    }
}
