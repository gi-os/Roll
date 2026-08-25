package com.gios.lightcamera.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightcamera.media.DayLabels
import com.gios.lightcamera.media.Photo
import com.gios.lightcamera.media.durationLabel
import com.gios.lightcamera.media.RollScope
import com.gios.lightcamera.ui.theme.LightIcon
import com.gios.lightcamera.ui.theme.LightIcons
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.LightThemeTokens
import com.gios.lightcamera.ui.theme.lightClickable
import com.gios.lightcamera.ui.theme.lightCombinedClickable
import kotlinx.coroutines.launch

/** One row of the flattened roll: either a photo or the day it was taken. */
private sealed interface RollEntry {
    data class Frame(val photo: Photo) : RollEntry
    data class Day(val label: String, val count: Int) : RollEntry
}

/**
 * The roll, hanging above the viewfinder.
 *
 * Laid out **in reverse**, and that is the whole trick. The list is built newest-first and
 * `reverseLayout` puts the head of it at the bottom of the screen, so:
 *
 *  - the photo you took a second ago sits directly against the top edge of the camera,
 *    which is where your eye already is;
 *  - older photographs run upwards, so the roll reads as film coming out of the camera
 *    rather than as a file listing;
 *  - the list's resting position is its own bottom edge, which is precisely where an upward
 *    swipe finds nothing left to scroll and hands the gesture to the pager — so you get back
 *    to the viewfinder from wherever you are, with the same flick every time.
 *
 * Day headings are emitted *after* their photographs for the same reason: reversed, that
 * puts each heading above the group it names.
 */
@Composable
fun RollScreen(
    vm: CameraViewModel,
    active: Boolean,
    mediaGranted: Boolean,
    onRequestMedia: () -> Unit,
    onOpen: (Photo) -> Unit,
    onOpenSettings: () -> Unit,
    onBackToCamera: () -> Unit,
    onSend: (List<Photo>) -> Unit,
) {
    val colours = LightThemeTokens.colors
    val photos by vm.photos.collectAsState()
    val loading by vm.loadingRoll.collectAsState()
    val scope by vm.prefs.scope.collectAsState()
    val roll by vm.roll.collectAsState()
    val colour by vm.prefs.colour.collectAsState()

    // **The roll is photographs too.** It used to be the one picture surface in the app that
    // stayed grey: the viewfinder and the viewer both held colour, and swiping up to the grid
    // dropped it, so a wall of colour photographs was drawn in monochrome while the frame you
    // had just taken was in colour one flick below. Held on `active` so the pager keeping this
    // page composed behind the viewfinder (`beyondViewportPageCount = 1`) doesn't hold colour
    // for a screen nobody is looking at. `ColorMode` counts holders, so overlapping with the
    // camera's own hold across a swipe is fine.
    ColourEffect(enabled = active && colour != com.gios.lightcamera.Colour.Off)

    val entries = remember(photos) { flatten(photos) }

    /**
     * **Selection mode, entered by holding a photograph.**
     *
     * Held as a set of MediaStore ids rather than of [Photo] objects: the roll is re-read
     * whenever the content observer fires, which replaces every object in the list, and a
     * selection compared by identity would empty itself the moment a new photograph was taken
     * while you were choosing. `rememberSaveable` so a rotation or a brief trip to another app
     * doesn't lose a set of eight ticks.
     *
     * An empty set is not selection mode — the mode *is* having something selected, so
     * unticking the last photograph leaves rather than stranding you in an empty toolbar with
     * a Cancel button.
     */
    var selected by rememberSaveable { mutableStateOf(setOf<Long>()) }
    val selecting = selected.isNotEmpty()
    // The roll is re-read on every media change, so a selected photograph that has since been
    // deleted (by this app or another) has to drop out of the set rather than being sent as a
    // URI that no longer resolves.
    LaunchedEffect(photos) {
        if (selected.isEmpty()) return@LaunchedEffect
        val alive = photos.mapTo(HashSet()) { it.id }
        val kept = selected.intersect(alive)
        if (kept.size != selected.size) selected = kept
    }
    // Back leaves selection before it leaves the roll — the same expectation as every gallery.
    // Gated on `active` as well: the pager keeps this page composed while the viewfinder is
    // showing (`beyondViewportPageCount = 1`), so without it a left-over selection would swallow
    // the back press on the camera page and nothing visible would happen.
    BackHandler(enabled = selecting && active) { selected = emptySet() }

    // Trashing through the system dialog, the same way the viewer does: the roll shows photos
    // this app did not create, so deleting must ask. The sender is launched once per tap and
    // the roll refreshes itself on the way back in.
    val scope = rememberCoroutineScope()
    val trash = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { scope.launch { vm.refreshRoll() } }

    val gridState = rememberLazyGridState()
    WheelScroll(gridState, active = active, reverse = true)

    // **Jump to the newest photograph whenever the roll comes into view.** Without this the grid
    // stays wherever it was last scrolled to — often a day or a week ago — because the pager keeps
    // it composed while the camera is showing. Scrolling to item 0 with reverseLayout puts the
    // newest frame at the bottom, right against the viewfinder, which is where a camera's roll
    // belongs.
    LaunchedEffect(active) {
        if (active && entries.isNotEmpty()) gridState.scrollToItem(0)
    }

    // **The thumbnails turn, the grid does not.** Turning the whole screen meant the header, the
    // scroll direction and every control moved the moment you tilted the phone, which is not what a
    // gallery does. So the layout stays put in the phone's own frame and each frame's contents come
    // round instead — the same split the viewfinder uses, where the chrome is pinned to the phone and
    // the image is upright in the world.
    val quarter = rememberDeviceQuarter(active = active)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            !mediaGranted -> Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LightText("The roll is your photos.", LightTextVariant.Subheading)
                LightText(
                    "Roll shows the camera roll itself rather than keeping a second album of its own, so it needs to read your photos.",
                    LightTextVariant.Paragraph,
                    lighten = true,
                    modifier = Modifier.padding(top = 10.dp),
                )
                LightText(
                    "ALLOW",
                    LightTextVariant.Button,
                    modifier = Modifier.padding(top = 24.dp).lightClickable { onRequestMedia() },
                )
            }

            entries.isEmpty() && !loading -> EmptyState(
                text = if (scope == RollScope.Favourites) {
                    "Nothing starred yet."
                } else {
                    "Nothing on the roll yet."
                },
                detail = if (scope == RollScope.Favourites) {
                    "Open a photograph and tap the star."
                } else {
                    "Swipe up and take a photograph."
                },
            )

            // **The newest photograph goes bottom right.**
            //
            // `reverseLayout` fills from the bottom, which is what puts the newest frame against
            // the viewfinder — but rows still fill left to right, so the newest landed
            // bottom-*left* and the corner nearest your thumb held the third-newest. Laying the
            // grid out right-to-left fixes it in one line: the head of the list takes the
            // bottom-right cell and the roll fills leftwards and upwards from there, which is
            // also the direction a contact sheet fills.
            else -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 54.dp,
                        bottom = 40.dp,
                        start = 1.dp,
                        end = 1.dp,
                    ),
                ) {
                items(
                    count = entries.size,
                    key = { index ->
                        when (val entry = entries[index]) {
                            is RollEntry.Frame -> entry.photo.id
                            is RollEntry.Day -> "day-${entry.label}"
                        }
                    },
                    span = { index ->
                        if (entries[index] is RollEntry.Day) {
                            GridItemSpan(maxLineSpan)
                        } else {
                            GridItemSpan(1)
                        }
                    },
                ) { index ->
                    when (val entry = entries[index]) {
                        is RollEntry.Day -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Just the day. The count of photographs in it was the sort of
                            // number an interface offers because it happens to know it, not
                            // because anybody wanted it.
                            LightText(entry.label.uppercase(), LightTextVariant.Detail)
                        }

                        is RollEntry.Frame -> Thumb(
                            vm = vm,
                            photo = entry.photo,
                            quarter = quarter,
                            selected = entry.photo.id in selected,
                            // Dimmed only while choosing, so the grid looks untouched the rest of
                            // the time. A tick on every cell all the time is a gallery that always
                            // looks like it is in the middle of an operation.
                            selecting = selecting,
                            modifier = Modifier
                                .padding(1.dp)
                                .aspectRatio(1f)
                                .lightCombinedClickable(
                                    onLongClick = {
                                        // Holding always *adds*, never toggles: a long press on
                                        // something already ticked reads as "yes, this one", and
                                        // having it disappear is the sort of thing you have to
                                        // undo.
                                        selected = selected + entry.photo.id
                                    },
                                    onClick = {
                                        if (selecting) {
                                            selected = if (entry.photo.id in selected) {
                                                selected - entry.photo.id
                                            } else {
                                                selected + entry.photo.id
                                            }
                                        } else {
                                            onOpen(entry.photo)
                                        }
                                    },
                                ),
                        )
                    }
                }
                }
            }
        }

        // Top chrome. Over the oldest photographs on screen rather than the newest, which is
        // the right way round: the header is a label for the screen, not for a photo.
        //
        // `swallowTaps` because a background does not consume touches in Compose: taps on the bar
        // were falling straight through to whichever photograph happened to be underneath, so
        // reaching for settings opened a picture instead.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(colours.scrim)
                .swallowTaps()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                // The whole bar becomes the selection's, because while choosing, the scope
                // toggle and settings are not what the bar is for.
                ChromeIcon(icon = LightIcons.Close, onClick = { selected = emptySet() })
                Spacer(Modifier.weight(1f))
                LightText("${selected.size} SELECTED", LightTextVariant.Detail)
                Spacer(Modifier.weight(1f))
                ChromeIcon(
                    icon = LightIcons.Trash,
                    onClick = {
                        val chosen = photos.filter { it.id in selected }
                        if (chosen.isNotEmpty()) {
                            val sender = vm.trashRequest(chosen)
                            if (sender != null) {
                                trash.launch(IntentSenderRequest.Builder(sender).build())
                            } else {
                                vm.showNotice("Can't bin those")
                            }
                        }
                    },
                )
                ChromeIcon(
                    icon = LightIcons.Share,
                    onClick = {
                        // In roll order — newest first, as the grid shows them — so a set sent
                        // together arrives in the order it was looked at rather than in the
                        // order it happened to be tapped.
                        val chosen = photos.filter { it.id in selected }.take(SEND_LIMIT)
                        if (chosen.isNotEmpty()) {
                            onSend(chosen)
                            // **Cleared on the way out.** Left standing, the ticks were still
                            // there when the picker closed, and because holding a photograph
                            // *adds* rather than toggles, the next send would quietly include
                            // everything already sent — to a different person.
                            selected = emptySet()
                        }
                    },
                )
                return@Row
            }
            LightText("ROLL", LightTextVariant.Detail)
            Spacer(Modifier.weight(1f))
            LightText(
                text = scope.label.uppercase(),
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier
                    .lightClickable {
                        // Three now: everything, the camera roll, the starred ones. A tap walks them,
                        // which is the same shape as every other value in this app.
                        val all = RollScope.entries
                        vm.prefs.setScope(all[(all.indexOf(scope) + 1) % all.size])
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            ChromeIcon(icon = LightIcons.Settings, lighten = true, onClick = onOpenSettings)
        }

        // Bottom chrome, against the camera. An undeveloped roll lives here, because this is
        // the edge you cross on your way back to the shutter.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(colours.scrim),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val loaded = roll
            if (loaded != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        "ROLL ${loaded.number} · ${loaded.shot} OF ${loaded.length}",
                        LightTextVariant.Superfine,
                    )
                    Spacer(Modifier.weight(1f))
                    LightText(
                        if (loaded.shot == 0) "UNLOAD" else "DEVELOP",
                        LightTextVariant.Superfine,
                        modifier = Modifier.lightClickable { vm.developRoll() },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onBackToCamera() }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightIcon(LightIcons.Down, size = 9.dp, tint = colours.contentSecondary)
                LightText("  CAMERA", LightTextVariant.Superfine, lighten = true)
            }
        }
    }
}

@Composable
private fun Thumb(
    vm: CameraViewModel,
    photo: Photo,
    quarter: Int,
    modifier: Modifier,
    selected: Boolean = false,
    selecting: Boolean = false,
) {
    val colours = LightThemeTokens.colors
    var image by remember(photo.id) {
        mutableStateOf(vm.thumbs.cached(photo.id)?.asImageBitmap())
    }
    LaunchedEffect(photo.id) {
        if (image != null) return@LaunchedEffect
        image = vm.thumbs.thumbnail(photo.uri, photo.id, THUMB_PX)?.asImageBitmap()
    }
    Box(modifier = modifier.background(colours.rule)) {
        val bitmap: ImageBitmap? = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = photo.name,
                contentScale = ContentScale.Crop,
                // The cell is square, so the picture inside it can be turned without swapping the
                // box or clipping anything — a square rotated a quarter turn is the same square.
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = quarter.toFloat()
                        // Unpicked frames recede while choosing. Greyscale has no tint to select
                        // with, so the selection is carried by what is *not* selected going dim —
                        // the same inversion trick the rest of the app uses for state.
                        alpha = if (selecting && !selected) UNSELECTED_ALPHA else 1f
                    },
            )
        }
        // **A clip says so, and says how long.** Without this a video is a still of its first
        // frame — indistinguishable from a photograph in the grid, which is most of why the
        // recorder looked broken even once the query found them.
        if (photo.isVideo) {
            LightText(
                text = photo.durationLabel(),
                variant = LightTextVariant.Superfine,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(3.dp)
                    .background(colours.scrim)
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
        // The tick is chrome, so it stays pinned to the phone rather than turning with the
        // picture: a checkmark lying on its side reads as a glitch.
        if (selecting) {
            LightIcon(
                icon = if (selected) LightIcons.SelectOn else LightIcons.SelectOff,
                size = 14.dp,
                tint = if (selected) colours.content else colours.contentSecondary,
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
            )
        }
    }
}

/**
 * The most photographs one send carries.
 *
 * Every URI in the intent is a grant the system has to record and the whole thing crosses a
 * Binder transaction with a hard size limit, so a selection of several hundred fails as a
 * `TransactionTooLargeException` rather than as anything a user could interpret. Reaching this
 * takes fifty long presses, so capping is free insurance rather than a restriction.
 */
private const val SEND_LIMIT = 50

/** How far an unpicked frame recedes while choosing. Enough to read as "not this one", not so
 *  far that you can no longer tell what the photograph is. */
private const val UNSELECTED_ALPHA = 0.35f

/**
 * Photos to entries, newest first, with each day's heading *after* its photographs.
 *
 * Reversed by the layout, that reads as heading-then-photos. Doing it here rather than in the
 * list builder keeps the ordering decision in one place, where it can be reasoned about
 * without also thinking about spans and keys.
 */
private fun flatten(photos: List<Photo>): List<RollEntry> {
    if (photos.isEmpty()) return emptyList()
    val out = ArrayList<RollEntry>(photos.size + 16)
    var day = Long.MIN_VALUE
    var count = 0
    var pending = ArrayList<RollEntry.Frame>()

    fun flush() {
        if (pending.isEmpty()) return
        out += pending
        out += RollEntry.Day(DayLabels.label(day), count)
        pending = ArrayList()
        count = 0
    }

    photos.forEach { photo ->
        val photoDay = DayLabels.dayOf(photo.takenAt)
        if (photoDay != day) {
            flush()
            day = photoDay
        }
        pending += RollEntry.Frame(photo)
        count++
    }
    flush()
    return out
}

private const val THUMB_PX = 256
