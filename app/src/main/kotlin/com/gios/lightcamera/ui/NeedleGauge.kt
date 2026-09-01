package com.gios.lightcamera.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gios.lightcamera.ui.theme.LightThemeTokens
import kotlin.math.atan2

/**
 * A dial read the way a meter is read: a ladder of values and a red needle on a fixed pivot.
 *
 * The pivot sits off the ladder's edge, so the needle sweeps like a speedometer's rather than
 * sliding like a cursor — the angle carries the reading at a glance, which a highlight in a list
 * does not. The ladder is whatever the wheel currently holds: shutter denominators, ISOs, focus
 * distances, zoom ratios. Small on purpose. It reports; the picture is the point.
 *
 * A finger works it two ways in one gesture: drag along the ladder and the needle follows the
 * finger's height. The drag maps position, not delta, so a value is somewhere you put the needle,
 * not something you scrub toward.
 */
@Composable
fun NeedleGauge(
    labels: List<String>,
    index: Int,
    onSet: (Int) -> Unit,
    /** Where the needle points, in rungs -- fractional for the dials that pass between marks. */
    position: Float = index.toFloat(),
    /** A tap that never became a drag. The caller decides what a tap means; here it is a latch. */
    onTap: (() -> Unit)? = null,
    /** The ladder's long axis. Filters hand the whole viewfinder edge in; everything else the default. */
    length: Dp = GAUGE_HEIGHT,
    /** Across the ladder: how much depth the numbers have to be drawn in. */
    depth: Dp = GAUGE_WIDTH,
    /**
     * Numbers set as large as the rungs allow.
     *
     * A value ladder has a handful of rungs and every one of them is a number you read at arm's
     * length in daylight. The filter ladder is the opposite -- twenty-odd rungs of three-letter
     * codes, where the same size would collide -- so it keeps the smaller setting.
     */
    large: Boolean = false,
    /** False while the ladder is faded out, so a hidden gauge does not eat touches. */
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val colours = LightThemeTokens.colors
    val set by rememberUpdatedState(onSet)
    val count = labels.size
    var heightPx by remember { mutableFloatStateOf(1f) }
    // The needle travels rather than teleports: a real meter's arm has mass. Short enough that a
    // spinning wheel still reads as one motion per notch, not a lagging pointer. Sixty not a
    // hundred-and-twenty: at 120ms the needle was still arriving when the next notch landed, so a
    // fast spin read as a pointer limping after the wheel.
    val sweep by animateFloatAsState(
        targetValue = position,
        animationSpec = tween(durationMillis = 60),
        label = "needle",
    )

    Box(
        modifier
            .width(depth)
            .height(length)
            .pointerInput(count, enabled, large) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, _ ->
                    change.consume()
                    val plan = ladderLayout(
                        height = size.height.toFloat(),
                        width = size.width.toFloat(),
                        labels = labels,
                        large = large,
                    )
                    set(plan.rungAt(change.position.y, count))
                }
            }
            .pointerInput(onTap, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { onTap?.invoke() }
            },
    ) {
        Canvas(Modifier.width(depth).height(length)) {
            heightPx = size.height
            // One layout for all three readers of this ladder: the needle, the numbers and the
            // finger. They used to compute their own and disagree -- the numbers were drawn on a
            // baseline tuned for text about a rung tall, so once EV's labels grew to span three
            // rungs each they sat low of the mark they name and the needle missed them.
            val plan = ladderLayout(
                height = size.height,
                width = size.width,
                labels = labels,
                large = large,
            )
            // **Needle first, labels second: the bar slides in *under* the text.** The pivot sits
            // off the left edge of the screen entirely — the gauge is flush with that edge — so
            // what appears is only the last stretch of a long arm sweeping on a hidden centre,
            // which is the sketch: a speedometer you see the tip of, not the works.
            val pivot = Offset(-PIVOT_REACH, size.height / 2f)
            val target = Offset(LABEL_X + OVERLAP, plan.centre(sweep))
            val angle = Math.toDegrees(
                atan2((target.y - pivot.y).toDouble(), (target.x - pivot.x).toDouble()),
            ).toFloat()
            val reach = kotlin.math.hypot(
                (target.x - pivot.x).toDouble(),
                (target.y - pivot.y).toDouble(),
            ).toFloat()
            rotate(degrees = angle, pivot = pivot) {
                drawLine(
                    color = NEEDLE_RED.copy(alpha = 0.8f),
                    start = pivot,
                    end = Offset(pivot.x + reach, pivot.y),
                    strokeWidth = 2.2.dp.toPx(),
                )
            }
            val textPaint = android.graphics.Paint().apply {
                color = colours.content.toArgb()
                textSize = plan.textSize
                isAntiAlias = true
                typeface = android.graphics.Typeface.MONOSPACE
            }
            // Centred on the rung by the font's own metrics rather than by a fraction of the
            // slot, which is the only way a number and the needle that points at it agree at
            // every size.
            val metrics = textPaint.fontMetrics
            val lift = (metrics.ascent + metrics.descent) / 2f
            labels.forEachIndexed { i, label ->
                if (label.isEmpty()) return@forEachIndexed
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    LABEL_X,
                    plan.centre(i.toFloat()) - lift,
                    textPaint,
                )
            }
        }
    }
}

/**
 * Where every rung sits, how tall its number is, and how much room the ends need.
 *
 * The ladder is not simply the height divided by the rungs. A label can be taller than its own
 * slot -- EV prints one number every third rung, so its numbers are sized off that wider gap --
 * and the first and last rungs only have half a slot of room before the strip's edge. Without an
 * inset the end labels overflow, and since the strip is clipped so the ladder cannot paint over
 * the roll, overflowing means cropped: "-4" lost its bottom half.
 */
private class Ladder(val inset: Float, val step: Float, val textSize: Float) {
    /** The centre of rung [at], fractional rungs included -- the needle rides between them. */
    fun centre(at: Float): Float = inset + step * at + step * 0.5f

    fun rungAt(y: Float, count: Int): Int =
        ((y - inset) / step).toInt().coerceIn(0, count - 1)
}

private fun ladderLayout(height: Float, width: Float, labels: List<String>, large: Boolean): Ladder {
    val count = labels.size.coerceAtLeast(1)
    val rawStep = height / count
    // Sized off the labels that actually draw, not the raw rung pitch: EV lays a rung down for
    // every third of a stop but prints only the whole stops, and sizing off the blank rungs shrank
    // "-2" until it was dust.
    val printed = labels.mapIndexedNotNull { i, l -> if (l.isNotEmpty()) i else null }
    fun pitch(step: Float): Float =
        if (printed.size >= 2) {
            step * (printed.last() - printed.first()) / (printed.size - 1)
        } else {
            step
        }
    // A monospace glyph advances about 0.62 of its size, so the longest label has to fit across
    // the strip as well.
    val chars = labels.maxOf { it.length }.coerceAtLeast(1)
    val across = (width - LABEL_X * 2f) / (chars * 0.62f)
    val factor = if (large) 0.86f else 0.62f
    val guess = minOf(pitch(rawStep) * factor, across)
    val inset = (guess * 0.6f - rawStep * 0.5f).coerceAtLeast(0f)
    val step = ((height - inset * 2f) / count).coerceAtLeast(1f)
    return Ladder(inset, step, minOf(pitch(step) * factor, across))
}

private val GAUGE_WIDTH = 44.dp
private val GAUGE_HEIGHT = 128.dp

/** How far the hidden pivot sits past the screen's left edge: long arm, shallow sweep. */
private const val PIVOT_REACH = 160f

/** Where the ladder's text starts, from the screen edge. Left-aligned, as drawn in the sketch. */
private const val LABEL_X = 8f

/** How far the needle's tip rides in under the text. Under: the labels draw over it. */
private const val OVERLAP = 14f

/** The one colour in the app that is not the theme's: a meter needle is red or it is not one. */
private val NEEDLE_RED = Color(0xFFCC2A1E)
