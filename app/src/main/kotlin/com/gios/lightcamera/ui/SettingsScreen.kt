package com.gios.lightcamera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.LightKeys
import com.gios.light.common.hw.WheelScroll
import com.gios.lightcamera.BandSlot
import com.gios.lightcamera.Chrome
import com.gios.lightcamera.Colour
import com.gios.light.common.report.CrashLog
import com.gios.lightcamera.PhotoSize
import com.gios.lightcamera.SelfTimer
import com.gios.lightcamera.StampStyle
import com.gios.lightcamera.camera.AfMode
import com.gios.lightcamera.camera.FrameAspect
import com.gios.lightcamera.camera.PuriArt
import com.gios.lightcamera.camera.PuriStrip
import com.gios.lightcamera.filter.Filters
import com.gios.lightcamera.hw.Binding
import com.gios.lightcamera.hw.CameraKeyAdvice
import com.gios.lightcamera.hw.Controls
import com.gios.lightcamera.hw.DialAction
import com.gios.lightcamera.hw.PressAction
import com.gios.lightcamera.hw.WheelClickWitness
import com.gios.lightcamera.send.Handoff
import com.gios.lightcamera.ui.theme.LightIcons
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.LightThemeTokens
import com.gios.lightcamera.ui.theme.lightClickable
import com.gios.lightcamera.media.CaptureFormat
import com.gios.lightcamera.camera.ExposureMode
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import com.gios.lightcamera.map.Locations

/**
 * Which page of settings is showing.
 *
 * **Five pages because one list stopped working.** Every setting here is worth having and the
 * prose next to them is worth reading, and together they were a single column about eleven screens
 * long on a 3.92" panel — so finding the film roll meant scrolling past the date back, and the
 * only way to know whether a setting existed was to read all of it. The tabs are the same rows in
 * the same order, cut where the subject changes.
 */
enum class SettingsTab(val label: String) {
    Frame("FRAME"),
    Camera("CAMERA"),
    Look("LOOK"),
    Controls("KEYS"),
    Film("FILM"),
    About("ABOUT"),
}

/**
 * Settings.
 *
 * Every row is a value you cycle by tapping it rather than a switch or a dialog, which is
 * how LightOS does settings and also the only shape that stays legible at this width.
 *
 * **The prose is folded away.** The notes under each section are the best documentation this app
 * has — they say why a setting is the way it is, which is the thing nobody can work out from the
 * row — but they were also most of the vertical, so a page of six settings read as a page of two.
 * Each section now carries a `?`, and what is behind it is unchanged.
 */
@Composable
fun SettingsScreen(vm: CameraViewModel, onClose: () -> Unit, onOpenFilterPicker: () -> Unit = {}) {
    val colours = LightThemeTokens.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by remember { mutableStateOf(SettingsTab.Frame) }
    // **A scroll position per tab.** One `rememberScrollState` shared between them left the new tab
    // opened halfway down, at whatever offset the last one happened to be at.
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    val roll by vm.roll.collectAsState()

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
                "SETTINGS",
                LightTextVariant.Detail,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            ChromeIcon(icon = LightIcons.Close, onClick = onClose)
        }

        TabRow(current = tab, onPick = { tab = it })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, bottom = 40.dp),
        ) {
            when (tab) {
                SettingsTab.Frame -> FrameTab(vm)
                SettingsTab.Camera -> CameraTab(vm)
                SettingsTab.Look -> LookTab(vm, context, onOpenFilterPicker)
                SettingsTab.Controls -> ControlsTab(vm, context, colours.content, colours.background)
                SettingsTab.Film -> FilmTab(vm, context, onClose, roll != null)
                SettingsTab.About -> AboutTab(vm, context, colours.rule)
            }
        }
    }
}

/**
 * The tabs, as words.
 *
 * Horizontally scrollable because five words at this tracking are wider than 3.92" and a row that
 * shrank its own type to fit would be the one line on the screen you cannot read. In practice all
 * five fit with the phone turned; upright you nudge it.
 */
@Composable
private fun TabRow(current: SettingsTab, onPick: (SettingsTab) -> Unit) {
    val colours = LightThemeTokens.colors
    val bar = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(bar)
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsTab.entries.forEach { entry ->
            val here = entry == current
            LightText(
                text = entry.label,
                variant = LightTextVariant.Detail,
                // The selected tab is simply the bright one. An underline would be a second
                // vocabulary for "this one" on a screen that already has brightness for it.
                lighten = !here,
                modifier = Modifier
                    .lightClickable { onPick(entry) }
                    .padding(end = 14.dp, top = 6.dp, bottom = 8.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colours.rule),
    )
}

/* ---------------------------------- frame ---------------------------------- */

@Composable
private fun FrameTab(vm: CameraViewModel) {
    val aspect by vm.prefs.aspect.collectAsState()
    val photoSize by vm.prefs.photoSize.collectAsState()
    val chrome by vm.prefs.chrome.collectAsState()
    val level by vm.prefs.level.collectAsState()
    val simpleMode by vm.prefs.simpleMode.collectAsState()

    Section("Frame") {
        Note(
            "Size is a Pro setting, and Simple ignores it. Simple always takes the frame already on the panel, which is the only instant path this camera has. Pro takes a real still, which costs about 1.8 seconds here no matter what it is asked for.",
        )
        Note(
            "Half-pressing still helps Pro. Half-press the camera button before pressing it home. That locks focus and exposure, so the shutter has nothing left to work out. With the flash off it can hand back a frame the camera had already buffered, which is as close to instant as the hardware goes.",
        )
        Note(
            when (photoSize) {
                PhotoSize.Full ->
                    "Everything the sensor has, and slow with it. A 50MP frame is most of a second of readout and encoding before the shutter answers."
                PhotoSize.Screen ->
                    "No capture at all. The frame already on the viewfinder, filtered by the same shader, is saved. Instant, and with a filter on it is exactly the frame you were looking at."
                else ->
                    "Each step down roughly halves the time between pressing the button and having a photograph."
            },
        )
        Note(
            "The viewfinder fills the screen, and the sensor is 4:3. So the photograph keeps a little more than you saw, at the top and bottom of the frame.",
        )
    }
    Setting("Size", photoSize.label) {
        val all = PhotoSize.entries
        vm.prefs.setPhotoSize(all[(all.indexOf(photoSize) + 1) % all.size])
    }
    Setting("Shape", aspect.label) {
        val all = FrameAspect.entries
        vm.prefs.setAspect(all[(all.indexOf(aspect) + 1) % all.size])
    }
    Setting("Grid", chrome.label) {
        val all = Chrome.entries
        vm.prefs.setChrome(all[(all.indexOf(chrome) + 1) % all.size])
    }
    Setting("Level", if (level) "On" else "Off") { vm.prefs.setLevel(!level) }
    Setting("Simple mode", if (simpleMode) "On" else "Off") {
        vm.prefs.setSimpleMode(!simpleMode)
    }

}

/* ---------------------------------- camera ---------------------------------- */

/**
 * Everything about *taking* the photograph, as opposed to what shape it comes out.
 *
 * Split out of FRAME because that tab had grown to the size the un-tabbed screen was when the
 * tabs were introduced — the fault the tabs exist to fix, reproduced inside one of them. The cut
 * is the same rule as then: where the subject changes. FRAME is the picture; CAMERA is the act.
 */
@Composable
private fun CameraTab(vm: CameraViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val afMode by vm.prefs.afMode.collectAsState()
    val facePriority by vm.prefs.facePriority.collectAsState()
    val timer by vm.prefs.timer.collectAsState()
    val sounds by vm.prefs.sounds.collectAsState()
    val facesSupported by vm.engine.facesSupported.collectAsState()

    Section("Exposure aids") {
        Note(
            "Greyscale is the hard case for judging exposure. A face and a window can read as the same grey, with no colour drifting to warn you. The histogram is the whole frame's brightness as a curve. Piled at the left is under, piled at the right is blown. Clipping marks hatch the cells that have gone to pure white, which is the part no amount of editing gets back. Both are drawn from the preview stream, so neither costs the shutter anything, and neither appears in the photograph.",
        )
    }
    val histogram by vm.prefs.histogram.collectAsState()
    val clipping by vm.prefs.clipping.collectAsState()
    Setting("Histogram", if (histogram) "On" else "Off") { vm.prefs.setHistogram(!histogram) }
    Setting("Clipping marks", if (clipping) "On" else "Off") { vm.prefs.setClipping(!clipping) }

    Section("Focus") {
        Note(
            if (facesSupported) {
                "Half press the camera button to focus on the nearest face and hold it. Press through to take the photograph. The mark closes into a box, and beeps, when the lens has it."
            } else {
                "This camera doesn't report faces, so the half press focuses on the centre of the frame."
            },
        )
    }
    Setting("Mode", if (afMode == AfMode.Single) "Single" else "Continuous") {
        vm.prefs.setAfMode(if (afMode == AfMode.Single) AfMode.Continuous else AfMode.Single)
    }
    Setting(
        label = "Faces",
        value = when {
            !facesSupported -> "Unavailable"
            facePriority -> "Priority"
            else -> "Ignore"
        },
        enabled = facesSupported,
    ) {
        vm.prefs.setFacePriority(!facePriority)
    }

    Section("Shutter") {
        Note(
            "The press is the photograph. The shot is taken the instant your finger lands. Filters, encodes and saving drain through a queue behind the viewfinder. The thin " +
                "bar in the status line is the gauge of that queue.\n\n" +
                "Reach back keeps the last moments of the viewfinder, so the shutter can take the frame from " +
                "just before you pressed. You see the expression, then decide, then your thumb moves. It " +
                "costs power while on, and it changes which moment you get. So it is wrong for anything you " +
                "are timing deliberately. With Sharpest of eight on as well, the sharpest held frame wins.\n\n" +
                "The sensor ring buffer trades a heavier live preview for buffered captures. On this phone " +
                "the preview usually matters more, which is why it ships off by default.",
        )
    }
    val burst by vm.prefs.burst.collectAsState()
    Setting("Self timer", timer.label) {
        val all = SelfTimer.entries
        vm.prefs.setTimer(all[(all.indexOf(timer) + 1) % all.size])
    }
    Setting("Sharpest of eight", if (burst) "On" else "Off") { vm.prefs.setBurst(!burst) }
    val preRoll by vm.prefs.preRollMs.collectAsState()
    Setting("Reach back", if (preRoll <= 0) "Off" else "${preRoll}ms") {
        val all = vm.prefs.preRollChoices
        vm.prefs.setPreRollMs(all[(all.indexOf(preRoll).coerceAtLeast(0) + 1) % all.size])
    }
    Setting("Sounds", if (sounds) "Focus beep" else "Off") { vm.prefs.setSounds(!sounds) }
    val zslRing by vm.prefs.zslRing.collectAsState()
    // Its cost written on the switch, because the switch is the cost: the ring runs a second
    // full-resolution stream beside the preview the whole time the camera is up.
    Setting("Sensor ring buffer", if (zslRing) "On, smoother shots, heavier preview" else "Off") {
        vm.prefs.setZslRing(!zslRing)
    }

    Section("Exposure") {
        Note(
            "Auto is the camera deciding both halves. The two priority modes hand you one of them " +
                "and leave the other to the meter. Hold the shutter open and the sensitivity " +
                "follows, or pin the sensitivity and the shutter follows. Camera2 has no " +
                "half-manual exposure, so this is built from the metered pair rather than asked " +
                "for. That is why it needs a moment of Auto first, to have something to hold.\n\n" +
                "Flat turns off noise reduction, edge enhancement and the tone curve. What comes " +
                "back is demosaiced and white-balanced and nothing else. Low contrast, and far " +
                "better to grade than something already contrast-stretched. It also makes the " +
                "filters better, because a shader is otherwise grading a grade.\n\n" +
                "Zone focus sets the lens by distance instead of pointing it at something. The " +
                "distances are worked out from this lens rather than copied. Hyperfocal falls " +
                "out of focal length, aperture and sensor size, and is a different number on the " +
                "selfie camera.",
        )
    }
    val exposureMode by vm.engine.exposureMode.collectAsState()
    val flat by vm.engine.flat.collectAsState()
    val lensCorrection by vm.engine.lensCorrection.collectAsState()
    val zone by vm.engine.zoneFocus.collectAsState()
    // Written to prefs, not to the engine: the collector in the view model carries them over, and
    // prefs is what survives a relaunch. The engine flows below are still what is *read*, so the
    // labels report the camera rather than the wish.
    Setting("Exposure", exposureMode.label) {
        val all = ExposureMode.entries
        vm.prefs.setExposureMode(all[(all.indexOf(exposureMode) + 1) % all.size])
    }
    Setting("Flat profile", if (flat) "On" else "Off") { vm.prefs.setFlat(!flat) }
    Setting("Lens correction", if (lensCorrection) "On" else "Off") {
        vm.prefs.setLensCorrection(!lensCorrection)
    }
    Setting("Zone focus", if (zone) "On" else "Off") { vm.prefs.setZoneFocus(!zone) }
    val feet by vm.prefs.feet.collectAsState()
    Setting("Distances", if (feet) "Feet" else "Meters") { vm.prefs.setFeet(!feet) }

    Section("Location") {
        Note(
            "Every photograph gets the coordinate the phone last had, which is what puts it on the " +
                "map. It costs nothing at the shutter. The last known position is used rather " +
                "than a fresh fix, because a camera must not wait for GPS.\n\n" +
                "It is worth knowing what this means. A coordinate lives inside the file and " +
                "travels with it, so a photograph you send carries where you were. Turn it off " +
                "and photographs from then on have none. The ones already taken keep theirs.\n\n" +
                "The map itself fetches tiles from OpenStreetMap, which is the only time this app " +
                "opens a connection other than sending a bug report. Tiles are kept once fetched.",
        )
    }
    val tagLocation by vm.prefs.tagLocation.collectAsState()
    // The toggle asks for the grant it needs, at the moment it starts needing it. The setting
    // defaulted on with nothing anywhere requesting the permission, so it read "On" while
    // Locations.lastKnown returned null on every shot — a switch reporting a state that was not
    // happening.
    val askTag = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> }
    Setting(
        "Tag photographs",
        when {
            !tagLocation -> "Off"
            Locations.canTag(context) -> "On"
            else -> "Needs permission"
        },
    ) {
        val next = !tagLocation
        vm.prefs.setTagLocation(next)
        if (next && !Locations.canTag(context)) askTag.launch(Locations.wanted())
    }

    Section("Files") {
        Note(
            "One press can write more than one file, and they stay one photograph on the roll. " +
                "The formats are alternatives to each other, not to the picture. Lossless is for " +
                "the flat-colour filters. Dither, Halftone and Game Boy are made of hard edges " +
                "beside flat fields, which is the exact thing JPEG smears. The pattern is the " +
                "photograph. It costs a decode, a second encode and about 30MB a shot.\n\n" +
                "RAW is the sensor's own readout before the picture is made, so no filter can " +
                "reach it. There is nothing to put a shader on yet, which is exactly what a " +
                "negative is for. It comes with its JPEG from the same exposure, not a second one.\n\n" +
                "Pro only. Simple writes the sensor's own JPEG untouched, and that is the whole " +
                "point of it.",
        )
    }
    val formats by vm.prefs.formats.collectAsState()
    // Whether this camera can produce a DNG at all is the camera's answer, re-read on every bind.
    // RAW is an optional capability and the selfie sensor commonly lacks it where the main one has
    // it, so the switch says "Unavailable" rather than turning on and failing at the shutter.
    val rawSupported by vm.engine.negativeSupported.collectAsState()
    CaptureFormat.entries.forEach { format ->
        val supported = format != CaptureFormat.Dng || rawSupported
        val on = format in formats && supported
        val only = on && formats.size == 1
        Setting(
            label = when (format) {
                CaptureFormat.Jpeg -> "JPEG"
                CaptureFormat.Png -> "Lossless PNG"
                CaptureFormat.Dng -> "RAW negative"
            },
            // The last format standing says so rather than silently refusing the tap, because a
            // control that does nothing and explains nothing reads as a bug.
            value = when {
                !supported -> "Unavailable"
                only -> "Only"
                on -> "On"
                else -> "Off"
            },
            enabled = supported && !only,
        ) {
            vm.prefs.toggleFormat(format)
        }
    }
}

/* ---------------------------------- look ---------------------------------- */

@Composable
private fun LookTab(vm: CameraViewModel, context: android.content.Context, onOpenFilterPicker: () -> Unit) {
    val stampPlain by vm.prefs.stampPlain.collectAsState()
    val stampFiltered by vm.prefs.stampFiltered.collectAsState()
    val stampCoarse by vm.prefs.stampCoarse.collectAsState()
    val stampStyle by vm.prefs.stampStyle.collectAsState()
    val colour by vm.prefs.colour.collectAsState()

    Section("Date") {
        Note(
            "The date prints like a 1990s compact's. Month, day, apostrophe-year, in leaning amber dots in the corner of the frame. Coarse filters are separate and start off. Dither 16, 1-Bit, Halftone and the two Game Boys quantise the picture onto a grid of a few hundred cells, and a date drawn at full precision over that reads as a caption pasted on rather than something the camera did. It is printed into the photograph, so it costs a decode and a re-encode on a shot that would otherwise be saved exactly as the camera made it. And there is no taking it off afterwards.",
        )
    }
    Setting("On plain photos", if (stampPlain) "On" else "Off") {
        vm.prefs.setStampPlain(!stampPlain)
    }
    Setting("On filters", if (stampFiltered) "On" else "Off") {
        vm.prefs.setStampFiltered(!stampFiltered)
    }
    Setting("On coarse filters", if (stampCoarse) "On" else "Off") {
        vm.prefs.setStampCoarse(!stampCoarse)
    }
    if (stampPlain || stampFiltered || stampCoarse) {
        Setting("Style", stampStyle.label) {
            val all = StampStyle.entries
            vm.prefs.setStampStyle(all[(all.indexOf(stampStyle) + 1) % all.size])
        }
    }

    Section("Filters") {
        Note(
            "Which filters are on the wheel, and in what order. Tap a name to take it off. It stays in this list, so you can put it back. It just stops being a notch you have to spin past. The arrows move it. Plain cannot be taken off. It is what the camera does when it is not doing anything, and Video, Simple and Reader are all it.\n\nThe grid and the wheel both read this, so they always agree. A filter added by a later version of Roll arrives at the bottom of the list switched on, rather than being hidden by an order saved before it existed.",
        )
    }
    FilterList(vm)
    Action("VIEW FILTERS") { onOpenFilterPicker() }

    Section("Purikura") {
        Note(
            "The same controls as the viewfinder's Purikura menu. Choose Purikura on the wheel and tap OPTIONS to change the frame, stickers, date and strip next to the picture. Everything here is remembered between launches. Set it once, and the booth keeps it.",
        )
    }
    PurikuraList(vm)

    Section("Colour") {
        Note(
            // Either half is enough to be in colour, and both have to be checked. The grant is
            // Roll doing it for itself; `phoneIsColour` is the phone already being in colour —
            // BrightControl holding it there, say — which Roll leaves alone. Reading only the
            // grant printed an adb line at somebody looking at a colour screen.
            if (ColorMode.granted(context) || ColorMode.phoneIsColour(context)) {
                "The panel is a full-colour AMOLED. Light's black and white is the accessibility daltonizer pinned to monochromacy. Roll lifts it wherever a photograph is on screen. The viewfinder, the roll and the full-screen viewer are in colour. When you leave the app, it goes back."
            } else {
                "Needs one adb grant, then the viewfinder and the roll are in colour.\n\nadb shell pm grant com.gios.lightcamera android.permission.WRITE_SECURE_SETTINGS\n\nUntil then everything stays grey, which is harmless. The write is refused."
            },
        )
    }
    Setting("Show", colour.label) {
        val all = Colour.entries
        vm.prefs.setColour(all[(all.indexOf(colour) + 1) % all.size])
    }
}

/* -------------------------------- controls -------------------------------- */

/**
 * The keys, and the two free slots in the band.
 *
 * **The one page in here that can lock you out of your own camera**, which is why every row goes
 * through [Controls.shutterSafe] and why an option that would leave no shutter is skipped over
 * rather than offered and refused. See the note in that function.
 */
@Composable
private fun ControlsTab(
    vm: CameraViewModel,
    context: android.content.Context,
    warnInk: Color,
    warnPaper: Color,
) {
    val wheel by vm.prefs.wheelEnabled.collectAsState()
    val dialLock by vm.prefs.dialLock.collectAsState()
    val bindings by vm.prefs.bindings.collectAsState()
    val slots by vm.prefs.bandSlots.collectAsState()
    val keyProblem = remember { CameraKeyAdvice.problem(context) }
    val cameraKeyWorks = keyProblem == null
    // Read on every recomposition rather than remembered: you come to this screen, click the
    // wheel, and look — a value captured when the tab opened would still say "never".
    val clickWitness = WheelClickWitness.readout()

    if (keyProblem != null) {
        // Inverted, because a dead shutter is not a footnote.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(warnInk)
                .padding(10.dp),
        ) {
            LightText(
                text = keyProblem,
                variant = LightTextVariant.Detail,
                color = warnPaper,
            )
        }
    }

    Section("Keys") {
        Note(
            "The camera button is not on this list and never will be. Half press to focus, press through to shoot. Everything else can be pointed somewhere. The defaults are what the app has always done. The wheel walks the filters, holding it in and turning is exposure, clicking it is the torch, and either volume key is a shutter. v2.49 briefly moved the click to the dial lock. v2.50 gave it back.",
        )
        Note(
            "There is no shutter button on the screen, because the phone has one on its side. So one control has to remain a shutter. If the camera button is being swallowed by an accessibility service, LightControl most likely, the volume keys are the only shutter left. An option that would take the last one away is skipped rather than offered.",
        )
        Note(
            if (LightKeys.wheelLabelsPresent()) {
                "A filter dial is unarmed. Each notch counts once and None is three notches wide, so a flick lands somewhere harmless. Exposure and zoom are armed, so a fast turn racks through them. Whichever way the wheel is pointed, an open strip takes it for as long as it is open."
            } else {
                "This build doesn't map the wheel keys, so the three wheel rows below may do nothing. The volume keys are AOSP keycodes and work on any build."
            },
        )
        // Only where there is a wheel to lock. The note above already says what a build without one
        // can expect, and saying it twice reads as a bug in the settings screen.
        if (LightKeys.wheelLabelsPresent()) {
            Note(
                "Dial lock is off unless you turn it on. With it on, the filter dial starts asleep every time the app opens. The wheel is shared with the rest of the phone and turns in a pocket. A click on the wheel wakes it, a second click puts it back. A turn while it is asleep says so instead of moving anything. Holding the wheel in and turning is never locked, and neither is an open strip. You cannot make either gesture by accident. While the lock is on, the wheel click belongs to it and not to whatever else it is pointed at.",
            )
        }
        Note(
            "A key set to Nothing is given back to the phone rather than eaten, so a volume key with no job here still changes the volume.",
        )
    }
    // **The one readout that tells two identical-looking faults apart.** The wheel click is the
    // only control here another app decides whether you get: LightControl filters keys before the
    // focused window and its factory default binds the click to the torch. So "the click does
    // nothing" is either a key that never arrived or a key that arrived and did nothing visible,
    // and from the phone those look the same. Click the wheel and read this row.
    if (LightKeys.wheelLabelsPresent()) {
        Section("Wheel click")
        Note(clickWitness)
    }
    Setting("Wheel", if (wheel) "On" else "Off") { vm.prefs.setWheelEnabled(!wheel) }
    // **Reachable by touch, and that is the point of it rather than a convenience.** The lock
    // disables the wheel, and the wheel is how the mode picker and therefore this screen are
    // normally reached — so the switch that turns it off must not need the wheel to press it.
    if (LightKeys.wheelLabelsPresent()) {
        Setting("Dial lock", if (dialLock) "On" else "Off") { vm.setDialLock(!dialLock) }
    }
    Binding.entries.forEach { binding ->
        val current = bindings[binding] ?: binding.default
        Setting(
            label = binding.label,
            value = if (binding.dial) {
                DialAction.byName(current).label
            } else {
                PressAction.byName(current).label
            },
            enabled = !binding.dial || wheel,
        ) {
            val next = nextBinding(vm, binding, current, cameraKeyWorks)
            if (next == null) {
                vm.showNotice("Keep one shutter")
            } else {
                vm.prefs.setBinding(binding, next)
            }
        }
    }
    Action("Back to the defaults") {
        vm.prefs.resetBindings()
        vm.showNotice("Keys reset")
    }

    Section("Band") {
        Note(
            "The two slots at the end of the band, after the flash. The album, the mode chip and the flash are the stock camera's own bar in the stock camera's own order, and they stay put. These two are yours. Exposure and nothing is where the app started.",
        )
        Note(
            "Front / rear is worth a slot. Until now the only way to turn the camera round was a double tap on the viewfinder, which is not a thing anybody finds. Zoom is worth one too. The lens is fixed and the crop is digital. But a digital crop is still the difference between a photograph of a sign and a photograph of the wall it is on. And a pinch on a 3.92\" panel held sideways in one hand is not a control.",
        )
    }
    slots.forEachIndexed { index, slot ->
        Setting("Slot ${index + 1}", slot.label) {
            val all = BandSlot.entries
            vm.prefs.setBandSlot(index, all[(all.indexOf(slot) + 1) % all.size])
        }
    }
}

/**
 * The next action for [binding], skipping any that would leave the camera with no shutter.
 *
 * Null when every option is unsafe, which can only happen if this is the last shutter on a phone
 * whose camera key is being swallowed — in which case the row is a dead end by design and says so.
 */
private fun nextBinding(
    vm: CameraViewModel,
    binding: Binding,
    current: String,
    cameraKeyWorks: Boolean,
): String? {
    val options: List<String> = if (binding.dial) {
        DialAction.entries.map { it.name }
    } else {
        PressAction.assignable.map { it.name }
    }
    val at = options.indexOf(current).coerceAtLeast(0)
    for (step in 1..options.size) {
        val candidate = options[(at + step) % options.size]
        if (binding.dial) return candidate
        val up = if (binding == Binding.VolumeUp) {
            PressAction.byName(candidate)
        } else {
            vm.prefs.pressFor(Binding.VolumeUp)
        }
        val down = if (binding == Binding.VolumeDown) {
            PressAction.byName(candidate)
        } else {
            vm.prefs.pressFor(Binding.VolumeDown)
        }
        val click = if (binding == Binding.WheelClick) {
            PressAction.byName(candidate)
        } else {
            vm.prefs.pressFor(Binding.WheelClick)
        }
        if (Controls.shutterSafe(up, down, click, cameraKeyWorks)) return candidate
    }
    return null
}

/* ---------------------------------- film ---------------------------------- */

@Composable
private fun FilmTab(
    vm: CameraViewModel,
    context: android.content.Context,
    onClose: () -> Unit,
    loaded: Boolean,
) {
    val rollLength by vm.prefs.rollLength.collectAsState()
    val roll by vm.roll.collectAsState()
    val recents by vm.prefs.recentRecipients.collectAsState()
    var confirmDiscard by remember { mutableStateOf(false) }

    Section("Film") {
        Note(
            "With a roll loaded, photographs go onto the roll instead of into the gallery. No preview and no review, just a counter, until you develop it. Developing writes every frame into the camera roll. Each frame keeps the time it was taken.",
        )
    }
    if (!loaded) {
        Setting("Frames per roll", "$rollLength") {
            vm.prefs.setRollLength(
                when (rollLength) {
                    12 -> 24
                    24 -> 36
                    else -> 12
                },
            )
        }
        Action("Load a roll") { vm.loadRoll(); onClose() }
    } else {
        val current = roll
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText("Roll ${current?.number}", LightTextVariant.Copy)
            Spacer(Modifier.weight(1f))
            LightText(
                "${current?.shot} of ${current?.length}",
                LightTextVariant.Copy,
                lighten = true,
            )
        }
        RollCounter(
            roll = current,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        )
        Action(if ((current?.shot ?: 0) == 0) "Unload" else "Develop") {
            vm.developRoll()
            onClose()
        }
        if ((current?.shot ?: 0) > 0) {
            Action(
                if (confirmDiscard) "Tap again to throw the roll away" else "Discard",
                lighten = true,
            ) {
                if (confirmDiscard) {
                    vm.discardRoll()
                    confirmDiscard = false
                    onClose()
                } else {
                    confirmDiscard = true
                }
            }
        }
    }

    // Remembered: it's a PackageManager binder call, and inside composition it would run
    // on every recomposition of the settings list.
    val lightChatTakesPhotos = remember { Handoff.lightChatCanReceive(context) }
    Section("Sending") {
        Note(
            if (lightChatTakesPhotos) {
                "The send button opens your contacts and hands the photograph to LightChat, already addressed. The people you send to most recently sit at the top of that list. Tap here to forget them."
            } else {
                "The send button opens your contacts rather than a grid of apps. LightChat can't receive photographs on this build, so a send goes to whatever else can take one. The person has to be chosen again inside it."
            },
        )
    }
    Setting(
        label = "Recents",
        value = if (recents.isEmpty()) "None yet" else "${recents.size} kept",
        enabled = recents.isNotEmpty(),
    ) {
        vm.prefs.clearRecentRecipients()
        vm.showNotice("Recents cleared")
    }
}

/* --------------------------------- about --------------------------------- */

@Composable
private fun AboutTab(vm: CameraViewModel, context: android.content.Context, rule: Color) {

    Section("Feedback") {
        Note(
            "Send feedback opens the same report the shake gesture offers. A note and an optional " +
                "screenshot, sent only when you confirm. The shake needs four sharp direction " +
                "changes in about a second, which is deliberate. A camera gets carried, and a " +
                "gesture that fires in a pocket reports nothing but the pocket.",
        )
    }
    Setting(
        "Send feedback",
        if (com.gios.light.common.report.LightReport.installed) "Opens the report chip" else "Reporting is off in this build",
    ) {
        com.gios.light.common.report.Feedback.ask()
    }
    val colours = LightThemeTokens.colors
    val timings by vm.prefs.timings.collectAsState()

    Section("Developer") {
        Note(
            "After each photograph, how long it took in milliseconds. The capture, and in Simple the save as well. The capture number is the camera hardware answering. The save is this app writing the file. It is here rather than up with the camera settings because it is a measurement, not a preference. It answered its question already (1.8 s in the camera, 87 ms in the app). What it is for now is checking whether a change did what it claimed.",
        )
    }
    Setting("Shutter timings", if (timings) "On" else "Off") { vm.prefs.setTimings(!timings) }

    val crash = remember { CrashLog.read(context) }
    if (crash != null) {
        Section("Last crash") {
            Note("Roll fell over. The trace is below. The first few lines are the ones that matter. Tap to clear it.")
        }
        var cleared by remember { mutableStateOf(false) }
        if (!cleared) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(rule)
                    .lightClickable {
                        CrashLog.clear(context)
                        cleared = true
                    }
                    .padding(8.dp),
            ) {
                LightText(
                    text = crash.lineSequence().take(14).joinToString("\n"),
                    variant = LightTextVariant.Superfine,
                )
            }
        }
    }

    Section("About")
    Note(
        "Roll, a camera for the Light Phone III. Filters are AGSL shaders applied to the live preview and to the photograph by the same code, so the file matches the frame.",
    )
    Box(Modifier.height(24.dp))
    LightText(
        "github.com/gi-os/LightCamera",
        LightTextVariant.Superfine,
        lighten = true,
    )
    Box(Modifier.height(8.dp))
    LightText(
        "Icons and design tokens from lightphone/light-sdk, MIT.",
        LightTextVariant.Superfine,
        color = colours.contentFaint,
    )
}

/* --------------------------------- pieces --------------------------------- */

/**
 * A section heading, and the prose behind it.
 *
 * The `?` is only drawn when there is something under it, so a heading with no notes is exactly the
 * heading it was before. Collapsed by default: the notes are for the once you wonder, not for every
 * time you come in to change the timer.
 */
/**
 * The whole catalog, switched on or off and in whatever order you put it.
 *
 * Drawn from [Filters.ordered] with nothing hidden, so a filter you switched off is still a row
 * here — it has to be, or there would be no way to get it back. What "off" means is "not a notch
 * on the wheel", which is the only thing anyone wants from this screen: the dial has no way to
 * jump, so every filter you never shoot is something you spin past to reach one you do.
 *
 * Two arrows rather than a drag. A long-press-and-drag reorder inside a screen that is itself
 * inside a vertical scroll is a gesture fight, and it would be the one control in the app that
 * needs a finger held still on a phone you are usually holding one-handed.
 */
@Composable
private fun FilterList(vm: CameraViewModel) {
    val order by vm.prefs.filterOrder.collectAsState()
    val off by vm.prefs.filtersOff.collectAsState()
    val rows = remember(order) { Filters.ordered(order, emptySet()) }
    val on = rows.count { it.id !in off }

    rows.forEachIndexed { index, filter ->
        val plain = filter.id == Filters.none.id
        FilterRow(
            label = filter.label,
            on = plain || filter.id !in off,
            plain = plain,
            atTop = index == 0,
            atBottom = index == rows.lastIndex,
            onToggle = { vm.prefs.toggleFilter(filter.id) },
            onUp = { vm.prefs.moveFilter(filter.id, -1) },
            onDown = { vm.prefs.moveFilter(filter.id, 1) },
        )
    }

    Note("$on of ${rows.size} on the wheel.")
    Action("RESET TO DEFAULT", lighten = true) { vm.prefs.resetFilters() }
}

@Composable
private fun FilterRow(
    label: String,
    on: Boolean,
    plain: Boolean,
    atTop: Boolean,
    atBottom: Boolean,
    onToggle: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            lighten = !on,
            modifier = Modifier
                .weight(1f)
                .lightClickable(enabled = !plain) { onToggle() }
                .padding(vertical = 10.dp),
        )
        LightText(
            // "Always" rather than a greyed-out "On": the row does not respond to a tap, and a
            // control that looks like the others and does nothing is worse than one that says why.
            text = if (plain) "ALWAYS" else if (on) "ON" else "OFF",
            variant = LightTextVariant.Detail,
            lighten = !on,
        )
        Arrow("↑", enabled = !atTop, onTap = onUp)
        Arrow("↓", enabled = !atBottom, onTap = onDown)
    }
}

/**
 * The purikura switches, mirroring the viewfinder's OPTIONS menu: frame, the two sticker kinds,
 * date, four-shot, and the five look filters. Everything is persisted — the old note claiming
 * "nothing here is kept between launches" was a lie from before the prefs were written down.
 */
@Composable
private fun PurikuraList(vm: CameraViewModel) {
    val frameId by vm.prefs.puriFrame.collectAsState()
    val faceStickers by vm.prefs.puriFaceStickers.collectAsState()
    val marginStickers by vm.prefs.puriMarginStickers.collectAsState()
    val dateId by vm.prefs.puriDate.collectAsState()
    val stripId by vm.prefs.puriStrip.collectAsState()
    val wash by vm.prefs.puriWash.collectAsState()
    val skin by vm.prefs.puriSkin.collectAsState()
    val eyes by vm.prefs.puriEyes.collectAsState()
    val chin by vm.prefs.puriChin.collectAsState()
    val slim by vm.prefs.puriSlim.collectAsState()

    val frameOptions = listOf(PuriArt.RANDOM to "Random") +
        PuriArt.frames.map { it.id to it.label }
    val dateOptions = listOf(PuriArt.RANDOM to "Random", PuriArt.OFF to "Off") +
        PuriArt.dates.map { it.id to it.label }
    val stripOptions = listOf(PuriStrip.OFF to "Off", PuriArt.RANDOM to "Random") +
        PuriStrip.layouts.drop(1).map { it.id to it.label }

    Setting("Frame", labelFor(frameId, frameOptions)) {
        vm.prefs.setPuriFrame(nextAfter(frameId, frameOptions))
    }
    Setting("Face stickers", if (faceStickers) "On" else "Off") {
        vm.prefs.setPuriFaceStickers(!faceStickers)
    }
    Setting("Margin stickers", if (marginStickers) "On" else "Off") {
        vm.prefs.setPuriMarginStickers(!marginStickers)
    }
    Setting("Date", labelFor(dateId, dateOptions)) {
        vm.prefs.setPuriDate(nextAfter(dateId, dateOptions))
    }
    Setting("Four-shot", labelFor(stripId, stripOptions)) {
        vm.prefs.setPuriStrip(nextAfter(stripId, stripOptions))
    }
    Setting("Pink wash", if (wash) "On" else "Off") { vm.prefs.setPuriWash(!wash) }
    Setting("Skin", if (skin) "On" else "Off") { vm.prefs.setPuriSkin(!skin) }
    Setting("Bigger eyes", if (eyes) "On" else "Off") { vm.prefs.setPuriEyes(!eyes) }
    Setting("Narrow chin", if (chin) "On" else "Off") { vm.prefs.setPuriChin(!chin) }
    Setting("Smaller face", if (slim) "On" else "Off") { vm.prefs.setPuriSlim(!slim) }
}

/** "Random", "Off", or the label of whatever was chosen. */
private fun labelFor(id: String, options: List<Pair<String, String>>): String = when (id) {
    PuriArt.RANDOM -> "Random"
    PuriArt.OFF -> "Off"
    else -> options.firstOrNull { it.first == id }?.second ?: "Random"
}

/** The option after [id] in [options], wrapping around the end. */
private fun nextAfter(id: String, options: List<Pair<String, String>>): String {
    val i = options.indexOfFirst { it.first == id }
    val next = if (i < 0) 0 else (i + 1) % options.size
    return options[next].first
}

@Composable
private fun Arrow(glyph: String, enabled: Boolean, onTap: () -> Unit) {
    LightText(
        text = glyph,
        variant = LightTextVariant.Button,
        lighten = !enabled,
        modifier = Modifier
            .lightClickable(enabled = enabled) { onTap() }
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun Section(title: String, help: (@Composable () -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = title.uppercase(),
            variant = LightTextVariant.Detail,
            lighten = true,
        )
        if (help != null) {
            Spacer(Modifier.weight(1f))
            LightText(
                text = if (open) "×" else "?",
                variant = LightTextVariant.Detail,
                lighten = !open,
                modifier = Modifier
                    .lightClickable { open = !open }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
    if (open && help != null) help()
}

@Composable
private fun Setting(
    label: String,
    value: String,
    enabled: Boolean = true,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(enabled = enabled) { onTap() }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(label, LightTextVariant.Copy, lighten = !enabled)
        Spacer(Modifier.weight(1f))
        LightText(value, LightTextVariant.Copy, lighten = true)
    }
}

@Composable
private fun Action(label: String, lighten: Boolean = false, onTap: () -> Unit) {
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

@Composable
private fun Note(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
    )
}
