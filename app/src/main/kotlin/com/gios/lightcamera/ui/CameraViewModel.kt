package com.gios.lightcamera.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightcamera.CaptureMode
import com.gios.lightcamera.PhotoSize
import com.gios.lightcamera.Prefs
import com.gios.lightcamera.SelfTimer
import com.gios.lightcamera.camera.CameraEngine
import com.gios.lightcamera.camera.CapturedFrame
import com.gios.lightcamera.camera.DateStamp
import com.gios.lightcamera.camera.FaceBox
import com.gios.lightcamera.camera.FlashMode
import com.gios.lightcamera.camera.FrameAspect
import com.gios.lightcamera.camera.Frames
import com.gios.lightcamera.camera.PuriArt
import com.gios.lightcamera.camera.PuriStrip
import com.gios.lightcamera.camera.Sharpness
import com.gios.lightcamera.filter.FaceQuads
import com.gios.lightcamera.filter.Filters
import com.gios.lightcamera.filter.ShaderRuntime
import com.gios.lightcamera.hw.Beeps
import com.gios.lightcamera.hw.Binding
import com.gios.lightcamera.hw.Controls
import com.gios.lightcamera.hw.PressAction
import com.gios.lightcamera.ocr.Found
import com.gios.lightcamera.ocr.PageReader
import com.gios.lightcamera.ocr.Reading
import com.gios.lightcamera.ocr.TextScan
import com.gios.lightcamera.qr.CodeHandoff
import com.gios.lightcamera.qr.Codes
import com.gios.lightcamera.qr.ScanGate
import com.gios.lightcamera.media.MediaStoreRepo
import com.gios.lightcamera.media.Photo
import com.gios.lightcamera.media.RollScope
import com.gios.lightcamera.media.Thumbs
import com.gios.lightcamera.report.Trouble
import com.gios.lightcamera.roll.FilmRoll
import com.gios.lightcamera.roll.Roll
import com.gios.lightcamera.ui.theme.LightHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

/**
 * A value strip that opens over the band.
 *
 * Both are a row of ticks and a marker, both take the full width of the band while open, and both
 * take the bare wheel for as long as they are up. Two members rather than a boolean because a
 * second one arrived and `evOpen` had no room for it.
 */
enum class Strip(val label: String) {
    Exposure("Exposure"),
    Zoom("Zoom"),
}

/**
 * Everything the two screens share.
 *
 * The interesting method is [shoot], which is the only place the app's three modes meet: a
 * filter that has to be applied to the bytes, a frame shape that has to be cropped to, and
 * a roll that may or may not be loaded and therefore decides whether the photo goes into the
 * gallery at all.
 */
class CameraViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)
    val engine = CameraEngine(app)
    val thumbs = Thumbs(app)
    private val repo = MediaStoreRepo(app)
    val filmRoll = FilmRoll(app)
    private val beeps = Beeps(app)

    val roll: StateFlow<Roll?> get() = filmRoll.roll

    private val _photos = MutableStateFlow<List<Photo>>(emptyList())

    /**
     * The roll, narrowed to the starred ones when that is the scope.
     *
     * Derived rather than re-queried: starring a photograph should update the grid immediately, and a
     * round trip to MediaStore for a filter this app already has in memory would be slower and would
     * flicker. Declared here, above `init`, for the reason at the top of that block.
     */
    val photos: StateFlow<List<Photo>> = combine(
        _photos,
        prefs.favourites,
        prefs.scope,
    ) { list, starred, scope ->
        if (scope == RollScope.Favourites) list.filter { it.name in starred } else list
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _loadingRoll = MutableStateFlow(true)
    val loadingRoll: StateFlow<Boolean> = _loadingRoll.asStateFlow()

    /** True from the moment the shutter is pressed until the file is written. */
    private val _shooting = MutableStateFlow(false)
    val shooting: StateFlow<Boolean> = _shooting.asStateFlow()

    /**
     * Ticks when the app is launched or brought forward — go back to the viewfinder.
     *
     * **A `SharedFlow`, not a `StateFlow`, and that is the whole design.** The activity is `singleTop`, so
     * the camera key does not create a new instance: it resumes the existing one, which comes back on
     * whatever page it was left on. Leave Roll looking at the roll, press the shutter key later, and you
     * arrive at the roll — which is not what a camera button means.
     *
     * A shared flow only fires on emission, never on composition, and never replays. That matters twice
     * over: a second press has to re-fire (a boolean already true would not), and it must *not* fire on
     * first composition — an effect that ran at startup is exactly what made tapping a photograph open the
     * newest one in v2.11.
     */
    private val _goToCamera = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val goToCamera: SharedFlow<Unit> = _goToCamera.asSharedFlow()

    /** Called from the activity when it is launched or resumed by an intent. */
    fun onCameraKeyLaunch() {
        _goToCamera.tryEmit(Unit)
    }

    /** Ticks once per captured frame, for the viewfinder blink. */
    private val _shutterTick = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val shutterTick: SharedFlow<Unit> = _shutterTick.asSharedFlow()

    /**
     * "Get out of the way, I am about to take this."
     *
     * The mode strip, the filter grid, the Purikura menu and the exposure and zoom strips are all
     * local state inside the camera composable, so there is nothing for the view model to set —
     * hence a signal rather than a flag. A `SharedFlow`: closing a panel is an event, and a
     * `StateFlow` would re-close them on every recomposition that re-read it.
     */
    private val _dismissPanels = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dismissPanels: SharedFlow<Unit> = _dismissPanels.asSharedFlow()

    /** Seconds left on the self timer, or null. */
    private val _countdown = MutableStateFlow<Int?>(null)
    val countdown: StateFlow<Int?> = _countdown.asStateFlow()

    /**
     * The seed everything random about a Purikura comes from — which stickers, where, which date.
     *
     * **Held still between shots, and that is the whole point of it existing.** The shader's own
     * `seed` moves ten times a second so the glitter twinkles; if the stickers came off that they
     * would rearrange themselves while you were composing, and the viewfinder would be showing you
     * something other than what you were about to get. This one changes when you take a photograph.
     */
    private val _puriSeed = MutableStateFlow(Random.nextLong())
    val puriSeed: StateFlow<Long> = _puriSeed.asStateFlow()

    /**
     * The frame the viewfinder is holding while a still is being made, or null.
     *
     * **A stand-in, and honest about it.** In Pro the light does not land at your press: `takePicture` runs
     * metering, then a burst, then stacking, so the exposure happens somewhere inside the next second and a
     * half. Freezing the panel frame at t=0 therefore shows you *approximately* the photograph — the same
     * framing, the same composition, from a moment slightly earlier. It is replaced by the real file the
     * instant that exists, so any difference resolves itself in front of you rather than being discovered
     * later in the roll.
     *
     * What this deliberately does not do is flash a shutter animation at the press, which would assert that
     * the exposure happened then. It did not.
     */
    private val _held = MutableStateFlow<Bitmap?>(null)
    val held: StateFlow<Bitmap?> = _held.asStateFlow()

    /**
     * A rolling average of how long a still has taken, in milliseconds.
     *
     * So the progress bar can finish at roughly the right moment. Accurate progress feels much shorter than
     * indeterminate motion; a bar that stalls at nine tenths feels longer than no bar at all. Seeded at the
     * 1.8 s this camera was measured at, and corrected by every shot after.
     */
    private val _stillMs = MutableStateFlow(1_800L)
    val stillMs: StateFlow<Long> = _stillMs.asStateFlow()

    /** Short-lived lines of text for the viewfinder. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * The QR payload currently being offered, or null while the camera is still looking.
     *
     * **Held rather than acted on.** An early version opened links the instant it read one, which is
     * how most scanners behave and is wrong on a phone whose camera is also its default camera: you
     * point it at a table, it reads a code on a menu you did not mean to scan, and a browser is now
     * in front of the picture you were about to take. So a scan puts a sheet up with the payload on
     * it and waits — the destination is legible *before* anything is launched, which is the only
     * defence a person has against a sticker over a QR code on a parking meter.
     */
    private val _scan = MutableStateFlow<String?>(null)
    val scan: StateFlow<String?> = _scan.asStateFlow()

    /** Decides which decoded frames are news; see [ScanGate]. */
    private val scanGate = ScanGate()

    /** Long edge, in pixels, above which the recogniser gains nothing. See [readExposure]. */
    private val READ_LONG_EDGE = 2000

    /** The most recent developed roll, so the contact sheet can be shown. */
    private val _developed = MutableStateFlow<FilmRoll.DevelopedRoll?>(null)
    val developed: StateFlow<FilmRoll.DevelopedRoll?> = _developed.asStateFlow()

    /**
     * Set when another app launched us with `IMAGE_CAPTURE`. The next photo goes there and
     * the activity finishes, rather than the photo landing in the roll.
     */
    var captureRequestOutput: Uri? = null
    private val _captureRequestDone = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val captureRequestDone: SharedFlow<Boolean> = _captureRequestDone.asSharedFlow()

    /**
     * The filter as the whole app sees it, **already resolved against the grade**.
     *
     * So this holds `Filters.preset` carrying the ten adjustments whenever the dial is on the first
     * slot and something is set, and the plain null-shader `Filters.none` whenever it is not. Every
     * reader — the viewfinder's `RenderEffect`, the shutter, `Frames.process`, the band's label —
     * goes on treating it as one opaque filter, and the `agsl == null` fast path still means what it
     * always meant. Resolving in one place is what stops the preview and the file disagreeing about
     * whether there is a shader to run.
     */
    private val _filter = MutableStateFlow(
        Filters.forGrade(Filters.byId(prefs.filterId.value), prefs.grade.value),
    )
    val filter: StateFlow<Filters.Filter> = _filter.asStateFlow()

    /**
     * Which value strip is open over the band, if any.
     *
     * **On the view model rather than in the composable**, which is where the exposure one used
     * to live, because three separate things now open a strip: the band slot, a remapped hardware
     * key dispatched from the activity, and the strip itself closing when a mode change makes it
     * meaningless. A `remember` inside `CameraScreen` is reachable by exactly one of those.
     *
     * One at a time: they occupy the same width, and both of them steal the bare wheel while open.
     */
    private val _strip = MutableStateFlow<Strip?>(null)
    val strip: StateFlow<Strip?> = _strip.asStateFlow()

    /**
     * Locked to None while taking a photograph *for another app*.
     *
     * Another app asking for a picture is asking for the picture, not for whatever the dial
     * happens to be resting on. A scanner, a form, or LightNotebook handing a page to Claude
     * wants the page — and a Game Boy dither of a page is unreadable, which presents to the
     * user as "the app can't read my handwriting" with nothing on screen to suggest why. So
     * filters are **off by default for capture requests** and the caller has to ask for them
     * with [MainActivity.EXTRA_ALLOW_FILTER].
     *
     * Declared above the `init` block on purpose: that block's collector runs synchronously
     * inside the constructor and reads this flag, and a field declared below it would still be
     * false there. See the note on `init` below — this is the same trap that shipped v1.5.6's
     * instant crash, one field along.
     */
    private var filterLocked = false

    /** Seconds into the current take, for the readout. */
    private val _recordSeconds = MutableStateFlow(0)
    val recordSeconds: StateFlow<Int> = _recordSeconds.asStateFlow()

    var audioGranted: Boolean = false

    /** While the dial is caught on None. See [Filters.NONE_DWELL_MS]. */
    private var dialHeldUntil = 0L

    private var observer: AutoCloseable? = null
    private var lastPriorityFace: FaceBox? = null

    /** Read from the face collector below, so declared above it. Ints, so harmless either way. */
    @Volatile private var viewWidth = 0

    @Volatile private var viewHeight = 0

    /**
     * **Every field this block touches must be declared above it.**
     *
     * `viewModelScope` runs on `Dispatchers.Main.immediate`, and the view model is built on the
     * main thread — so each `launch` here starts executing *synchronously, inside the
     * constructor*, and a `StateFlow` hands over its current value on subscription. A field
     * declared below this point is therefore still null when the collector first fires, and the
     * app dies in the view model's constructor with a null-pointer exception that names a
     * property Kotlin swore was non-null. That is exactly how v1.5.6 shipped an instant crash:
     * the recording collector wrote to a counter declared thirty lines further down.
     */
    init {
        viewModelScope.launch {
            // `filterLocked` gates this rather than the collector being skipped: the lock is
            // set after construction, and a user changing the filter in the grid mid-request
            // must not leak into somebody else's photograph either.
            prefs.filterId.collect { id ->
                if (!filterLocked) _filter.value = Filters.forGrade(Filters.byId(id), prefs.grade.value)
            }
        }
        // Turning an adjustment has to change the viewfinder in the same frame, and on the first
        // notch away from zero it has to *attach* a shader that was not there a moment ago — which
        // is a change of filter, not a change of uniform. Hence a collector rather than a uniform
        // write: `none` becomes `preset`, and the preview's `LaunchedEffect` keys on the filter.
        viewModelScope.launch {
            prefs.grade.collect { grade ->
                if (filterLocked) return@collect
                _filter.value = Filters.forGrade(Filters.byId(prefs.filterId.value), grade)
            }
        }
        viewModelScope.launch {
            prefs.afMode.collect { engine.afMode = it }
        }
        viewModelScope.launch {
            prefs.facePriority.collect { engine.facePriority = it }
        }
        viewModelScope.launch {
            prefs.flash.collect { engine.setFlash(it) }
        }
        // Size is a use-case configuration, so changing it rebinds the camera.
        viewModelScope.launch {
            prefs.photoSize.collect { engine.setPhotoSize(it, prefs.flash.value) }
        }
        // Continuous AF is driven from the face list rather than from a timer, so a still
        // subject costs nothing at all.
        viewModelScope.launch {
            engine.faces.collect { faces ->
                val priority = com.gios.lightcamera.camera.FaceMapper
                    .priority(faces, viewWidth, viewHeight)
                engine.trackFaces(lastPriorityFace, priority)
                lastPriorityFace = priority
            }
        }
        viewModelScope.launch {
            prefs.scope.collect { refreshRoll() }
        }
        // Focus confirmation: two blips and a buzz, the way a compact camera does it. Fired
        // from the camera's own AF result, so it lands when the lens lands — not when a
        // request was sent.
        viewModelScope.launch {
            engine.focusOutcome.collect { locked ->
                if (locked) {
                    LightHaptics.click(getApplication<Application>())
                    if (prefs.sounds.value) beeps.focusLocked()
                } else {
                    if (prefs.sounds.value) beeps.focusFailed()
                }
            }
        }
        viewModelScope.launch {
            shutterTick.collect { if (prefs.sounds.value) beeps.shutter() }
        }
        // Arrives on the camera's analysis thread, so it is hopped onto the view model's scope
        // before it touches any state the UI is reading.
        engine.onCode = { text -> viewModelScope.launch { onCodeRead(text) } }
        // The elapsed counter ticks only while something is being recorded, so an idle camera
        // isn't waking up once a second to look at a clock.
        viewModelScope.launch {
            engine.recording.collect { on ->
                if (!on) {
                    _recordSeconds.value = 0
                    return@collect
                }
                val startedAt = System.currentTimeMillis()
                while (engine.recording.value) {
                    _recordSeconds.value = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                    delay(500)
                }
                _recordSeconds.value = 0
            }
        }
    }

    fun onViewSized(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        engine.onViewSized(width, height)
    }

    /* ---------------- the roll above the viewfinder ---------------- */

    fun startObservingMedia() {
        if (observer != null) return
        observer = repo.observe {
            viewModelScope.launch { refreshRoll() }
        }
        viewModelScope.launch { refreshRoll() }
    }

    suspend fun refreshRoll() {
        val loaded = repo.load(prefs.scope.value)
        _photos.value = loaded
        _loadingRoll.value = false
    }

    fun onPermissionsChanged() {
        viewModelScope.launch { refreshRoll() }
    }

    /* ---------------- filters ---------------- */

    /**
     * One notch of the wheel, or one sideways swipe.
     *
     * Every notch buzzes, whether or not it moves the dial — the wheel is a physical control and
     * silence from it reads as a control that isn't working. The buzz is also what tells you the
     * dial is *caught* on None rather than dead: notches inside the dwell window are felt and
     * discarded.
     */
    fun stepFilter(by: Int) {
        if (shotInFlight()) {
            // **The dial is closed while the shutter is open.** In Pro the filter is applied to the
            // bytes *after* the sensor answers, about 1.8 seconds after your finger — so a notch
            // turned inside that window would bake a look you were not framing into the file, and
            // the held frame on the panel would be showing you the old one while it happened. No
            // haptic, for the same reason as below: nothing here is broken.
            showNotice("Taking the photograph")
            return
        }
        if (filterLocked) {
            // No haptic: a notch that buzzes and does nothing reads as a broken dial, and here
            // nothing is broken — the filter is deliberately not this photograph's to choose.
            showNotice("Plain, for the app that asked")
            return
        }
        if (videoMode()) {
            showNotice("Filters are photo only")
            return
        }
        // The wheel is a filter dial and QR has no filters, but it also must not silently walk the
        // dial underneath a mode that isn't showing it — coming back to Pro to find a different
        // filter on than the one you left is worse than the wheel doing nothing here.
        if (prefs.mode.value.isReader) {
            showNotice("Filters are photo only")
            return
        }
        LightHaptics.advance(getApplication<Application>())
        val now = System.currentTimeMillis()
        if (now < dialHeldUntil) return

        // **Simple sits one notch before None on the same track.** The wheel is the one control this phone
        // has that a camera doesn't, and taking it away in the mode you spend most of your time in would
        // waste it — so a turn out of Simple lands on Pro with no filter, and carries on into the filters
        // from there. A turn back at None returns to Simple. One dial, one line: Simple, None, Film, Mono,
        // and so on.
        if (prefs.mode.value.isSimple) {
            if (by <= 0) return
            setMode(CaptureMode.Photo)
            prefs.setFilter(Filters.none.id)
            dialHeldUntil = now + Filters.NONE_DWELL_MS
            return
        }
        // Only walks into Simple when Simple is switched on; otherwise None is the end of the track.
        if (by < 0 && filter.value.id == Filters.none.id && prefs.simpleMode.value) {
            setMode(CaptureMode.Simple)
            dialHeldUntil = now + Filters.NONE_DWELL_MS
            return
        }

        val next = Filters.step(filter.value, by)
        prefs.setFilter(next.id)
        dialHeldUntil = now + Filters.dwellMs(next)
        // **No name flashed on screen.** The viewfinder is already showing you the filter — a
        // label naming what you can plainly see is a label in the way of it. The buzz says the
        // dial moved; the picture says where to.
    }

    fun setFilter(id: String) {
        // Same rule as the wheel, and it has to be here as well rather than only in the grid:
        // the grid is one caller of this and a capture request is another.
        if (shotInFlight()) {
            showNotice("Taking the photograph")
            return
        }
        if (filterLocked) return
        // Chosen deliberately from the grid, so the dial has no business holding on to it.
        dialHeldUntil = 0L
        prefs.setFilter(id)
    }

    fun toggleStrip(kind: Strip) {
        _strip.value = if (_strip.value == kind) null else kind
    }

    fun closeStrip() {
        _strip.value = null
    }

    /**
     * Do whatever a remapped press is pointed at.
     *
     * One place, so the volume keys, the wheel click and any button that ends up in the band all
     * agree about what "front / rear" means. Lives here rather than in the activity because every
     * arm of it is a view-model call, and the activity holding a lambda per action was five
     * closures that had to be kept in step with an enum.
     */
    /**
     * True while a clip is playing in the viewer. Set by the viewer, cleared when it leaves —
     * cleared on the way out as well as on every page change, because a flag left set here takes
     * the fallback shutter away with nothing on screen to say why.
     */
    private var clipPlaying = false

    fun setClipPlaying(playing: Boolean) {
        clipPlaying = playing
    }

    /**
     * The binding as pressed rather than as stored. See [Controls.pressNow] — this is the one
     * place the two can differ, and it is here rather than in [Prefs] because whether a clip is
     * playing is state, not a preference.
     */
    fun pressFor(binding: Binding): PressAction = Controls.pressNow(
        binding,
        prefs.pressFor(binding),
        clipPlaying,
        // Read at the moment of the press, so turning the setting off hands the wheel click back
        // to the torch on the very next click rather than at the next launch.
        dialLockOn = prefs.dialLock.value,
    )

    /**
     * Whether the dial is asleep, which it is at every launch while the setting is on.
     *
     * **The state is not remembered; the setting is.** "Always boot on a locked dial" is the whole
     * of the request — the wheel is shared with the rest of the phone and turns in a pocket, so the
     * safe state is the one you start in, and remembering that you unlocked it yesterday would hand
     * the pocket back the filter dial. Whether the lock exists at all is `Prefs.dialLock`, and that
     * one is remembered, because it is a choice rather than a state.
     */
    private val _dialLocked = MutableStateFlow(true)
    val dialLocked: StateFlow<Boolean> = _dialLocked.asStateFlow()

    /**
     * Toggle it, and say which way it went.
     *
     * The notice is the only feedback there is — nothing else on the panel changes — so it is not
     * optional. A lock whose state you cannot see is a wheel that intermittently does nothing.
     */
    fun toggleDialLock() {
        val locked = !_dialLocked.value
        _dialLocked.value = locked
        showNotice(if (locked) "Dial locked" else "Dial unlocked")
    }

    /**
     * Said when a locked dial is turned.
     *
     * **Six words, because it is read mid-gesture.** You are looking at a dial that just refused to
     * move and you want to know what to press — not where the setting lives, which is a thing to go
     * and find later. Settings › Keys is still the way to turn the lock off for good; it does not
     * belong in a notice that has to be read at a glance.
     */
    fun sayDialLocked() {
        showNotice("Click wheel to unlock")
    }

    /** Turning the setting off has to wake the dial, or the switch appears to do nothing. */
    fun setDialLock(on: Boolean) {
        prefs.setDialLock(on)
        _dialLocked.value = on
        showNotice(if (on) "Dial locks on launch" else "Dial lock off")
    }

    fun press(action: PressAction) {
        when (action) {
            PressAction.Shutter -> shoot()
            PressAction.Torch -> engine.toggleTorch()
            PressAction.FlipLens -> flipLens()
            PressAction.NextMode -> nextMode()
            PressAction.Timer -> cycleTimer()
            PressAction.Exposure -> openStripOrSayWhyNot(Strip.Exposure)
            PressAction.Zoom -> openStripOrSayWhyNot(Strip.Zoom)
            PressAction.DialLock -> toggleDialLock()
            PressAction.Nothing -> Unit
        }
    }

    /**
     * The mode chip's list, one step along.
     *
     * The same filter the picker uses, so a key and the chip can never disagree about which modes
     * exist — Simple is only in the list when it is switched on.
     */
    fun nextMode() {
        val offered = CaptureMode.entries.filter { !it.isSimple || prefs.simpleMode.value }
        val at = offered.indexOf(prefs.mode.value)
        setMode(offered[(at + 1) % offered.size])
    }

    fun cycleTimer() {
        val all = SelfTimer.entries
        val next = all[(all.indexOf(prefs.timer.value) + 1) % all.size]
        prefs.setTimer(next)
        showNotice(if (next.seconds == 0) "Timer off" else "Timer ${next.seconds}s")
    }

    /**
     * A strip that cannot do anything is worse than no strip: it is a panel of ticks with the
     * marker pinned to the middle, and nothing on it to explain itself. Say so instead.
     */
    private fun openStripOrSayWhyNot(kind: Strip) {
        when {
            kind == Strip.Exposure && engine.evRange.value.let { it.first == it.last } ->
                showNotice("No exposure control")
            kind == Strip.Zoom && engine.maxZoom.value <= 1.01f ->
                showNotice("No zoom on this lens")
            else -> toggleStrip(kind)
        }
    }

    /**
     * True from the press until the file is written.
     *
     * Both halves matter. `shooting` is latched across the capture and the save; `held` is the
     * frozen composition sitting over the live preview, which outlives the capture by the length
     * of its own fade. Between them there is no moment where the viewfinder is showing a
     * photograph being made and the dial is still live.
     */
    private fun shotInFlight(): Boolean = _shooting.value || _held.value != null

    /**
     * Serve this capture request plain, whatever the dial says.
     *
     * Does **not** write to prefs: the user's own filter has to be exactly where they left it
     * when they next open the app for themselves. This only moves the live value, and the
     * prefs collector is gated on the same flag so a later write cannot undo it.
     */
    fun lockFilterPlain() {
        filterLocked = true
        _filter.value = Filters.none
    }

    /* ---------------- modes ---------------- */

    fun videoMode(): Boolean = prefs.mode.value == CaptureMode.Video

    /**
     * The half detent on the shutter release.
     *
     * Focus is the obvious half of it. The other half is that **a half press is the clearest
     * statement of intent the camera gets**: a finger resting on the first detent is a finger about
     * to take a photograph, and a mode strip covering the frame at that moment is a menu you have
     * to dismiss before you can shoot the thing you were looking at. So the panels go, and the
     * viewfinder is clear before the lens has finished hunting.
     */
    fun halfPress() {
        _dismissPanels.tryEmit(Unit)
        closeStrip()
        engine.halfPress()
    }

    /**
     * Change mode, stopping a recording first if one is running.
     *
     * **"Stop recording first" was the wrong answer and the crash was underneath it.** Tapping Pro
     * while filming obviously means stop filming, so it now does that — but the important part is
     * what follows: `Recording.stop()` returns long before the muxer has finished, and rebinding
     * the camera during that window is what took the app down when you came back to Photo. So the
     * switch waits for the recorder to go idle and then rebinds, and [applyMode] only commits the
     * mode to preferences once the engine confirms it actually rebound.
     */
    fun setMode(next: CaptureMode) {
        if (engine.recording.value) {
            engine.stopRecording()
            viewModelScope.launch {
                engine.awaitIdle()
                applyMode(next)
            }
            return
        }
        applyMode(next)
    }

    private fun applyMode(next: CaptureMode) {
        // **Simple drops Auto flash.** Auto is not free even when it decides not to fire: the HAL runs a
        // precapture metering sequence — often a preflash — before it will start the frame you asked for,
        // which is most of a second that a mode whose whole argument is speed should not be spending. Off
        // by default there; explicitly turning it on in Simple still works.
        if (next.isSimple && prefs.flash.value == FlashMode.Auto) prefs.setFlash(FlashMode.Off)
        // A result belongs to the mode that produced it. Leaving QR with the sheet up would carry a
        // stale payload back into Pro, where the shutter would then try to open it.
        _scan.value = null
        scanGate.reset()
        // A reading belongs to the mode that produced it, the same as a scan. This also means a
        // stuck reader cannot outlive a trip through the mode strip, which is the first thing
        // anybody tries when a mode looks broken.
        _page.value = null
        _pageFound.value = emptyList()
        _pageSheet.value = null
        _held.value = null
        _reading.value = false
        // **The engine goes first and preferences follow it.** The other order is what let the
        // interface get ahead of the camera: preferences said Pro, every composable redrew for Pro,
        // and the engine — which refuses to rebind mid-recording — was still bound to `VideoCapture`
        // with an `ImageCapture` attached to nothing behind it.
        if (!engine.setMode(next, prefs.flash.value)) {
            showNotice("Still finishing the recording")
            return
        }
        prefs.setMode(next)
        showNotice(next.bandLabel)
    }

    /** The lens switch, which in Photo and Selfie is the same thing as switching mode. */
    fun flipLens() {
        when (prefs.mode.value) {
            CaptureMode.Simple, CaptureMode.Photo -> setMode(CaptureMode.Selfie)
            CaptureMode.Selfie -> setMode(CaptureMode.Photo)
            // Nothing to flip to: both readers are the back lens by definition, and a double tap
            // that quietly moved you into Selfie would be the camera changing mode behind your
            // back — while you are holding it over the thing you were trying to read.
            CaptureMode.Scan -> showNotice("QR uses the back camera")
            CaptureMode.Text -> showNotice("Text uses the back camera")
            CaptureMode.Video -> {
                if (engine.recording.value) return
                val front = engine.lensFacing.value ==
                    androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                engine.setLens(
                    if (front) {
                        androidx.camera.core.CameraSelector.LENS_FACING_BACK
                    } else {
                        androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                    },
                    prefs.flash.value,
                )
            }
        }
    }

    /* ---------------- video ---------------- */

    /**
     * The shutter in video mode. Start, or stop — the same button, the way every camera does it.
     */
    fun toggleRecording() {
        if (engine.recording.value) {
            engine.stopRecording()
            return
        }
        val started = engine.startRecording(withAudio = audioGranted)
        if (!started) showNotice("Couldn't start recording")
    }

    /* ---------------- QR ---------------- */

    /**
     * A frame that decoded. Most of them are the same code as the last twenty frames.
     */
    private fun onCodeRead(text: String) {
        if (!prefs.mode.value.isScan) return
        // The gate first and unconditionally, even when a sheet is already up: its window slides on
        // every read, so a code sitting in frame while you read the result does not re-fire the
        // moment you dismiss it.
        if (!scanGate.accept(text, System.currentTimeMillis())) return
        if (_scan.value != null) return
        _scan.value = text
        // The same two blips the lens makes when focus lands. A scan is the same event as far as the
        // camera is concerned — it found the thing you pointed it at — and it needs to be felt,
        // because you are looking at a poster rather than at the screen.
        LightHaptics.click(getApplication<Application>())
        if (prefs.sounds.value) beeps.focusLocked()
    }

    /** Put the sheet away and go back to looking. */
    fun dismissScan() {
        _scan.value = null
    }

    /**
     * Do the obvious thing with the payload: open it if it is openable, copy it if it is not.
     *
     * Falling back to a copy rather than refusing, because "there is nothing to open" is a fact
     * about the payload and not a mistake the user made — a Wi-Fi credential or a paragraph of text
     * is still something they scanned on purpose and still something they want in hand.
     */
    fun openScan() {
        val raw = _scan.value
        if (raw == null) {
            showNotice("Point at a code")
            return
        }
        val target = Codes.openable(raw)
        if (target == null) {
            copyScan()
            return
        }
        if (CodeHandoff.open(getApplication<Application>(), target)) {
            dismissScan()
        } else {
            showNotice("Nothing here opens that")
        }
    }

    /** The payload on the clipboard, verbatim. */
    fun copyScan() {
        val raw = _scan.value ?: return
        CodeHandoff.copy(getApplication<Application>(), raw)
        showNotice("Copied")
        dismissScan()
    }

    // ------------------------------------------------------------------ reading a photograph

    /**
     * The text found in the photograph currently open, or null when the sheet is closed.
     *
     * Held here rather than in the viewer so it survives the viewer's page changing underneath
     * it — and so it is cleared when it should be. A reading belongs to one photograph, and
     * showing the last one's words over this one's picture would be worse than showing nothing.
     */
    private val _page = MutableStateFlow<Reading?>(null)
    val page: StateFlow<Reading?> = _page.asStateFlow()

    /**
     * The turn the frame was read at, kept beside the reading.
     *
     * The boxes come back in the upright image's coordinates and have to be put back into the
     * frame's before they can be drawn. Recomputing the turn at draw time would read the *current*
     * orientation, so tilting the phone while the sheet was up would slide every box off its
     * words — the number that matters is the one at the moment of the press.
     */
    private val _pageTurn = MutableStateFlow(0)
    val pageTurn: StateFlow<Int> = _pageTurn.asStateFlow()

    /** What the page yielded that is worth pressing, and which line each came off. */
    private val _pageFound = MutableStateFlow<List<Found>>(emptyList())
    val pageFound: StateFlow<List<Found>> = _pageFound.asStateFlow()

    /**
     * The text the sheet is showing, or null while only the boxes are up.
     *
     * Two states rather than one, because they answer different questions. The boxes answer
     * "which part of this said that", which is the one you have standing in front of a menu; the
     * sheet answers "what does it say", which is the one you have afterwards. Going straight to
     * the sheet — which is what v2.41 did — skipped the first question entirely.
     */
    private val _pageSheet = MutableStateFlow<String?>(null)
    val pageSheet: StateFlow<String?> = _pageSheet.asStateFlow()

    private val _reading = MutableStateFlow(false)
    val reading: StateFlow<Boolean> = _reading.asStateFlow()

    /**
     * Read the words off a photograph already on the roll.
     *
     * Deliberately a verb you press rather than something that happens to every picture. The
     * recogniser costs a few hundred milliseconds and several megabytes of model, most
     * photographs have no writing in them, and a roll that quietly indexed itself would be
     * spending the battery on a question nobody asked.
     */
    fun readPage(photo: Photo) {
        if (!claimReader()) return
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    PageReader.read(getApplication(), photo.uri)
                }
                if (found == null) {
                    showNotice("No text in this one")
                    return@launch
                }
                // A photograph from the roll arrives upright: `fromFilePath` has already applied
                // the file's EXIF rotation, so there is no turn left to undo.
                // The two blips a scan gets are raised by `showReading`: finding the words is the
                // same event as finding a code — the thing you pointed the phone at was there.
                showReading(found, turn = 0)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Trouble.record("Reading a photograph failed", t)
                showNotice("Could not read that one")
            } finally {
                _reading.value = false
            }
        }
    }

    /**
     * Text mode's shutter: read what the viewfinder is looking at, without taking a photograph.
     *
     * **The frame comes off the panel, not off the sensor**, which is the `Screen` route the
     * coarse filters already use — `previewView.bitmap`, no `takePicture`, no readout, no encode.
     * On this hardware a still is most of a second and a 50MP one is nearer two; a reading that
     * took that long would be slower than typing the thing out. This is instant, and the frame is
     * literally what you were looking at when you pressed.
     *
     * **Nothing is written to the roll.** The frame is held on screen so you can see what was
     * read, and dropped when the sheet closes. A roll filling up with pictures of car park signs
     * is the wrong outcome; this is a reading, not a photograph.
     *
     * The panel is the catch, and it is handled rather than hidden. A page at panel resolution is
     * fine for a sign, a menu or a business card, and marginal for small print — so when the panel
     * frame comes back with nothing or with almost nothing, this takes one real exposure and reads
     * that instead. The slow path is paid only by the shots that need it, and only after the fast
     * path has already been tried, which is the right way round: most readings never reach it.
     */
    fun readFrame() {
        val grabbed = engine.previewFrame()
        if (grabbed == null) {
            showNotice("Nothing on the viewfinder yet")
            return
        }
        if (!claimReader()) return
        // Freeze the panel on the frame that was read. Without this the live preview carries on
        // moving under the sheet, and the words on screen stop matching the picture behind them.
        _held.value = grabbed
        _shutterTick.tryEmit(Unit)

        viewModelScope.launch {
            try {
                val turn = engine.previewRotationDegrees()
                // The turn is passed rather than applied. ML Kit rotates internally and a manual
                // rotate would mean allocating a second full-size bitmap to hand it the same thing.
                var reading = withContext(Dispatchers.Default) { PageReader.read(grabbed, turn) }
                // The turn the boxes have to be undone by. A closer look comes back upright,
                // because it went through a file with its own rotation on it.
                var boxTurn = turn

                if (thin(reading?.text) && engine.ready.value) {
                    showNotice("Looking closer")
                    val closer = withContext(Dispatchers.IO) { readExposure() }
                    // Only if it did better. A real exposure can also come back empty — pointed at
                    // a wall, it should say so rather than replacing a partial reading with none.
                    if (!thin(closer?.text)) {
                        // **The boxes are dropped when the closer look wins**, which is the honest
                        // thing rather than the lazy one: those rectangles are in the *exposure's*
                        // coordinates and the picture still on screen is the panel grab. Drawing
                        // one on the other would put every box confidently in the wrong place,
                        // which is worse than drawing none — so that reading goes straight to the
                        // sheet, as it did before the boxes existed.
                        reading = closer?.copy(lines = emptyList())
                        boxTurn = 0
                    }
                }

                val got = reading
                if (got == null) {
                    _held.value = null
                    showNotice("No text in view")
                    return@launch
                }
                showReading(got, boxTurn)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _held.value = null
                Trouble.record("Reading the viewfinder failed", t)
                showNotice("Could not read that")
            } finally {
                _reading.value = false
            }
        }
    }

    /**
     * Take the reader, or say why not.
     *
     * **The silent version of this was a real bug and worth the comment.** `readPage` used to set
     * the flag and clear it on the happy path only, so one reading that threw — or was cancelled
     * — left it set for the life of the process. The view model is activity-scoped and shared
     * between the roll and the viewfinder, so the next thing to notice was Text mode's shutter,
     * doing nothing at all, with no message, forever.
     *
     * Two fixes, and both are needed. Every caller now clears the flag in a `finally`, and the
     * refusal is no longer silent: a button that has decided not to work has to say so, or the
     * only symptom is a phone that seems broken.
     */
    private fun claimReader(): Boolean {
        if (_reading.value) {
            showNotice("Still reading")
            return false
        }
        _reading.value = true
        return true
    }

    /**
     * One real exposure, decoded small, read.
     *
     * Sampled down on the way in rather than decoded whole: the recogniser gains nothing above
     * roughly two thousand pixels on the long edge, and decoding a 12MP JPEG to ARGB to read a
     * street sign is two hundred megabytes to throw away. `inSampleSize` is powers of two, so this
     * lands between 2000 and 4000 rather than exactly on it, which is close enough.
     */
    private suspend fun readExposure(): Reading? {
        val frame = runCatching { engine.capture() }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= READ_LONG_EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            frame.jpeg,
            0,
            frame.jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return PageReader.read(bitmap, frame.rotationDegrees)
    }

    /**
     * Whether a reading is worth keeping, or worth spending an exposure to improve on.
     *
     * Not just null. A recogniser handed a frame too coarse for the print does not fail — it
     * returns two or three characters it half-saw, which is worse than nothing because it looks
     * like an answer. A dozen characters is the line: below it there is nothing anyone
     * photographed a page to get.
     */
    private fun thin(text: String?): Boolean = (text?.count(Char::isLetterOrDigit) ?: 0) < 12

    /**
     * Put a reading on screen: boxes first, sheet on demand.
     *
     * The findings are worked out here rather than in the sheet because the overlay needs them
     * too — a line is drawn as actionable or as plain, and that is the same question the sheet's
     * list is answering. Computing it twice is how the two would come to disagree.
     */
    private fun showReading(reading: Reading, turn: Int) {
        _page.value = reading
        _pageTurn.value = turn
        _pageFound.value = if (reading.lines.isEmpty()) {
            // No boxes to attribute anything to — the closer-look path. Scan the page as one
            // piece so the findings still exist; they simply have no line to point at.
            TextScan.found(reading.text)
        } else {
            TextScan.found(reading.lines.map { it.text })
        }
        // A reading with no boxes has nothing to show over the frame, so it opens where it would
        // have ended up anyway rather than putting up an empty overlay and asking for a tap.
        _pageSheet.value = if (reading.lines.isEmpty()) reading.text else null
        LightHaptics.click(getApplication<Application>())
        if (prefs.sounds.value) beeps.focusLocked()
    }

    /** One line, opened from its box. */
    fun openLine(index: Int) {
        val line = _page.value?.lines?.getOrNull(index) ?: return
        _pageSheet.value = line.text
    }

    /** The whole page, for when the boxes are not what you wanted. */
    fun openWholePage() {
        _pageSheet.value = _page.value?.text
    }

    /** Close the sheet but keep the boxes — going back a step, not all the way out. */
    fun closePageSheet() {
        _pageSheet.value = null
    }

    fun dismissPage() {
        _page.value = null
        _pageFound.value = emptyList()
        _pageSheet.value = null
        // Text mode's frame lives only as long as the sheet. Clearing it here rather than in the
        // screen keeps the two in step: the held frame *is* the thing the words came from, and a
        // frozen viewfinder with no sheet over it looks like the camera has locked up.
        _held.value = null
    }

    /**
     * Open something lifted off a page.
     *
     * The payload arrives already shaped like a QR code's contents — see `ocr/TextScan` — so this
     * is the same call `openScan` makes, against the same handoff. That equivalence is the point
     * of the feature and not a coincidence worth abstracting away.
     */
    fun openFromPage(target: String) {
        if (!CodeHandoff.open(getApplication<Application>(), target)) {
            showNotice("Nothing here opens that")
        }
    }

    /** Something off a page on the clipboard, verbatim — the reading, never the completion. */
    fun copyFromPage(text: String) {
        CodeHandoff.copy(getApplication<Application>(), text)
        showNotice("Copied")
    }

    /** Just the password out of a `WIFI:` payload — the only part of one anybody retypes. */
    fun copyScanPassword() {
        val raw = _scan.value ?: return
        val password = Codes.wifi(raw)?.password.orEmpty()
        if (password.isEmpty()) {
            copyScan()
            return
        }
        CodeHandoff.copy(getApplication<Application>(), password)
        showNotice("Password copied")
        dismissScan()
    }

    /* ---------------- the shutter ---------------- */

    /**
     * A new arrangement of stickers. Called after each Purikura, and when the frame changes so that
     * flicking through the borders also reshuffles what is on them.
     */
    fun reshufflePuri() {
        _puriSeed.value = Random.nextLong()
    }

    /**
     * The frame this photograph will have, Random resolved from the seed.
     *
     * The seed is the one held still between shots, so the answer is stable while you compose and
     * changes when you shoot — which is what makes Random honest rather than a surprise.
     */
    fun puriFrame(): PuriArt.Frame = PuriArt.resolveFrame(prefs.puriFrame.value, _puriSeed.value)

    /** The strip this press will take, Random resolved the same way. */
    fun puriStripLayout(): PuriStrip.Layout =
        PuriStrip.resolveLayout(prefs.puriStrip.value, _puriSeed.value)

    /** The four frames behind a strip, for the viewer's button. Empty for an ordinary photograph. */
    suspend fun framesBehind(photo: Photo): List<Photo> =
        if (photo.name.contains("_strip")) repo.framesOf(photo.name) else emptyList()

    /**
     * What to draw on top of a Purikura, or null if this is not one.
     *
     * Built here rather than in the shutter so the viewfinder can call the same function with the
     * same seed and show the truth. [faces] arrive after the turn and the crop, which is why this is
     * a lambda taking them rather than a plan made in advance.
     */
    fun puriOverlay(
        filter: Filters.Filter,
        withDate: Boolean,
        millis: Long,
    ): ((android.graphics.Canvas, Int, Int, List<com.gios.lightcamera.filter.FaceQuad>) -> Unit)? {
        if (!filter.facesAware) return null
        val frame = puriFrame()
        val faceStickers = prefs.puriFaceStickers.value
        val marginStickers = prefs.puriMarginStickers.value
        val dateId = if (withDate) prefs.puriDate.value else PuriArt.OFF
        val seed = _puriSeed.value
        return { canvas, w, h, faces ->
            PuriArt.draw(
                canvas = canvas,
                w = w,
                h = h,
                frame = frame,
                plan = PuriArt.plan(seed, faces, faceStickers, marginStickers, dateId),
                millis = millis,
            )
        }
    }

    /**
     * When to write a date on this frame, or null for no stamp.
     *
     * Three separate settings rather than one, because the stamp belongs on a plain photograph and
     * fights with a coarse filter — a full-precision date over a 160-cell dither reads as a caption
     * stuck on top rather than something the camera did.
     */
    private fun stampTime(filter: Filters.Filter): Long? {
        val wanted = when {
            filter.agsl == null -> prefs.stampPlain.value
            filter.lowRes -> prefs.stampCoarse.value
            else -> prefs.stampFiltered.value
        }
        return if (wanted) System.currentTimeMillis() else null
    }

    /**
     * Take a photograph.
     *
     * Ordered so that nothing that can be got wrong happens twice. The self timer runs
     * first; the capture is a single suspend call; processing and writing happen off the
     * main thread; and the roll decides at the end whether this frame is a photo yet.
     */
    fun shoot() {
        // In video mode the shutter is the record button. One control, two modes, which is what
        // every camera with a video mode has always done.
        if (videoMode()) {
            toggleRecording()
            return
        }
        // **In QR the shutter is the accept key.** It does not scan — the camera is already
        // scanning, continuously, and a button that started that would be a button that did nothing
        // visible. It commits to the result on screen, which is the one decision left to make and
        // exactly what the hardware key is best at: your eyes are on the payload, not on the panel.
        if (prefs.mode.value.isScan) {
            openScan()
            return
        }
        // **In Text the shutter reads rather than shoots.** Same sentence as every other mode —
        // point at a thing, press the button, get the thing — except that the thing is the words
        // and no file is made. See `readFrame`.
        if (prefs.mode.value.isText) {
            if (_page.value != null) dismissPage() else readFrame()
            return
        }
        if (_shooting.value) return
        // **A camera that never came up has to say so, because the viewfinder cannot.** `rebind`
        // unbinds before it binds, and a bind that fails — the front lens declining a configuration
        // the back one accepted is the case that happens — leaves the panel showing the last frame
        // the TextureView held, an `ImageCapture` attached to no camera, and a shutter that looks
        // ordinary. This is the only place that can tell the difference.
        if (!engine.ready.value) {
            showNotice("Camera isn't ready")
            return
        }
        // **Simple: the shortest route from a press to a file.** No filter, no crop, no stamp, no timer
        // and no roll, so `Frames.process` recognises that there is nothing to do and writes the sensor's
        // own JPEG straight out — no decode of a huge bitmap, no re-encode, EXIF intact. Everything below
        // this branch exists to serve the options Simple does not have.
        if (prefs.mode.value.isSimple) {
            shootSimple()
            return
        }
        // Four shots and a strip, if that is what the menu says. Its own routine, because a booth
        // sequence is not a photograph taken four times: it counts you in, it cannot be stopped
        // halfway, and what it produces is one print.
        if (PuriStrip.enabled(prefs.puriStrip.value) && filter.value.facesAware && !videoMode()) {
            shootStrip(puriStripLayout())
            return
        }
        val loadedRoll = roll.value
        if (loadedRoll != null && loadedRoll.finished) {
            showNotice("Roll finished — develop it")
            return
        }
        _shooting.value = true
        viewModelScope.launch {
            try {
                val timer = prefs.timer.value
                if (timer.seconds > 0 && captureRequestOutput == null) {
                    for (second in timer.seconds downTo 1) {
                        _countdown.value = second
                        delay(1_000)
                    }
                    _countdown.value = null
                }

                // Screen size never touches the shutter: the frame is already on the panel. This
                // is the whole of the fast path, and with a filter on it is the very frame you
                // were looking at rather than a second one processed to match.
                //
                // **The coarse filters always come this way too, whatever the size is set to.** A
                // Game Boy frame is 160 cells wide by definition; capturing 12MP to throw all of it
                // into those cells costs a second and a half and changes nothing in the file. So the
                // size setting governs the photographs where resolution is a real quantity, and the
                // ones where it isn't just take the panel.
                if (prefs.photoSize.value.isPreviewGrab ||
                    filter.value.lowRes ||
                    filter.value.facesAware
                ) {
                    if (!shootPanelFrame(click = true)) showNotice("Nothing on the viewfinder yet")
                    return@launch
                }

                // **The click goes here, at the press.** It used to fire when the capture *returned*, a second
                // and a half after your finger — which is the wrong end of the event. A shutter sound is
                // feedback for the press; the file landing has its own sound now.
                _shutterTick.tryEmit(Unit)

                // **Hold the composition, but not when the flash is on.** A flash exposure takes long
                // enough that the preview frame grabbed here — before the flash has even fired — is a
                // plainly wrong picture: the scene is dark, the flash hasn't lit it, and the frozen
                // panel sits there misleading you for however long the capture takes. Without flash
                // the held frame is roughly what the photograph will look like; with it the preview
                // is the wrong moment entirely, so the viewfinder stays live and the flash itself
                // is the freeze-frame the user sees.
                if (prefs.flash.value == FlashMode.Off) {
                    // **Filtered, if the photograph will be.** `previewFrame` hands back the
                    // *unfiltered* surface — the live filter is a `RenderEffect` on the view, which
                    // never reaches the bitmap. Holding that over a filtered viewfinder would show a
                    // plain picture and then save a Game Boy one, which is the same class of
                    // dishonesty as the Purikura preview showing one thing and saving another. So the
                    // same shader runs over the held frame, at panel size, where it is a few
                    // milliseconds.
                    _held.value = engine.previewFrame()?.let { panel ->
                        val look = filter.value
                        if (look.agsl == null) {
                            panel
                        } else {
                            runCatching {
                                ShaderRuntime.applyToBitmap(panel, look, Random.nextFloat() * 1000f)
                            }.getOrDefault(panel)
                        }
                    }
                }
                val startedAt = System.nanoTime()
                // **A deadline on the capture, because a shutter that hangs never comes back.**
                // `takePicture` reports both success and failure through a callback, and a HAL that
                // delivers neither leaves this coroutine suspended for ever — with `_shooting` still
                // latched, which is the first line of this function, so every press after it is
                // dropped in silence and the only cure is force-stopping the app. Stills measure 1.8 s
                // on this camera; twelve seconds is not a budget, it is the line past which the sensor
                // has plainly stopped answering.
                val attempt = runCatching { withTimeout(CAPTURE_DEADLINE_MS) { engine.capture() } }
                    .onFailure { Log.e(TAG, "capture failed", it) }
                // Averaged over four, so one slow shot in the dark does not make every bar wrong afterwards.
                val took = (System.nanoTime() - startedAt) / 1_000_000
                _stillMs.value = (_stillMs.value * 3 + took) / 4
                Log.i(TAG, "pro: shot ${took}ms")
                // Reported here as well as in Simple: a diagnostic that only measures the fast path cannot
                // tell you whether a change to the slow one helped.
                if (prefs.timings.value) showNotice("${took}ms shot")
                val frame = attempt.getOrNull()
                if (frame == null) {
                    // **The frame on the panel rather than no photograph at all.** The shutter was
                    // pressed and there is a picture on the screen; saving that is worse than the
                    // capture would have been and better than everything else on offer. It also
                    // means a camera whose stills unit has stopped answering degrades to a working
                    // camera instead of a dead button.
                    //
                    // Say *what* went wrong either way. "Shutter failed" cost a round trip to work
                    // out that zero-shutter-lag was accepting the configuration and then refusing
                    // every capture; the camera's own message would have named it.
                    val rescued = shootPanelFrame(click = false)
                    val why = attempt.exceptionOrNull()?.message?.take(48)
                    showNotice(
                        when {
                            rescued -> "Sensor didn't answer — saved the viewfinder frame"
                            why.isNullOrBlank() -> "Shutter failed"
                            else -> "Shutter: $why"
                        },
                    )
                    return@launch
                }
                val activeFilter = filter.value
                val aspect = prefs.aspect.value
                // A fresh seed per frame, so two shots of the same scene don't carry
                // identical grain — and so the grain in the file is not the grain that
                // happened to be on screen at the moment of the press.
                val seed = Random.nextFloat() * 1000f
                val stampAt = stampTime(activeFilter)
                val processed = withContext(Dispatchers.Default) {
                    Frames.process(frame, activeFilter, aspect, seed, stampAt, prefs.stampStyle.value)
                }

                finish(processed, activeFilter.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                reportShutterFailure(failure)
            } finally {
                // Let the picture go, whatever happened: saved, failed, or cancelled. A viewfinder frozen for
                // ever is a worse bug than a slow one.
                _held.value = null
                _countdown.value = null
                _shooting.value = false
            }
        }
    }

    /**
     * The photograph off the panel, filter and all. False when there was nothing there to take.
     *
     * The whole of `Screen` size and the only route the coarse and face-aware filters ever take —
     * and, since the capture has a deadline now, what happens when the sensor does not answer. Its
     * own function because it has two callers with two different ideas about the shutter click: at a
     * deliberate panel grab the click *is* the exposure, while after a failed capture the click
     * already sounded at the press and a second one would claim a second photograph.
     */
    private suspend fun shootPanelFrame(click: Boolean): Boolean {
        val grabbed = grabBestFrame() ?: return false
        if (click) _shutterTick.tryEmit(Unit)
        val activeFilter = filter.value
        val seed = Random.nextFloat() * 1000f
        val turn = engine.previewRotationDegrees()
        val aspect = prefs.aspect.value
        val stampAt = stampTime(activeFilter)
        // The faces as the preview found them, in the preview's own pixels. `fromPreview`
        // carries them through the turn and the crop, so the warp stays on the face.
        val faces = if (activeFilter.facesAware) {
            FaceQuads.of(engine.faces.value, grabbed.width, grabbed.height)
        } else {
            emptyList()
        }
        // A Purikura brings its own date — a bubble capsule, a ticket stub, one of
        // eight — so the ordinary date back stands down rather than both of them
        // printing into the same corner.
        // A Purikura's date is its own switch in its own menu, not the date back's:
        // they are different objects that happen to both be dates, and one of them is
        // random by design.
        val puri = puriOverlay(
            filter = activeFilter,
            withDate = prefs.puriDate.value != PuriArt.OFF,
            millis = System.currentTimeMillis(),
        )
        val processed = withContext(Dispatchers.Default) {
            Frames.fromPreview(
                preview = grabbed,
                rotationDegrees = turn,
                filter = activeFilter,
                aspect = aspect,
                seed = seed,
                stampAt = if (puri != null) null else stampAt,
                stampStyle = prefs.stampStyle.value,
                faces = faces,
                overlay = puri,
                tune = prefs.puriTune(),
            )
        }
        finish(processed, activeFilter.id)
        // A fresh arrangement for the next one, so two shots in a row are not the same
        // print with a different face in it.
        if (puri != null) reshufflePuri()
        return true
    }

    /**
     * The frame to make the photograph out of — the newest, or the sharpest of a short burst.
     *
     * **Why the burst happens after the press and not before it.** The textbook version keeps a ring
     * buffer of the last few frames and picks from ones that had already arrived, which costs nothing
     * at the press. That needs a frame source running continuously, and in photo mode this app
     * deliberately has none: `CameraEngine` binds an `ImageAnalysis` only in QR, because a second
     * full-rate consumer of the ISP costs power on every frame whether or not anything reads it. The
     * only frame source here is the panel, one readback at a time.
     *
     * So the trade is stated rather than hidden: this spends about a quarter of a second grabbing
     * eight frames and keeps the sharpest. That is the cost of hand shake being chosen against
     * instead of frozen into the file, it is off by default, and Simple without it is exactly as
     * quick as it ever was.
     */
    private suspend fun grabBestFrame(): Bitmap? {
        if (!prefs.burst.value) return engine.previewFrame()
        var best: Bitmap? = null
        var bestScore = -1f
        repeat(BURST_FRAMES) { index ->
            // No wait before the first: if the burst is going to be abandoned for any reason, the
            // frame it starts from should still be the one that was on the panel at the press.
            if (index > 0) delay(BURST_GAP_MS)
            val frame = engine.previewFrame() ?: return@repeat
            val score = withContext(Dispatchers.Default) {
                runCatching {
                    val small = Bitmap.createScaledBitmap(frame, SCORE_W, SCORE_H, true)
                    val pixels = IntArray(SCORE_W * SCORE_H)
                    small.getPixels(pixels, 0, SCORE_W, 0, 0, SCORE_W, SCORE_H)
                    if (small != frame) small.recycle()
                    Sharpness.of(pixels, SCORE_W, SCORE_H)
                }.getOrDefault(-1f)
            }
            if (score > bestScore) {
                // The loser is released here rather than left to the collector: these are
                // panel-sized bitmaps and eight of them is most of a hundred megabytes.
                val previous = best
                best = frame
                bestScore = score
                if (previous != null && previous != frame) runCatching { previous.recycle() }
            } else {
                runCatching { frame.recycle() }
            }
        }
        // Every grab failing is a viewfinder with nothing on it, which the caller reports. One last
        // try, because a single failed readback mid-burst should not lose the photograph.
        return best ?: engine.previewFrame()
    }

    /**
     * A failed shutter, said out loud.
     *
     * **Nothing may leave the shutter quietly.** An exception out of one of these coroutines reaches
     * `viewModelScope`, which has no handler of its own, so the process dies — and because the camera
     * key relaunches Roll, that arrives as a shutter that did nothing rather than as a crash. Every
     * shooting routine therefore catches everything and names it on the viewfinder, where the phone
     * has no other way of telling you.
     */
    private fun reportShutterFailure(failure: Throwable) {
        Log.e(TAG, "shutter failed", failure)
        val why = failure.message?.take(48)
        showNotice(if (why.isNullOrBlank()) "Shutter failed" else "Shutter: $why")
    }

    /**
     * A photograph, and nothing else.
     *
     * The three things that make this quick, in order of how much they matter:
     *
     *  1. **Nothing to process.** With no filter, no crop and no date the JPEG the ISP produced is the
     *     file — `Frames.process` returns it whole. A filtered 12MP shot has to be decoded to a 48MB
     *     bitmap, run through a shader and re-encoded; skipping that is most of a second.
     *  2. **Twelve megapixels, not fifty.** Reading out and encoding the full sensor is most of the ISP's
     *     second on its own, and each step down is roughly a halving. 12MP is four times the largest
     *     print anyone makes from a phone.
     *  3. **No waiting for focus.** Continuous AF is already running and already converged on whatever
     *     you are pointing at; a press means take it now, not focus and then take it. The two-stage
     *     shutter is a Pro feature.
     *
     * The size is set for the duration and put back afterwards, so a trip through Simple does not quietly
     * rewrite a Pro setting.
     */
    private fun shootSimple() {
        _shooting.value = true
        viewModelScope.launch {
            try {
                // The size is read but never *written* here. An earlier version set it to 12MP before
                // each shot and put it back after — which rebinds the camera, and a rebind mid-capture is
                // what made changing the megapixels in Simple answer "camera is closed".

                // **Simple never reads the size setting, and never writes it.** Size belongs to Pro.
                // Reading it made Simple's behaviour depend on a Pro choice; *writing* it — which an earlier
                // version did on entering the mode — changed that choice behind your back and rebound the
                // camera into the bargain. Both are gone: Simple has exactly one way of taking a photograph.
                //
                // That way is the panel frame. It is the only path on this camera that is actually immediate,
                // it is what the coarse filters have always used, and the picture is already on the screen so
                // there is nothing to capture. Panel resolution — real for sending and for looking at, not
                // for cropping or printing. A still costs 1.8 s on this hardware and that is what Pro is for.
                val startedAt = System.nanoTime()
                val grabbed = engine.previewFrame()
                if (grabbed == null) {
                    showNotice("Nothing on the viewfinder yet")
                    return@launch
                }
                val frame = withContext(Dispatchers.Default) {
                    val made = Frames.fromPreview(
                        preview = grabbed,
                        rotationDegrees = engine.previewRotationDegrees(),
                        filter = Filters.none,
                        aspect = FrameAspect.Full,
                        seed = 0f,
                    )
                    CapturedFrame(jpeg = made.jpeg, rotationDegrees = 0, mirrored = false)
                }
                val captureMs = (System.nanoTime() - startedAt) / 1_000_000
                _shutterTick.tryEmit(Unit)

                // **Everything from here is off the critical path.** The bytes are in hand; the shutter's
                // work is done. Writing five megabytes and inserting a MediaStore row is fast but not
                // free, and it used to sit inside the same coroutine as the capture, so the shot was not
                // "finished" — and `_shooting` not cleared, and the next press ignored — until the file
                // was on disk. Launched separately, the camera is ready again immediately.
                val takenAt = System.currentTimeMillis()
                val stamp = if (prefs.stampPlain.value) prefs.stampStyle.value else null
                val reportTimings = prefs.timings.value
                // Its own catch, because it is its own coroutine: a throw in here would not pass
                // through the one below it and would reach `viewModelScope` unhandled.
                viewModelScope.launch {
                    try {
                        val savedAt = System.nanoTime()
                        val size = Frames.sizeOf(frame.jpeg)
                        val uri = repo.save(
                            jpeg = frame.jpeg,
                            takenAt = takenAt,
                            width = size.first,
                            height = size.second,
                        )
                        val saveMs = (System.nanoTime() - savedAt) / 1_000_000
                        Log.i(
                            TAG,
                            "simple: shot ${captureMs}ms, save ${saveMs}ms, " +
                                "${size.first}x${size.second}",
                        )
                        // The achieved resolution is reported rather than assumed: an analysis stream is
                        // often capped well below the sensor, and what this camera actually hands over is
                        // a fact about the phone rather than something the code gets to decide.
                        if (reportTimings) {
                            val mp = (size.first.toLong() * size.second / 100_000) / 10.0
                            showNotice("${captureMs}ms shot · ${saveMs}ms save · ${mp}MP")
                        }
                        if (uri == null) {
                            showNotice("Couldn't save")
                            return@launch
                        }
                        // The date goes on after the file exists, for the same reason: printing it means
                        // decoding a 12MP JPEG and encoding it again, which is a second that has no
                        // business being between a finger and a photograph. Worst case is an undated
                        // photograph.
                        if (stamp != null) {
                            val stamped = withContext(Dispatchers.Default) {
                                DateStamp.applyTo(frame.jpeg, takenAt, stamp)
                            }
                            if (stamped != null) repo.rewrite(uri, stamped)
                            refreshRoll()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        reportShutterFailure(failure)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                reportShutterFailure(failure)
            } finally {
                _shooting.value = false
            }
        }
    }

    /**
     * Four photographs, three seconds apart, and then the strip.
     *
     * Each frame goes through exactly the same path a single Purikura does — same shader, same frame,
     * same stickers, same date — so a frame off a strip is indistinguishable from one taken on its
     * own. What differs is where they are saved: the four go into a folder the roll does not show, and
     * the strip goes into the camera roll as the one photograph you took.
     *
     * The stickers are **reshuffled between frames**, which is deliberate. A booth's four panels are
     * four different decorations of the same three seconds, and a strip with identical cat ears in
     * every panel looks like a mistake rather than a set.
     */
    private fun shootStrip(layout: PuriStrip.Layout) {
        _shooting.value = true
        viewModelScope.launch {
            val bitmaps = ArrayList<Bitmap>(PuriStrip.SHOTS)
            val takenAt = System.currentTimeMillis()
            // **One orientation for all four, decided before the first frame.**
            //
            // Four photographs on a strip are one object, and the sheet is measured from the first
            // of them — so a phone turned between shots used to give a strip of frames that didn't
            // match, stretched into cells built for a different shape. Read the way up once, here,
            // and hold it for the whole sequence. Turning the phone halfway through a booth
            // countdown now changes nothing, which is the correct amount for it to change.
            //
            // The aspect ratio is pinned for the same reason and at the same moment: it is a setting,
            // and a setting that moves mid-sequence is four photographs that don't stack.
            val stripRotation = engine.previewRotationDegrees()
            val stripAspect = prefs.aspect.value
            try {
                for (shot in 1..PuriStrip.SHOTS) {
                    // Count in before every frame, including the first: a booth gives you a moment
                    // to arrange your face, and the first one is the one you are least ready for.
                    for (second in STRIP_GAP_SECONDS downTo 1) {
                        _countdown.value = second
                        delay(1_000)
                    }
                    _countdown.value = null
                    showNotice("$shot of ${PuriStrip.SHOTS}")

                    val grabbed = engine.previewFrame()
                    if (grabbed == null) {
                        showNotice("Nothing on the viewfinder yet")
                        return@launch
                    }
                    _shutterTick.tryEmit(Unit)
                    val activeFilter = filter.value
                    val faces = FaceQuads.of(engine.faces.value, grabbed.width, grabbed.height)
                    // **No date on the panels.** A booth prints it once, in the margin of the strip,
                    // because the four photographs are one object — four copies of the same date down a
                    // strip is a bug that looks like a feature. It goes on the sheet below.
                    val puri = puriOverlay(
                        filter = activeFilter,
                        withDate = false,
                        millis = takenAt,
                    )
                    val processed = withContext(Dispatchers.Default) {
                        Frames.fromPreview(
                            preview = grabbed,
                            rotationDegrees = stripRotation,
                            filter = activeFilter,
                            aspect = stripAspect,
                            seed = Random.nextFloat() * 1000f,
                            faces = faces,
                            overlay = puri,
                            tune = prefs.puriTune(),
                        )
                    }
                    repo.save(
                        jpeg = processed.jpeg,
                        takenAt = takenAt,
                        width = processed.width,
                        height = processed.height,
                        suffix = shot.toString(),
                        hidden = true,
                    )
                    withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(processed.jpeg, 0, processed.jpeg.size)
                    }?.let { bitmaps += it }
                    reshufflePuri()
                }

                // No date on a strip, in the panels or on the print. The layouts that want one have a
                // footer of their own, which the composer fills in.
                val sheet = withContext(Dispatchers.Default) {
                    PuriStrip.compose(bitmaps, layout, puriFrame(), takenAt)
                }
                if (sheet == null) {
                    showNotice("Couldn't build the strip")
                    return@launch
                }
                val jpeg = withContext(Dispatchers.Default) {
                    java.io.ByteArrayOutputStream(sheet.width * sheet.height / 6).also {
                        sheet.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    }.toByteArray()
                }
                val uri = repo.save(
                    jpeg = jpeg,
                    takenAt = takenAt,
                    width = sheet.width,
                    height = sheet.height,
                    suffix = "strip",
                )
                if (uri == null) showNotice("Couldn't save the strip") else showNotice("Strip saved")
                sheet.recycle()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                reportShutterFailure(failure)
            } finally {
                bitmaps.forEach { it.recycle() }
                _countdown.value = null
                _shooting.value = false
                refreshRoll()
            }
        }
    }

    /**
     * Where a finished photograph goes, whichever way it was made.
     *
     * Shared by the capture path and the `Screen` grab so that the three destinations — another
     * app's `IMAGE_CAPTURE` request, a loaded roll, the gallery — are decided in exactly one place.
     * They were duplicated once and the roll branch was missing from the fast path.
     */
    private suspend fun finish(processed: Frames.Processed, filterId: String) {
        val output = captureRequestOutput
        if (output != null) {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver
                        .openOutputStream(output)?.use { it.write(processed.jpeg) }
                        ?: error("no stream")
                }.isSuccess
            }
            _captureRequestDone.tryEmit(ok)
            return
        }

        val takenAt = System.currentTimeMillis()
        val updated = filmRoll.expose(
            jpeg = processed.jpeg,
            takenAt = takenAt,
            filterId = filterId,
            width = processed.width,
            height = processed.height,
        )
        if (updated != null) {
            showNotice(
                if (updated.finished) "Roll finished" else "${updated.shot} of ${updated.length}",
            )
            return
        }

        val uri = repo.save(
            jpeg = processed.jpeg,
            takenAt = takenAt,
            width = processed.width,
            height = processed.height,
        )
        if (uri == null) {
            showNotice("Couldn't save")
        } else if (prefs.sounds.value) {
            // The other end of the bracket: click at the press, this when the file exists.
            beeps.saved()
        }
    }

    /* ---------------- the roll of film ---------------- */

    fun loadRoll() {
        filmRoll.load(prefs.rollLength.value)
        showNotice("Roll ${filmRoll.developedCount + 1} loaded")
    }

    fun developRoll() {
        val current = roll.value ?: return
        if (current.shot == 0) {
            filmRoll.discard()
            showNotice("Roll unloaded")
            return
        }
        viewModelScope.launch {
            val result = filmRoll.develop(repo)
            _developed.value = result
            refreshRoll()
            showNotice(
                when {
                    result.failed > 0 -> "${result.uris.size} developed, ${result.failed} stuck"
                    else -> "Roll ${result.number} developed"
                },
            )
        }
    }

    fun dismissDeveloped() {
        _developed.value = null
    }

    fun discardRoll() {
        filmRoll.discard()
        showNotice("Roll discarded")
    }

    /* ---------------- deleting ---------------- */

    fun trashRequest(photo: Photo) = repo.trashRequest(listOf(photo.uri))

    /* ---------------- notices ---------------- */

    private var noticeToken = 0

    fun showNotice(text: String) {
        val token = ++noticeToken
        _notice.value = text
        viewModelScope.launch {
            delay(NOTICE_MS)
            if (noticeToken == token) _notice.value = null
        }
    }

    override fun onCleared() {
        observer?.let { runCatching { it.close() } }
        observer = null
        engine.shutdown()
        ShaderRuntime.releasePool()
        beeps.release()
        thumbs.clear()
        super.onCleared()
    }

    private companion object {
        /** Eight, which is the number in the setting's name and about a quarter of a second of them. */
        const val BURST_FRAMES = 8

        /** A little over one frame at 30fps, so each grab is a different frame rather than the same one. */
        const val BURST_GAP_MS = 34L

        /** Small enough that eight Laplacian passes are free, large enough to still contain the edges. */
        const val SCORE_W = 96
        const val SCORE_H = 128

        const val TAG = "CameraViewModel"

        /** The count-in before each frame of a strip. Long enough to change your face, not your mind. */
        const val STRIP_GAP_SECONDS = 3
        const val NOTICE_MS = 1_400L

        /**
         * How long the shutter waits for `takePicture` before it gives up on the sensor.
         *
         * Generous on purpose. A 50MP capture with the flash on auto is a slow thing on this phone
         * and cutting a real photograph short would be the worse bug; this is only here to make sure
         * a capture that is never coming cannot hold the shutter shut for ever.
         */
        const val CAPTURE_DEADLINE_MS = 12_000L
    }
}
