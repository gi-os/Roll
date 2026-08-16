package com.gios.lightcamera.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.gios.lightcamera.StampStyle
import java.util.Calendar

/**
 * The quartz date back, burned into the photograph.
 *
 * Every compact camera from about 1986 to about 2006 could print the date in the corner of the
 * frame, in amber, and it is the one piece of camera furniture everybody now wants back: the date on
 * a photograph, put there by a camera that had no idea what year it would be looked at in.
 *
 * **Three of them**, because they were three different mechanisms and drawing them the same way is
 * what makes fake ones look fake: the compact camera's dot matrix, the film SLR's seven-segment
 * quartz back, and the camcorder's character generator. Each has its own order, padding, colour and
 * typography. See [StampStyle].
 *
 * What the dot matrix needs, all read off photographs rather than guessed:
 *
 *  - **A dot matrix, not a typeface.** A date back exposed a small LED array through the film gate,
 *    so close up the digits are plainly discrete round lamps with the picture showing between them.
 *    Each glyph here is a 5x7 bitmask and each lit cell is a circle a little under half a cell
 *    across. A real font — even a pixel font — gets hinted and kerned and comes out looking like a
 *    screenshot of a font rather than like lamps behind a mask.
 *  - **Sized to the frame, not to the pixels.** The cell is a fraction of the image, so the stamp is
 *    the same size relative to the photograph at 2MP and at 50MP.
 *  - **It leans**, about twelve degrees, and because the glyph is a grid of cells the lean comes out
 *    as a staircase. Shearing a typeface would give clean diagonals and the wrong decade.
 *  - **It glows.** Amber-green at nine tenths opacity so the picture shows through the way a light
 *    does rather than sitting on top like paint, with a second pass underneath, larger and barely
 *    there, for the halation of a bright lamp against emulsion. Without it the stamp reads as a
 *    watermark.
 */
object DateStamp {

    /**
     * The two colours a stamp is drawn in: the lit element and the bloom beneath it.
     *
     * Packed ARGB, and packed by hand rather than by `Color.argb`, so that [inkFor] is arithmetic
     * with no Android in it and the palette can be checked off-device. `Paint.color` takes exactly
     * this Int.
     */
    data class Ink(val lamp: Int, val halo: Int)

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * What to draw the stamp in, given the style and whether the photograph underneath has any
     * colour in it.
     *
     * **Colour is the default and the whole point.** A date back was an amber LED array or an
     * orange-red LCD, and that colour is half of why a stamped photograph reads as 1994.
     *
     * On a black-and-white frame it reads as a mistake instead, which is what light-reports#25 was:
     * the stamp is printed after the filter — see [com.gios.lightcamera.filter.Filters.Filter.mono]
     * — so a Mono, Dither BW, 1-Bit or Halftone photograph came out with a full-colour amber date
     * sitting on top of it.
     *
     * The mono palette is **not** simply the amber desaturated. Take the hue away and contrast is
     * the only channel the stamp has left, and a light-grey date is invisible over a white sky and a
     * 1-Bit frame has nothing *but* white and black. So the bloom inverts: near-black, drawn under
     * lamps that are near-white and slightly smaller, which leaves a dark keyline around every lit
     * dot. On white the keyline reads the digits; on black the lamps do. The camcorder style already
     * worked this way — a black keyline under a bright fill is what a character generator drew — so
     * in mono it only loses its amber.
     */
    fun inkFor(style: StampStyle, mono: Boolean): Ink = when {
        // Near-white rather than white, and the halo heavy enough to hold its own against a blown
        // highlight without reading as a drop shadow.
        mono && style == StampStyle.Outline -> Ink(argb(255, 245, 245, 245), argb(245, 0, 0, 0))
        mono -> Ink(argb(235, 245, 245, 245), argb(120, 0, 0, 0))
        // The compact camera's amber-green dot matrix, at nine tenths opacity so the picture shows
        // through the way a light does rather than sitting on top like paint.
        style == StampStyle.Dots -> Ink(argb(230, 205, 222, 74), argb(58, 214, 232, 96))
        // The film SLR's quartz back: orange-red, with the halation as a stroke around the same path.
        style == StampStyle.Quartz -> Ink(argb(240, 240, 86, 30), argb(50, 255, 132, 60))
        // The camcorder's character generator: amber fill, heavy black keyline.
        else -> Ink(argb(255, 247, 160, 42), argb(245, 0, 0, 0))
    }

    /**
     * `11  5 '21` — month, day, then the two-digit year behind an apostrophe.
     *
     * Month-day-year with the year last and apostrophised is what the Japanese compacts printed on
     * their American firmware, and it is the order in the photographs this was built from. Days and
     * months are **space**-padded rather than zero-padded, which is why the fifth of a month sits
     * with a gap in front of it.
     */
    fun format(millis: Long, style: StampStyle = StampStyle.Dots): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val year = cal.get(Calendar.YEAR) % 100
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return when (style) {
            // The compact-camera dot matrix, American firmware: month, day, apostrophe-year.
            StampStyle.Dots -> "%2d %2d '%02d".format(month, day, year)
            // The film SLR quartz back put the year first, and zero-padded everything.
            StampStyle.Quartz -> "'%02d %02d %02d".format(year, month, day)
            // Camcorders wrote a full date with slashes and all four digits of the year.
            StampStyle.Outline -> "%02d/%02d/%d".format(month, day, cal.get(Calendar.YEAR))
        }
    }

    /**
     * JPEG in, JPEG out, with the date on it.
     *
     * For Simple, which saves the sensor's own bytes the instant the shutter returns and then comes back
     * here on a background thread to print the date onto the file it already wrote. Null if the decode
     * fails, which the caller reads as "leave the photograph as it is" — an undated photograph is a fine
     * outcome and a lost one is not.
     *
     * The orientation tag is read and baked in, because the stamp has to go in the corner of the picture
     * as a person sees it rather than the corner of the buffer, and once the pixels are turned the tag has
     * to go or everything downstream turns them again.
     */
    fun applyTo(jpeg: ByteArray, millis: Long, style: StampStyle): ByteArray? {
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
                inMutable = true
            })
        }.getOrNull() ?: return null
        val turned = runCatching {
            val exif = ExifInterface(ByteArrayInputStream(jpeg))
            val degrees = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) {
                decoded
            } else {
                val matrix = Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    .also { if (it != decoded) decoded.recycle() }
            }
        }.getOrDefault(decoded)

        val stamped = apply(turned, millis, style)
        val out = ByteArrayOutputStream(jpeg.size)
        val ok = runCatching {
            stamped.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }.getOrDefault(false)
        stamped.recycle()
        return if (ok) out.toByteArray() else null
    }

    /**
     * Draw the stamp onto [bitmap], returning a bitmap that has it.
     *
     * Takes a copy when handed an immutable bitmap, which a freshly decoded JPEG always is.
     */
    fun apply(
        bitmap: Bitmap,
        millis: Long,
        style: StampStyle = StampStyle.Dots,
        /** Whether the filter under the stamp left the photograph black and white. See [inkFor]. */
        mono: Boolean = false,
    ): Bitmap {
        val target = if (bitmap.isMutable) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
        }
        val text = format(millis, style)
        val canvas = Canvas(target)
        val ink = inkFor(style, mono)
        when (style) {
            StampStyle.Dots -> drawDots(canvas, target, text, ink)
            StampStyle.Quartz -> drawQuartz(canvas, target, text, ink)
            StampStyle.Outline -> drawOutline(canvas, target, text, ink)
        }
        return target
    }

    /* ---------------- dots: the compact-camera matrix ---------------- */

    private fun drawDots(canvas: Canvas, target: Bitmap, text: String, ink: Ink) {

        // One glyph is seven cells tall, and the whole stamp about a twenty-fifth of the frame —
        // measured off photographs from a Canon Sure Shot, which is as principled as this gets.
        val cell = (minOf(target.width, target.height) / 175f).coerceAtLeast(1.5f)
        val glyphW = 5 * cell
        val glyphH = 7 * cell
        val gap = cell
        // The lean pushes the top row right, so the run is that much wider than the glyph boxes.
        val overhang = 6 * cell * SLANT
        val width = text.length * (glyphW + gap) - gap + overhang
        val right = target.width - cell * 8
        val bottom = target.height - cell * 8
        var x = right - width
        val y = bottom - glyphH

        val glow = Paint().apply {
            // Circles, so antialiasing goes back on — a hard-edged circle at this size is a
            // polygon. The squares it replaced wanted it off for exactly the opposite reason.
            isAntiAlias = true
            color = ink.halo
        }
        val lamp = Paint().apply {
            isAntiAlias = true
            color = ink.lamp
        }

        text.forEach { ch ->
            val rows = GLYPHS[ch]
            if (rows != null) {
                // Halation first, so the lit cells sit on top of their own bloom — and kept small,
                // because a wide bloom bridges the gaps between the lamps and undoes the dots.
                drawGlyph(canvas, rows, x, y, cell, glow, spill = cell * 0.22f)
                drawGlyph(canvas, rows, x, y, cell, lamp, spill = 0f)
            }
            x += glyphW + gap
        }
    }

    /* ---------------- quartz: seven segments ---------------- */

    /**
     * The film SLR's date back: **seven segments**, orange-red, leaning.
     *
     * Drawn rather than typeset, and that is not a compromise. There is no seven-segment face on
     * Android to switch to — `sans-serif`, `serif`, `monospace` and the condensed and light variants
     * are the lot — and a seven-segment font is not really a typeface anyway: every glyph in DSEG or
     * any of its relatives is the same seven chamfered bars with a different subset filled in. So
     * this draws those seven bars, built the way the real ones are:
     *
     *  - **ends mitred at 45°**, not square. This is the detail that makes or breaks the look. A
     *    segment on a real LCD is a hexagon, tapered at both ends so its neighbours can sit close
     *    without touching, and square-ended bars read as a bar chart instead of a display.
     *  - **one shear for the whole digit**, applied to the canvas, rather than a lean fudged onto
     *    each bar separately. The display leans; the segments are upright inside it.
     *  - a hair of a gap at every joint, because seven separate bars is what it is.
     */
    private fun drawQuartz(canvas: Canvas, target: Bitmap, text: String, ink: Ink) {
        // Sized off the long edge, and smaller again — a date back's display is a couple of
        // centimetres of LCD reflected into the corner of a 35mm frame. The digit is now about a
        // fiftieth of the long edge, with the classic LCD proportions: near twice as tall as it is
        // wide, bars a fifth of the width.
        val unit = (maxOf(target.width, target.height) / 720f).coerceAtLeast(0.7f)
        val digitH = unit * 13f
        val digitW = digitH * 0.55f
        val thick = digitW * 0.19f
        val gap = digitW * 0.34f
        val lean = 0.11f
        val width = text.length * (digitW + gap) - gap
        var x = target.width - unit * 16 - width - digitH * lean
        val baseline = target.height - unit * 16

        val glow = Paint().apply {
            isAntiAlias = true
            color = ink.halo
            // Halation as a stroke around the same path, so the bloom follows the mitred ends
            // instead of being a second, fatter, differently-shaped bar.
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = unit * 0.9f
        }
        val lamp = Paint().apply {
            isAntiAlias = true
            color = ink.lamp
        }

        text.forEach { ch ->
            if (ch != ' ') {
                canvas.save()
                // Shear about the baseline, so the digit leans and its feet stay put.
                canvas.translate(x, baseline)
                canvas.skew(-lean, 0f)
                when (ch) {
                    // The apostrophe was a single short segment, high up.
                    '\'' -> for (paint in listOf(glow, lamp)) {
                        vbar(canvas, digitW * 0.42f, -digitH, thick, digitH * 0.24f, paint)
                    }
                    else -> SEGMENTS[ch]?.let { on ->
                        for (paint in listOf(glow, lamp)) {
                            drawSegments(canvas, on, digitW, digitH, thick, paint)
                        }
                    }
                }
                canvas.restore()
            }
            x += digitW + gap
        }
    }

    /**
     * `a` top, then clockwise `b c d e f`, and `g` the middle.
     *
     * Local coordinates: the baseline is y = 0 and the digit runs up to -[h], which is what lets the
     * caller shear the whole thing about its feet with one canvas transform.
     */
    private fun drawSegments(
        canvas: Canvas,
        on: String,
        w: Float,
        h: Float,
        thick: Float,
        paint: Paint,
    ) {
        val half = h / 2f
        // The joint gap. Mitred ends already pull away from each other, so this is small — enough to
        // read as seven bars, not enough to look broken.
        val nick = thick * 0.5f
        val across = w - thick
        val run = half - thick - nick
        if ('a' in on) hbar(canvas, 0f, -h, across, thick, paint)
        if ('g' in on) hbar(canvas, 0f, -half - thick / 2f, across, thick, paint)
        if ('d' in on) hbar(canvas, 0f, -thick, across, thick, paint)
        if ('f' in on) vbar(canvas, 0f, -h + thick + nick, thick, run, paint)
        if ('b' in on) vbar(canvas, across, -h + thick + nick, thick, run, paint)
        if ('e' in on) vbar(canvas, 0f, -half + thick * 0.5f + nick, thick, run, paint)
        if ('c' in on) vbar(canvas, across, -half + thick * 0.5f + nick, thick, run, paint)
    }

    /** A horizontal segment: a hexagon, mitred at 45° into a point at each end. */
    private fun hbar(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        thick: Float,
        paint: Paint,
    ) {
        val m = thick / 2f
        canvas.drawPath(
            Path().apply {
                moveTo(x + m, y)
                lineTo(x + w - m, y)
                lineTo(x + w, y + m)
                lineTo(x + w - m, y + thick)
                lineTo(x + m, y + thick)
                lineTo(x, y + m)
                close()
            },
            paint,
        )
    }

    /** A vertical segment, mitred the same way. */
    private fun vbar(
        canvas: Canvas,
        x: Float,
        y: Float,
        thick: Float,
        h: Float,
        paint: Paint,
    ) {
        val m = thick / 2f
        canvas.drawPath(
            Path().apply {
                moveTo(x, y + m)
                lineTo(x + m, y)
                lineTo(x + thick, y + m)
                lineTo(x + thick, y + h - m)
                lineTo(x + m, y + h)
                lineTo(x, y + h - m)
                close()
            },
            paint,
        )
    }

    /* ---------------- camcorder: a typeface, outlined ---------------- */

    /**
     * The camcorder stamp, and the one style where **a real font is right**.
     *
     * This one was never a lamp array — it was a character generator drawing an ordinary bold sans
     * into the video signal, with a black keyline so it stayed readable over anything. So it is
     * drawn as text: stroke pass first for the outline, fill pass on top, no lean, slashes between
     * the numbers, and all four digits of the year.
     */
    private fun drawOutline(canvas: Canvas, target: Bitmap, text: String, ink: Ink) {
        // A character generator drew this into a video line, so it was small — about a
        // twenty-fifth of the frame, not a fifteenth — and the keyline was one pixel of video,
        // which is a hairline here rather than the heavy slab of the first attempt.
        val size = maxOf(target.width, target.height) / 34f
        val outline = Paint().apply {
            isAntiAlias = true
            // **Condensed**, because the character generators in camcorders were: the digits are
            // tall and tight, not the wide default sans. And a heavy keyline — on a video line the
            // black border around each glyph was as thick as the strokes themselves, which is what
            // kept the date legible over grass, sky or anything else.
            typeface = CONDENSED
            textSize = size
            style = Paint.Style.STROKE
            strokeWidth = size * 0.22f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = ink.halo
        }
        val fill = Paint().apply {
            isAntiAlias = true
            typeface = CONDENSED
            textSize = size
            color = ink.lamp
        }
        val width = fill.measureText(text)
        val x = target.width - size * 0.7f - width
        val y = target.height - size * 0.7f
        canvas.drawText(text, x, y, outline)
        canvas.drawText(text, x, y, fill)
    }

    private fun drawGlyph(
        canvas: Canvas,
        rows: Array<String>,
        left: Float,
        top: Float,
        cell: Float,
        paint: Paint,
        spill: Float,
    ) {
        for (row in rows.indices) {
            val bits = rows[row]
            // Sheared right, and by whole rows rather than smoothly: a date back's digits lean,
            // and because the glyph is made of square cells the lean comes out as a staircase.
            // That staircase is a large part of why the originals look the way they do — slanting
            // a real typeface instead gives you clean diagonal edges and the wrong thing entirely.
            val lean = (rows.size - 1 - row) * cell * SLANT
            for (col in bits.indices) {
                if (bits[col] != '1') continue
                val cx = left + col * cell + lean + cell / 2f
                val cy = top + row * cell + cell / 2f
                // **Round dots with gaps between them, not a solid grid.** Close up, a date back
                // is plainly a dot matrix: each lamp prints a small circle and the paper shows
                // between them. Filling whole cells gives you blocky digits that read as a pixel
                // font, which is a different era entirely.
                canvas.drawCircle(cx, cy, cell * DOT + spill, paint)
            }
        }
    }

    /**
     * How far each row leans right, as a fraction of a cell. About twelve degrees over seven
     * rows, which is where the date backs sat.
     */
    private const val SLANT = 0.26f

    /**
     * The camcorder face. `sans-serif-condensed` is present on every Android and is the closest
     * thing in the system to what a character generator drew — the default sans is far too wide.
     *
     * **Lazy, and it has to be.** Resolved eagerly it runs during class initialisation, which on a
     * JVM unit test means calling into the stubbed `android.jar` and throwing out of a static
     * initialiser — taking every test in the file with it, including the ones about date formatting
     * that never touch a typeface. Deferred, the date formatting stays testable off-device.
     */
    private val CONDENSED: Typeface by lazy {
        Typeface.create(Typeface.create("sans-serif-condensed", Typeface.BOLD), Typeface.BOLD)
    }

    /**
     * Dot radius as a fraction of a cell.
     *
     * The gaps are **tiny** — the lamps nearly touch, and what you see between them is a hairline
     * rather than a grid. At 0.42 the dots leave about a sixth of a cell of picture showing, which
     * is what the photographs show; at a half exactly they meet and every stroke turns solid, and
     * much under 0.4 it stops reading as digits and starts reading as beadwork.
     */
    private const val DOT = 0.42f

    /** Which of the seven segments each digit lights. */
    private val SEGMENTS: Map<Char, String> = mapOf(
        '0' to "abcdef",
        '1' to "bc",
        '2' to "abdeg",
        '3' to "abcdg",
        '4' to "bcfg",
        '5' to "acdfg",
        '6' to "acdefg",
        '7' to "abc",
        '8' to "abcdefg",
        '9' to "abcdfg",
    )

    /**
     * 5x7 masks. Only the characters a date needs, because a date back could only make those —
     * the originals had ten digits, an apostrophe and a space, and that was the whole ROM.
     */
    private val GLYPHS: Map<Char, Array<String>> = mapOf(
        '0' to arrayOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
        '1' to arrayOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to arrayOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to arrayOf("01110", "10001", "00001", "00110", "00001", "10001", "01110"),
        '4' to arrayOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to arrayOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to arrayOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to arrayOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to arrayOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to arrayOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
        '\'' to arrayOf("00100", "00100", "01000", "00000", "00000", "00000", "00000"),
    )
}
