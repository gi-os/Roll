package com.gios.lightcamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val colours = LightThemeTokens.colors
    val set by rememberUpdatedState(onSet)
    val count = labels.size
    var heightPx by remember { mutableFloatStateOf(1f) }

    Box(
        modifier
            .width(GAUGE_WIDTH)
            .height(GAUGE_HEIGHT)
            .pointerInput(count) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val step = size.height.toFloat() / count
                    val at = (change.position.y / step).toInt().coerceIn(0, count - 1)
                    set(at)
                }
            },
    ) {
        Canvas(Modifier.width(GAUGE_WIDTH).height(GAUGE_HEIGHT)) {
            heightPx = size.height
            val step = size.height / count
            val textPaint = android.graphics.Paint().apply {
                color = colours.content.toArgb()
                textSize = step * 0.62f
                isAntiAlias = true
                typeface = android.graphics.Typeface.MONOSPACE
            }
            labels.forEachIndexed { i, label ->
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    NEEDLE_ROOM,
                    step * i + step * 0.72f,
                    textPaint,
                )
            }
            // The pivot: a fixed point past the ladder's left edge, vertically centred. The
            // needle's angle to each rung is what makes it a meter — computed, not styled, so
            // the sweep is honest about where the value sits in its range.
            val pivot = Offset(-PIVOT_REACH, size.height / 2f)
            val target = Offset(NEEDLE_ROOM - 4.dp.toPx(), step * index + step * 0.5f)
            val angle = Math.toDegrees(
                atan2((target.y - pivot.y).toDouble(), (target.x - pivot.x).toDouble()),
            ).toFloat()
            rotate(degrees = angle, pivot = pivot) {
                drawLine(
                    color = NEEDLE_RED,
                    start = pivot,
                    end = Offset(pivot.x + PIVOT_REACH + NEEDLE_ROOM - 2.dp.toPx(), pivot.y),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            drawCircle(color = colours.content, radius = 2.5f.dp.toPx(), center = Offset(0f, pivot.y))
        }
    }
}

private val GAUGE_WIDTH = 44.dp
private val GAUGE_HEIGHT = 128.dp

/** How far the pivot sits off-canvas: longer reach, shallower sweep, more speedometer. */
private const val PIVOT_REACH = 70f

/** Room left of the labels for the needle to cross into. */
private const val NEEDLE_ROOM = 30f

/** The one colour in the app that is not the theme's: a meter needle is red or it is not one. */
private val NEEDLE_RED = Color(0xFFCC2A1E)
