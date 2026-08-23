package com.gios.lightcamera.ui

import android.graphics.Bitmap
import android.view.TextureView
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.lightcamera.filter.Filters
import com.gios.lightcamera.filter.ShaderRuntime
import com.gios.lightcamera.ui.theme.LightIcons
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.LightThemeTokens
import com.gios.lightcamera.ui.theme.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Every filter, live, at once — Photo Booth's grid.
 *
 * Picking a filter by cycling through a list means holding the last one in your head to
 * compare against. Seeing all fifteen running on the thing in front of you means not having
 * to. It's the single most useful screen in Photo Booth and it has been almost entirely
 * forgotten since.
 *
 * The cost is one camera frame a sixth of a second, scaled to about a hundred pixels wide
 * and pushed through fifteen shaders:
 *
 *  - The frame comes from the preview's [TextureView] at cell size, not from
 *    `PreviewView.getBitmap()`, which allocates a full-resolution bitmap — six megabytes a
 *    call, forty a second, which the collector would notice and so would you.
 *  - One [ShaderRuntime.Offscreen] is built for the cell size and reused for every filter
 *    and every frame; the GPU surface and its reader are the expensive part, not the draws.
 *  - The read runs on the main thread because that is where the view is, and the fifteen
 *    renders run off it.
 */
@Composable
fun FilterGrid(
    vm: CameraViewModel,
    previewView: PreviewView,
    onPick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val colours = LightThemeTokens.colors
    val current by vm.filter.collectAsState()
    val grade by vm.prefs.grade.collectAsState()
    val order by vm.prefs.filterOrder.collectAsState()
    val off by vm.prefs.filtersOff.collectAsState()
    // The dial as the user arranged it in settings, not the whole catalog. The grid and the wheel
    // have to agree about what is on the dial, or picking a filter here would put the wheel
    // somewhere it cannot get back to.
    val dial = remember(order, off) { Filters.ordered(order, off) }
    var frames by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }

    LaunchedEffect(previewView, grade, dial) {
        val renderer = withContext(Dispatchers.Default) {
            ShaderRuntime.Offscreen(CELL_PX_W, CELL_PX_H)
        }
        try {
            while (coroutineContext.isActive) {
                val source = previewView.grabFrame(CELL_PX_W, CELL_PX_H)
                if (source != null) {
                    val seed = Random.nextFloat() * 1000f
                    val rendered = withContext(Dispatchers.Default) {
                        dial.associate { entry ->
                            // Resolved here too, or the Preset cell would be the one tile in the
                            // grid showing you something other than what picking it would give you.
                            val filter = Filters.forGrade(entry, grade)
                            val bitmap = if (filter.agsl == null) {
                                source
                            } else {
                                renderer?.render(source, filter, seed) ?: source
                            }
                            entry.id to bitmap.asImageBitmap()
                        }
                    }
                    frames = rendered
                }
                delay(FRAME_MS)
            }
        } finally {
            withContext(Dispatchers.Default) { renderer?.close() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText("FILTERS", LightTextVariant.Detail, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            // The viewfinder has no room for a settings icon any more, so it lives in the two
            // places you can reach in one tap from it: here, and the roll's header.
            ChromeIcon(icon = LightIcons.Settings, lighten = true, onClick = onOpenSettings)
            ChromeIcon(icon = LightIcons.Close, onClick = onClose)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        ) {
            items(dial, key = { it.id }) { filter ->
                val selected = filter.id == current.id
                Column(
                    modifier = Modifier
                        .padding(3.dp)
                        .lightClickable { onPick(filter.id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(CELL_PX_W.toFloat() / CELL_PX_H)
                            .background(colours.rule),
                    ) {
                        val bitmap = frames[filter.id]
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = filter.label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (selected) {
                            // The same corner brackets the viewfinder uses for a locked
                            // subject, for the same reason: mark it without covering it.
                            SelectionBrackets(Modifier.fillMaxSize())
                        }
                    }
                    LightText(
                        text = filter.label.uppercase(),
                        variant = LightTextVariant.Superfine,
                        lighten = !selected,
                        align = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBrackets(modifier: Modifier) {
    val colours = LightThemeTokens.colors
    androidx.compose.foundation.Canvas(modifier) {
        val arm = size.minDimension * 0.22f
        val stroke = 1.6.dp.toPx()
        val corners = listOf(
            Triple(0f, 0f, 1f to 1f),
            Triple(size.width, 0f, -1f to 1f),
            Triple(0f, size.height, 1f to -1f),
            Triple(size.width, size.height, -1f to -1f),
        )
        corners.forEach { (x, y, dir) ->
            val (dx, dy) = dir
            drawLine(
                colours.content,
                androidx.compose.ui.geometry.Offset(x, y),
                androidx.compose.ui.geometry.Offset(x + arm * dx, y),
                stroke,
            )
            drawLine(
                colours.content,
                androidx.compose.ui.geometry.Offset(x, y),
                androidx.compose.ui.geometry.Offset(x, y + arm * dy),
                stroke,
            )
        }
    }
}

/**
 * A small, unfiltered copy of what the camera is seeing.
 *
 * Reaching into `PreviewView` for its `TextureView` is not tidy, but the alternative is
 * `PreviewView.getBitmap()`, which is documented to allocate at the preview's own
 * resolution. `TextureView.getBitmap(w, h)` scales during the copy, which is exactly the
 * operation wanted and about sixty times less memory.
 *
 * The result is deliberately the *unfiltered* image even while a filter is on the preview:
 * a `RenderEffect` is applied when the view is drawn into the hierarchy, whereas this reads
 * the camera's surface directly. So the grid can show fifteen filters of the same frame
 * without any of them being applied twice.
 */
private fun PreviewView.grabFrame(width: Int, height: Int): Bitmap? {
    val texture = findTextureView(this)
    if (texture != null && texture.isAvailable) {
        return runCatching { texture.getBitmap(width, height) }.getOrNull()
    }
    return runCatching { bitmap }.getOrNull()
}

private fun findTextureView(group: ViewGroup): TextureView? {
    for (i in 0 until group.childCount) {
        val child = group.getChildAt(i)
        if (child is TextureView) return child
        if (child is ViewGroup) findTextureView(child)?.let { return it }
    }
    return null
}

/**
 * Cell size in pixels, and the tick.
 *
 * 108 x 144 is a little under what a third of the LPIII's width needs, which is deliberate:
 * the cells are drawn with `ContentScale.Crop` and a slightly soft thumbnail is invisible,
 * whereas fifteen sharp ones cost three times the fill rate. Six frames a second is fast
 * enough to see yourself move, which is all the grid has to prove.
 */
private const val CELL_PX_W = 108
private const val CELL_PX_H = 144
private const val FRAME_MS = 160L
