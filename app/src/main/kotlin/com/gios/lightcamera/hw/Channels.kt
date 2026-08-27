package com.gios.lightcamera.hw

import com.gios.lightcamera.camera.ExposureMode

/**
 * What the wheel is currently holding.
 *
 * **The list is not fixed, because half of these do not always exist.** Shutter is only a channel
 * when the photographer has taken the shutter off the meter; focus distance is only a channel in
 * zone focus. Offering a dial position that does nothing is how a physical control loses people's
 * trust — turn it, nothing happens, and now every other position is suspect too.
 */
enum class Channel(val label: String) {
    Filter("FILTER"),
    Exposure("EV"),
    Shutter("SHUTTER"),
    Iso("ISO"),
    Focus("FOCUS"),
    Zoom("ZOOM"),
    ;

    companion object {

        /**
         * The channels available right now, in dial order.
         *
         * Filter and exposure compensation are always there. The two halves of the exposure appear
         * only when they are actually being held — in Auto the camera owns both and a shutter dial
         * would be a lie. Focus appears only in zone focus, for the same reason.
         */
        fun available(
            exposure: ExposureMode,
            zoneFocus: Boolean,
            filters: Boolean,
        ): List<Channel> = buildList {
            if (filters) add(Filter)
            // Exposure compensation is meaningless with the meter switched off — it biases a
            // meter that is no longer deciding anything.
            if (!exposure.manualAe) add(Exposure)
            if (exposure.holdsShutter) add(Shutter)
            if (exposure.holdsIso) add(Iso)
            if (zoneFocus) add(Focus)
            add(Zoom)
        }

        /**
         * The next channel round, given what is available.
         *
         * Falls to the first available channel when the current one has just disappeared — which
         * happens the moment somebody leaves a priority mode while the wheel is holding the half
         * that mode was holding.
         */
        fun next(current: Channel, available: List<Channel>): Channel {
            if (available.isEmpty()) return current
            val at = available.indexOf(current)
            if (at < 0) return available.first()
            return available[(at + 1) % available.size]
        }
    }
}
