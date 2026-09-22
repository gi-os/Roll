package com.gios.lightcamera.camera

/**
 * What happens to a panel frame that arrives while the darkroom is behind.
 *
 * **The choice is between smaller photographs and fewer of them, and it used to be made for
 * you.** Instant and the coarse filters shoot the frame already on the panel, and a finger can
 * press faster than the darkroom encodes. The queue behind it has to be bounded — a 128MB heap
 * holds a few dozen panel frames and no more — so past some depth something has to give. The
 * original ladder gave resolution: the first couple of frames queued whole, the next ten at half
 * size, the next twenty at quarter, and only past that was a press refused. It kept the burst
 * alive under any hammering a hand can manage, and it also meant that the eighth photograph of a
 * burst was a quarter the size of the first with nothing anywhere saying so.
 *
 * That is a trade worth offering and not worth imposing. So it is a setting, off by default:
 * with it off every queued frame keeps its full dimensions and the queue is capped at what that
 * many whole frames cost in heap, and a press past the cap is refused out loud — fewer
 * photographs, every one of them the size you were shown. With it on, the ladder is exactly what
 * it was. Either way nothing is silently halved.
 *
 * Numbers, not bitmaps, so the decision is testable. The caller does the scaling.
 */
object PanelPressure {

    /** Panel frames queued at full resolution before the ladder starts trading pixels. */
    const val FULL_DEPTH = 2

    /** Half resolution to here: ~2.5MB a frame, a quarter of the encode. */
    const val HALF_DEPTH = 12

    /** Quarter resolution to here (~0.6MB); past it a drop is at least a named one. */
    const val MAX_DEPTH = 32

    /**
     * The cap with shrinking off.
     *
     * Sized to the same heap the ladder was sized to: roughly 45MB at the very worst, which is
     * what two whole frames, ten halves and twenty quarters come to with their encode buffers.
     * A whole panel frame is about 5.4MB, so eight of them is that budget spent on whole frames
     * alone. Deeper would be more photographs at the cost of the very allocation failures the
     * ladder was introduced to end.
     */
    const val FULL_ONLY_DEPTH = 8

    /**
     * How much to divide a frame's edges by before it queues, or null to refuse it.
     *
     * @param depth how many panel frames are already queued.
     * @param shrinkUnderPressure the setting: true for the resolution ladder, false for
     *   full size up to a shorter cap.
     */
    fun divisor(depth: Int, shrinkUnderPressure: Boolean): Int? =
        if (shrinkUnderPressure) {
            when {
                depth < FULL_DEPTH -> 1
                depth < HALF_DEPTH -> 2
                depth < MAX_DEPTH -> 4
                else -> null
            }
        } else {
            if (depth < FULL_ONLY_DEPTH) 1 else null
        }
}
