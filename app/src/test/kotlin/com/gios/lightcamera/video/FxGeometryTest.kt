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

    /** The shader's own `toUp`, in pixels, exactly as the GLSL prelude writes it. */
    private fun toUp(p: Pair<Float, Float>, size: Pair<Float, Float>, turn: Int): Pair<Float, Float> = when (turn % 4) {
        1 -> (size.second - p.second) to p.first
        2 -> (size.first - p.first) to (size.second - p.second)
        3 -> p.second to (size.first - p.first)
        else -> p
    }

    /** A clockwise quarter turn of a point in a w×h image, y down, into the turned h×w image. */
    private fun cw(p: Pair<Float, Float>, h: Float): Pair<Float, Float> = (h - p.second) to p.first

    @Test
    fun `a mark at the world's top left lands there in the clip, every way up, on both lenses`() {
        // Independent of worldTurn: the scene is built from the sensor side and played back the
        // way CameraX rotates a clip, and the shader's own toUp is asked where the mark is.
        for (front in listOf(false, true)) for (sensor in listOf(90, 270)) for (device in listOf(0, 90, 180, 270)) {
            val playback = if (front) (sensor + device) % 360 else (sensor - device + 360) % 360
            // A sensor buffer 400x300. Find the buffer pixel that plays back at world (0.1, 0.2).
            val bw = 400f; val bh = 300f
            val candidates = (0 until 400 step 4).flatMap { x -> (0 until 300 step 4).map { y -> x.toFloat() to y.toFloat() } }
            fun turned(p: Pair<Float, Float>, quarters: Int): Pair<Pair<Float, Float>, Pair<Float, Float>> {
                var q = p; var w = bw; var h = bh
                repeat(quarters) { q = cw(q, h); val t = w; w = h; h = t }
                return q to (w to h)
            }
            val target = candidates.minByOrNull { b ->
                val (q, sz) = turned(b, playback / 90)
                val dx = q.first / sz.first - 0.1f; val dy = q.second / sz.second - 0.2f
                dx * dx + dy * dy
            }!!
            // The same pixel in the look's frame, which is the buffer turned by the sensor angle.
            val (s, sSize) = turned(target, sensor / 90)
            val turn = FxGeometry.worldTurn(device, front)
            val up = toUp(s, sSize, turn)
            val upSize = if (turn % 2 == 1) sSize.second to sSize.first else sSize
            val where = "front=$front sensor=$sensor device=$device turn=$turn"
            assertEquals(where, 0.1f, up.first / upSize.first, 0.02f)
            assertEquals(where, 0.2f, up.second / upSize.second, 0.02f)
        }
    }

    @Test
    fun `on the back lens the world turn is the photo filters' turn`() {
        // previewRotationDegrees(): ROTATION_90 -> 270, ROTATION_180 -> 180, ROTATION_270 -> 90.
        assertEquals(0, FxGeometry.worldTurn(0, front = false))
        assertEquals(3, FxGeometry.worldTurn(90, front = false))
        assertEquals(2, FxGeometry.worldTurn(180, front = false))
        assertEquals(1, FxGeometry.worldTurn(270, front = false))
    }

    @Test
    fun `on the front lens a sideways phone turns the other way`() {
        assertEquals(1, FxGeometry.worldTurn(90, front = true))
        assertEquals(3, FxGeometry.worldTurn(270, front = true))
        assertEquals(2, FxGeometry.worldTurn(180, front = true))
    }
}
