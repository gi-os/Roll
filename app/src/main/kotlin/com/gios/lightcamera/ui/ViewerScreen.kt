package com.gios.lightcamera.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelTurns
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Path
import com.gios.lightcamera.media.durationLabel
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.rememberUpdatedState
import android.net.Uri
import com.gios.lightcamera.media.Photo
import com.gios.lightcamera.ui.theme.LightIcons
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.LightThemeTokens
import com.gios.lightcamera.ui.theme.lightClickable
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One photograph, full screen.
 *
 * The wheel steps between frames, which is the same gesture as advancing film and reads
 * better than a swipe once there are more than a few photographs to get through. Chrome
 * hides on a tap, because the reason you opened this was to look at the picture.
 */
@Composable
fun ViewerScreen(
    vm: CameraViewModel,
    initial: Photo,
    onClose: () -> Unit,
    onSend: (List<Photo>) -> Unit,
) {
    var playing by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val colours = LightThemeTokens.colors
    val roll by vm.photos.collectAsState()
    val pageSheet by vm.pageSheet.collectAsState()
    val reading by vm.reading.collectAsState()

    // **Opened out of a strip, the viewer shows its four frames instead of the roll.**
    // They are deliberately absent from the grid — a booth hands you one print — so this is the only
    // way to them, and it is a button on the print itself rather than a folder to go and find.
    var behind by remember { mutableStateOf<List<Photo>>(emptyList()) }
    val photos = behind.ifEmpty { roll }

    // **Pages run oldest to newest, left to right** — the reverse of the list, which is
    // newest-first. The roll grid puts the newest frame bottom-right with the one before it to its
    // left, so reading the grid the ordinary way, left to right and down the rows, walks forwards in
    // time. The viewer has to agree with that or the two screens contradict each other: swiping the
    // photograph leftwards, like turning a page, now moves towards newer, which is also what Photos
    // on an iPhone does.
    val lastPage = (photos.size - 1).coerceAtLeast(0)
    fun photoAt(page: Int): Photo? = photos.getOrNull(lastPage - page)

    val startIndex = remember(initial.id, photos) {
        val found = photos.indexOfFirst { it.id == initial.id }.coerceAtLeast(0)
        (photos.size - 1 - found).coerceAtLeast(0)
    }
    val pager = rememberPagerState(initialPage = startIndex, pageCount = { photos.size })
    // Swapping to the strip's frames changes what page zero means, so go to the end of the new list
    // rather than staying on whichever index happened to be current.
    //
    // **Skipping the first run, which is the whole bug it caused.** A `LaunchedEffect` fires once on
    // composition as well as on every change, so this was jumping the pager the moment the viewer opened
    // — and tapping any photograph took you to the newest one instead of the one you tapped, throwing
    // away the `initialPage` computed just above.
    var swapped by remember { mutableStateOf(false) }
    LaunchedEffect(behind.isNotEmpty()) {
        if (!swapped) {
            swapped = true
            return@LaunchedEffect
        }
        if (photos.isNotEmpty()) pager.scrollToPage(pager.pageCount - 1)
    }
    var chromeVisible by remember { mutableStateOf(true) }

    // Zoom lives here rather than per page so that leaving a photograph resets it — coming back to
    // a picture you left at 4x, scrolled into a corner, is a small mystery every time.
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(pager.currentPage) {
        scale = 1f
        pan = Offset.Zero
        // A reading belongs to one photograph. Swiping to the next one with the last one's words
        // still on screen would be worse than showing nothing, because it would look correct.
        vm.dismissPage()
    }
    val zoomed = scale > 1.01f

    // Which way up the phone is. A photograph should fill the long edge when the phone is on its
    // side, the way it would if the window were free to rotate — which it deliberately is not.
    val quarter = rememberDeviceQuarter()

    val trash = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { scope.launch { vm.refreshRoll() } }

    WheelTurns(active = true, armed = true) { notches ->
        scope.launch {
            // Subtracting, and only because the page mapping above was reversed: the dial has to
            // move the same photographs the same way it did before, so flipping which end of the
            // list page zero is means flipping this too. Direction settled by hand, not by argument.
            val next = (pager.currentPage - notches).coerceIn(0, lastPage)
            pager.animateScrollToPage(next)
        }
    }

    // Every photograph gone means there is nothing left to look at.
    LaunchedEffect(photos.size) { if (photos.isEmpty()) onClose() }

    // A photograph is the one thing on this phone that is definitely worth seeing in colour.
    val colour by vm.prefs.colour.collectAsState()
    ColourEffect(enabled = colour != com.gios.lightcamera.Colour.Off)

    // Decode no bigger than the panel. A 12MP JPEG at 1:1 is 48MB of heap for a 1080px view.
    val screenWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .lightClickable(haptics = false) {
                // Zoomed in, a tap is the way back out — the chrome is not what you are trying to
                // get at when a picture is at four times.
                if (zoomed) {
                    scale = 1f
                    pan = Offset.Zero
                } else {
                    chromeVisible = !chromeVisible
                }
            },
    ) {
        // Pinch to zoom, drag to move about, double tap to come back. The pager keeps the
        // horizontal drag until you are zoomed in, at which point panning has to win or a zoomed
        // photograph is impossible to look around.
        // Which clip is playing, by id. **Cleared whenever the page changes**, so swiping to the
        // next photograph stops the audio rather than leaving a clip running behind a still.
        LaunchedEffect(pager.currentPage) { playing = null }
        // **The volume keys are the shutter, so a playing clip has to borrow them back.**
        // `dispatchKeyEvent` sees them before anything else does, so without this there is no way
        // to change a video's volume at all. Cleared on dispose as well as on every change: left
        // set, it would take the fallback shutter away for the rest of the session.
        LaunchedEffect(playing) { vm.setClipPlaying(playing != null) }
        DisposableEffect(Unit) { onDispose { vm.setClipPlaying(false) } }
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val photo = photoAt(page) ?: return@HorizontalPager
            var image by remember(photo.id) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(photo.id) {
                image = vm.thumbs
                    .frame(photo.uri, photo.id, screenWidthPx, photo.isVideo)
                    ?.asImageBitmap()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(page) {
                        // **Arbitrated by hand, because `detectTransformGestures` consumes every
                        // drag it sees** — including single-finger ones — which took the swipe
                        // between photographs away entirely. So: two fingers is always a pinch and
                        // always ours; one finger is ours only when zoomed in, where panning has to
                        // win; and one finger at 1x is consumed by nobody here, so the pager gets
                        // its swipe back.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.none { it.pressed }) break
                                val fingers = event.changes.count { it.pressed }
                                val pinching = fingers >= 2
                                if (!pinching && scale <= 1.01f) continue
                                if (pinching) scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                                val drag = event.calculatePan()
                                pan = if (scale <= 1.01f) {
                                    Offset.Zero
                                } else {
                                    // Bounded to the overhang, so the picture cannot be dragged off
                                    // the screen and lost.
                                    val limitX = size.width * (scale - 1f) / 2f
                                    val limitY = size.height * (scale - 1f) / 2f
                                    Offset(
                                        (pan.x + drag.x).coerceIn(-limitX, limitX),
                                        (pan.y + drag.y).coerceIn(-limitY, limitY),
                                    )
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = image
                if (bitmap != null) {
                    // **The photograph turns; the interface does not.** Same split as the
                    // viewfinder, and for the same reason — turn the phone on its side and the
                    // picture comes round to fill the long edge, while the close button, the date
                    // and the bin stay exactly where your thumb left them. Rotating the whole screen
                    // instead meant the controls moved every time you tilted it, and a swipe that
                    // was horizontal a moment ago became vertical.
                    //
                    // Zoom and pan sit *outside* the rotation, so dragging moves the picture the way
                    // your finger went rather than the way the phone happens to be held.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = pan.x
                                translationY = pan.y
                            },
                    ) {
                        RotatedToDevice(quarter) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = photo.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                // **The player is in here, because on this phone there is nowhere else.**
                //
                // This used to hand the clip to `ACTION_VIEW` and let the system pick a player,
                // which is the polite Android thing to do and was wrong here: LightOS ships almost
                // no apps, so on most phones nothing claimed the intent and tapping a video did
                // nothing but show a notice. A camera whose own roll cannot play its own recordings
                // is not finished.
                //
                // `VideoView` rather than a codec and a surface by hand: it is a `MediaPlayer`, a
                // `SurfaceView` and the state machine between them, which is exactly the part that
                // is fiddly to get right and nothing here needs to be clever about.
                if (photo.isVideo) {
                    if (playing == photo.id) {
                        VideoSurface(
                            uri = photo.uri,
                            quarter = quarter,
                            onEnded = { playing = null },
                            onFailed = {
                                playing = null
                                vm.showNotice("That clip wouldn't play")
                            },
                        )
                    } else {
                        PlayBadge(
                            label = photo.durationLabel(),
                            modifier = Modifier.align(Alignment.Center),
                            onClick = { playing = photo.id },
                        )
                    }
                }
            }
        }

        if (chromeVisible) {
            val photo = photoAt(pager.currentPage)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(colours.scrim)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChromeIcon(icon = LightIcons.Close, onClick = onClose)
                Spacer(Modifier.weight(1f))
                if (photo != null) {
                    LightText(
                        text = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())
                            .format(Date(photo.takenAt)),
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                    )
                }
                Spacer(Modifier.weight(1f))
                LightText(
                    text = "${pager.currentPage + 1}/${photos.size}",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(colours.scrim)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChromeIcon(
                    icon = LightIcons.Trash,
                    onClick = {
                        val target = photoAt(pager.currentPage) ?: return@ChromeIcon
                        val sender = vm.trashRequest(target)
                        if (sender != null) {
                            trash.launch(IntentSenderRequest.Builder(sender).build())
                        } else {
                            vm.showNotice("Can't bin that one")
                        }
                    },
                )
                Spacer(Modifier.weight(1f))
                // Four-shot strips only. `_strip` in the name is the whole test: the frames behind
                // one are named from the same stamp, which is the only relationship MediaStore has
                // anywhere to store.
                val current = photoAt(pager.currentPage)
                // The star. Filled when this photograph is in the list, outline when it is not — the two
                // icons the SDK set already carries for exactly this.
                val starred by vm.prefs.favourites.collectAsState()
                if (current != null) {
                    ChromeIcon(
                        icon = if (current.name in starred) LightIcons.Star else LightIcons.StarOutline,
                        lighten = current.name !in starred,
                        onClick = {
                            val nowStarred = vm.prefs.toggleFavourite(current.name)
                            vm.showNotice(if (nowStarred) "Starred" else "Unstarred")
                        },
                    )
                    Spacer(Modifier.weight(1f))
                }
                if (behind.isNotEmpty()) {
                    ChromeLabel(
                        text = "Strip",
                        onClick = { behind = emptyList() },
                    )
                    Spacer(Modifier.weight(1f))
                } else if (current != null && current.name.contains("_strip")) {
                    ChromeLabel(
                        text = "Frames",
                        onClick = {
                            scope.launch {
                                val found = vm.framesBehind(current)
                                if (found.isEmpty()) {
                                    vm.showNotice("The frames behind this one are gone")
                                } else {
                                    behind = found
                                }
                            }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                }
                // Reading the words off a photograph. A label rather than an icon because
                // there is no glyph in the set that means "text in a picture", and on this panel
                // a word is more legible than an invented mark.
                //
                // Absent while the sheet is up, so the row does not offer to do again the thing
                // already on screen.
                if (current != null && pageSheet == null) {
                    ChromeLabel(
                        text = if (reading) "Reading" else "Text",
                        lighten = reading,
                        onClick = { if (!reading) vm.readPage(current) },
                    )
                    Spacer(Modifier.weight(1f))
                }
                // **The share button asks who, not which app.**
                //
                // It used to open the system chooser: a colour Material sheet listing every app
                // that ever registered for an image, on a phone whose argument is that there
                // aren't any, and still leaving you to pick the person once inside. The question
                // worth asking on this phone is who the photograph is for, so this opens the
                // app's own picker over the address book instead. See `SendSheet` and `Handoff`.
                ChromeIcon(
                    icon = LightIcons.Share,
                    onClick = {
                        val target = photoAt(pager.currentPage) ?: return@ChromeIcon
                        onSend(listOf(target))
                    },
                )
            }
        }

        // Over the photograph, inside the same Box as the chrome, so it covers the picture and
        // the controls both. Last in the Box because Compose paints in order and this has to win.
        pageSheet?.let { text ->
            TextSheet(
                text = text,
                onOpen = vm::openFromPage,
                onCopy = vm::copyFromPage,
                onClose = vm::dismissPage,
            )
        }
    }
}

/**
 * A developed roll, all at once.
 *
 * The only screen in the app that exists purely for a moment: twenty-four photographs you
 * have not seen, laid out as a contact sheet with their frame numbers, which is what
 * developing a roll ought to feel like. Dismissed and never shown again — from then on they
 * are just photographs on the roll like any others.
 */
@Composable
fun ContactSheet(
    vm: CameraViewModel,
    developed: com.gios.lightcamera.roll.FilmRoll.DevelopedRoll,
    onClose: () -> Unit,
) {
    val colours = LightThemeTokens.colors
    val photos by vm.photos.collectAsState()
    val frames = remember(developed, photos) {
        val wanted = developed.uris.toSet()
        photos.filter { it.uri in wanted }.sortedBy { it.takenAt }
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
                "ROLL ${developed.number} DEVELOPED",
                LightTextVariant.Detail,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            ChromeIcon(icon = LightIcons.Accept, onClick = onClose)
        }

        if (frames.isEmpty()) {
            EmptyState(
                text = "Nothing came out.",
                detail = "The frames couldn't be written to the camera roll.",
            )
            return@Column
        }

        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        ) {
            items(count = frames.size, key = { frames[it].id }) { index ->
                val photo = frames[index]
                Column(
                    modifier = Modifier.padding(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    var image by remember(photo.id) { mutableStateOf<ImageBitmap?>(null) }
                    LaunchedEffect(photo.id) {
                        image = vm.thumbs.thumbnail(photo.uri, photo.id, 256)?.asImageBitmap()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colours.rule)
                            .padding(0.dp),
                    ) {
                        val bitmap = image
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = photo.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(96.dp),
                            )
                        } else {
                            Box(Modifier.fillMaxWidth().height(96.dp))
                        }
                    }
                    LightText(
                        text = "%02d".format(index + 1),
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * A play triangle and a running time, drawn rather than shipped.
 *
 * The icon set this app draws from has no play glyph, and a triangle is three lines of `Path`.
 * Sized in dp so it is the same size on the panel whatever the clip's resolution is.
 */
@Composable
private fun PlayBadge(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colours = LightThemeTokens.colors
    Column(
        modifier = modifier.lightClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(modifier = Modifier.size(54.dp)) {
            drawCircle(color = Color.Black.copy(alpha = 0.45f), radius = size.minDimension / 2f)
            val r = size.minDimension / 2f
            val w = r * 0.62f
            val h = r * 0.72f
            // Nudged right by an eighth of its width: a triangle centred on its bounding box
            // reads as sitting left of centre, because its mass is on the flat edge.
            val cx = center.x + w * 0.12f
            drawPath(
                path = Path().apply {
                    moveTo(cx - w / 2f, center.y - h)
                    lineTo(cx + w, center.y)
                    lineTo(cx - w / 2f, center.y + h)
                    close()
                },
                color = Color.White,
            )
        }
        Spacer(Modifier.height(6.dp))
        LightText(
            text = label,
            variant = LightTextVariant.Superfine,
            modifier = Modifier
                .background(colours.scrim)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/**
 * A clip, playing.
 *
 * `VideoView` is an old widget and the right one: it wraps a `MediaPlayer` and a `SurfaceView` and
 * owns the handshake between them, which is the only genuinely awkward part of playing a file. It
 * takes a MediaStore `content://` URI directly, so there is no path to resolve and no file to copy.
 *
 * **Turned with the photograph, not with the phone.** The activity is locked to portrait, so a clip
 * recorded holding the phone sideways arrives rotated exactly like a still does — and it is rotated
 * here the same way, by [quarter], so a video and the photograph before it in the roll are the same
 * way up.
 *
 * Starts as soon as it is ready and reports back when it ends, so the caller can put the poster
 * frame and the triangle back rather than leaving a black rectangle. Errors do the same: a clip that
 * will not decode returns you to a still you can still look at.
 */
@Composable
private fun VideoSurface(
    uri: Uri,
    quarter: Int,
    onEnded: () -> Unit,
    onFailed: () -> Unit,
) {
    val current = rememberUpdatedState(onEnded)
    val failed = rememberUpdatedState(onFailed)
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { rotationZ = quarter.toFloat() },
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { player ->
                    // Looping is wrong for a camera roll: a clip should end so you can see it has,
                    // and the triangle coming back is what says so.
                    player.isLooping = false
                    start()
                }
                setOnCompletionListener { current.value() }
                setOnErrorListener { _, _, _ ->
                    failed.value()
                    // True: the error is handled here, and returning false would let the widget put
                    // up its own system dialog on top of the photograph.
                    true
                }
            }
        },
        update = { view ->
            if (view.tag != uri) {
                view.tag = uri
                view.setVideoURI(uri)
            }
        },
        onRelease = { view ->
            // Without this the MediaPlayer outlives the composable and the audio keeps going after
            // the viewer is closed — the classic version of this bug.
            runCatching { view.stopPlayback() }
        },
    )
}
