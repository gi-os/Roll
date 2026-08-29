package com.gios.lightcamera.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gios.lightcamera.R
import com.gios.lightcamera.filter.Filters
import com.gios.lightcamera.filter.ShaderRuntime
import com.gios.lightcamera.ui.theme.LightIcons
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The filter picker: swipe through every filter rendered live on a real photograph, and tap
 * to choose which ones the camera carries.
 *
 * Reached two ways — from Look → "View filters", and automatically the first time a fresh
 * install has no saved filter choice. Both open the same screen, which is the point: a
 * camera decides what it carries once, and can always revisit the decision.
 *
 * **The preview is not an approximation.** Each page is the actual AGSL shader the viewfinder
 * would run, applied to a bundled photograph the same way a capture is — one [ShaderRuntime.Offscreen]
 * built at the sample's size and reused for every filter, exactly the pattern [FilterGrid]
 * uses for its cells. The photo is a real scene rather than a test pattern because half these
 * filters are about skin and sky, and a gradient says nothing about either. It ships at panel
 * resolution so the look you pick is the look you get.
 *
 * Every toggle writes the same `filtersOff` preference the wheel and the Photo Booth grid
 * read, so closing the picker and turning the dial shows exactly what was chosen here.
 */
@Composable
fun FilterPickerScreen(
    vm: CameraViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val grade by vm.prefs.grade.collectAsState()
    val moshModeId by vm.prefs.moshMode.collectAsState()
    val off by vm.prefs.filtersOff.collectAsState()

    // The full catalog, not the dial: you cannot turn a filter back on from a list that only
    // shows what is already on. The dial is derived from these same entries, so nothing here
    // can show a filter the wheel cannot reach.
    val rows = remember { Filters.all }

    val pager = rememberPagerState(pageCount = { rows.size })

    var previews by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }

    // The state a fresh camera sees: only Mono and Preset on, everything else off. Runs the
    // first time the picker opens (before the user has chosen), so the wheel and the grid
    // inherit what the picker shows rather than a dial of everything-turned-on. Idempotent —
    // once a choice has been saved, reopening the picker from Look settings leaves it alone.
    LaunchedEffect(Unit) {
        vm.prefs.seedDefaultFilterChoice()
    }

    // Render every filter once into the sample. Keyed on grade and the datamosh look so a
    // change in Look settings is reflected here, but not on `off` — toggling must be instant,
    // not wait on a GPU pass for the page you just tapped.
    LaunchedEffect(grade, moshModeId) {
        val source = withContext(Dispatchers.Default) {
            BitmapFactory.decodeResource(context.resources, R.drawable.sample_filter_photo)
        } ?: return@LaunchedEffect

        val renderer = withContext(Dispatchers.Default) {
            ShaderRuntime.Offscreen(source.width, source.height)
        }
        try {
            val seed = 1234f
            val rendered = withContext(Dispatchers.Default) {
                rows.associate { entry ->
                    val filter = Filters.forMosh(
                        Filters.forGrade(entry, grade),
                        Filters.moshById(moshModeId),
                    )
                    val bitmap = if (filter.agsl == null) {
                        source
                    } else {
                        renderer?.render(source, filter, seed) ?: source
                    }
                    entry.id to bitmap.asImageBitmap()
                }
            }
            previews = rendered
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
            LightText(
                "FILTERS",
                LightTextVariant.Detail,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            ChromeIcon(icon = LightIcons.Close, onClick = onClose)
        }

        LightText(
            "Swipe to see every filter. Tap to switch it on or off for the wheel and the Photo Booth grid.",
            LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val entry = rows[page]
            val plain = entry.id == Filters.none.id
            val on = plain || entry.id !in off

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .lightClickable(enabled = !plain) { vm.prefs.toggleFilter(entry.id) },
            ) {
                val image = previews[entry.id]
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = entry.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(image.width.toFloat() / image.height)
                            .align(Alignment.Center),
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LightText(
                            text = entry.label.uppercase(),
                            variant = LightTextVariant.Subheading,
                            modifier = Modifier.weight(1f),
                        )
                        LightText(
                            text = if (plain) "ALWAYS" else if (on) "ON" else "OFF",
                            variant = LightTextVariant.Detail,
                            lighten = !on,
                        )
                    }
                    LightText(
                        text = if (plain) "The camera's plain photograph. Always available."
                        else if (on) "On the wheel and the grid. Tap to turn off."
                        else "Hidden from the wheel and the grid. Tap to turn on.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
