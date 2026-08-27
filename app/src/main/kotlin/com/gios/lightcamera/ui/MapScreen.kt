package com.gios.lightcamera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.gios.lightcamera.map.Cluster
import com.gios.lightcamera.map.Geo
import com.gios.lightcamera.map.Point
import com.gios.lightcamera.map.Tile
import com.gios.lightcamera.map.Tiles
import com.gios.lightcamera.media.Photo
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.LightThemeTokens
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The roll, on a map.
 *
 * **A way of looking at the roll rather than a place of its own.** It is a scope beside Camera and
 * Starred, so the same photographs are underneath and the same actions reach them — a tap opens
 * the viewer at that photograph, exactly as the grid does.
 *
 * **It is also the only screen in this app that fetches anything.** Roll's network story is one
 * sentence — it opens a connection when you send a bug report — and a map is the one feature that
 * cannot honour it, so the exception is contained here: tiles are fetched only while this screen is
 * on, cached on disk for good, and never touched from anywhere else.
 *
 * Photographs with no coordinate are simply not on it, which is most of them for most people. The
 * empty state says so rather than showing an empty ocean.
 */
@Composable
fun MapScreen(
    located: List<Pair<Photo, Point>>,
    tiles: Tiles,
    onOpen: (Photo) -> Unit,
) {
    val colours = LightThemeTokens.colors
    var viewport by remember { mutableStateOf(Size.Zero) }
    var zoom by remember { mutableStateOf(Geo.DEFAULT_ZOOM) }
    var centre by remember { mutableStateOf<Point?>(null) }

    // Opening on the whole world with three photographs in one city is the same failure as opening
    // somewhere arbitrary. Fit once, when the size is known, and never again — otherwise every pan
    // is undone by the next recomposition.
    LaunchedEffect(located.size, viewport) {
        if (centre == null && viewport != Size.Zero && located.isNotEmpty()) {
            val points = located.map { it.second }
            centre = Geo.centre(points)
            zoom = Geo.zoomToFit(points, viewport.width.roundToInt(), viewport.height.roundToInt())
        }
    }

    val here = centre
    if (located.isEmpty() || here == null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colours.background)
                .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) },
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = if (located.isEmpty()) {
                    "No photographs with a location yet"
                } else {
                    "Finding them"
                },
                variant = LightTextVariant.Superfine,
                lighten = true,
            )
        }
        return
    }

    val width = viewport.width.roundToInt()
    val height = viewport.height.roundToInt()
    val wanted = remember(here, zoom, width, height) {
        Geo.tilesFor(here, zoom, width, height)
    }
    val loaded = remember { mutableStateMapOf<Tile, androidx.compose.ui.graphics.ImageBitmap>() }
    LaunchedEffect(wanted) {
        wanted.forEach { tile ->
            if (loaded.containsKey(tile)) return@forEach
            tiles.get(tile)?.let { loaded[tile] = it.asImageBitmap() }
        }
    }

    val clusters = remember(located, zoom) {
        Geo.cluster(located.map { it.first.id to it.second }, zoom)
    }
    val byId = remember(located) { located.associate { it.first.id to it.first } }

    Box(
        Modifier
            .fillMaxSize()
            .background(colours.background)
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(here, zoom) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    // Pan in pixels, converted back to a coordinate, so dragging moves the map by
                    // exactly the distance the finger moved at every latitude.
                    val current = Geo.toPixel(centre ?: here, zoom)
                    centre = Geo.toPoint(
                        com.gios.lightcamera.map.Pixel(current.x - pan.x, current.y - pan.y),
                        zoom,
                    )
                    // A pinch is a whole zoom level or nothing. Fractional zoom would mean scaling
                    // tiles, and a scaled raster tile on a small panel is a blurred one.
                    if (gestureZoom > 1.15f) zoom = (zoom + 1).coerceAtMost(Geo.MAX_ZOOM)
                    if (gestureZoom < 0.87f) zoom = (zoom - 1).coerceAtLeast(Geo.MIN_ZOOM)
                }
            }
            .pointerInput(clusters, here, zoom) {
                detectTapGestures { tap ->
                    val hit = clusters.minByOrNull { cluster ->
                        val at = Geo.screenOf(cluster.point, here, zoom, width, height)
                        abs(at.x - tap.x) + abs(at.y - tap.y)
                    } ?: return@detectTapGestures
                    val at = Geo.screenOf(hit.point, here, zoom, width, height)
                    if (abs(at.x - tap.x) > TOUCH_SLOP || abs(at.y - tap.y) > TOUCH_SLOP) {
                        return@detectTapGestures
                    }
                    // A mark holding several photographs zooms in rather than guessing which one
                    // was meant; a mark holding one opens it.
                    if (hit.size > 1 && zoom < Geo.MAX_ZOOM) {
                        centre = hit.point
                        zoom += 1
                    } else {
                        byId[hit.ids.first()]?.let(onOpen)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            wanted.forEach { tile ->
                val bitmap = loaded[tile] ?: return@forEach
                val offset = Geo.offsetOf(tile, here, width, height)
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                )
            }
            clusters.forEach { cluster ->
                drawMark(cluster, here, zoom, width, height, colours.content, colours.background)
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(colours.scrim)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Required by the tile usage policy, and not decoration: these servers are donated.
            LightText(text = Tiles.ATTRIBUTION, variant = LightTextVariant.Superfine, lighten = true)
        }
    }
}

/**
 * One mark: a filled dot, or a ring with a count when it stands for several.
 *
 * Drawn rather than composed because there can be a hundred of them over a pannable canvas, and a
 * hundred composables that move every frame is a map that stutters.
 */
private fun DrawScope.drawMark(
    cluster: Cluster,
    centre: Point,
    zoom: Int,
    width: Int,
    height: Int,
    ink: Color,
    paper: Color,
) {
    val at = Geo.screenOf(cluster.point, centre, zoom, width, height)
    if (at.x < -MARK_RADIUS || at.y < -MARK_RADIUS) return
    if (at.x > width + MARK_RADIUS || at.y > height + MARK_RADIUS) return
    val position = Offset(at.x.toFloat(), at.y.toFloat())
    // Paper under ink: a black dot on a dark map tile is invisible, and the halo is what makes a
    // mark readable over anything underneath it.
    drawCircle(color = paper, radius = MARK_RADIUS + 2f, center = position)
    drawCircle(color = ink, radius = MARK_RADIUS, center = position)
    if (cluster.size > 1) {
        drawCircle(color = paper, radius = MARK_RADIUS - 3f, center = position)
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = ink.toArgb()
                textSize = MARK_RADIUS * 1.2f
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            drawText(
                if (cluster.size > 99) "99+" else cluster.size.toString(),
                position.x,
                position.y + MARK_RADIUS * 0.42f,
                paint,
            )
        }
    }
}

private const val MARK_RADIUS = 11f

/** How far a tap may miss a mark and still count. A fingertip on this panel. */
private const val TOUCH_SLOP = 34
