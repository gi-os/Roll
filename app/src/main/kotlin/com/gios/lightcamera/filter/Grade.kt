package com.gios.lightcamera.filter

/**
 * The ten adjustments behind Preset.
 *
 * **Why this exists at all.** Every other filter in this app is a look somebody else decided on —
 * Film is Film, Game Boy is Game Boy, and the only choice you have is which one. The first slot on
 * the dial used to be None, which is not a look but the absence of one, and it was the slot people
 * spent most of their time on. So None became Preset: still the plain photograph by default, but
 * with somewhere to put the small corrections a photograph actually wants. A touch warmer. A stop
 * down. A little more in the shadows.
 *
 * **Steps, not floats.** Every adjustment is an integer from [MIN] to [MAX] with zero in the middle,
 * because the control is a stepper on a 3.92" panel driven by a thumb or a click wheel, and a
 * continuous slider there is a control you cannot land on a value with. Eleven positions is enough
 * range to change a photograph and few enough to walk end to end without letting go.
 *
 * The shader reads these as -1..1; see [normalised].
 */
data class Grade(
    val exposure: Int = 0,
    val contrast: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    val vibrance: Int = 0,
    val warmth: Int = 0,
    val tint: Int = 0,
    val sharpness: Int = 0,
    /** One-sided: there is no such thing as negative grain. 0..[MAX]. */
    val grain: Int = 0,
    val vignette: Int = 0,
) {

    /**
     * True when this grade would change nothing.
     *
     * **Load-bearing, not a nicety.** A neutral Preset has to be byte-for-byte the untouched
     * photograph — the same file the camera would have written with no filter at all — so the
     * whole GPU path is skipped rather than run with identity uniforms. That is what keeps
     * "no filter" free, and it is why [Filters.forGrade] hands back the null-shader filter here.
     */
    val isNeutral: Boolean
        get() = exposure == 0 && contrast == 0 && highlights == 0 && shadows == 0 &&
            vibrance == 0 && warmth == 0 && tint == 0 && sharpness == 0 &&
            grain == 0 && vignette == 0

    operator fun get(adjust: Adjust): Int = when (adjust) {
        Adjust.Exposure -> exposure
        Adjust.Contrast -> contrast
        Adjust.Highlights -> highlights
        Adjust.Shadows -> shadows
        Adjust.Vibrance -> vibrance
        Adjust.Warmth -> warmth
        Adjust.Tint -> tint
        Adjust.Sharpness -> sharpness
        Adjust.Grain -> grain
        Adjust.Vignette -> vignette
    }

    /** [value] is clamped to the adjustment's own range, so callers can add blindly. */
    fun with(adjust: Adjust, value: Int): Grade {
        val v = value.coerceIn(adjust.min, adjust.max)
        return when (adjust) {
            Adjust.Exposure -> copy(exposure = v)
            Adjust.Contrast -> copy(contrast = v)
            Adjust.Highlights -> copy(highlights = v)
            Adjust.Shadows -> copy(shadows = v)
            Adjust.Vibrance -> copy(vibrance = v)
            Adjust.Warmth -> copy(warmth = v)
            Adjust.Tint -> copy(tint = v)
            Adjust.Sharpness -> copy(sharpness = v)
            Adjust.Grain -> copy(grain = v)
            Adjust.Vignette -> copy(vignette = v)
        }
    }

    fun step(adjust: Adjust, by: Int): Grade = with(adjust, this[adjust] + by)

    /** How many adjustments are off zero. The chip in the band shows this. */
    val touched: Int get() = Adjust.entries.count { this[it] != 0 }

    /** -1..1 for the shader. */
    fun normalised(adjust: Adjust): Float = this[adjust].toFloat() / MAX.toFloat()

    companion object {
        const val MIN = -5
        const val MAX = 5

        val NEUTRAL = Grade()
    }
}

/**
 * One adjustment, with the words the menu uses.
 *
 * [hint] is written for somebody who has not read a photography book. "Vibrance" and "saturation"
 * are the same word to most people and the difference is the entire reason vibrance is the one
 * here, so the row says what it does instead of naming it twice.
 */
enum class Adjust(
    val label: String,
    val hint: String,
    val min: Int = Grade.MIN,
    val max: Int = Grade.MAX,
) {
    Exposure("Exposure", "Brighter or darker overall"),
    Contrast("Contrast", "How far apart the darks and lights sit"),
    Highlights("Highlights", "Recover a blown sky, or push it whiter"),
    Shadows("Shadows", "Open up what is in the dark, or crush it"),
    Vibrance("Vibrance", "Colour, but it leaves skin alone"),
    Warmth("Warmth", "Toward orange, or toward blue"),
    Tint("Tint", "Toward magenta, or toward green"),
    Sharpness("Sharpness", "Crisper edges, or softer ones"),

    /**
     * The one that only goes up. Grain is something you add; there is no negative grain to take
     * away, and a stepper that offered -5 would be offering nothing.
     */
    Grain("Grain", "Film texture, added", min = 0),
    Vignette("Vignette", "Darker corners, or brighter ones"),
    ;

    /** `0`, `+2`, `-4`. The sign is the point, so zero is the only one shown bare. */
    fun display(value: Int): String = when {
        value == 0 -> "0"
        value > 0 -> "+" + value
        else -> "" + value
    }
}
