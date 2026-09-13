package com.gios.lightcamera.camera

/**
 * Telling a photograph from a dead readback.
 *
 * **The viewfinder does not fail by returning nothing.** Half of Roll's capture paths take the
 * frame that is already on the panel — Simple, the Screen size, every coarse filter, and the
 * rescue that runs when a sensor capture fails. When the window has stopped drawing, which is
 * what the screen going off means, `PreviewView.getBitmap()` does not return null. It returns a
 * bitmap of the right size, full of zeroes. Every check downstream reads that as a successful
 * grab, so it goes through the shader, gets encoded, and lands in the camera roll as a black
 * photograph with the right timestamp on it.
 *
 * A `null` would have been handled everywhere. A black rectangle is handled nowhere, which is why
 * this exists: a frame has to be asked whether it is a picture, and not merely whether it is
 * there.
 *
 * **Sampled, not scanned.** This sits on the shutter's path, and reading ten megapixels to find
 * out whether they are all the same number would cost more than the photograph. A grid of
 * sixty-four points spread across the frame answers it: a dead readback is uniformly zero at every
 * one of them, and a real photograph — even one taken in the dark with a hand over the lens — has
 * sensor noise, so sixty-four points spread across it are never all the same number. The test is
 * deliberately the strictest one available, because the cost of the two mistakes is not
 * symmetrical: refusing a real photograph loses a picture somebody took, while accepting a dead
 * frame only writes a black file they will have to find and delete.
 *
 * Android-free and unit-tested, the [FaceMapper] precedent. See `BlankFrameTest`.
 */
object BlankFrame {

    /** Points down each side of the sample grid. Sixty-four samples in total. */
    const val GRID = 8

    /** How many samples [points] produces, as x,y pairs. */
    const val SAMPLES = GRID * GRID

    /**
     * Where to look, as interleaved `x, y` — `2 * [SAMPLES]` entries.
     *
     * Cell centres rather than a corner-to-corner ruler. A grid that starts at 0 and ends at
     * `width - 1` puts a quarter of its points on the outermost rows and columns, which on a
     * camera preview are the letterboxing as often as they are the picture.
     */
    fun points(width: Int, height: Int): IntArray {
        if (width <= 0 || height <= 0) return IntArray(0)
        val out = IntArray(SAMPLES * 2)
        var at = 0
        for (row in 0 until GRID) {
            val y = ((2 * row + 1) * height / (2 * GRID)).coerceIn(0, height - 1)
            for (column in 0 until GRID) {
                val x = ((2 * column + 1) * width / (2 * GRID)).coerceIn(0, width - 1)
                out[at++] = x
                out[at++] = y
            }
        }
        return out
    }

    /**
     * True when [samples] carry no picture: every value identical.
     *
     * An empty or one-point sample is **not** blank. There is no evidence either way in a single
     * number, and the direction to fail in is "this is a photograph" — see the note above about
     * the two mistakes.
     */
    fun isBlank(samples: IntArray): Boolean {
        if (samples.size < 2) return false
        val first = samples[0]
        for (value in samples) if (value != first) return false
        return true
    }
}
