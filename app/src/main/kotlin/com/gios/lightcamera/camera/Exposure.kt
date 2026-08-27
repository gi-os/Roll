package com.gios.lightcamera.camera

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * How much of the exposure the photographer is holding.
 *
 * **Not a two-way switch, because auto-or-nothing is the wrong shape for this.** Full manual is
 * rarely what anyone actually wants: a long exposure wants the shutter pinned and the sensitivity
 * left to the meter, and a shot that must not go grainy wants the opposite. Zero offers manual or
 * automatic and nothing between, and between is where photographs get taken.
 */
enum class ExposureMode(val label: String) {
    /** The camera decides both. Exposure compensation still applies. */
    Auto("Auto"),

    /**
     * You hold the shutter, the camera picks the sensitivity.
     *
     * Camera2 has no half-manual auto-exposure — `CONTROL_AE_MODE` is on or it is off — so this is
     * built rather than requested: the metered pair is read back out of the capture result, and the
     * free half is re-derived against it every time the held half moves. See [Exposure.rebalance].
     */
    Shutter("Shutter"),

    /** You hold the sensitivity, the camera picks the shutter. Same mechanism, other way up. */
    Iso("ISO"),

    /** Both held. Nothing is metered and nothing drifts. */
    Manual("Manual"),
    ;

    /** True where `CONTROL_AE_MODE` has to be off, which is everything except [Auto]. */
    val manualAe: Boolean get() = this != Auto

    val holdsShutter: Boolean get() = this == Shutter || this == Manual

    val holdsIso: Boolean get() = this == Iso || this == Manual
}

/**
 * The exposure arithmetic, with no camera in it.
 *
 * Every function here is total and testable: the parts that talk to the HAL are in
 * [CameraEngine], and the parts that can be *wrong in a way you only notice later* are here.
 */
object Exposure {

    /** 1/8000 s to 30 s, in nanoseconds, clamped to what the sensor actually offers. */
    const val NANOS_PER_SECOND = 1_000_000_000L

    /**
     * The shutter speeds the dial walks through, as denominators of a second.
     *
     * Full stops, plus the slow end a phone can actually hold: this is a dial, and a dial that
     * needs forty turns to cross its range is a slider with extra steps.
     */
    val SHUTTER_STOPS: List<Long> = listOf(
        8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2, 1,
    )

    /** Sensitivities in full stops. Clamped to the sensor's range at use. */
    val ISO_STOPS: List<Int> = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)

    /**
     * `1/125`, `0.5"`, `2"`. What a photographer reads, not what the API takes.
     *
     * Sub-second exposures are written as fractions because that is how they are spoken, and
     * anything at or past a second is written with a seconds mark for the same reason.
     */
    fun shutterLabel(nanos: Long): String {
        if (nanos <= 0L) return "—"
        if (nanos >= NANOS_PER_SECOND) {
            val seconds = nanos.toDouble() / NANOS_PER_SECOND
            return if (seconds >= 10) "${seconds.toInt()}\"" else "%.1f\"".format(seconds)
        }
        val denominator = (NANOS_PER_SECOND.toDouble() / nanos).toInt().coerceAtLeast(1)
        return "1/$denominator"
    }

    fun isoLabel(iso: Int): String = "ISO $iso"

    /** A shutter stop as nanoseconds. */
    fun stopToNanos(denominator: Long): Long =
        if (denominator <= 0L) NANOS_PER_SECOND else NANOS_PER_SECOND / denominator

    /**
     * Move a position along a list of stops.
     *
     * **An index, not a value.** An earlier version took the current value and looked for its
     * nearest neighbour, which needs to know whether the list runs up or down — and the two lists
     * here run opposite ways, because shutter stops are written as denominators. Keeping the
     * position rather than the value removes the question.
     *
     * Clamps rather than wrapping. A dial that rolls off 1/8000 straight round to a full second
     * because one notch too many arrived is a dial that ruins the next photograph, and this wheel
     * reports several notches for one flick.
     */
    fun stepIndex(size: Int, index: Int, notches: Int): Int {
        if (size <= 0) return 0
        return (index + notches).coerceIn(0, size - 1)
    }

    /** The stop at [index], clamped, so a stored index from an older build cannot throw. */
    fun shutterAt(index: Int): Long =
        stopToNanos(SHUTTER_STOPS[index.coerceIn(0, SHUTTER_STOPS.lastIndex)])

    fun isoAt(index: Int): Int = ISO_STOPS[index.coerceIn(0, ISO_STOPS.lastIndex)]

    /** The stop nearest a metered value, so switching out of Auto starts where the meter was. */
    fun nearestShutterIndex(nanos: Long): Int =
        SHUTTER_STOPS.indices.minByOrNull {
            kotlin.math.abs(stopToNanos(SHUTTER_STOPS[it]) - nanos)
        } ?: 0

    fun nearestIsoIndex(iso: Int): Int =
        ISO_STOPS.indices.minByOrNull { kotlin.math.abs(ISO_STOPS[it] - iso) } ?: 0

    /**
     * ISO past the sensor's ceiling, without lying about it.
     *
     * A sensor that stops at 3200 can still be pushed further, but not by asking for a
     * sensitivity it does not have — `CONTROL_POST_RAW_SENSITIVITY_BOOST` applies the rest as gain
     * after the raw readout. So a request for 6400 on a 3200 sensor becomes 3200 plus a boost of
     * 200%, which is what the number meant.
     *
     * Returns the sensitivity to ask for and the boost in percent, where 100 means none.
     */
    fun splitIso(wanted: Int, sensorMax: Int): Pair<Int, Int> {
        if (wanted <= sensorMax) return wanted to 100
        val boost = (wanted.toLong() * 100 / sensorMax.coerceAtLeast(1)).toInt()
        // The API's own ceiling. Past this the request is refused outright, which reads on the
        // phone as a shutter that did nothing.
        return sensorMax to boost.coerceIn(100, 3199)
    }

    /**
     * Keeping half the exposure fixed while the camera meters the other half.
     *
     * Given what the meter last settled on, and the half the photographer is now holding, this
     * returns the other half so the *total* exposure stays where the meter put it. Doubling the
     * shutter speed halves the light, so the sensitivity doubles to match.
     *
     * Both results are clamped to the hardware's ranges, and that clamp is the honest part: at the
     * end of the range the exposure genuinely cannot be held, and the caller says so rather than
     * silently taking a dark frame.
     */
    fun rebalance(
        meteredShutterNanos: Long,
        meteredIso: Int,
        heldShutterNanos: Long?,
        heldIso: Int?,
        shutterRange: LongRange,
        isoRange: IntRange,
    ): Pair<Long, Int> {
        val metered = meteredShutterNanos.toDouble() * meteredIso
        if (heldShutterNanos != null && heldIso != null) {
            return heldShutterNanos.coerceIn(shutterRange.first, shutterRange.last) to
                heldIso.coerceIn(isoRange.first, isoRange.last)
        }
        // **Rounded, not truncated, and that is not a nicety.** A stop is stored as a whole number
        // of nanoseconds — 1/60 is 16,666,666 ns, not 16,666,666.67 — so the metered product is
        // always a hair light. Truncating the division turns "ISO 400" into 399, which is a
        // sensitivity the sensor will happily accept and which reads back wrong for ever after.
        if (heldShutterNanos != null) {
            val shutter = heldShutterNanos.coerceIn(shutterRange.first, shutterRange.last)
            val iso = (metered / shutter).roundToInt().coerceIn(isoRange.first, isoRange.last)
            return shutter to iso
        }
        if (heldIso != null) {
            val iso = heldIso.coerceIn(isoRange.first, isoRange.last)
            val shutter = (metered / iso).roundToLong()
                .coerceIn(shutterRange.first, shutterRange.last)
            return shutter to iso
        }
        return meteredShutterNanos.coerceIn(shutterRange.first, shutterRange.last) to
            meteredIso.coerceIn(isoRange.first, isoRange.last)
    }

    /**
     * Whether the pair asked for is the pair that can be delivered.
     *
     * False means the range ran out and the photograph will not be exposed the way the meter
     * wanted. The viewfinder says so; it does not quietly take it.
     */
    fun withinRange(
        shutterNanos: Long,
        iso: Int,
        shutterRange: LongRange,
        isoRange: IntRange,
    ): Boolean = shutterNanos in shutterRange && iso in isoRange
}
