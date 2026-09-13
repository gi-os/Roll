package com.gios.lightcamera.camera

/**
 * Whether to light the lamp for a photograph taken off the panel.
 *
 * **Why there is a second flash decision in this app at all.** Roll has two ways of taking a
 * photograph. One asks the sensor for a frame, and there the flash is the camera's own business:
 * `ImageCapture.flashMode` goes to CameraX, CameraX asks the HAL for a flash-firing auto-exposure
 * mode, and the lamp fires in step with an exposure. The other grabs the frame that is already on
 * the screen — Simple, the Screen size, and every coarse filter — and that path never calls
 * `takePicture` at all, so nothing in it had ever read the flash mode. Three of the app's capture
 * routes therefore offered a flash control that did nothing, which is how a camera comes to be
 * reported as one whose flash never fires.
 *
 * What a grab can have is a lamp held on across it, so the decision is which grabs get one. That
 * decision is here rather than in the view model because it is the part worth being sure about: On
 * must not fire where there is no lamp, Auto must not fire in daylight, and neither may take a
 * torch away from somebody who switched it on by hand.
 *
 * Android-free and unit-tested, the [FaceMapper] precedent. See `PanelFlashTest`.
 */
object PanelFlash {

    /**
     * How long the lamp is held before the frame is taken.
     *
     * The number is auto-exposure's, not the LED's. The preview stream is metered for the room as
     * it was a moment ago, and a frame grabbed the instant the light arrives is that old metering
     * applied to a newly lit scene — a face burnt to white. The preview's AE settles inside about a
     * quarter of a second on this camera; 320 ms leaves a margin and is still short enough to read
     * as part of the press rather than as a wait.
     */
    const val SETTLE_MS = 320L

    /**
     * Where Auto decides the room is dark, as a mean luma out of 255.
     *
     * Deliberately low. Auto flash that fires in a lit room is worse than one that declines in a
     * dim one: the first ruins a photograph you could have had, the second gives you the
     * photograph the panel was already showing you. An indoor scene at night reads around 30-50
     * here; a room with the lights on clears 100 comfortably.
     */
    const val DARK_BELOW = 70

    /**
     * True when the lamp should be held on for this grab.
     *
     * @param mode what the band's flash chip says.
     * @param hasLamp whether this camera has a flash unit at all — the selfie lens does not.
     * @param torchHeld whether the person is already holding the torch on. Their switch wins: the
     *   scene is lit, there is nothing to add, and a lamp cycle would end by turning their torch
     *   off.
     * @param meanLuma the panel's average brightness, 0-255, or null when no reading could be
     *   taken. **Null does not fire.** Auto with no measurement is a guess, and a guess that fires
     *   goes off in somebody's face across a restaurant table.
     */
    fun wanted(
        mode: FlashMode,
        hasLamp: Boolean,
        torchHeld: Boolean,
        meanLuma: Int?,
    ): Boolean {
        if (!hasLamp || torchHeld) return false
        return when (mode) {
            FlashMode.Off -> false
            FlashMode.On -> true
            FlashMode.Auto -> meanLuma != null && meanLuma < DARK_BELOW
        }
    }

    /**
     * The average brightness of a frame, 0-255, from ARGB pixels as `Bitmap.getPixels` gives them.
     *
     * Rec. 601, the same weighting [Luma] uses, so the meter the histogram draws and the meter the
     * flash decides on cannot disagree about what dark means. Returns null for an empty frame,
     * which [wanted] reads as "no measurement" rather than as "black".
     */
    fun meanLuma(pixels: IntArray, width: Int, height: Int): Int? {
        val count = width * height
        if (width <= 0 || height <= 0 || count > pixels.size) return null
        var sum = 0L
        for (i in 0 until count) {
            val p = pixels[i]
            sum += (
                ((p shr 16 and 0xFF) * 299) +
                    ((p shr 8 and 0xFF) * 587) +
                    ((p and 0xFF) * 114)
                ) / 1000
        }
        return (sum / count).toInt()
    }
}
