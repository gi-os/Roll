package com.gios.lightcamera.camera

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Focus set by distance instead of by pointing at something.
 *
 * **The distances are computed from this lens, not copied from another camera.** Zero pins focus
 * at 0.45 diopters and calls it hyperfocal, which is right for the phone it was measured on and an
 * assertion everywhere else — the hyperfocal distance falls straight out of focal length, aperture
 * and how big a blur circle counts as sharp, and all three are things the camera will tell you. A
 * constant is also silently wrong on the selfie camera, which has a different lens entirely.
 *
 * Everything here is arithmetic, so it can be checked without a sensor.
 */
object ZoneFocus {

    /**
     * How big a blur circle still reads as a point, as a fraction of the sensor diagonal.
     *
     * The textbook figure for 35mm is 0.03mm on a 43mm diagonal, which is 1/1440. Phone sensors
     * are looked at proportionally much larger, so this is deliberately tighter than the film
     * convention: a photograph judged on a 3.92" panel at arm's length is being enlarged far more
     * than a 6x4 print ever was.
     */
    private const val CIRCLE_OF_CONFUSION_RATIO = 1.0 / 1730.0

    /** Diopters, which is what `LENS_FOCUS_DISTANCE` takes: 1/metres. Infinity is zero. */
    fun metresToDiopters(metres: Float): Float =
        if (metres <= 0f || metres.isInfinite()) 0f else 1f / metres

    fun dioptersToMetres(diopters: Float): Float =
        if (diopters <= 0f) Float.POSITIVE_INFINITY else 1f / diopters

    /**
     * The circle of confusion for a sensor of this physical size, in millimetres.
     */
    fun circleOfConfusion(sensorWidthMm: Float, sensorHeightMm: Float): Double {
        val diagonal = sqrt(
            sensorWidthMm.toDouble() * sensorWidthMm + sensorHeightMm.toDouble() * sensorHeightMm,
        )
        return diagonal * CIRCLE_OF_CONFUSION_RATIO
    }

    /**
     * The hyperfocal distance in metres: focus here and everything from half of it to infinity is
     * acceptably sharp.
     *
     * `H = f²/(N·c) + f`, with f and c in millimetres and the result converted to metres.
     */
    fun hyperfocalMetres(
        focalLengthMm: Float,
        aperture: Float,
        sensorWidthMm: Float,
        sensorHeightMm: Float,
    ): Float {
        val c = circleOfConfusion(sensorWidthMm, sensorHeightMm)
        if (focalLengthMm <= 0f || aperture <= 0f || c <= 0.0) return DEFAULT_HYPERFOCAL_M
        val f = focalLengthMm.toDouble()
        val h = (f * f) / (aperture * c) + f
        return (h / 1000.0).toFloat()
    }

    /** Near and far edges of what is sharp, in metres. [far] is infinite past the hyperfocal. */
    class Depth(val near: Float, val far: Float)

    /**
     * What is sharp when focused at [subjectMetres].
     *
     * Standard depth-of-field, and the important case is the last one: at or past the hyperfocal
     * distance the far edge is infinity, which is the whole reason anyone focuses there.
     */
    fun depthOfField(
        subjectMetres: Float,
        hyperfocalMetres: Float,
        focalLengthMm: Float,
    ): Depth {
        if (subjectMetres <= 0f || hyperfocalMetres <= 0f) {
            return Depth(0f, Float.POSITIVE_INFINITY)
        }
        val f = focalLengthMm / 1000f
        val h = hyperfocalMetres
        val s = subjectMetres
        val near = (s * (h - f)) / (h + s - 2 * f)
        val far = if (s >= h) {
            Float.POSITIVE_INFINITY
        } else {
            (s * (h - f)) / (h - s)
        }
        return Depth(near.coerceAtLeast(0f), far)
    }

    /**
     * `2.2 m · sharp 1.1 m–∞`. What the readout says while the wheel is turning.
     *
     * Zero prints nothing at all here, which is the difference between zone focusing and guessing.
     */
    fun label(subjectMetres: Float, depth: Depth): String {
        val subject = if (subjectMetres.isInfinite()) "∞" else formatMetres(subjectMetres)
        val near = formatMetres(depth.near)
        val far = if (depth.far.isInfinite()) "∞" else formatMetres(depth.far)
        return "$subject · sharp $near–$far"
    }

    private fun formatMetres(metres: Float): String = when {
        metres.isInfinite() -> "∞"
        metres < 1f -> "%.0f cm".format(metres * 100)
        metres < 10f -> "%.1f m".format(metres)
        else -> "%.0f m".format(metres)
    }

    /**
     * The distances the dial rests at.
     *
     * **Detents, because a blind turn has to land somewhere useful.** A continuous focus ring on a
     * wheel you cannot see is a control you have to look at the screen to use, which defeats the
     * point of putting it on the wheel. Hyperfocal is included as a stop of its own because it is
     * the one setting a street photographer actually wants, and computing where it falls is
     * exactly what this file is for.
     */
    fun stops(hyperfocalMetres: Float, closestMetres: Float): List<Float> {
        val fixed = listOf(0.3f, 0.5f, 1f, 2f, 5f)
        return buildList {
            fixed.filter { it >= closestMetres }.forEach { add(it) }
            add(hyperfocalMetres)
            add(Float.POSITIVE_INFINITY)
        }.distinct().sortedBy { if (it.isInfinite()) Float.MAX_VALUE else it }
    }

    /** Nearest stop to a distance, so switching into zone focus starts where the lens was. */
    fun nearestStop(stops: List<Float>, metres: Float): Int {
        if (stops.isEmpty()) return 0
        if (metres.isInfinite()) return stops.lastIndex
        return stops.indices.minByOrNull {
            val v = stops[it]
            if (v.isInfinite()) Float.MAX_VALUE else abs(v - metres)
        } ?: 0
    }

    /** Where a camera that will not say anything about its lens is pointed. */
    const val DEFAULT_HYPERFOCAL_M = 2.2f
}
