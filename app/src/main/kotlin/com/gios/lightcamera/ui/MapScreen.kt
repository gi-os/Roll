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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.gios.light.common.hw.WheelTurns
import com.gios.lightcamera.map.Cluster
import com.gios.lightcamera.map.Geo
import com.gios.lightcamera.map.Point
import com.gios.lightcamera.map.Tile
import com.gios.lightcamera.map.Tiles
import com.gios.lightcamera.media.Photo
import com.gios.lightcamera.media.Thumbs
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
    thumbs: Thumbs,
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
        // **Kept for exactly as long as it is wanted.** This map held every tile ever shown, so a
        // long pan across a city accumulated hundreds of bitmaps with nothing to evict them — the
        // out-of-memory arrives an afternoon later, in whatever allocation happens to be next.
        // [Tiles] has the LRU and the disk cache, so dropping a tile here costs a lookup, not a
        // fetch, and panning back is instant anyway.
        val keep = wanted.toSet()
        loaded.keys.retainAll(keep)
        wanted.forEach { tile ->
            if (loaded.containsKey(tile)) return@forEach
            tiles.get(tile)?.let { loaded[tile] = it.asImageBitmap() }
        }
    }

    val clusters = remember(located, zoom) {
        Geo.cluster(located.map { it.first.id to it.second }, zoom)
    }
    val byId = remember(located) { located.associate { it.first.id to it.first } }

    // **A mark is the photograph, not a dot.** A dot says a photograph exists somewhere. The point
    // of a map of your own pictures is recognising the place from the picture, which needs the
    // picture. One thumbnail per mark, the newest of the group, at the size the mark is drawn.
    val marks = remember { mutableStateMapOf<Long, ImageBitmap>() }
    LaunchedEffect(clusters) {
        val wantedIds = clusters.mapNotNull { it.ids.firstOrNull() }.toSet()
        marks.keys.retainAll(wantedIds)
        wantedIds.forEach { id ->
            if (marks.containsKey(id)) return@forEach
            val photo = byId[id] ?: return@forEach
            thumbs.thumbnail(photo.uri, photo.id, MARK_PX)?.let { marks[id] = it.asImageBitmap() }
        }
    }

    // Which mark the finger landed on, and so which photographs to lay out along the bottom. A
    // mark holding twelve pictures used to zoom in and hope they separated, which they do not when
    // twelve were taken standing in one spot.
    var opened by remember { mutableStateOf<Cluster?>(null) }
    LaunchedEffect(clusters) { opened = null }

    // The wheel zooms, because the wheel is what this phone has instead of a second finger, and
    // because a pinch on a 3.9 inch panel covers most of the map you are trying to look at.
    WheelTurns(active = true, armed = true) { notches ->
        opened = null
        zoom = (zoom + if (notches > 0) 1 else -1).coerceIn(Geo.MIN_ZOOM, Geo.MAX_ZOOM)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colours.background)
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            // **Keyed on nothing.** These used to key on the centre and the zoom, both of which
            // the gesture itself writes, so the first pan restarted the detector and the drag died
            // under the finger. The delegated state reads current values inside the lambda anyway.
            .pointerInput(Unit) {
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
            .pointerInput(clusters) {
                detectTapGestures { tap ->
                    val at0 = centre ?: here
                    val hit = clusters.minByOrNull { cluster ->
                        val at = Geo.screenOf(cluster.point, at0, zoom, width, height)
                        abs(at.x - tap.x) + abs(at.y - tap.y)
                    } ?: return@detectTapGestures
                    val at = Geo.screenOf(hit.point, at0, zoom, width, height)
                    if (abs(at.x - tap.x) > MARK_TOUCH || abs(at.y - tap.y) > MARK_TOUCH) {
                        opened = null
                        return@detectTapGestures
                    }
                    // One photograph opens. Several lay themselves out along the bottom of the
                    // map, which is the question the tap was asking: what did I take here.
                    if (hit.size > 1) {
                        opened = hit
                    } else {
                        byId[hit.ids.first()]?.let(onOpen)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            wanted.forEach { tile ->
                val bitmap = loaded[tile] ?: return@forEach
                val offset = Geo.offsetOf(tile, centre ?: here, width, height)
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                )
            }
            val eye = centre ?: here
            clusters.forEach { cluster ->
                drawMark(
                    cluster = cluster,
                    centre = eye,
                    zoom = zoom,
                    width = width,
                    height = height,
                    ink = colours.content,
                    paper = colours.background,
                    thumb = cluster.ids.firstOrNull()?.let { marks[it] },
                    open = cluster.point == opened?.point,
                )
            }
        }

        opened?.let { cluster ->
            val shown = remember(cluster, byId) { cluster.ids.mapNotNull { byId[it] } }
            LazyRow(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .background(colours.scrim)
                    .padding(vertical = 6.dp),
            ) {
                items(shown, key = { it.id }) { photo ->
                    MapThumb(photo = photo, thumbs = thumbs, onOpen = onOpen)
                }
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
    thumb: ImageBitmap?,
    open: Boolean,
) {
    val at = Geo.screenOf(cluster.point, centre, zoom, width, height)
    val half = MARK_SIDE / 2f
    if (at.x < -MARK_SIDE || at.y < -MARK_SIDE) return
    if (at.x > width + MARK_SIDE || at.y > height + MARK_SIDE) return
    val position = Offset(at.x.toFloat(), at.y.toFloat())
    val corner = Offset(position.x - half, position.y - half)

    // Paper under the picture: a frame is what separates one photograph from the map behind it and
    // from the next photograph half over it. The open mark gets an ink frame instead, which is the
    // only state this screen has to show.
    val edge = if (open) ink else paper
    drawRect(
        color = edge,
        topLeft = Offset(corner.x - MARK_EDGE, corner.y - MARK_EDGE),
        size = Size(MARK_SIDE + MARK_EDGE * 2f, MARK_SIDE + MARK_EDGE * 2f),
    )
    if (thumb == null) {
        // Before the thumbnail arrives, and for anything that fails to decode.
        drawRect(color = ink, topLeft = corner, size = Size(MARK_SIDE, MARK_SIDE))
    } else {
        drawImage(
            image = thumb,
            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
            srcSize = IntSize(thumb.width, thumb.height),
            dstOffset = androidx.compose.ui.unit.IntOffset(
                corner.x.roundToInt(),
                corner.y.roundToInt(),
            ),
            dstSize = IntSize(MARK_SIDE.roundToInt(), MARK_SIDE.roundToInt()),
        )
    }

    if (cluster.size > 1) {
        // The count rides the corner rather than the middle, because the middle is the photograph.
        val badge = Offset(position.x + half, position.y - half)
        drawCircle(color = paper, radius = BADGE_RADIUS + 1.5f, center = badge)
        drawCircle(color = ink, radius = BADGE_RADIUS, center = badge)
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = paper.toArgb()
                textSize = BADGE_RADIUS * 1.25f
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            drawText(
                if (cluster.size > 99) "99+" else cluster.size.toString(),
                badge.x,
                badge.y + BADGE_RADIUS * 0.45f,
                paint,
            )
        }
    }
}

/**
 * One photograph from a tapped mark, in the strip along the bottom.
 *
 * Loaded here rather than up front: a mark can hold fifty pictures and only the ones a thumb
 * scrolls past are worth decoding.
 */
@Composable
private fun MapThumb(photo: Photo, thumbs: Thumbs, onOpen: (Photo) -> Unit) {
    var image by remember(photo.id) {
        mutableStateOf(thumbs.cached(photo.id)?.asImageBitmap())
    }
    LaunchedEffect(photo.id) {
        if (image == null) {
            image = thumbs.thumbnail(photo.uri, photo.id, STRIP_PX)?.asImageBitmap()
        }
    }
    val colours = LightThemeTokens.colors
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .height(STRIP_SIDE)
            .aspectRatio(1f)
            .background(colours.rule)
            .pointerInput(photo.id) { detectTapGestures { onOpen(photo) } },
    ) {
        image?.let {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    image = it,
                    srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    srcSize = IntSize(it.width, it.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
            }
        }
    }
}

/** The side of a mark, in pixels on the canvas. Big enough to recognise the place in it. */
private const val MARK_SIDE = 48f

/** The frame around a mark. */
private const val MARK_EDGE = 2f

/** The count on a stack of photographs, in the corner of the top one. */
private const val BADGE_RADIUS = 9f

/** What to ask MediaStore for. Twice the mark, so it stays sharp on a dense panel. */
private const val MARK_PX = 96

private val STRIP_SIDE = 64.dp

private const val STRIP_PX = 192

/** How far a tap may miss a mark and still count. A fingertip on this panel. */
private const val MARK_TOUCH = 40
