package com.gios.lightcamera.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.lightcamera.ui.theme.LightIcon
import com.gios.lightcamera.ui.theme.LightIconSpec
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.LightThemeTokens
import com.gios.lightcamera.ui.theme.lightClickable
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Turns its content a quarter clockwise inside its own box.
 *
 * **Chrome only, never the picture.** LightOS's camera writes its controls sideways — in
 * portrait the band runs down the left edge with `PHOTO ⌄` reading down it — while the
 * viewfinder image stays upright in the phone's own frame. That is exactly what a screengrab of
 * the real thing shows, and it is the right split: the band is sideways because you hold the
 * phone like a camera to shoot, and the image is upright because it is the image.
 *
 * An earlier version put this around the entire app. That rotated the preview with everything
 * else, which looked wrong the moment you held the phone normally, and it turned the swipe down
 * to the roll into a sideways one. Now the roll, the viewer and the settings are ordinary
 * portrait screens, the swipe stays a swipe down, and only the strips of chrome use this.
 *
 * The mechanics: a box of the parent's *swapped* dimensions, escaping the incoming constraints
 * with `requiredSize`, centred, and rotated 90°. Rotated about its own centre it covers the
 * parent exactly, so nothing is clipped and nothing is letterboxed. Compose maps pointer input
 * back through the layer, so touches land where they appear to. Content +x ends up pointing
 * down the strip, so a `Row` written the ordinary way round reads top-to-bottom on screen.
 */
@Composable
fun HeldSideways(opaque: Boolean = true, content: @Composable () -> Unit) {
    // **[opaque] exists for the one caller that draws over a live viewfinder.** The black fill is
    // right for a panel that replaces the picture and fatal for one that sits beside it — the Adjust
    // strip's whole argument is that you can watch the frame change while you set a value, and a
    // full-bleed background behind it turns that frame into a black rectangle. `RotatedToDevice`
    // carries the same switch for the same reason.
    BoxWithConstraints(
        Modifier.fillMaxSize().then(if (opaque) Modifier.background(Color.Black) else Modifier),
    ) {
        val across = maxHeight
        val down = maxWidth
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .requiredSize(width = across, height = down)
                .graphicsLayer { rotationZ = 90f },
        ) {
            content()
        }
    }
}

/**
 * Which way up the phone is being held, in degrees clockwise: 0, 90, 180 or 270.
 *
 * The window is locked to portrait, so Android will not tell anyone this — it has to come off the
 * accelerometer. Snapped to quarters with a **wide dead zone**: a picture that reorients itself
 * while you are looking at it is worse than one that is the wrong way up, so a quarter has to be
 * clearly the nearest before it wins, and once won it keeps hold until the phone is well past the
 * next diagonal.
 */
@Composable
fun rememberDeviceQuarter(active: Boolean = true): Int {
    val context = LocalContext.current
    var quarter by remember { mutableStateOf(0) }
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) return@DisposableEffect onDispose { }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                // Flat on a table there is no meaningful answer, so don't invent one.
                if (abs(z) > 8.5f) return
                val roll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
                val nearest = ((Math.round(roll / 90f) * 90) + 360) % 360
                // 60 degrees of hysteresis: past the diagonal by a clear margin, not just past it.
                val settled = wrap(roll - quarter)
                if (nearest != quarter && abs(settled) > 60f) quarter = nearest
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
    return quarter
}

/**
 * Turns its content against the phone, so what is inside stays upright in the world.
 *
 * The opposite job to [HeldSideways], which pins chrome *to* the phone. This is for a photograph:
 * turn the phone on its side and a landscape picture should fill the long edge, the way it would if
 * the window were free to rotate. The window is not free — it is locked to portrait, and unlocking
 * it would let the viewfinder reflow, which is the thing that must never happen.
 *
 * Quarter turns get the parent's swapped dimensions so the content lays out in the space it will
 * occupy after rotating; 180 keeps them.
 */
@Composable
fun RotatedToDevice(
    quarter: Int,
    /**
     * Whether to fill the box behind the content with black.
     *
     * True for a photograph, which needs something behind the letterboxing. **False for anything laid
     * over the viewfinder** — an opaque background here turned the live Purikura overlay into a black
     * rectangle the moment the phone tilted past 45°, which is a good reminder that a helper written
     * for full-screen pictures is not automatically right for a transparent layer.
     */
    opaque: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (quarter == 0) {
        content()
        return
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(if (opaque) Modifier.background(Color.Black) else Modifier),
    ) {
        val sideways = quarter == 90 || quarter == 270
        val w = if (sideways) maxHeight else maxWidth
        val h = if (sideways) maxWidth else maxHeight
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .requiredSize(width = w, height = h)
                // **Plus, not minus.** `atan2(x, y)` grows as the phone turns *anticlockwise*, so
                // countering an anticlockwise turn means turning the content clockwise by the same
                // amount — and `rotationZ` is clockwise-positive. Negating it put the picture 180°
                // out in both sideways poses, which reads as upside down.
                .graphicsLayer { rotationZ = quarter.toFloat() },
        ) {
            content()
        }
    }
}

/**
 * Eats taps that land on it, so whatever is behind gets none of them.
 *
 * A `background` paints but does not claim touches, and neither does a `Row` — so the roll's header
 * bar was transparent to the finger: reaching for the settings icon opened whichever photograph
 * happened to be tiled underneath it. The children keep their own clicks, because this consumes on
 * the way back *up* the pass; only what falls through the gaps between them stops here.
 */
fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/** A word or two over the image. Reads as an annotation, not as a toast. */
@Composable
fun Notice(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    val colors = LightThemeTokens.colors
    Box(
        modifier = modifier
            .background(colors.scrim, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        LightText(text.uppercase(), LightTextVariant.Detail)
    }
}

/** An icon that behaves: no ripple, a buzz on finger-down, a generous invisible target. */
@Composable
fun ChromeIcon(
    icon: LightIconSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lighten: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 22.dp,
) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = modifier
            .lightClickable { onClick() }
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(
            icon = icon,
            size = size,
            tint = if (lighten) colors.contentSecondary else colors.content,
        )
    }
}

/** A word used as a button, tracked out the way LightOS sets its bar labels. */
@Composable
fun ChromeLabel(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lighten: Boolean = false,
) {
    LightText(
        text = text.uppercase(),
        variant = LightTextVariant.Detail,
        lighten = lighten,
        modifier = modifier
            .lightClickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
fun EmptyState(text: String, detail: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText(text, LightTextVariant.Subheading, align = TextAlign.Center)
        if (detail != null) {
            LightText(
                detail,
                LightTextVariant.Paragraph,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * How far the phone is rolled from level, in degrees, for the horizon line.
 *
 * The accelerometer rather than the rotation vector: gravity is all this needs, the
 * accelerometer is the cheapest sensor on the phone, and the rotation vector would drag in
 * the magnetometer and with it every fridge magnet in the room.
 *
 * **Zero is the nearest quarter turn, not portrait.** A photograph is level when the phone is at
 * some multiple of 90°, and which multiple is your business: upright, or turned sideways like a
 * camera. So the reading is the deviation from whichever is closest, which lands in (-45, 45]
 * and reads zero in every pose you would actually shoot from. Referencing portrait alone is what
 * made the line sit permanently 90° over when the phone was held the way this app expects.
 *
 * Smoothed hard, along the shorter way round the circle — a raw accelerometer feed makes a line
 * that shivers, and a shivering level is worse than none. Wrapping matters because ±180 is one
 * place, and averaging across it the naive way sends the line spinning the long way.
 */
@Composable
fun rememberTilt(active: Boolean = true): State<Float> {
    val context = LocalContext.current
    val degrees = remember { mutableFloatStateOf(0f) }
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) return@DisposableEffect onDispose { }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                // Portrait upright is x≈0, y≈+9.8, so atan2 of the two is the roll in the
                // device's frame — and it stays sane past 90° where asin folds back on itself.
                val roll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
                val raw = fromNearestQuarter(roll)
                val delta = wrap(raw - degrees.floatValue)
                degrees.floatValue = wrap(degrees.floatValue + delta * SMOOTHING)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
    return degrees
}

/** Into (-180, 180], so that ±180 is one angle and not two. */
private fun wrap(degrees: Float): Float {
    var d = degrees
    while (d <= -180f) d += 360f
    while (d > 180f) d -= 360f
    return d
}

/**
 * How far off the nearest multiple of 90°, in (-45, 45].
 *
 * Which multiple you are near is the pose you chose; the level's job is only to say whether you
 * are square to it. Internal so the test can check the four poses and the seams between them.
 */
internal fun fromNearestQuarter(roll: Float): Float =
    wrap(roll - 90f * Math.round(roll / 90f))

private const val SMOOTHING = 0.12f

/** Whether the phone is level enough to say so. A degree either way is within a hand's steadiness. */
fun Float.isLevel(): Boolean = abs(this) < 1.0f

/**
 * Whether the horizon line is worth drawing.
 *
 * A level that is always on screen is a line through the middle of every photograph you frame.
 * This one appears when the phone is crooked, which is when it has something to say, and lingers
 * for a beat after you straighten up so you see it close — the confirmation is the entire point
 * of a level, and a line that vanished the instant it was satisfied would never be seen doing it.
 *
 * The reading is already relative to the nearest quarter turn, so there is no pose in which the
 * line is permanently on: hold the phone any of the four ways up and it is square.
 */
@Composable
fun rememberLevelVisible(tilt: Float, enabled: Boolean = true): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, tilt.isLevel()) {
        if (!enabled) {
            visible = false
            return@LaunchedEffect
        }
        if (!tilt.isLevel()) {
            visible = true
        } else if (visible) {
            delay(LEVEL_LINGER_MS)
            visible = false
        }
    }
    return enabled && visible
}

private const val LEVEL_LINGER_MS = 1_100L
