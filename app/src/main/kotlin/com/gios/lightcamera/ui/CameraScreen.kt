package com.gios.lightcamera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas as AndroidCanvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gios.light.common.hw.WheelTurns
import com.gios.lightcamera.BandSlot
import com.gios.lightcamera.filter.Adjust
import com.gios.lightcamera.filter.Grade
import com.gios.lightcamera.CaptureMode
import com.gios.lightcamera.Chrome
import com.gios.lightcamera.Colour
import com.gios.lightcamera.SelfTimer
import com.gios.lightcamera.camera.AfState
import com.gios.lightcamera.camera.CameraEngine
import com.gios.lightcamera.camera.FaceMapper
import com.gios.lightcamera.camera.FlashMode
import com.gios.lightcamera.camera.FrameAspect
import com.gios.lightcamera.camera.PuriArt
import com.gios.lightcamera.camera.PuriStrip
import com.gios.lightcamera.camera.Zooms
import com.gios.lightcamera.filter.FaceQuad
import com.gios.lightcamera.filter.FaceQuads
import android.graphics.RenderEffect
import android.os.SystemClock
import com.gios.lightcamera.filter.ShaderRuntime
import com.gios.lightcamera.hw.Binding
import com.gios.lightcamera.hw.Channel
import com.gios.lightcamera.hw.CameraKeyAdvice
import com.gios.lightcamera.hw.Controls
import com.gios.lightcamera.hw.DialAction
import com.gios.lightcamera.hw.PressAction
import com.gios.lightcamera.ocr.TextBoxes
import com.gios.lightcamera.qr.Codes
import com.gios.lightcamera.ui.theme.LightHaptics
import com.gios.lightcamera.ui.theme.LightIcons
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.LightThemeTokens
import com.gios.lightcamera.ui.theme.lightClickable
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay

/** How wide the strips of chrome down the left edge are. */
private val BAND = 54.dp

/**
 * The viewfinder, arranged the way LightOS's own camera is.
 *
 * The split that matters, and it took a screengrab of the real thing to see it: **the chrome is
 * written sideways and the picture is not**. In portrait the control band runs down the left
 * edge with `PHOTO ⌄` reading down it, while the image stays upright in the phone's own frame.
 * Turn the phone anticlockwise to shoot — the camera key comes round to the top edge, where a
 * shutter release belongs — and the band is along the bottom where a camera's controls are.
 *
 * So the band is wrapped in [HeldSideways] and the preview is left alone. An earlier version
 * rotated the whole app, which spun the image with it and turned the swipe down to the roll into
 * a sideways one.
 *
 * Nothing else is drawn over the picture: the band's strips take their own width out of the
 * left-hand side rather than floating, which is why there are no gradients anywhere here.
 */
@Composable
fun CameraScreen(
    vm: CameraViewModel,
    active: Boolean,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colours = LightThemeTokens.colors
    val engine = vm.engine

    val filter by vm.filter.collectAsState()
    val mode by vm.prefs.mode.collectAsState()
    val chrome by vm.prefs.chrome.collectAsState()
    val aspect by vm.prefs.aspect.collectAsState()
    val flash by vm.prefs.flash.collectAsState()
    val timer by vm.prefs.timer.collectAsState()
    val facePriority by vm.prefs.facePriority.collectAsState()
    val wheelEnabled by vm.prefs.wheelEnabled.collectAsState()
    val colour by vm.prefs.colour.collectAsState()
    val roll by vm.roll.collectAsState()
    val faces by engine.faces.collectAsState()
    val afState by engine.afState.collectAsState()
    val focusPoint by engine.focusPoint.collectAsState()
    val zoom by engine.zoom.collectAsState()
    val ev by engine.ev.collectAsState()
    val evRange by engine.evRange.collectAsState()
    val maxZoom by engine.maxZoom.collectAsState()
    val bandSlots by vm.prefs.bandSlots.collectAsState()
    val histogramOn by vm.prefs.histogram.collectAsState()
    // Read up here rather than beside the overlay that draws it: the exposure meter below has to
    // know a still is being held, and a `by` declared further down the function is not in scope.
    val held by vm.held.collectAsState()
    // The wheel's heartbeat for the meter: every channel turn nudges this, so the gauge block
    // below recomposes on the wheel's own authority and a Shutter or ISO turn (which changes no
    // other collected state) still moves its needle.
    val wheelTick by vm.wheelTick.collectAsState()

    // Latched across the capture and the save, and true for the shots that hold no frame — the
    // saving bar below reads this rather than `held`, so a flash shot is not a silent wait.
    val shooting by vm.shooting.collectAsState()
    val clippingOn by vm.prefs.clipping.collectAsState()
    val bindings by vm.prefs.bindings.collectAsState()
    val torch by engine.torch.collectAsState()
    val countdown by vm.countdown.collectAsState()
    val recording by engine.recording.collectAsState()
    val recordSeconds by vm.recordSeconds.collectAsState()
    val scanned by vm.scan.collectAsState()
    val page by vm.page.collectAsState()
    val pageTurn by vm.pageTurn.collectAsState()
    val pageFound by vm.pageFound.collectAsState()
    val pageSheet by vm.pageSheet.collectAsState()

    var frameWidth by remember { mutableStateOf(0) }
    var frameHeight by remember { mutableStateOf(0) }
    var gridOpen by remember { mutableStateOf(false) }
    var modeOpen by remember { mutableStateOf(false) }
    var puriOpen by remember { mutableStateOf(false) }
    val openStrip by vm.strip.collectAsState()
    val evOpen = openStrip == Strip.Exposure
    val zoomOpen = openStrip == Strip.Zoom
    var presetOpen by remember { mutableStateOf(false) }
    val grade by vm.prefs.grade.collectAsState()

    // Half press clears the frame. Everything that covers the viewfinder closes at once, because
    // half of them cover it completely and you cannot see which one you are dismissing.
    LaunchedEffect(Unit) {
        vm.dismissPanels.collect {
            modeOpen = false
            gridOpen = false
            puriOpen = false
            presetOpen = false
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE, and this is load-bearing rather than a compatibility hedge.
            // PERFORMANCE mode draws the camera into a SurfaceView, which the compositor hands
            // to the display on its own layer — a RenderEffect on that view filters nothing,
            // because the pixels never pass through the view hierarchy's draw. The TextureView
            // that COMPATIBLE uses is an ordinary hardware-layer view, so the shader applies.
            // Every filter in this app depends on it.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(previewView) { engine.bind(lifecycleOwner, previewView, flash) }

    // **The camera is released whenever the viewfinder is not the thing on screen.** `active` is already
    // false for the roll, the viewer and the settings — it was only ever used to stop *drawing*, which
    // left the sensor and the preview stream running behind a full-screen photograph. A short delay
    // before letting go, because flicking to the roll and back is a common gesture and a rebind mid-flick
    // would show a black frame.
    LaunchedEffect(active) {
        if (active) {
            engine.resume(flash)
        } else {
            delay(400)
            engine.release()
        }
    }

    // The microphone is asked for when you switch into video, never at the moment you press
    // record — a dialog in front of the thing you were filming is worse than silent footage.
    val askAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.audioGranted = granted }
    LaunchedEffect(mode) {
        if (mode != CaptureMode.Video) return@LaunchedEffect
        val has = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        vm.audioGranted = has
        if (!has) askAudio.launch(Manifest.permission.RECORD_AUDIO)
    }

    /* ---- colour, and saying so when it can't ---- */

    val wantsColour = active && colour != Colour.Off
    ColourEffect(enabled = wantsColour)
    LaunchedEffect(wantsColour) {
        if (!wantsColour) return@LaunchedEffect
        if (ColorMode.granted(context) || ColorMode.phoneIsColour(context)) return@LaunchedEffect
        // Nothing this app can do about it from in here, so say what will: the panel is a
        // colour panel and one adb line unlocks it.
        vm.showNotice("Colour needs an adb grant — see settings")
    }

    /* ---- grain that moves ---- */

    var seed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(filter, active) {
        if (!filter.animated || !active) return@LaunchedEffect
        while (true) {
            seed = Random.nextFloat() * 1000f
            delay(100)
        }
    }

    // The live filter, attached in exactly one place. In video it is forced off: a RenderEffect
    // is a property of the *view*, so it never reaches the recorded stream — a filtered preview
    // would be promising something the file wouldn't deliver.
    // **Simple has no filter, and cannot be talked into one.** Not merely hidden: the shutter writes the
    // sensor's JPEG untouched, so a filtered preview would be promising something the file would not
    // deliver — the same reason video forces it off.
    // QR joins them for a third reason: the analyser reads the camera's own frames, which a
    // `RenderEffect` never touches, so a dithered viewfinder would be showing you a picture the
    // decoder is not looking at — and half the filters here would make a code unreadable to a person
    // while it carried on scanning perfectly, which is a viewfinder that lies about why it failed.
    val liveFilter = if (mode == CaptureMode.Video || mode.isSimple || mode.isReader) {
        com.gios.lightcamera.filter.Filters.none
    } else {
        filter
    }
    // Purikura is the one filter that needs to know where the faces are, so the effect is rebuilt
    // when they move — which the detector publishes about fifteen times a second. Every other filter
    // keys on nothing that changes, so nothing extra happens for them.
    val faceQuads = if (liveFilter.facesAware) {
        FaceQuads.of(faces, frameWidth, frameHeight)
    } else {
        emptyList()
    }
    // `liveFilter` rather than `filter`: it is the one that has already had video, Simple and the
    // readers forced back to plain, which is exactly the set of modes where an adjustment would be
    // promising something the file would not deliver.
    val presetOffered = liveFilter.id == com.gios.lightcamera.filter.Filters.none.id &&
        mode != CaptureMode.Video && !mode.isSimple && !mode.isReader
    val puriFrameId by vm.prefs.puriFrame.collectAsState()
    val puriFaceStickers by vm.prefs.puriFaceStickers.collectAsState()
    val puriMarginStickers by vm.prefs.puriMarginStickers.collectAsState()
    val puriDates by vm.prefs.puriDate.collectAsState()
    val puriStripId by vm.prefs.puriStrip.collectAsState()
    val puriWash by vm.prefs.puriWash.collectAsState()
    val puriSkin by vm.prefs.puriSkin.collectAsState()
    val puriEyes by vm.prefs.puriEyes.collectAsState()
    val puriChin by vm.prefs.puriChin.collectAsState()
    val puriSlim by vm.prefs.puriSlim.collectAsState()
    val simpleOffered by vm.prefs.simpleMode.collectAsState()
    val puriSeed by vm.puriSeed.collectAsState()
    // Which way up the photograph will be, from the same number the shutter uses.
    val turn by vm.engine.previewRotation.collectAsState()
    // Peaking rides with zone focus rather than having a switch of its own: it exists to answer
    // "is this in focus", and with autofocus running the camera has already answered.
    val peaking by vm.engine.zoneFocus.collectAsState()
    val channel by vm.channel.collectAsState()
    val formats by vm.prefs.formats.collectAsState()
    val developing by vm.developing.collectAsState()
    val developingSince by vm.developingSince.collectAsState()
    val developEst by vm.developEstMs.collectAsState()
    val faults by vm.faults.collectAsState()
    // Named apart from the progress bar's own `inFlight` boolean further down — same word,
    // different question, and Kotlin resolves the nearer one silently.
    val capturing by vm.inFlight.collectAsState()
    val rawWanted = com.gios.lightcamera.media.CaptureFormat.Dng in formats
    LaunchedEffect(
        liveFilter,
        seed,
        frameWidth,
        frameHeight,
        faceQuads,
        puriWash,
        puriSkin,
        puriEyes,
        puriChin,
        puriSlim,
        turn,
        peaking,
    ) {
        val look = ShaderRuntime.effectFor(
            filter = liveFilter,
            width = frameWidth,
            height = frameHeight,
            seed = seed,
            faces = faceQuads,
            // The preview image is still in the panel's frame, so the shader needs to know how the
            // face is lying in it. The captured photograph is turned upright before the shader sees
            // it, which is why the shutter passes no turn at all.
            tune = vm.prefs.puriTune(turns = turn / 90),
            // And the same number again for the frame itself. Mirror, Kaleido and Datamosh
            // have a left and a right, and the panel image does not have the one the
            // photograph will — see Filters.TURN.
            turn = turn / 90,
        )
        // **Peaking goes on last, over the filter rather than under it.** It marks what the *lens*
        // is resolving, so in principle it belongs on the unfiltered frame — but a chain is one
        // effect and the edges survive every filter here, while running it first would mean the
        // filter then smearing the marks. It is also only ever on in zone focus: peaking with
        // autofocus running is a viewfinder full of marks telling you what the camera already did.
        val peak = if (peaking) {
            ShaderRuntime.peakingEffect(frameWidth, frameHeight)
        } else {
            null
        }
        previewView.setRenderEffect(
            when {
                peak != null && look != null -> RenderEffect.createChainEffect(peak, look)
                peak != null -> peak
                else -> look
            },
        )
    }
    DisposableEffect(Unit) { onDispose { previewView.setRenderEffect(null) } }

    /* ---- the wheel ---- */

    // **A bare turn walks the filters by default**, grid open or not — a dial that changes what the
    // photograph looks like earns every notch, and it is live in Simple too, where a turn steps up
    // into Pro and on into the filters. It is a default rather than a law now: see [Binding].
    //
    // **An open strip takes the bare wheel, whatever the wheel is bound to.** A strip is a value
    // you came here to set and the wheel is the best control on the phone for setting it; walking
    // the filters underneath an open exposure strip was never what the turn meant.
    //
    // `bindings` is in the key so a change in settings re-arms these routes rather than waiting for
    // the next recomposition to happen for some other reason.
    val bareDial = remember(bindings, openStrip) {
        when (openStrip) {
            Strip.Exposure -> DialAction.Exposure
            Strip.Zoom -> DialAction.Zoom
            null -> vm.prefs.dialFor(Binding.WheelTurn)
        }
    }
    val heldDial = remember(bindings) { vm.prefs.dialFor(Binding.WheelPressTurn) }
    // **One binding is enough.** The first version routed turns by the *turn* binding alone, so
    // pointing the click at Channel produced a click that "switched" and a spin that still walked
    // the filters — the two gestures disagreed about what the wheel was. If either gesture is
    // pointed at the channel system, the whole wheel belongs to it.
    val clickIsChannel = bindings[Binding.WheelClick] == PressAction.Channel.name
    val channelWheel = bareDial == DialAction.Channel || clickIsChannel
    // Named on the panel only when the wheel actually cycles: with a fixed binding there is
    // nothing to disambiguate, and an unobstructed viewfinder is worth more than a label.
    val showChannel = channelWheel

    // **The dial lock.** The wheel is shared with the rest of the phone and turns in a pocket, so
    // with the setting on the dial boots asleep and a click on the wheel wakes it. The setting is
    // the master switch and it is off until it is asked for — see `Prefs.dialLock` for why that is
    // not a detail. Nothing is looked up per binding any more: while the setting is on the click is
    // claimed by `Controls.pressNow`, so there is no mapping in which the dial can be locked with
    // nothing able to open it.
    val dialLocked by vm.dialLocked.collectAsState()
    val dialLockOn by vm.prefs.dialLock.collectAsState()
    val dialLive = Controls.dialLive(
        locked = dialLocked,
        lockOn = dialLockOn,
        stripOpen = openStrip != null,
    )

    // **Unarmed for the filters, armed for a value.** Each filter notch has to count — None is
    // three notches wide on the track so a stray one lands somewhere harmless — whereas exposure
    // and zoom are values you rack through, where swallowing the overflow makes the dial feel
    // stuck. So the arming follows the action rather than the control.
    // **Nothing scrolls while the Purikura menu is open.** The menu is a list of five things you are
    // reading; a wheel that walked the filters underneath it would change the picture behind the menu
    // and take Purikura away, closing the menu you were using.
    //
    // The lock is read **inside** the route rather than switching it off, so that a turn against a
    // locked dial is still received and can be answered. A route that simply went inactive would
    // give a wheel that silently does nothing, which is the fault this feature exists to fix rather
    // than a way of fixing it.
    // **Armed by the effect, not by the binding.** Each filter notch has to count — None is three
    // notches wide on the track so a stray one lands somewhere harmless — whereas exposure, zoom
    // and focus are values you rack through, where swallowing the overflow makes the dial feel
    // stuck. With the wheel on a channel the binding no longer says which of those it is, so
    // asking the binding armed the filter track and made it skip.
    val picking by vm.picking.collectAsState()
    // Picking a channel and walking the filters are both read one name at a time, so both take one
    // step per gesture; a value is racked through, so overflow notches must not be swallowed.
    val oneStepPerGesture = channelWheel && (picking || channel == Channel.Filter) ||
        !channelWheel && bareDial == DialAction.Filter
    WheelTurns(
        active = active && wheelEnabled && !puriOpen &&
            (channelWheel || bareDial != DialAction.Nothing),
        armed = !oneStepPerGesture,
    ) { notches ->
        vm.touchLadder()
        if (dialLive) {
            if (channelWheel) vm.channelTurn(notches) else turnDial(vm, engine, bareDial, notches)
        } else {
            // Every locked turn, and it does not stack: the notice is one line of state that
            // replaces itself, so holding the wheel over keeps it up rather than queueing a
            // second copy behind the first.
            vm.sayDialLocked()
        }
    }
    // **Press-and-turn ignores the lock.** You cannot make this gesture in a pocket — it needs the
    // wheel held in — so there is nothing here to protect against, and locking it would take
    // exposure away from the one turn that was never the problem.
    WheelTurns(
        active = active && wheelEnabled && !puriOpen && heldDial != DialAction.Nothing,
        armed = heldDial != DialAction.Filter,
        pressed = true,
    ) { notches ->
        vm.touchLadder()
        turnDial(vm, engine, heldDial, notches)
    }

    /* ---- the shutter blink ---- */

    var blink by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        vm.shutterTick.collect {
            blink = 1f
            repeat(4) {
                delay(16)
                blink -= 0.25f
            }
            blink = 0f
        }
    }

    val levelOn by vm.prefs.level.collectAsState()
    val tilt by rememberTilt(active = active && levelOn)
    val levelVisible = rememberLevelVisible(tilt, enabled = active && levelOn)
    val priority = remember(faces, frameWidth, frameHeight, facePriority) {
        if (facePriority) FaceMapper.priority(faces, frameWidth, frameHeight) else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(Modifier.fillMaxSize()) {
            /* ---------------- the band, written sideways ---------------- */
            Box(
                Modifier
                    .width(BAND)
                    .fillMaxHeight(),
            ) {
                HeldSideways {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // **The band's first slot is Adjust, not the album.** The roll is already
                        // one swipe from the viewfinder and that is how it is reached in practice,
                        // so the icon was spending a permanent slot on a shortcut to a gesture.
                        // The adjustments had no home at all, which is the worse problem.
                        // **The wheel's channel, where Adjust used to sit.** Adjust moved into
                        // the mode dropdown; this slot now names what the wheel holds and a tap
                        // opens the pick — the same act as clicking the wheel, for thumbs. When
                        // the wheel is on fixed bindings the old Adjust chip keeps its seat.
                        if (channelWheel) {
                            val channelLocked by vm.channelLocked.collectAsState()
                            // An icon, not a word: the meter names the values, so the button only
                            // has to say which dial — and whether it is held. Solid means locked,
                            // lightened means free; a tap toggles, and the wheel click also
                            // unlocks. Shutter, ISO and zoom borrow glyphs until they earn their
                            // own — the mapping is one place, here.
                            ChromeIcon(
                                icon = when (channel) {
                                    Channel.Filter -> LightIcons.Filter
                                    Channel.Exposure -> LightIcons.Exposure
                                    Channel.Shutter -> LightIcons.Camera
                                    Channel.Iso -> LightIcons.Circle
                                    Channel.Focus -> LightIcons.Crosshair
                                    Channel.Zoom -> LightIcons.Zoom
                                },
                                lighten = !channelLocked,
                                onClick = { vm.toggleChannelLock() },
                            )
                        } else {
                            ChromeIcon(
                                icon = LightIcons.List,
                                onClick = { if (presetOffered) presetOpen = !presetOpen },
                                lighten = !presetOffered,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // The stock camera's "PHOTO ⌄": what the camera is set to, and a
                        // chevron that opens the picker.
                        Row(
                            modifier = Modifier
                                .lightClickable { modeOpen = !modeOpen }
                                .padding(horizontal = 6.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // **In Pro and Selfie the slot names the filter, not the mode.** "PRO" is a label for a
                            // thing you already know — you can see the chrome — whereas which filter is on is
                            // the one piece of state you cannot read off the picture with certainty, and it is
                            // the thing the wheel changes. Selfie shares the same need: the wheel cycles filters
                            // and the active one is invisible without the label. Video and Simple keep their
                            // own names, because in those the mode *is* the news.
                            LightText(
                                // **The filter's name, unless there isn't one to report.** Which
                                // filter is on is the one piece of state you cannot read off the
                                // picture with certainty, so in Pro the slot names it. But the first
                                // slot is Preset, and "PRESET" sitting in the band says nothing —
                                // it is the default, and whether anything is set is the Adjust
                                // chip's job to show. So that case falls back to the mode.
                                text = if ((mode == CaptureMode.Photo || mode == CaptureMode.Selfie) && !presetOffered) {
                                    filter.label.uppercase()
                                } else {
                                    mode.bandLabel
                                },
                                variant = LightTextVariant.Button,
                                align = TextAlign.Center,
                            )
                            Spacer(Modifier.width(7.dp))
                            Chevron(pointingUp = modeOpen)
                        }
                        Spacer(Modifier.weight(1f))
                        // The Purikura chip, and only while Purikura is on. It opens the menu rather
                        // than stepping the frame: there are fourteen frames, two kinds of sticker, a
                        // date and a strip layout behind it, and a chip that cycled one of those and
                        // hid the rest would be a worse version of both.
                        if (liveFilter.facesAware) {
                            Row(
                                modifier = Modifier
                                    .lightClickable { puriOpen = !puriOpen }
                                    .padding(horizontal = 6.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LightText(
                                    // "Options", because that is what is behind it: a frame, two kinds of
                                    // sticker, a date, a strip and five parts of the look.
                                    text = "OPTIONS",
                                    variant = LightTextVariant.Button,
                                    align = TextAlign.Center,
                                )
                                Spacer(Modifier.width(7.dp))
                                Chevron(pointingUp = puriOpen)
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        // **The same slot, and in QR it is the torch.** A flash mode is a property
                        // of a capture and QR takes none, so the control would be dead there — while
                        // the thing you actually want in a dim restaurant, a lamp held on the code,
                        // has no home in the chrome at all. Same icon, same place, and it is on or
                        // off rather than a three-way cycle because a torch has two states.
                        if (mode.isScan) {
                            ChromeIcon(
                                icon = if (torch) LightIcons.FlashOn else LightIcons.FlashOff,
                                lighten = !torch,
                                onClick = { engine.toggleTorch() },
                            )
                        } else {
                            ChromeIcon(
                                icon = when (flash) {
                                    FlashMode.Off -> LightIcons.FlashOff
                                    FlashMode.On -> LightIcons.FlashOn
                                    FlashMode.Auto -> LightIcons.FlashAuto
                                },
                                lighten = flash == FlashMode.Off,
                                onClick = {
                                    vm.prefs.setFlash(
                                        when (flash) {
                                            FlashMode.Off -> FlashMode.Auto
                                            FlashMode.Auto -> FlashMode.On
                                            FlashMode.On -> FlashMode.Off
                                        },
                                    )
                                },
                            )
                        }
                        // **The two free slots.** Exposure and nothing by default, which is exactly
                        // the row this replaced. Everything about which control goes here is in
                        // [BandSlot]; this only places them.
                        bandSlots.forEach { slot ->
                            BandSlotControl(
                                slot = slot,
                                mode = mode,
                                open = openStrip,
                                ev = ev,
                                zoom = zoom,
                                timer = timer,
                                aspect = aspect,
                                chrome = chrome,
                                zoneOn = peaking,
                                rawOn = rawWanted,
                                onToggleZone = { vm.prefs.setZoneFocus(!peaking) },
                                onToggleRaw = { vm.toggleRaw() },
                                onPress = { vm.press(it) },
                                onCycleTimer = { vm.cycleTimer() },
                                onCycleAspect = {
                                    val all = FrameAspect.entries
                                    vm.prefs.setAspect(all[(all.indexOf(aspect) + 1) % all.size])
                                },
                                onCycleGrid = {
                                    val all = Chrome.entries
                                    vm.prefs.setChrome(all[(all.indexOf(chrome) + 1) % all.size])
                                },
                            )
                        }
                    }
                }
            }

            /* ---------------- the picture, upright ---------------- */
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds(),
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged {
                            frameWidth = it.width
                            frameHeight = it.height
                            vm.onViewSized(it.width, it.height)
                        }
                        .viewfinderGestures(
                            enabled = active && !puriOpen,
                            onTapFocus = { x, y ->
                                // A buzz for the *ask*. The buzz and beep for the lens landing
                                // come off the camera's own AF result, in the view model.
                                LightHaptics.advance(context)
                                engine.tapFocus(x, y)
                            },
                            onDoubleTap = { vm.flipLens() },
                            onFilterStep = { vm.stepFilter(it) },
                            // The ratio is applied to the zoom the pinch started from, so the
                            // gesture is absolute rather than accumulating drift over a long one.
                            onPinchStart = { engine.zoom.value },
                            onPinch = { engine.setZoom(it) },
                        ),
                )

                // **The frame, the stickers and the date, live.** Rendered by the same
                // `PuriArt.draw` the shutter calls, from the same seed, so this is not an
                // impression of the photograph — it is the photograph's furniture, drawn once into a
                // bitmap and laid over the preview.
                //
                // Half resolution, because it is redrawn whenever a face moves and a full-panel
                // ARGB bitmap fifteen times a second is not a thing to do to a phone. Everything in
                // it is vector work scaled from the short edge, so scaling the result back up costs
                // a little softness on a hairline and nothing else.
                //
                // The face positions are quantised to fiftieths before they key the redraw, or the
                // detector's jitter alone would rebuild this constantly while nothing visibly moved.
                if (liveFilter.facesAware && frameWidth > 0 && frameHeight > 0) {
                    val settled = faceQuads.map { q ->
                        listOf(q.cx, q.cy, q.hw, q.hh).map { (it * 50f).toInt() }
                    }
                    // **Drawn the way up the photograph will be, then turned back to face you.**
                    // Hold the phone sideways and the file comes out landscape, so the frame's bands
                    // run along its long edges and the date reads horizontally across the bottom of
                    // it. Drawing the overlay in the panel's portrait space instead would put the date
                    // up the side of the finished photograph — and, worse, would show you one thing
                    // and save another.
                    val sideways = turn == 90 || turn == 270
                    val overlay = remember(
                        puriFrameId,
                        puriSeed,
                        puriFaceStickers,
                        puriMarginStickers,
                        puriDates,
                        settled,
                        frameWidth,
                        frameHeight,
                        turn,
                    ) {
                        // Half resolution: this is redrawn whenever a face moves, and a full-panel
                        // ARGB bitmap fifteen times a second is not a thing to do to a phone.
                        val half = { n: Int -> (n / 2).coerceAtLeast(1) }
                        val ow = if (sideways) half(frameHeight) else half(frameWidth)
                        val oh = if (sideways) half(frameWidth) else half(frameHeight)
                        val bitmap = createBitmap(ow, oh)
                        PuriArt.draw(
                            canvas = AndroidCanvas(bitmap),
                            w = ow,
                            h = oh,
                            // Random resolved from the same seed the shutter will use, so the frame
                            // you are looking at is the frame you are about to get.
                            frame = PuriArt.resolveFrame(puriFrameId, puriSeed),
                            plan = PuriArt.plan(
                                seed = puriSeed,
                                // Faces are in panel space, so they turn with everything else.
                                faces = faceQuads.map { FaceQuads.rotated(it, turn) },
                                faceStickers = puriFaceStickers,
                                marginStickers = puriMarginStickers,
                                dateId = puriDates,
                            ),
                            millis = System.currentTimeMillis(),
                        )
                        bitmap.asImageBitmap()
                    }
                    // Undo the turn for display: the photograph will be rotated by `turn`, so showing
                    // the same thing on an unrotated panel means rotating the overlay the other way.
                    RotatedToDevice((360 - turn) % 360, opaque = false) {
                        Image(
                            bitmap = overlay,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                FrameOverlay(
                    // Simple draws no grid, and neither does QR: a rule-of-thirds grid is for
                    // composing, and in QR the only thing on screen that should draw the eye is the
                    // window you are meant to put the code in.
                    chrome = if (mode.isSimple || mode.isReader) Chrome.Clean else chrome,
                    faces = faces,
                    priority = priority,
                    afState = afState,
                    focusPoint = focusPoint,
                    tilt = tilt,
                    levelVisible = levelVisible,
                    turn = turn,
                    // **Metered only while the viewfinder is live and unobstructed.** The reading
                    // costs a panel readback three times a second, and a frozen frame, an open menu
                    // or a backgrounded camera all mean nobody is composing — so the loop stops
                    // rather than measuring a still picture over and over.
                    luma = rememberLuma(
                        engine = engine,
                        active = active && (histogramOn || clippingOn) &&
                            !puriOpen && !gridOpen && !modeOpen && held == null,
                    ),
                    histogram = histogramOn,
                    clipping = clippingOn,
                    modifier = Modifier.fillMaxSize(),
                )

                // **The held frame, over the live preview.** While a still is being made the viewfinder shows
                // what you framed rather than carrying on live — the moment reads as taken, and the second and
                // a half becomes "the file is being written" rather than "the camera has not answered". It is
                // replaced by the real photograph the instant that exists.
                //
                // Under it, a bar timed to how long stills have *actually* been taking on this phone.
                // Determinate on purpose: a bar that arrives at about the right moment feels far shorter than
                // a spinner, and nothing feels longer than one that stalls near the end. It stops at nine
                // tenths — the last tenth belongs to the photograph arriving.
                val stillMs by vm.stillMs.collectAsState()
                val heldFrame = held
                if (heldFrame != null) {
                    val shot = remember(heldFrame) { heldFrame.asImageBitmap() }
                    Image(
                        bitmap = shot,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // **The bar is tied to the shutter, not to the held frame.** It used to be nested
                // inside the `held != null` branch, so the two arrived and left together — which
                // was fine until there were shots that hold nothing. With the flash on the frame is
                // deliberately *not* frozen (the preview grab predates the flash and would be a
                // plainly wrong picture), and `previewView.bitmap` can hand back null whenever the
                // panel is not streaming. Either way the still took its usual second and a half
                // with nothing on screen saying so: press, then nothing, then a photograph.
                //
                // `shooting` is latched across the capture *and* the save, so it covers the whole
                // wait whether or not there is a frame over the preview. Suppressed during a
                // countdown, because the Purikura strip keeps `shooting` up across all four frames
                // and the number on screen is already the answer to "what is it doing".
                val inFlight = heldFrame != null || (shooting && countdown == null)
                if (inFlight) {
                    var progress by remember(heldFrame, shooting) { mutableFloatStateOf(0f) }
                    LaunchedEffect(heldFrame, shooting) {
                        val expected = stillMs.coerceIn(300L, 4_000L)
                        val started = System.currentTimeMillis()
                        while (progress < 0.9f) {
                            delay(40)
                            progress = ((System.currentTimeMillis() - started).toFloat() / expected)
                                .coerceAtMost(0.9f)
                        }
                    }
                    Canvas(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(2.dp),
                    ) {
                        drawRect(
                            color = Color.White,
                            size = Size(size.width * progress, size.height),
                        )
                    }
                }

                // The scanning window. Drawn only while nothing has been found, so the moment a code
                // lands the marks come off and the sheet is the only thing on screen.
                if (mode.isScan && scanned == null) {
                    ScanWindow(Modifier.fillMaxSize())
                }

                // The same marks in Text mode, because it is the same act: you are framing a thing
                // rather than composing a picture, and the corners are what tell you so. Gone the
                // moment there is a reading, so the sheet is the only thing on screen.
                if (mode.isText && page == null) {
                    ScanWindow(Modifier.fillMaxSize())
                }

                if (blink > 0f) {
                    Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black.copy(alpha = blink)) }
                }

                if (countdown != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LightText("$countdown", LightTextVariant.Title)
                    }
                }

                // **Only what is abnormal.** The `AF-S` badge and the filter name are gone: the
                // focus mark already says what focus is doing, and the picture already shows what
                // the filter is doing. A label naming a thing you can see is a label in the way.
                //
                // What is left is state you could not otherwise know: that it is recording, that
                // the torch is on, that the lens is zoomed, that exposure is pushed, that a timer
                // is armed. Each disappears the moment it goes back to normal.
                //
                // **These turn with the phone, and the band does not.** The band is pinned sideways
                // because that is where a camera's controls belong once you have turned the phone
                // anticlockwise to shoot. These are not controls, they are five words you read —
                // `TORCH`, `3.5x`, `EV +1.0` — and words on their side while you are shooting
                // landscape are words you stop and tilt your head at. Same argument as the scan
                // sheet: [RotatedToDevice] off the accelerometer, with the 60° of hysteresis in
                // [rememberDeviceQuarter] so they do not flip while you are composing. Upright in
                // portrait, upright held sideways, upright either way up.
                RotatedToDevice(quarter = rememberDeviceQuarter(active = active), opaque = false) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (recording) {
                            RecordDot()
                            LightText(
                                " ${"%d:%02d".format(recordSeconds / 60, recordSeconds % 60)}",
                                LightTextVariant.Detail,
                            )
                        }
                        if (torch) {
                            LightText(
                                " TORCH",
                                LightTextVariant.Detail,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        if (zoom > 1.02f) {
                            LightText(
                                " ${engine.zoomLabel()}",
                                LightTextVariant.Detail,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        if (ev != 0) {
                            LightText(
                                " EV ${engine.evLabel()}",
                                LightTextVariant.Detail,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        // **Zone focus has to say where it is focused, permanently.** It shipped
                        // reporting only through the notice a wheel turn raises, which meant that
                        // with the wheel pointed at anything else the readout never appeared at
                        // all — a manual focus with no distance on screen, which is the thing it
                        // exists to replace. Focus is not a transient like a zoom nudge: it is the
                        // state of the lens, and it belongs beside the other state.
                        // What the wheel is holding. Only shown for
                        // the channel binding, because with a fixed binding there is nothing to
                        // disambiguate and an unobstructed viewfinder is worth more.

                        // **The fault chip.** A notice lives two seconds; a dropped frame in the
                        // middle of a burst deserves a mark that stays until it is read. Tap to
                        // replay the last fault and clear it. A crash from the previous run
                        // arrives here too, which is how a silent black screen introduces itself.
                        if (faults > 0) {
                            LightText(
                                " !$faults",
                                LightTextVariant.Detail,
                                modifier = Modifier
                                    .lightClickable { vm.readFaults() }
                                    .padding(start = 6.dp),
                            )
                        }
                        // **The buffer gauge, the way a body draws it:** a thin vertical bar that
                        // fills as the current develop finishes, with the count of what is still
                        // behind it. The count falling and the bar refilling is the queue
                        // draining; a dot could only say "busy", and after the shutter learned to
                        // outrun the darkroom, "busy" stopped being information.
                        if (developing + capturing > 0) {
                            var fill by remember { mutableFloatStateOf(0f) }
                            LaunchedEffect(developingSince, developEst) {
                                while (true) {
                                    fill = if (developingSince == 0L) {
                                        0f
                                    } else {
                                        ((SystemClock.elapsedRealtime() - developingSince)
                                            .toFloat() / developEst.coerceAtLeast(1L))
                                            .coerceIn(0f, 0.95f)
                                    }
                                    delay(60)
                                }
                            }
                            val ink = LightThemeTokens.colors.content
                            Canvas(
                                Modifier
                                    .padding(start = 6.dp)
                                    .width(3.dp)
                                    .height(12.dp),
                            ) {
                                drawRect(color = ink.copy(alpha = 0.25f))
                                // Reversed on request: the bar drains from the top as the develop
                                // completes, the way a buffer empties, instead of filling upward.
                                val remaining = size.height * (1f - fill)
                                drawRect(
                                    color = ink,
                                    topLeft = Offset(0f, size.height - remaining),
                                    size = Size(size.width, remaining),
                                )
                            }
                            LightText(
                                " ${developing + capturing}",
                                LightTextVariant.Detail,
                                modifier = Modifier.padding(start = 2.dp),
                            )
                        }
                        if (timer.seconds > 0 && mode != CaptureMode.Video) {
                            LightText(
                                " ${timer.label}",
                                LightTextVariant.Detail,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }

                // The notice is drawn once, by `Shell`, above every overlay — drawn here as well
                // it would appear twice whenever the viewfinder is the page underneath.

                // The saving bar lives with the held frame above, not here.
            }
        }

        // The strips that open out of the band, drawn over the picture's left edge rather than
        // taking width from it: resizing the preview would rebind the shader and reflow the
        // frame, and a menu should never cost you your framing.
        if (puriOpen) {
            PuriMenu(
                seed = puriSeed,
                frameId = puriFrameId,
                faceStickers = puriFaceStickers,
                marginStickers = puriMarginStickers,
                dateId = puriDates,
                stripId = puriStripId,
                onFrame = { vm.prefs.setPuriFrame(it) },
                onFaceStickers = { vm.prefs.setPuriFaceStickers(!puriFaceStickers) },
                onMarginStickers = { vm.prefs.setPuriMarginStickers(!puriMarginStickers) },
                onDate = { vm.prefs.setPuriDate(it) },
                onStrip = { vm.prefs.setPuriStrip(it) },
                wash = puriWash,
                skin = puriSkin,
                eyes = puriEyes,
                chin = puriChin,
                slim = puriSlim,
                onWash = { vm.prefs.setPuriWash(!puriWash) },
                onSkin = { vm.prefs.setPuriSkin(!puriSkin) },
                onEyes = { vm.prefs.setPuriEyes(!puriEyes) },
                onChin = { vm.prefs.setPuriChin(!puriChin) },
                onSlim = { vm.prefs.setPuriSlim(!puriSlim) },
                onClose = { puriOpen = false },
            )
        }

        if (presetOpen && presetOffered) {
            AdjustPanel(
                grade = grade,
                onStep = { adjust, by -> vm.prefs.stepGrade(adjust, by) },
                onReset = { vm.prefs.clearGrade() },
                onClose = { presetOpen = false },
                modifier = Modifier.padding(start = BAND),
            )
        }
        // **The meter.** A left-aligned ladder of the channel's stops flush with the left edge,
        // a red needle sweeping on a pivot hidden off-screen — only the tip enters the frame,
        // sliding in under the numbers. Read like a speedometer, dragged like a slider, tapped
        // to lock the dial. Filters ride it as three-letter codes.
        if (channelWheel && !picking) {
            vm.gaugeSpec()?.let { spec ->
                // Sideways, like every other word on this viewfinder: the ladder is drawn in the
                // gauge's own portrait space and the whole thing turned 90°, so it stands upright
                // on the screen's edge when the phone is in the shooting grip — the sketch's
                // landscape-left ladder. The pivot stays off-screen; only the needle's tip enters,
                // under the numbers. A tap latches the dial; a drag slides the value; rotation is
                // a graphics layer, so touch coordinates arrive already mapped.
                // Flush with the screen's edge: the ladder's text starts a few pixels in and the
                // pivot hangs past it, so in the shooting grip the numbers sit right on the frame
                // line. Filters get the whole edge -- the ladder is the dial's full track laid
                // out, and a fingertip can land anywhere on it; every other channel stays the
                // small meter, fixed in place.
                val gaugeLength =
                    if (channel == Channel.Filter) {
                        // Not the whole edge: the ladder used to run the full width and spill over
                        // the black chrome at both ends — the report was "filters go over the black
                        // bar". Fifteen percent off each end puts the ladder's rungs between the
                        // bars, on the viewfinder, where a fingertip can still land on any of them.
                        LocalConfiguration.current.screenWidthDp.dp * 0.7f
                    } else {
                        128.dp
                    }
                // Fast in, slow out: the ladder is summoned by a dial touch, so it arrives in the
                // same gesture; it retreats on its own, so the retreat reads as a drift rather than
                // a flicker. Hidden, it draws nothing and takes no touches — a ghost ladder would
                // still swallow the swipe down to the roll.
                val ladderVisible by vm.ladderVisible.collectAsState()
                val ladderAlpha by animateFloatAsState(
                    targetValue = if (ladderVisible) 1f else 0f,
                    animationSpec = tween(durationMillis = if (ladderVisible) 120 else 700),
                    label = "ladder",
                )
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp)
                        .requiredSize(width = gaugeLength, height = 44.dp)
                        .graphicsLayer { alpha = ladderAlpha },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .requiredSize(width = 44.dp, height = gaugeLength)
                            .graphicsLayer { rotationZ = 90f },
                    ) {
                        NeedleGauge(
                            labels = spec.labels,
                            index = spec.index,
                            onSet = { i ->
                                vm.touchLadder()
                                spec.onSet(i)
                            },
                            onTap = {
                                vm.touchLadder()
                                vm.toggleDialLock()
                            },
                            length = gaugeLength,
                            // Gate on the boolean, not the animated alpha: the float flips this key
                            // at 0.5 mid-fade and restarts the gesture detectors every frame of the
                            // animation, which swallows the very tap or drag that summoned the ladder.
                            enabled = ladderVisible,
                        )
                    }
                }
            }
        }

        if (modeOpen) {
            ModeStrip(
                mode = mode,
                simpleOffered = simpleOffered,
                onPick = {
                    vm.setMode(it)
                    modeOpen = false
                },
                onFilters = {
                    modeOpen = false
                    gridOpen = true
                },
                onSettings = {
                    modeOpen = false
                    onOpenSettings()
                },
                onAdjust = if (presetOffered) {
                    {
                        modeOpen = false
                        presetOpen = true
                    }
                } else {
                    null
                },
                photoType = if (mode == CaptureMode.Photo || mode == CaptureMode.Selfie) {
                    vm.photoTypeLabel()
                } else {
                    null
                },
                onPhotoType = { vm.cyclePhotoType() },
                modifier = Modifier.padding(start = BAND),
            )
        }
        if (evOpen) {
            ExposureStrip(
                index = ev,
                range = evRange,
                label = engine.evLabel(),
                onStep = { engine.stepEv(it) },
                onSet = { engine.setEv(it) },
                onReset = { engine.resetEv() },
                modifier = Modifier.padding(start = BAND),
            )
        }
        if (zoomOpen) {
            ZoomStrip(
                zoom = zoom,
                maxZoom = maxZoom,
                label = engine.zoomLabel(),
                onSet = { engine.setZoom(it) },
                onReset = { engine.setZoom(1f) },
                modifier = Modifier.padding(start = BAND),
            )
        }
        // **The boxes, over the frozen frame, before any sheet.** Standing in front of a menu the
        // question is which part of it said that, and the sheet cannot answer it — it covers the
        // picture. So a reading shows its rectangles first and opens on a tap.
        val reading = page
        if (reading != null && pageSheet == null && reading.lines.isNotEmpty()) {
            TextOverlay(
                reading = reading,
                found = pageFound,
                rotationDegrees = pageTurn,
                // The frame underneath is drawn `ContentScale.Crop` into the whole panel, so the
                // boxes have to be laid out the same way or they are out by the overhang.
                placement = { w, h ->
                    val (srcW, srcH) = TextBoxes.sourceSize(reading.width, reading.height, pageTurn)
                    TextBoxes.fill(srcW, srcH, w, h)
                },
                onTapLine = vm::openLine,
            )
            TextHint(
                found = pageFound.size,
                lines = reading.lines.size,
                onAll = vm::openWholePage,
                onClose = vm::dismissPage,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        // The sheet: one line if a box was tapped, the whole page otherwise. The same sheet a
        // photograph on the roll gets, and the same one a QR code gets once `TextScan` has shaped
        // the findings into payloads — three ways in, one screen out.
        pageSheet?.let { text ->
            TextSheet(
                text = text,
                onOpen = vm::openFromPage,
                onCopy = vm::copyFromPage,
                // Back to the boxes rather than out of the reading, so tapping a second line is
                // one press and not four.
                onClose = { if (reading?.lines?.isNotEmpty() == true) vm.closePageSheet() else vm.dismissPage() },
            )
        }
        scanned?.let { payload ->
            ScanSheet(
                raw = payload,
                onOpen = { vm.openScan() },
                onCopy = { vm.copyScan() },
                onCopyPassword = { vm.copyScanPassword() },
                onClose = { vm.dismissScan() },
            )
        }
        // No counter in Video or QR: neither one spends a frame, and a film counter beside a mode
        // that cannot advance it is a number that looks stuck.
        if (roll != null && mode != CaptureMode.Video && !mode.isReader) {
            // The film counter down the far edge, opposite the band: it belongs to the
            // photograph rather than to the controls.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(BAND)
                    .fillMaxHeight(),
            ) {
                HeldSideways {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        SprocketStrip(offsetFrames = roll?.shot ?: 0)
                        RollCounter(
                            roll = roll,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .lightClickable { onOpenSettings() },
                        )
                    }
                }
            }
        }

        if (gridOpen) {
            FilterGrid(
                vm = vm,
                previewView = previewView,
                onPick = { id ->
                    vm.setFilter(id)
                    gridOpen = false
                },
                onOpenSettings = {
                    gridOpen = false
                    onOpenSettings()
                },
                onClose = { gridOpen = false },
            )
        }
    }

    LaunchedEffect(Unit) {
        if (CameraKeyAdvice.problem(context) != null) {
            vm.showNotice("Camera key held — see settings")
        }
    }
}

/**
 * Camera, Video, Selfie — the stock camera's three, out of the same slot and in the same order,
 * with QR on the end of them.
 *
 * QR is last rather than folded in among the picture-taking modes because it is the one entry that
 * does not produce a photograph, and putting it between Video and Selfie would have it caught by
 * somebody stepping through the modes looking for a camera.
 *
 * A strip beside the band rather than a sheet over the picture, so it reads as the band opening
 * out. Filters and settings are on the end of it because the viewfinder has no room for them and
 * this is the one menu in the app.
 */
@Composable
private fun ModeStrip(
    mode: CaptureMode,
    simpleOffered: Boolean,
    onPick: (CaptureMode) -> Unit,
    onFilters: () -> Unit,
    onSettings: () -> Unit,
    /** The Preset sliders, moved here from the band — the band slot now names the wheel. */
    onAdjust: (() -> Unit)? = null,
    /** Which files a press writes, cycled per scene. Pro and Selfie only; null hides it. */
    photoType: String? = null,
    onPhotoType: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colours = LightThemeTokens.colors
    Box(
        modifier = modifier
            .width(BAND + 34.dp)
            .fillMaxHeight()
            .background(colours.background),
    ) {
        HeldSideways {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Simple is in the list only when its switch is on. Off, it is not a mode this camera has.
                CaptureMode.entries.filter { !it.isSimple || simpleOffered }.forEach { candidate ->
                    val here = candidate == mode
                    Box(
                        modifier = Modifier
                            .lightClickable { onPick(candidate) }
                            .background(if (here) colours.content else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        LightText(
                            text = candidate.label.uppercase(),
                            variant = LightTextVariant.Detail,
                            color = if (here) colours.background else colours.content,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Spacer(Modifier.weight(1f))
                if (photoType != null && onPhotoType != null) {
                    ChromeLabel(text = photoType, onClick = onPhotoType, lighten = true)
                    Spacer(Modifier.width(6.dp))
                }
                if (onAdjust != null) {
                    ChromeLabel(text = "Adjust", onClick = onAdjust, lighten = true)
                    Spacer(Modifier.width(6.dp))
                }
                ChromeLabel(text = "Filters", onClick = onFilters, lighten = true)
                ChromeIcon(icon = LightIcons.Settings, lighten = true, onClick = onSettings)
            }
        }
    }
}

/**
 * Everything a Purikura is made of, on one screen, with a sample of it beside the rows.
 *
 * **The whole screen, not a strip beside the band.** A menu you have to scroll on a 3.92" screen held
 * sideways is a menu that hides half its options, so this covers the viewfinder while it is open and
 * nothing scrolls underneath it.
 *
 * Frame, Date, Four-shot and Look are **lists**, not values you cycle: fourteen frames and eight dates
 * are too many to walk one tap at a time, the first item in each is Random, and every row in a list
 * carries a thumbnail of what it does — which is the only way to choose between fourteen borders whose
 * names are one word each.
 *
 * Look is where the effect itself lives, five switches deep: the wash, the skin, the eyes, the chin, the
 * slimming. They are separate because they fail separately — the chin and the slimming are the two that
 * look uncanny on a face the detector has boxed slightly wrong, and you should be able to drop those
 * without losing the eyes.
 */
@Composable
private fun PuriMenu(
    seed: Long,
    frameId: String,
    faceStickers: Boolean,
    marginStickers: Boolean,
    dateId: String,
    stripId: String,
    wash: Boolean,
    skin: Boolean,
    eyes: Boolean,
    chin: Boolean,
    slim: Boolean,
    onFrame: (String) -> Unit,
    onFaceStickers: () -> Unit,
    onMarginStickers: () -> Unit,
    onDate: (String) -> Unit,
    onStrip: (String) -> Unit,
    onWash: () -> Unit,
    onSkin: () -> Unit,
    onEyes: () -> Unit,
    onChin: () -> Unit,
    onSlim: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = LightThemeTokens.colors
    var picking by remember { mutableStateOf<String?>(null) }
    val strip = if (PuriStrip.enabled(stripId)) PuriStrip.resolveLayout(stripId, seed) else null

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colours.background)
            // Eats every touch: the swipe down to the roll is a drag on a pager two levels up, and a
            // background does not stop one.
            .swallowTaps(),
    ) {
        HeldSideways {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LightText(
                            text = when (picking) {
                                "frame" -> "FRAME"
                                "date" -> "DATE"
                                "strip" -> "FOUR-SHOT"
                                "look" -> "LOOK"
                                else -> "PURIKURA"
                            },
                            variant = LightTextVariant.Detail,
                        )
                        Spacer(Modifier.weight(1f))
                        ChromeIcon(
                            icon = LightIcons.Close,
                            lighten = true,
                            onClick = { if (picking != null) picking = null else onClose() },
                        )
                    }
                    when (picking) {
                        "frame" -> PuriPicker(
                            options = listOf(PuriArt.RANDOM to "Random") +
                                PuriArt.frames.map { it.id to it.label },
                            chosen = frameId,
                            onPick = { onFrame(it); picking = null },
                            thumbnail = { id, w, h ->
                                puriTile(w, h, PuriArt.resolveFrame(id, seed), PuriArt.OFF, seed, false, false)
                            },
                        )

                        "date" -> PuriPicker(
                            options = listOf(PuriArt.RANDOM to "Random", PuriArt.OFF to "Off") +
                                PuriArt.dates.map { it.id to it.label },
                            chosen = dateId,
                            onPick = { onDate(it); picking = null },
                            thumbnail = { id, w, h ->
                                puriTile(w, h, PuriArt.frameById("none"), id, seed, false, false)
                            },
                        )

                        "strip" -> PuriPicker(
                            options = listOf(PuriStrip.OFF to "Off", PuriArt.RANDOM to "Random") +
                                PuriStrip.layouts.drop(1).map { it.id to it.label },
                            chosen = stripId,
                            onPick = { onStrip(it); picking = null },
                            thumbnail = { id, w, h ->
                                if (id == PuriStrip.OFF) {
                                    puriTile(w, h, PuriArt.resolveFrame(frameId, seed), PuriArt.OFF, seed, false, false)
                                } else {
                                    puriStripTile(
                                        w,
                                        h,
                                        PuriStrip.resolveLayout(id, seed),
                                        PuriArt.resolveFrame(frameId, seed),
                                        seed,
                                    )
                                }
                            },
                        )

                        "look" -> Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        ) {
                            PuriRow("Pink wash", if (wash) "On" else "Off", onWash)
                            PuriRow("Skin", if (skin) "On" else "Off", onSkin)
                            PuriRow("Bigger eyes", if (eyes) "On" else "Off", onEyes)
                            PuriRow("Narrow chin", if (chin) "On" else "Off", onChin)
                            PuriRow("Smaller face", if (slim) "On" else "Off", onSlim)
                            LightText(
                                "The wash is the pink, the blow-out and the glitter. Without it you get the smoothing and the shaping, which is a beauty filter rather than a booth print.",
                                LightTextVariant.Superfine,
                                lighten = true,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }

                        else -> {
                            PuriRow(
                                "Frame",
                                labelFor(frameId, PuriArt.frames.map { it.id to it.label }),
                            ) { picking = "frame" }
                            PuriRow("Face stickers", if (faceStickers) "On" else "Off", onFaceStickers)
                            PuriRow(
                                "Margin stickers",
                                if (marginStickers) "On" else "Off",
                                onMarginStickers,
                            )
                            PuriRow(
                                "Date",
                                labelFor(dateId, PuriArt.dates.map { it.id to it.label }),
                            ) { picking = "date" }
                            PuriRow(
                                "Four-shot",
                                labelFor(stripId, PuriStrip.layouts.map { it.id to it.label }),
                            ) { picking = "strip" }
                            PuriRow("Look", "${listOf(wash, skin, eyes, chin, slim).count { it }} of 5") {
                                picking = "look"
                            }
                            Spacer(Modifier.weight(1f))
                            LightText(
                                text = if (strip != null) {
                                    "Four shots, three seconds apart. The strip goes on the roll; the frames are kept behind it."
                                } else {
                                    "Random is chosen fresh for each photograph."
                                },
                                variant = LightTextVariant.Superfine,
                                lighten = true,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                PuriSample(
                    seed = seed,
                    frame = PuriArt.resolveFrame(frameId, seed),
                    faceStickers = faceStickers,
                    marginStickers = marginStickers,
                    dateId = dateId,
                    strip = strip,
                )
            }
        }
    }
}

/** "Random", "Off", or the label of whatever was chosen. */
private fun labelFor(id: String, options: List<Pair<String, String>>): String = when (id) {
    PuriArt.RANDOM -> "Random"
    PuriArt.OFF -> "Off"
    else -> options.firstOrNull { it.first == id }?.second ?: "Random"
}

/**
 * One cell of the stand-in: a grey head and shoulders with the furniture drawn on it.
 *
 * The same call the photograph makes, at the size of a postage stamp. That is the whole reason the
 * thumbnails are worth having — they are not illustrations of the frames, they are the frames.
 */
private fun puriTile(
    w: Int,
    h: Int,
    frame: PuriArt.Frame,
    dateId: String,
    seed: Long,
    faceStickers: Boolean,
    marginStickers: Boolean,
): android.graphics.Bitmap {
    val bitmap = createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1))
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(0x3A, 0x3A, 0x38))
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.rgb(0x8C, 0x86, 0x80)
    }
    canvas.drawCircle(w * 0.5f, h * 0.4f, w * 0.22f, paint)
    canvas.drawOval(w * 0.16f, h * 0.66f, w * 0.84f, h * 1.3f, paint)
    PuriArt.draw(
        canvas = canvas,
        w = w,
        h = h,
        frame = frame,
        plan = PuriArt.plan(
            seed = seed,
            faces = listOf(FaceQuad(cx = 0.5f, cy = 0.4f, hw = 0.22f, hh = 0.165f)),
            faceStickers = faceStickers,
            marginStickers = marginStickers,
            dateId = dateId,
        ),
        millis = System.currentTimeMillis(),
    )
    return bitmap
}

/** Four tiles, run through the real strip composer, so a layout row shows its own layout. */
private fun puriStripTile(
    w: Int,
    h: Int,
    layout: PuriStrip.Layout,
    frame: PuriArt.Frame,
    seed: Long,
): android.graphics.Bitmap {
    val cellH = (h / PuriStrip.SHOTS).coerceAtLeast(8)
    val cellW = (cellH * 3 / 4).coerceAtLeast(6)
    val cells = (0 until PuriStrip.SHOTS).map {
        puriTile(
            cellW,
            cellH,
            if (layout.outerFrame) PuriArt.frameById("none") else frame,
            PuriArt.OFF,
            seed + it * 977L,
            false,
            false,
        )
    }
    val sheet = PuriStrip.compose(cells, layout, frame, System.currentTimeMillis())
    cells.forEach { it.recycle() }
    return sheet ?: puriTile(w, h, frame, PuriArt.OFF, seed, false, false)
}

/**
 * A list of options, each with a thumbnail of itself and the current one filled in.
 *
 * Filled rather than ticked: there is no tick in the icon set, and an inverted row reads at a glance in a
 * way a small mark beside text does not.
 */
@Composable
private fun PuriPicker(
    options: List<Pair<String, String>>,
    chosen: String,
    onPick: (String) -> Unit,
    thumbnail: (String, Int, Int) -> android.graphics.Bitmap,
) {
    val colours = LightThemeTokens.colors
    val density = LocalDensity.current
    val tileW = with(density) { 26.dp.roundToPx() }
    val tileH = with(density) { 35.dp.roundToPx() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        options.forEach { (id, label) ->
            val here = id == chosen
            // Random has nothing of its own to show, so it borrows whatever the seed currently says.
            val tile = remember(id, chosen == id, tileW, tileH) {
                runCatching { thumbnail(id, tileW, tileH).asImageBitmap() }.getOrNull()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onPick(id) }
                    .background(if (here) colours.content else Color.Transparent)
                    .padding(horizontal = 5.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tile != null) {
                    Image(
                        bitmap = tile,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.width(26.dp).height(35.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                }
                LightText(
                    text = label,
                    variant = LightTextVariant.Copy,
                    color = if (here) colours.background else colours.content,
                )
            }
        }
    }
}

/**
 * A thumbnail of what you are about to get, as large as the panel allows.
 *
 * A stand-in rather than the live viewfinder, because the point of it is the *furniture* and a moving
 * picture behind a menu is a distraction. With a [strip] it builds four cells and runs them through the
 * real `PuriStrip.compose`, so the sample is not an illustration of a strip — it is one. A strip is 1:4,
 * so it gets the full height of the panel and takes whatever width that leaves: at a fixed size it came
 * out the width of a fingernail.
 */
@Composable
private fun PuriSample(
    seed: Long,
    frame: PuriArt.Frame,
    faceStickers: Boolean,
    marginStickers: Boolean,
    dateId: String,
    strip: PuriStrip.Layout?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cellW = with(density) { 150.dp.roundToPx() }
    val cellH = with(density) { 200.dp.roundToPx() }

    val sample = remember(seed, frame.id, faceStickers, marginStickers, dateId, strip?.id, cellW, cellH) {
        if (strip == null) {
            puriTile(cellW, cellH, frame, dateId, seed, faceStickers, marginStickers).asImageBitmap()
        } else {
            val cells = (0 until PuriStrip.SHOTS).map {
                puriTile(
                    cellW,
                    cellH,
                    // One border round the whole strip means none on the cells inside it.
                    if (strip.outerFrame) PuriArt.frameById("none") else frame,
                    // The date goes on the print, once, not into all four panels.
                    PuriArt.OFF,
                    // A different seed per cell: a strip's panels are decorated separately, exactly as
                    // the shutter does it.
                    seed + it * 977L,
                    faceStickers,
                    marginStickers,
                )
            }
            val sheet = PuriStrip.compose(cells, strip, frame, System.currentTimeMillis())
            cells.forEach { it.recycle() }
            // No date anywhere on a strip. Four copies down the panels was wrong, and one in the margin
            // was still a date on something that is already stamped by being four photographs of one
            // moment — the layouts that want a printed date have their own footer.
            sheet?.asImageBitmap()
                ?: puriTile(cellW, cellH, frame, dateId, seed, faceStickers, marginStickers).asImageBitmap()
        }
    }

    // **A bounded width, and that is not cosmetic.** `ContentScale.Fit` inside a column with no width
    // constraint takes the bitmap's intrinsic width, so the 2x2 sheet — twice as wide as a single frame —
    // shoved the rows off the side of the screen. Full height, fixed width, and every layout fits inside
    // it: a strip lands tall and narrow, the grid lands square.
    Column(
        modifier = modifier.fillMaxHeight().width(124.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            bitmap = sample,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        LightText(
            text = "EXAMPLE",
            variant = LightTextVariant.Micro,
            lighten = true,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun PuriRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(label, LightTextVariant.Copy)
        Spacer(Modifier.weight(1f))
        LightText(value, LightTextVariant.Copy, lighten = true)
    }
}

/**
 * What one of the two free band slots draws.
 *
 * Two shapes only. Exposure keeps the SDK's brightness glyph because that icon is the one a Light
 * Phone owner already knows; everything else is its own current value as a word — "1.8x", "10s",
 * "3:2" — because the SDK has no glyph for any of them and a word that reads the value is worth
 * more than a glyph that doesn't. The mode chip two slots along is already a word, so the row is
 * consistent either way.
 *
 * Exposure hides itself in Simple, which is the one rule the old hardcoded slot had and the one
 * worth keeping: Simple takes the frame off the panel and exposure compensation on the way to it
 * is a control over a photograph that has already been taken.
 */
@Composable
private fun BandSlotControl(
    slot: BandSlot,
    mode: CaptureMode,
    open: Strip?,
    ev: Int,
    zoom: Float,
    timer: SelfTimer,
    aspect: FrameAspect,
    chrome: Chrome,
    zoneOn: Boolean,
    rawOn: Boolean,
    onToggleZone: () -> Unit,
    onToggleRaw: () -> Unit,
    onPress: (PressAction) -> Unit,
    onCycleTimer: () -> Unit,
    onCycleAspect: () -> Unit,
    onCycleGrid: () -> Unit,
) {
    when (slot) {
        BandSlot.None -> Unit

        BandSlot.Exposure -> if (!mode.isSimple) {
            ChromeIcon(
                icon = LightIcons.Exposure,
                lighten = open != Strip.Exposure && ev == 0,
                onClick = { onPress(PressAction.Exposure) },
            )
        }

        BandSlot.Zoom -> BandWord(
            // One decimal below 10x and none above it, the same rule as the status readout, so the
            // slot never changes width mid-pinch and shove the row along.
            text = if (zoom < 9.95f) String.format("%.1fx", zoom) else String.format("%.0fx", zoom),
            lighten = open != Strip.Zoom && zoom <= 1.02f,
            onClick = { onPress(PressAction.Zoom) },
        )

        BandSlot.Flip -> ChromeIcon(
            icon = LightIcons.FlipLens,
            onClick = { onPress(PressAction.FlipLens) },
        )

        BandSlot.Focus -> if (!mode.isSimple) {
            BandWord(
                // The words a lens barrel uses. AF is the ordinary state and stays dim; MF is the
                // one you chose, and lit is what chosen looks like everywhere else in this band.
                text = if (zoneOn) "MF" else "AF",
                lighten = !zoneOn,
                onClick = onToggleZone,
            )
        }

        BandSlot.Raw -> if (!mode.isSimple) {
            BandWord(
                text = "RAW",
                lighten = !rawOn,
                onClick = onToggleRaw,
            )
        }

        BandSlot.Timer -> BandWord(
            text = if (timer.seconds == 0) "OFF" else "${timer.seconds}s",
            lighten = timer.seconds == 0,
            onClick = onCycleTimer,
        )

        BandSlot.Shape -> BandWord(text = aspect.label, onClick = onCycleAspect)

        BandSlot.Grid -> BandWord(
            text = chrome.label.uppercase(),
            lighten = chrome == Chrome.Clean,
            onClick = onCycleGrid,
        )
    }
}

/** A band slot that is a word rather than a glyph, set the way the mode chip beside it is. */
@Composable
private fun BandWord(text: String, lighten: Boolean = false, onClick: () -> Unit) {
    LightText(
        text = text,
        variant = LightTextVariant.Button,
        lighten = lighten,
        align = TextAlign.Center,
        modifier = Modifier
            .lightClickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 10.dp),
    )
}

/**
 * Point a dial's notches at whatever it is bound to.
 *
 * A function rather than a `when` at each of the two call sites, because the bare wheel and
 * press-and-turn have to mean the same thing by the same action or a mapping is not a mapping.
 */
private fun turnDial(
    vm: CameraViewModel,
    engine: CameraEngine,
    action: DialAction,
    notches: Int,
) {
    when (action) {
        // One filter per turn regardless of how many notches arrived: the track is a list of names
        // and skipping two of them because the wheel was flicked is not what the gesture meant.
        DialAction.Filter -> vm.stepFilter(if (notches > 0) 1 else -1)
        DialAction.Exposure -> {
            engine.stepEv(notches)
            vm.showNotice("EV ${engine.evLabel()}")
        }
        DialAction.Zoom -> {
            engine.stepZoom(notches)
            vm.showNotice(engine.zoomLabel())
        }
        // **One binding, whatever the wheel is currently holding.** The channel is named on the
        // band, so this is never a guess about what a turn will do — and every branch reports what
        // it changed, because a dial you cannot see needs to say so.
        DialAction.Channel -> when (vm.channel.value) {
            Channel.Filter -> vm.stepFilter(if (notches > 0) 1 else -1)
            Channel.Exposure -> {
                engine.stepEv(notches)
                vm.showNotice("EV ${engine.evLabel()}")
            }
            Channel.Shutter -> {
                engine.stepShutter(notches)
                vm.showNotice(engine.exposureLabel.value)
            }
            Channel.Iso -> {
                engine.stepIso(notches)
                vm.showNotice(engine.exposureLabel.value)
            }
            Channel.Focus -> {
                engine.stepFocus(notches)
                vm.showNotice(engine.focusLabel.value)
            }
            Channel.Zoom -> {
                engine.stepZoom(notches)
                vm.showNotice(engine.zoomLabel())
            }
        }
        DialAction.Nothing -> Unit
    }
}

/**
 * Exposure compensation, as a row of stops.
 *
 * Opened from the band or from a key, and while it is open the bare wheel drives it — the wheel is
 * a better exposure dial than a thumb on a 3.92" screen will ever be.
 *
 * **And now a thumb works too.** Twelve notches is the whole range on this camera, so getting from
 * -2 to +2 by tapping `+` was twelve taps; a drag along the ticks is one gesture. The wheel is
 * still the better control and this is the one you reach for when the phone is already in your
 * hand and the wheel is bound to something else.
 */
@Composable
private fun ExposureStrip(
    index: Int,
    range: IntRange,
    label: String,
    onStep: (Int) -> Unit,
    onSet: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = LightThemeTokens.colors
    Box(
        modifier = modifier
            .width(BAND)
            .fillMaxHeight()
            .background(colours.background),
    ) {
        HeldSideways {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    "\u2212",
                    LightTextVariant.Copy,
                    modifier = Modifier
                        .lightClickable { onStep(-1) }
                        .padding(horizontal = 8.dp),
                )
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(18.dp)
                        // **Taller than it looks.** The ticks are 18dp and a thumb is not; the extra
                        // height is hit area either side of the drawing, which costs nothing in a
                        // row that is already centred and is the difference between a strip you can
                        // drag and one you keep missing.
                        .stripDrag(
                            steps = (range.last - range.first).coerceAtLeast(1),
                            onPick = { step -> onSet(range.first + step) },
                            onReset = onReset,
                        ),
                ) {
                    val span = (range.last - range.first).coerceAtLeast(1)
                    val pitch = size.width / span
                    for (i in 0..span) {
                        val x = i * pitch
                        // Whole stops taller than the thirds between them, so the scale can be
                        // read without labels.
                        val whole = (range.first + i) % 3 == 0
                        val h = if (whole) size.height else size.height * 0.45f
                        drawLine(
                            color = colours.contentSecondary.copy(alpha = 0.55f),
                            start = Offset(x, size.height - h),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    val here = (index - range.first) * pitch
                    drawLine(
                        color = colours.content,
                        start = Offset(here, 0f),
                        end = Offset(here, size.height),
                        strokeWidth = 2.4.dp.toPx(),
                        cap = StrokeCap.Square,
                    )
                }
                LightText(
                    "+",
                    LightTextVariant.Copy,
                    modifier = Modifier
                        .lightClickable { onStep(1) }
                        .padding(horizontal = 8.dp),
                )
                LightText(
                    text = label,
                    variant = LightTextVariant.Superfine,
                    modifier = Modifier.width(32.dp),
                    align = TextAlign.End,
                )
            }
        }
    }
}

/**
 * Zoom, as a strip.
 *
 * **The control this app never had.** The lens is fixed and the crop is digital, which is why the
 * wheel was spent on the filters instead — but a digital crop is still the difference between a
 * photograph of a sign and a photograph of the wall it is on, and until now the only way to get one
 * was a pinch on a 3.92" panel with the phone held sideways in one hand.
 *
 * **Logarithmic.** Half the travel takes you from 1x to the square root of the maximum and the
 * other half covers the rest, so the low end — where every tenth is visible in the frame — is not
 * crushed into the first few millimetres. Ticks at each whole doubling, so the scale reads.
 */
@Composable
private fun ZoomStrip(
    zoom: Float,
    maxZoom: Float,
    label: String,
    onSet: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = LightThemeTokens.colors
    val top = maxZoom.coerceAtLeast(1.01f)
    Box(
        modifier = modifier
            .width(BAND)
            .fillMaxHeight()
            .background(colours.background),
    ) {
        HeldSideways {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    "1x",
                    LightTextVariant.Superfine,
                    lighten = true,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(18.dp)
                        .stripDrag(
                            // A step per percent of the travel: finer than the panel can resolve, so
                            // the drag is continuous as far as a thumb is concerned, and the maths
                            // below stays in integers like the exposure strip's.
                            steps = ZOOM_STEPS,
                            onPick = { step -> onSet(Zooms.at(step.toFloat() / ZOOM_STEPS, top)) },
                            onReset = onReset,
                        ),
                ) {
                    // Ticks at every doubling the lens can reach — 1x, 2x, 4x — placed by the same
                    // curve the drag reads, so a tick and the value under it agree.
                    var mark = 1f
                    while (mark <= top + 0.001f) {
                        val x = Zooms.positionOf(mark, top) * size.width
                        drawLine(
                            color = colours.contentSecondary.copy(alpha = 0.55f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                        mark *= 2f
                    }
                    val here = Zooms.positionOf(zoom, top) * size.width
                    drawLine(
                        color = colours.content,
                        start = Offset(here, 0f),
                        end = Offset(here, size.height),
                        strokeWidth = 2.4.dp.toPx(),
                        cap = StrokeCap.Square,
                    )
                }
                LightText(
                    text = label,
                    variant = LightTextVariant.Superfine,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .width(40.dp),
                    align = TextAlign.End,
                )
            }
        }
    }
}

private const val ZOOM_STEPS = 100

/**
 * Drag, tap or long-press a value strip.
 *
 * Three gestures on one pointer, arbitrated by hand rather than by stacking `detectTapGestures` on
 * `detectHorizontalDragGestures` — two detectors on the same node race for the first `down` and
 * whichever wins eats it, which showed up as a strip that responded to drags only after a tap.
 *
 *  - **Drag** → the value follows your thumb, continuously.
 *  - **Tap** → the value goes to where you tapped. Picked on the *up*, not the down, so a long
 *    press is still available underneath it.
 *  - **Long press without moving** → back to neutral. Which is the gesture the tap used to be, and
 *    it had to move: a tap that reset the value made the strip unusable as a strip.
 *
 * @param steps how many intervals the strip is divided into. The caller converts a step index into
 *   whatever it is measuring.
 */
private fun Modifier.stripDrag(
    steps: Int,
    onPick: (Int) -> Unit,
    onReset: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    var width by remember { mutableStateOf(0) }
    onSizeChanged { width = it.width }.pointerInput(steps) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startedAt = System.currentTimeMillis()
            var moved = false
            var reset = false

            fun pick(x: Float) {
                val span = width
                if (span <= 0) return
                val fraction = (x / span).coerceIn(0f, 1f)
                onPick((fraction * steps).roundToInt())
            }

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                // **The long press is decided by the clock, not by an up.** Waiting for the release
                // to measure it would mean a reset that only happens once you let go, which reads as
                // a control that ignored you and then changed its mind.
                if (!moved && !reset && System.currentTimeMillis() - startedAt > LONG_PRESS_MS) {
                    reset = true
                    LightHaptics.click(context)
                    onReset()
                }

                if (!change.pressed) {
                    // A short press that never moved is a tap: put the value where the thumb was.
                    if (!moved && !reset) pick(change.position.x)
                    break
                }

                if (!moved && (change.position.x - down.position.x).absoluteValue > SLOP_PX) {
                    moved = true
                }
                if (moved) pick(change.position.x)
                change.consume()
            }
        }
    }
}

/** Long enough not to fire on a slow tap, short enough to feel deliberate. */
private const val LONG_PRESS_MS = 420L

/** A thumb never lands perfectly still; below this the press is a tap, not a drag. */
private const val SLOP_PX = 5f

/**
 * The little chevron next to the mode. Drawn rather than an icon: every arrow glyph in the SDK
 * is bigger and heavier than the one beside "PHOTO" on the stock camera.
 */
@Composable
private fun Chevron(pointingUp: Boolean) {
    val colours = LightThemeTokens.colors
    Canvas(Modifier.width(9.dp).height(6.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.4.dp.toPx()
        val tipY = if (pointingUp) 0f else h
        val baseY = if (pointingUp) h else 0f
        drawLine(colours.content, Offset(0f, baseY), Offset(w / 2f, tipY), stroke, StrokeCap.Round)
        drawLine(colours.content, Offset(w, baseY), Offset(w / 2f, tipY), stroke, StrokeCap.Round)
    }
}

/** Recording. A filled disc, because that is what a record light is. */
@Composable
private fun RecordDot() {
    val colours = LightThemeTokens.colors
    Canvas(Modifier.size(9.dp)) {
        drawCircle(color = colours.content, radius = size.minDimension / 2f)
    }
}

/**
 * Tap to focus, double tap to switch lens, swipe sideways for the next filter.
 *
 * Written against [PointerEventPass.Initial] and arbitrating by hand, because the viewfinder
 * sits inside a vertical pager: on the main pass the pager has already claimed the gesture. The
 * axis is decided once, past the slop, and only a horizontal decision is consumed — the vertical
 * one is left entirely alone, which is what keeps the swipe down to the roll working.
 */
private fun Modifier.viewfinderGestures(
    enabled: Boolean,
    onTapFocus: (Float, Float) -> Unit,
    onDoubleTap: () -> Unit,
    onFilterStep: (Int) -> Unit,
    onPinchStart: () -> Float,
    onPinch: (Float) -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val slopPx = 14.dp.toPx()
        val swipePx = 52.dp.toPx()
        // Enough span change to be a pinch rather than a second thumb landing untidily.
        val pinchSlopPx = 18.dp.toPx()
        // Kept across gestures, which is the only way to see a double tap: two taps are two
        // complete gestures, and the second only means anything in the light of the first.
        var lastTapAt = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dx = 0f
            var dy = 0f
            var horizontal = false
            var decided = false
            var fired = false
            // A pinch, once it starts, owns the rest of the gesture. Nothing else may fire from it.
            var pinching = false
            var startSpan = 0f
            var startZoom = 1f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) break
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    // **Two fingers are never anything else.** Before this, the second finger was
                    // simply not looked at: the loop followed the first pointer only, so a pinch
                    // arrived as one finger wandering sideways and stepped the filter instead of
                    // zooming. Claiming the gesture here is what makes both work.
                    val span = (pressed[0].position - pressed[1].position).getDistance()
                    if (!pinching) {
                        if (startSpan == 0f) startSpan = span
                        if (abs(span - startSpan) > pinchSlopPx) {
                            pinching = true
                            // Suppress the single-finger readings: whatever the first pointer did
                            // while the second was arriving is not a swipe and not a tap.
                            decided = true
                            fired = true
                            startSpan = span
                            startZoom = onPinchStart()
                        }
                    } else if (startSpan > 0f) {
                        onPinch(startZoom * (span / startSpan))
                    }
                    event.changes.forEach { it.consume() }
                    continue
                }
                if (pinching) {
                    // A finger lifted mid-pinch. Don't resume swiping with what's left of it.
                    event.changes.forEach { it.consume() }
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                if (!decided && (abs(dx) > slopPx || abs(dy) > slopPx)) {
                    decided = true
                    horizontal = abs(dx) > abs(dy) * 1.3f
                }
                if (horizontal) {
                    event.changes.forEach { it.consume() }
                    if (!fired && abs(dx) > swipePx) {
                        fired = true
                        onFilterStep(if (dx < 0) 1 else -1)
                    }
                }
            }
            if (!pinching && !decided && abs(dx) < slopPx && abs(dy) < slopPx) {
                val now = System.currentTimeMillis()
                if (now - lastTapAt < DOUBLE_TAP_MS) {
                    lastTapAt = 0L
                    onDoubleTap()
                } else {
                    lastTapAt = now
                    // The first tap focuses regardless. Waiting to find out whether a second is
                    // coming would put a third of a second of lag on every tap to focus, to save
                    // one pointless autofocus on the rare double.
                    onTapFocus(down.position.x, down.position.y)
                }
            }
        }
    },
)

/** Long enough to be deliberate, short enough that two taps to focus aren't one gesture. */
private const val DOUBLE_TAP_MS = 320L

/**
 * Where to point it, and nothing else.
 *
 * **Four corner marks, not a rectangle.** The decoder reads the whole frame — there is no crop and
 * the window is not a boundary — so a closed box would be claiming a restriction the camera does not
 * have, and people dutifully line codes up inside boxes that mean nothing. Corners read as *aim
 * here*, which is true: a code near the middle is in focus and large in the frame, and both of those
 * are what make it decode from across a room.
 *
 * Sized off the short edge in grid units like everything else in this app, so it is the same
 * proportion of the panel whichever way the phone is held.
 */
@Composable
private fun ScanWindow(modifier: Modifier = Modifier) {
    val colours = LightThemeTokens.colors
    Canvas(modifier) {
        val short = minOf(size.width, size.height)
        val side = short * 0.62f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val arm = side * 0.16f
        val stroke = short / 220f
        val corners = listOf(
            // x, y, dx, dy for the two arms of each corner
            Triple(Offset(left, top), Offset(arm, 0f), Offset(0f, arm)),
            Triple(Offset(left + side, top), Offset(-arm, 0f), Offset(0f, arm)),
            Triple(Offset(left, top + side), Offset(arm, 0f), Offset(0f, -arm)),
            Triple(Offset(left + side, top + side), Offset(-arm, 0f), Offset(0f, -arm)),
        )
        corners.forEach { (corner, across, down) ->
            drawLine(
                color = colours.content,
                start = corner,
                end = Offset(corner.x + across.x, corner.y + across.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = colours.content,
                start = corner,
                end = Offset(corner.x + down.x, corner.y + down.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * What was scanned, over the picture, with the destination legible before anything is launched.
 *
 * **The whole screen, like the Purikura menu and for the same reason** — a payload can be a URL
 * eighty characters long and this panel is 3.92" held sideways, so a strip beside the band would
 * show a third of it. Covering the viewfinder is also honest here in a way it would not be in Pro:
 * the camera has finished, there is nothing left to frame.
 *
 * **It turns with the phone, and it is the only thing in the viewfinder that does.** Everything else
 * here is chrome, and chrome is pinned sideways on purpose — you turn the phone anticlockwise to
 * shoot, which brings the camera key round to the top edge where a shutter release belongs, and the
 * band comes with it. A scan result is not chrome. It is a paragraph of text you stopped to read,
 * and you read it holding the phone the way you were already holding it when you pointed it at the
 * code — which, for a poster or a menu or a parking meter, is upright. The first version wrapped
 * this in [HeldSideways] like every other panel and it was sideways text on an upright phone.
 *
 * So it is [RotatedToDevice] off the accelerometer instead: at 0 it lays out portrait and fills the
 * long edge, at 90 it is exactly what [HeldSideways] used to give, and the 60° of hysteresis in
 * [rememberDeviceQuarter] is what stops it flipping while you are halfway through reading it.
 *
 * Three rows at most, which is the LightOS bottom-bar rule applied to a sheet: OPEN, COPY, and the
 * way out. A payload with nowhere to go drops OPEN rather than showing it greyed — a row you cannot
 * press is a row explaining itself.
 *
 * The title is the host, or the network name, or the person's name — the part that answers "do I
 * want this", set in Heading. The raw payload is underneath in Detail, scrollable, because on a code
 * with a tracking query string forty characters long the thing that matters is the domain and the
 * rest is evidence.
 */
@Composable
private fun ScanSheet(
    raw: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onCopyPassword: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = LightThemeTokens.colors
    val kind = remember(raw) { Codes.kindOf(raw) }
    val title = remember(raw) { Codes.title(raw) }
    val target = remember(raw) { Codes.openable(raw) }
    val wifi = remember(raw) { Codes.wifi(raw) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colours.background)
            // Eats every touch, or the swipe down to the roll would drag the pager underneath it.
            .swallowTaps(),
    ) {
        // `opaque = false`: the Box above has already painted the background across the whole panel,
        // and a second fill inside the rotation would letterbox the corners in a different black.
        RotatedToDevice(quarter = rememberDeviceQuarter(), opaque = false) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LightText(Codes.heading(kind), LightTextVariant.Detail)
                        Spacer(Modifier.weight(1f))
                        ChromeIcon(icon = LightIcons.Close, lighten = true, onClick = onClose)
                    }
                    Spacer(Modifier.height(6.dp))
                    LightText(title, LightTextVariant.Heading)
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (wifi != null) {
                            // A Wi-Fi code is the one payload whose parts are separately useful:
                            // nobody wants `WIFI:S:...;T:WPA;P:...;;` on the clipboard, they want the
                            // password, and they want to be able to read it off the screen while
                            // typing it into a laptop.
                            LightText("Network  ${wifi.ssid}", LightTextVariant.Detail, lighten = true)
                            if (wifi.password.isNotEmpty()) {
                                LightText(
                                    "Password  ${wifi.password}",
                                    LightTextVariant.Detail,
                                    lighten = true,
                                )
                            }
                            LightText(
                                "Security  ${wifi.security.uppercase()}",
                                LightTextVariant.Detail,
                                lighten = true,
                            )
                        } else {
                            LightText(raw, LightTextVariant.Detail, lighten = true)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (target != null) {
                        ScanAction(label = "Open", onTap = onOpen)
                    }
                    if (wifi != null && wifi.password.isNotEmpty()) {
                        ScanAction(label = "Copy password", onTap = onCopyPassword)
                    } else {
                        ScanAction(label = "Copy", lighten = target != null, onTap = onCopy)
                    }
                    ScanAction(label = "Scan again", lighten = true, onTap = onClose)
                }
            }
        }
    }
}

@Composable
private fun ScanAction(label: String, lighten: Boolean = false, onTap: () -> Unit) {
    LightText(
        text = label.uppercase(),
        variant = LightTextVariant.Button,
        lighten = lighten,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onTap() }
            .padding(vertical = 12.dp),
    )
}

/**
 * The ten adjustments, in a column beside a **live** viewfinder.
 *
 * **The frame stays up, and that is the entire design.** A grade is not a setting you can reason
 * about from its numbers — "warmth +2" means nothing until you see it on the thing you are pointing
 * at — so a full-screen menu was the wrong shape for it. The list takes a narrow strip and the
 * photograph keeps the rest, which is why the labels are [Adjust.shortLabel] and why the hint line
 * was dropped: at this width they would not fit, and with the picture right there they are not
 * needed.
 *
 * Only the strip swallows taps. The viewfinder beside it is left alone so the shutter, the wheel and
 * the half press all still work while the panel is open — you adjust and shoot without closing
 * anything, which is the whole reason to keep the frame visible.
 */
@Composable
private fun AdjustPanel(
    grade: Grade,
    onStep: (Adjust, Int) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = LightThemeTokens.colors
    Box(modifier = modifier.fillMaxSize()) {
        // Transparent, so the photograph is still there beside the strip. This is the only caller
        // that passes it; see [HeldSideways].
        HeldSideways(opaque = false) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(ADJUST_COLUMN)
                        // A scrim rather than the solid background: the strip has to be readable
                        // over whatever is behind it without becoming a second opaque panel.
                        .background(colours.scrim)
                        .swallowTaps()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LightText(text = "ADJUST", variant = LightTextVariant.Superfine)
                        Spacer(Modifier.weight(1f))
                        if (grade.touched > 0) {
                            LightText(
                                text = "RESET",
                                variant = LightTextVariant.Superfine,
                                lighten = true,
                                modifier = Modifier
                                    .lightClickable(onClick = onReset)
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                        ChromeIcon(
                            icon = LightIcons.Close,
                            size = 11.dp,
                            lighten = true,
                            onClick = onClose,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Adjust.entries.forEach { adjust ->
                            AdjustRow(
                                adjust = adjust,
                                value = grade[adjust],
                                onStep = { by -> onStep(adjust, by) },
                            )
                        }
                    }
                }
                // The photograph. Nothing is drawn over it and nothing intercepts it.
                Spacer(Modifier.weight(1f - ADJUST_COLUMN).fillMaxHeight())
            }
        }
    }
}

/**
 * How much of the width the strip takes. The frame keeps the rest.
 *
 * A third was too wide to be worth it and a fifth could not fit `Sharp` beside `+2`. This is the
 * narrowest the column goes without the numbers wrapping.
 */
private const val ADJUST_COLUMN = 0.28f

/**
 * One adjustment: short name, then `-`, the value, `+`.
 *
 * The value sits in a fixed-width box so the plus does not shuffle sideways between `0` and `-5` —
 * a control that moves under your thumb as you use it is one you cannot hold down. At the end of a
 * range the arrow dims and stops; no wrap, because unlike the filter dial a scale with a middle has
 * nothing circular about it, and jumping from +5 to -5 on one press is the kind of surprise this app
 * is trying not to have.
 */
@Composable
private fun AdjustRow(adjust: Adjust, value: Int, onStep: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = adjust.shortLabel,
            variant = LightTextVariant.Superfine,
            // Set adjustments read at full strength and untouched ones recede, so a glance tells
            // you what this preset is without reading ten numbers.
            lighten = value == 0,
        )
        Spacer(Modifier.weight(1f))
        Stepper(text = "-", enabled = value > adjust.min) { onStep(-1) }
        Box(modifier = Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            LightText(
                text = adjust.display(value),
                variant = LightTextVariant.Superfine,
                align = TextAlign.Center,
                lighten = value == 0,
            )
        }
        Stepper(text = "+", enabled = value < adjust.max) { onStep(1) }
    }
}

/** One end of a stepper. A character rather than an icon, because the icon set has no plus. */
@Composable
private fun Stepper(text: String, enabled: Boolean, onClick: () -> Unit) {
    LightText(
        text = text,
        variant = LightTextVariant.Button,
        align = TextAlign.Center,
        lighten = !enabled,
        modifier = Modifier
            .lightClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}
