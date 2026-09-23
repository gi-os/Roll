package com.gios.lightcamera.video

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The coordinate arithmetic behind [VideoFx], kept apart so it can be tested without a GPU.
 *
 * Three spaces are in play, all in GL's texture convention (0..1, origin bottom-left):
 *
 *  - **buffer** — the camera's frame as `SurfaceTexture` hands it over once its own transform is
 *    applied. Upright for the *sensor*, which on this phone is a quarter turn off the panel.
 *  - **upright** — the frame the way the viewfinder shows it, portrait, top at the top. Every look
 *    is drawn here, because it is the space the photo filters were written for.
 *  - **output** — one of CameraX's destinations, the preview or the encoder, each with its own
 *    crop and rotation that CameraX folds into `SurfaceOutput.updateTransformMatrix`.
 *
 * The rotation into upright is only ever used as a pair — in on the way into the looks, and
 * [invert]ed on the way out to each output — so if it were ever a quarter turn wrong, the clip
 * would still come out the right way up and only a look that has a left and a right would notice.
 * That is deliberate: it keeps the one number CameraX does not hand over from being able to wreck
 * a recording.
 */
object FxGeometry {

    /** An affine map of texture coordinates: `x' = a·x + c·y + tx`, `y' = b·x + d·y + ty`. */
    data class Affine(
        val a: Float, val b: Float,
        val c: Float, val d: Float,
        val tx: Float, val ty: Float,
    ) {
        fun apply(x: Float, y: Float): Pair<Float, Float> = (a * x + c * y + tx) to (b * x + d * y + ty)

        /** This then [other]: `this(other(p))`. */
        operator fun times(other: Affine): Affine = Affine(
            a = a * other.a + c * other.b,
            b = b * other.a + d * other.b,
            c = a * other.c + c * other.d,
            d = b * other.c + d * other.d,
            tx = a * other.tx + c * other.ty + tx,
            ty = b * other.tx + d * other.ty + ty,
        )

        /** Column-major 4×4, the layout `android.opengl.Matrix` and `glUniformMatrix4fv` use. */
        fun toGl(): FloatArray = floatArrayOf(
            a, b, 0f, 0f,
            c, d, 0f, 0f,
            0f, 0f, 1f, 0f,
            tx, ty, 0f, 1f,
        )

        companion object {
            val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)

            /** The 2D part of a column-major 4×4, which is all a texture transform uses. */
            fun fromGl(m: FloatArray): Affine = Affine(m[0], m[1], m[4], m[5], m[12], m[13])
        }
    }

    /**
     * Upright texture coordinates to buffer ones, for a buffer that has to be turned
     * [clockwiseDegrees] to stand upright — `SENSOR_ORIENTATION`'s own definition.
     *
     * Derived in image coordinates (y down), where a clockwise turn sends buffer (x, y) to upright
     * (1−y, x), then carried into GL's y-up convention. The tests check it corner by corner.
     */
    fun uprightToBuffer(clockwiseDegrees: Int): Affine = when (((clockwiseDegrees % 360) + 360) % 360) {
        90 -> Affine(a = 0f, b = 1f, c = -1f, d = 0f, tx = 1f, ty = 0f)
        180 -> Affine(a = -1f, b = 0f, c = 0f, d = -1f, tx = 1f, ty = 1f)
        270 -> Affine(a = 0f, b = -1f, c = 1f, d = 0f, tx = 0f, ty = 1f)
        else -> Affine.IDENTITY
    }

    /** The inverse of an affine map, or identity for one that has collapsed to a line. */
    fun invert(m: Affine): Affine {
        val det = m.a * m.d - m.b * m.c
        if (kotlin.math.abs(det) < 1e-9f) return Affine.IDENTITY
        val ia = m.d / det
        val ib = -m.b / det
        val ic = -m.c / det
        val id = m.a / det
        return Affine(
            a = ia, b = ib, c = ic, d = id,
            tx = -(ia * m.tx + ic * m.ty),
            ty = -(ib * m.tx + id * m.ty),
        )
    }

    /**
     * The size the looks are drawn at, upright.
     *
     * A buffer turned a quarter swaps its sides. The long edge is then held to [longEdgeCap]:
     * a look is a full-screen pass or several, and 1440 is the point past which this GPU starts
     * dropping frames on the heavier ones while a 3.92" panel and an FHD file stop getting any
     * sharper. Even dimensions, because some encoders' colour conversion reads pixel pairs.
     */
    fun workingSize(bufferWidth: Int, bufferHeight: Int, clockwiseDegrees: Int, longEdgeCap: Int = 1440): Pair<Int, Int> {
        if (bufferWidth <= 0 || bufferHeight <= 0) return 2 to 2
        val quarter = (((clockwiseDegrees % 360) + 360) % 360) % 180 == 90
        val w = if (quarter) bufferHeight else bufferWidth
        val h = if (quarter) bufferWidth else bufferHeight
        val scale = minOf(1f, longEdgeCap.toFloat() / max(w, h))
        fun even(v: Float) = max(2, (v.roundToInt() / 2) * 2)
        return even(w * scale) to even(h * scale)
    }

    /**
     * Quarter turns clockwise from the look's frame to the **world's** upright, for a clip shot
     * with the device at [deviceRotationDegrees] (`Surface.ROTATION_*` as degrees) on the front
     * or back lens.
     *
     * Derived rather than borrowed. The look's frame is the buffer turned clockwise by
     * `SENSOR_ORIENTATION`. The clip plays the buffer turned clockwise by CameraX's relative
     * rotation, which is `sensor − device` on the back lens and `sensor + device` on the front,
     * because a front camera faces the other way. The sensor angle cancels, leaving `−device` or
     * `+device`.
     *
     * **The front lens is why this exists.** The first build reused the photo filters' turn,
     * `previewRotationDegrees() / 90`, which is the back-lens answer. On the front lens held
     * sideways it is half a turn out, so a CCTV caption would have landed upside down in the
     * bottom-right corner of the clip. `FxGeometryTest` walks every device angle on both lenses.
     */
    fun worldTurn(deviceRotationDegrees: Int, front: Boolean): Int {
        val q = (((deviceRotationDegrees / 90) % 4) + 4) % 4
        return if (front) q else (4 - q) % 4
    }

    /** The history ring's size for a frame of [w]×[h], its long edge held to [edge]. */
    fun historySize(w: Int, h: Int, edge: Int): Pair<Int, Int> {
        val scale = minOf(1f, edge.toFloat() / max(1, max(w, h)))
        return max(2, (w * scale).roundToInt()) to max(2, (h * scale).roundToInt())
    }

    /** Which layer the next frame goes into, and how many layers now hold a frame. */
    fun pushHistory(head: Int, length: Int, capacity: Int): Pair<Int, Int> {
        if (capacity <= 0) return -1 to 0
        return ((head + 1) % capacity) to minOf(length + 1, capacity)
    }
}
