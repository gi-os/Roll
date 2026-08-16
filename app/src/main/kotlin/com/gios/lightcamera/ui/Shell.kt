package com.gios.lightcamera.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gios.lightcamera.media.Photo
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.lightClickable
import kotlinx.coroutines.launch

/** What the roll needs to read: stills and clips are two tables with two permissions. */
private val MEDIA_PERMISSIONS = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
)

private const val PAGE_ROLL = 0
private const val PAGE_CAMERA = 1

/**
 * The whole app, which is two pages stacked vertically.
 *
 * **The roll sits above the viewfinder.** Pulling down on the camera brings it into view,
 * the way pulling down on a window blind brings the blind down: the photographs you have
 * already taken are behind the phone's top edge, and the gesture that reveals them is the
 * gesture that would physically move them into sight.
 *
 * That geometry is also why the grid is laid out in reverse ([RollScreen]). The newest photo
 * hangs immediately above the viewfinder and older ones run further up, so the roll is a
 * strip of film coming out of the camera and the resting position of the list is its bottom
 * edge — which is exactly where an upward swipe has nothing left to scroll and hands the
 * gesture back to the pager. Lay it out the usual way and the only route back to the camera
 * is scrolling to the end of your entire photo library.
 */
@Composable
fun Shell(
    vm: CameraViewModel,
    /** True when another app asked for a single photo; there is no roll to browse then. */
    captureRequest: Boolean,
) {
    ShellContent(vm = vm, captureRequest = captureRequest)
}

@Composable
private fun ShellContent(vm: CameraViewModel, captureRequest: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // **Both halves, and images alone is not enough.** The roll reads stills and clips out of two
    // MediaStore tables, and each table has its own granular permission since API 33. Held to
    // images only, every video not recorded by this app is missing from the roll and refuses to
    // play in the viewer — which looks like the roll dropping clips rather than like a permission.
    var mediaGranted by remember {
        mutableStateOf(
            MEDIA_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] ?: cameraGranted
        // **Re-queried rather than read out of the result map.** The map omits anything that was
        // already granted, so folding it into the old value can only ever keep a false false — and
        // since the launcher is only reached when `mediaGranted` is false, granting both would have
        // left the roll permanently in its refusal state until the app was killed and relaunched.
        // Asking the system is correct for the omitted case and the refused one alike.
        mediaGranted = MEDIA_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (mediaGranted) vm.onPermissionsChanged()
    }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (!cameraGranted) add(Manifest.permission.CAMERA)
            if (!mediaGranted) addAll(MEDIA_PERMISSIONS)
        }
        if (wanted.isNotEmpty()) ask.launch(wanted.toTypedArray())
    }

    LaunchedEffect(mediaGranted) {
        if (mediaGranted) vm.startObservingMedia()
    }

    // "Whole app" holds colour for the roll as well, which is what you want if you are
    // looking through photographs rather than taking them.
    val colour by vm.prefs.colour.collectAsState()
    ColourEffect(enabled = colour == com.gios.lightcamera.Colour.Always)

    if (!cameraGranted) {
        Refusal(
            "Roll needs the camera.",
            "Grant it and the viewfinder appears.",
            onRetry = { ask.launch(arrayOf(Manifest.permission.CAMERA)) },
        )
        return
    }

    var viewing by remember { mutableStateOf<Photo?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }

    /**
     * The photographs waiting for a recipient.
     *
     * Hoisted to here rather than kept inside the viewer or the roll because both of them raise
     * it — one photograph from the viewer, a selected set from the roll — and the picker has to
     * be drawn above both, on top of the pager. Empty means closed.
     */
    var sending by remember { mutableStateOf<List<Photo>>(emptyList()) }

    val pager = rememberPagerState(initialPage = PAGE_CAMERA, pageCount = { 2 })

    // Back to the viewfinder when the camera key brings the app forward — and back *out* of whatever was
    // over it, since a photograph or the settings covering the picture is the same problem as being on the
    // wrong page. Collecting a shared flow rather than keying an effect on state: this must fire on the
    // second press and must not fire on composition.
    LaunchedEffect(Unit) {
        vm.goToCamera.collect {
            viewing = null
            settingsOpen = false
            if (pager.currentPage != PAGE_CAMERA) pager.scrollToPage(PAGE_CAMERA)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (captureRequest) {
            // One photo for somebody else. No roll, no settings, no way to wander off.
            CameraScreen(
                vm = vm,
                active = true,
                onOpenSettings = {},
            )
        } else {
            VerticalPager(
                state = pager,
                pageSize = PageSize.Fill,
                // Keep both pages composed: the camera takes a few hundred milliseconds to
                // rebind, and a viewfinder that has to warm up every time you glance at the
                // roll is a viewfinder you stop trusting to be ready.
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    PAGE_ROLL -> RollScreen(
                        vm = vm,
                        active = pager.currentPage == PAGE_ROLL,
                        mediaGranted = mediaGranted,
                        onRequestMedia = { ask.launch(MEDIA_PERMISSIONS) },
                        onOpen = { viewing = it },
                        onOpenSettings = { settingsOpen = true },
                        onBackToCamera = {
                            scope.launch { pager.animateScrollToPage(PAGE_CAMERA) }
                        },
                        onSend = { sending = it },
                    )

                    else -> CameraScreen(
                        vm = vm,
                        active = pager.currentPage == PAGE_CAMERA && viewing == null && !settingsOpen,
                        onOpenSettings = { settingsOpen = true },
                    )
                }
            }
        }

        AnimatedVisibility(visible = viewing != null, enter = fadeIn(), exit = fadeOut()) {
            val photo = viewing
            if (photo != null) {
                ViewerScreen(
                    vm = vm,
                    initial = photo,
                    onClose = { viewing = null },
                    onSend = { sending = it },
                )
            }
        }

        AnimatedVisibility(visible = settingsOpen, enter = fadeIn(), exit = fadeOut()) {
            SettingsScreen(vm = vm, onClose = { settingsOpen = false })
        }

        // Above the viewer, so sending from a photograph leaves the photograph behind it and
        // closing the picker lands back on it rather than on the roll.
        AnimatedVisibility(visible = sending.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            val photos = sending
            if (photos.isNotEmpty()) {
                val recents by vm.prefs.recentRecipients.collectAsState()
                SendSheet(
                    photos = photos,
                    recentKeys = recents,
                    onRemember = { vm.prefs.rememberRecipient(it) },
                    onNotice = { vm.showNotice(it) },
                    onClose = { sending = emptyList() },
                )
            }
        }

        /**
         * **The one place a notice is drawn.**
         *
         * It used to live inside `CameraScreen`, which is a page of the pager — so every message
         * raised from the viewer, the settings screen or the send picker was posted onto a surface
         * covered by an opaque full-screen overlay. All four of the picker's failure paths ("no
         * way to reach Alex", "nothing on the phone takes photos") were therefore invisible, and
         * a send that failed was indistinguishable from a tap that didn't register. Drawn here it
         * is above everything, which is what a notice is for.
         */
        val notice by vm.notice.collectAsState()
        Notice(
            text = notice,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
        )

        val developed by vm.developed.collectAsState()
        AnimatedVisibility(visible = developed != null, enter = fadeIn(), exit = fadeOut()) {
            val result = developed
            if (result != null) {
                ContactSheet(vm = vm, developed = result, onClose = { vm.dismissDeveloped() })
            }
        }
    }
}

@Composable
private fun Refusal(title: String, detail: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText(title, LightTextVariant.Subheading, align = TextAlign.Center)
        LightText(
            detail,
            LightTextVariant.Paragraph,
            lighten = true,
            align = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        LightText(
            "ASK AGAIN",
            LightTextVariant.Button,
            modifier = Modifier
                .padding(top = 28.dp)
                .lightClickable { onRetry() },
        )
    }
}
