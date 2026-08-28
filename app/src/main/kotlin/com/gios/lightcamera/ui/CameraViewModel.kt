package com.gios.lightcamera.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightcamera.CaptureMode
import com.gios.lightcamera.CrashLog
import com.gios.lightcamera.PhotoSize
import com.gios.lightcamera.Prefs
import com.gios.lightcamera.SelfTimer
import com.gios.lightcamera.StampStyle
import com.gios.lightcamera.camera.CameraEngine
import com.gios.lightcamera.camera.CapturedFrame
import com.gios.lightcamera.camera.DateStamp
import com.gios.lightcamera.camera.FaceBox
import com.gios.lightcamera.camera.FlashMode
import com.gios.lightcamera.camera.FrameAspect
import com.gios.lightcamera.camera.Frames
import com.gios.lightcamera.camera.Ring
import com.gios.lightcamera.map.Locations
import com.gios.lightcamera.map.Point
import com.gios.lightcamera.map.Tiles
import com.gios.lightcamera.camera.PuriArt
import com.gios.lightcamera.camera.PuriStrip
import com.gios.lightcamera.camera.Sharpness
import com.gios.lightcamera.filter.FaceQuad
import com.gios.lightcamera.filter.FaceQuads
import com.gios.lightcamera.filter.FaceTune
import com.gios.lightcamera.filter.Filters
import com.gios.lightcamera.filter.ShaderRuntime
import com.gios.lightcamera.hw.Beeps
import com.gios.lightcamera.hw.Binding
import com.gios.lightcamera.hw.Channel
import com.gios.lightcamera.hw.Controls
import com.gios.lightcamera.hw.PressAction
import com.gios.lightcamera.ocr.Found
import com.gios.lightcamera.ocr.PageReader
import com.gios.lightcamera.ocr.Reading
import com.gios.lightcamera.ocr.TextScan
import com.gios.lightcamera.qr.CodeHandoff
import com.gios.lightcamera.qr.Codes
import com.gios.lightcamera.qr.ScanGate
import androidx.camera.core.ImageCapture
import com.gios.lightcamera.media.CaptureFormat
import com.gios.lightcamera.media.CaptureGroup
import com.gios.lightcamera.media.Captures
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
import kotlinx.coroutines.withTimeoutOrNull
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
        val scoped = if (scope == RollScope.Favourites) {
            list.filter { it.name in starred }
        } else {
            list
        }
        // **One press is one item, even when it wrote three files.** The grid draws this list
        // directly and the viewer pages through it, so collapsing here is what stops a negative and
        // its print appearing as two photographs of the same moment — which is what a roll full of
        // near-duplicates would be, and the reason most cameras make you pick a format instead.
        // The alternates are not lost: [groupOf] finds them, and the viewer's corner control walks
        // them.
        Captures.of(scoped).map { it.primary.photo }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Every file from every press, grouped, before the roll collapses it.
     *
     * Kept beside [photos] rather than recomputed by the viewer: the grouping is a parse of every
     * filename in the roll, which is cheap once and wasteful on every recomposition.
     */
    val groups: StateFlow<List<CaptureGroup>> = combine(
        _photos,
        prefs.favourites,
        prefs.scope,
    ) { list, starred, scope ->
        val scoped = if (scope == RollScope.Favourites) {
            list.filter { it.name in starred }
        } else {
            list
        }
        Captures.of(scoped)
        // Off the main thread, because this re-parses every filename on the roll and runs on
        // every save — which during a burst is once per shot. Grouping a thousand photographs on
        // the thread the viewfinder draws on was a measurable slice of the burst lag.
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * What the wheel is holding.
     *
     * Kept here rather than in `Prefs` on purpose: this is where you *are*, not what you have
     * chosen. Coming back to the camera with the wheel still on Shutter from yesterday — in a mode
     * that no longer holds the shutter — is a control pointing at nothing.
     */
    private val _channel = MutableStateFlow(Channel.Filter)
    val channel: StateFlow<Channel> = _channel.asStateFlow()

    /** The channels that mean something right now. See [Channel.available]. */
    fun channelsAvailable(): List<Channel> = Channel.available(
        exposure = engine.exposureMode.value,
        filters = !prefs.mode.value.isSimple && prefs.mode.value != CaptureMode.Video,
    )

    /**
     * Whether the wheel is choosing *what to hold* rather than adjusting it.
     *
     * **The wheel is modal, and the first version was not — which made it useless.** A click that
     * silently cycled the channel left the turn doing whatever the turn was bound to, so binding
     * the click to Channel produced a notice and no behaviour: click "switched", the spin still
     * walked the filters. The model people actually described wanting is a camera menu dial —
     * click to open the choice, turn to choose, click to lock it in, turn to adjust — and that
     * needs one bit of state, which is this.
     */
    private val _picking = MutableStateFlow(false)
    val picking: StateFlow<Boolean> = _picking.asStateFlow()

    /**
     * Entering Video hands the wheel to zoom.
     *
     * The one mode where what you want from the wheel is not a question: there is no filter track,
     * no shutter dial, and re-framing mid-recording is the entire reason a camcorder puts zoom
     * under a finger. Set on entry rather than remembered, so leaving Video gives the wheel back
     * to whatever it held before.
     */
    private var channelBeforeVideo: Channel? = null

    /**
     * Zone focus puts the distance on the wheel the moment it is switched on.
     *
     * The GR bodies are the model: their snap-focus mode exists for the street, and on the street
     * the distance *is* the exposure — you set it walking, from the hip, without looking. A wheel
     * that needs click-turn-click to reach focus after you already said "manual focus" is asking
     * you to say it twice. Switching MF on is the statement; the dial follows it.
     *
     * Same shape as [channelForMode]'s Video claim, and it hands back what the wheel held before,
     * so AF -> MF -> AF round-trips leave the dial where you had it.
     */
    private var channelBeforeFocus: Channel? = null

    private fun channelForZone(on: Boolean) {
        if (on) {
            // Not stored when the wheel is already on Focus — zone switched on *by* the wheel
            // would otherwise remember Focus as the place to go back to, and the dial could
            // never leave.
            if (_channel.value != Channel.Focus) channelBeforeFocus = _channel.value
            _channel.value = Channel.Focus
            _picking.value = false
        } else {
            // Restored only if the wheel is still on Focus: zone switched off *by* locking a
            // different channel has already moved the dial, and that choice outranks a memory.
            if (_channel.value == Channel.Focus) {
                channelBeforeFocus?.let { held ->
                    if (held in channelsAvailable()) _channel.value = held
                }
            }
            channelBeforeFocus = null
            settleChannel()
        }
    }

    private fun channelForMode(mode: CaptureMode) {
        if (mode == CaptureMode.Video) {
            channelBeforeVideo = _channel.value
            _channel.value = Channel.Zoom
            _picking.value = false
        } else {
            channelBeforeVideo?.let { held ->
                channelBeforeVideo = null
                if (held in channelsAvailable()) _channel.value = held
            }
            settleChannel()
        }
    }

    /** The click, both of its meanings: open the choice, or lock it in. */
    fun toggleChannelPicking() {
        if (_picking.value) {
            _picking.value = false
            // **Locking the pick is the AF/MF switch.** FOCUS is on the dial whether or not zone
            // focus is on, and choosing it *means* manual focus — asking for a second gesture to
            // say so would be the modal wheel's own click-turn-click complaint again, one layer
            // up. Locking anything else says the lens is the camera's again.
            prefs.setZoneFocus(_channel.value == Channel.Focus)
            showNotice("${_channel.value.label} — turn to adjust")
        } else {
            _picking.value = true
            showNotice("Pick: ${_channel.value.label} — turn, then click")
        }
    }

    /**
     * A turn of the wheel while it belongs to the channel system.
     *
     * Picking: one channel per gesture, whichever way it was flicked — the list is short and names
     * are read one at a time. Locked in: the turn adjusts the held channel's value, every notch.
     */
    fun channelTurn(notches: Int) {
        if (_picking.value) {
            val available = channelsAvailable()
            if (available.isEmpty()) return
            val at = available.indexOf(_channel.value).coerceAtLeast(0)
            val step = if (notches > 0) 1 else -1
            val next = available[(at + step + available.size) % available.size]
            _channel.value = next
            showNotice("Pick: ${next.label} — turn, then click")
            return
        }
        when (_channel.value) {
            Channel.Filter -> stepFilter(if (notches > 0) 1 else -1)
            Channel.Exposure -> {
                engine.stepEv(notches)
                showNotice("EV ${engine.evLabel()}")
            }
            Channel.Shutter -> {
                engine.stepShutter(notches)
                showNotice(engine.exposureLabel.value)
            }
            Channel.Iso -> {
                engine.stepIso(notches)
                showNotice(engine.exposureLabel.value)
            }
            Channel.Focus -> {
                engine.stepFocus(notches)
                showNotice(engine.focusLabel.value)
            }
            Channel.Zoom -> {
                engine.stepZoom(notches)
                showNotice(engine.zoomLabel())
            }
        }
    }

    /**
     * Keep the wheel pointing at something real.
     *
     * Leaving a priority mode takes its half of the exposure off the dial, and the wheel must not
     * be left holding it — turning a dead channel is how a physical control loses trust.
     */
    fun settleChannel() {
        val available = channelsAvailable()
        if (_channel.value !in available) {
            _channel.value = available.firstOrNull() ?: Channel.Zoom
            // Mid-pick, the list just changed under the reader; closing is less surprising than
            // the selection silently jumping to a channel nobody chose.
            _picking.value = false
        }
    }

    /** The tile cache, held here so it outlives a trip to the map and back. */
    val tiles = Tiles(app)

    private val _located = MutableStateFlow<List<Pair<Photo, Point>>>(emptyList())
    val located: StateFlow<List<Pair<Photo, Point>>> = _located.asStateFlow()

    /**
     * Read coordinates off the roll, for the map.
     *
     * **Only while the map is the scope**, and one photograph at a time. Every read is a file
     * opened and an EXIF header parsed, and doing that for a thousand photographs on every roll
     * refresh would be a gallery that stalls for a feature most people are not looking at.
     *
     * It also needs `ACCESS_MEDIA_LOCATION`: since Android 10 MediaStore strips GPS out of
     * anything it hands you unless the original is asked for, so without that permission this
     * returns nothing for every photograph — including ones stamped a second ago.
     */
    private var locateJob: Job? = null

    /**
     * Which notice is current, so an old one's fade cannot clear a newer one.
     *
     * Up here rather than beside [showNotice] because the watchdog made that function reachable
     * from `init`, and InitOrderTest holds every such property to the rule the v2.64 launch crash
     * taught: anything a construction-time collector can touch is declared above `init`, however
     * safe the individual call happens to be.
     */
    private var noticeToken = 0

    /**
     * The darkroom: captures waiting to be developed, drained behind the shutter.
     *
     * **The shutter waits for the capture and for nothing else — the buffer model every real
     * camera body uses.** Developing a frame is a 12-megapixel decode, a GPU pass, one or two
     * encodes and a MediaStore write, most of a second on this hardware, and it used to sit
     * inside the same latch as the shutter: press, and the next press waited for the previous
     * photograph's *filter*. A camera that is quick once and then slow is not a quick camera.
     *
     * The channel's capacity is the buffer depth, and `send` suspending on a full buffer is the
     * entire backpressure design: shoot faster than the darkroom drains for long enough and the
     * shutter waits exactly as a body's does when the card cannot keep up — briefly, and only
     * then. Each queued job holds one full-resolution JPEG, so the depth is memory: six of them
     * is ~30MB, which this phone can hold and a longer queue could not.
     *
     * **One worker, deliberately serial.** Developing means a ~48MB bitmap; two at once is an
     * out-of-memory wearing a throughput argument.
     *
     * What is lost if the process dies mid-queue: the undeveloped shots, which existed only here.
     * The same is true of any camera's buffer when the battery comes out.
     */
    private val darkroom = kotlinx.coroutines.channels.Channel<Darkwork>(DARKROOM_DEPTH)

    /**
     * How many captures may be outstanding at the sensor at once.
     *
     * **This is what makes the shutter re-arm in milliseconds.** CameraX explicitly permits
     * `takePicture` while an earlier one is still in flight — it queues requests internally — and
     * the old shape awaited each frame before unlatching, which billed the whole sensor round trip
     * to the finger. Now a press *issues* a capture and returns; the frame lands whenever it
     * lands and walks into the darkroom on its own.
     *
     * Two, not more: a ZSL ring holds only a few buffers and refills at frame rate, and asking
     * for six at once is asking five of them to be the same frame or a failure. Presses beyond
     * the permits queue — a held shutter becomes a burst, which is what a held shutter means —
     * and the darkroom's own depth bounds the whole pipeline behind it.
     */
    private val captureGate = kotlinx.coroutines.sync.Semaphore(MAX_IN_FLIGHT)

    /** Sensor captures issued and not yet landed, part of the status row's depth gauge. */
    private val _inFlight = MutableStateFlow(0)
    val inFlight: StateFlow<Int> = _inFlight.asStateFlow()



    /** Jobs enqueued and not yet finished, for the buffer gauge in the status row. */
    private val _developing = MutableStateFlow(0)
    val developing: StateFlow<Int> = _developing.asStateFlow()

    /**
     * Panel frames waiting in the queue — the one job type that still holds heap.
     *
     * A queued panel frame is a ~10MB bitmap, so these are counted separately and capped at two:
     * [develop] waits for a slot rather than queueing a third. Everything else in the darkroom is
     * a Uri or a byte array already accounted for, and file jobs are a few hundred bytes each.
     */
    private val _panelQueued = MutableStateFlow(0)

    /** When the worker started the job it is on, elapsed-realtime; zero while idle. */
    private val _developingSince = MutableStateFlow(0L)
    val developingSince: StateFlow<Long> = _developingSince.asStateFlow()

    /** Rolling estimate of one develop, for the gauge's fill. Seeded pessimistically. */
    private val _developEstMs = MutableStateFlow(900L)
    val developEstMs: StateFlow<Long> = _developEstMs.asStateFlow()

    /**
     * Faults since launch, for the chip on the viewfinder.
     *
     * The notice a failure raises lives for two seconds and then the evidence is gone; a burst
     * that dropped one frame in the middle deserves a mark that stays until it is read. Tapping
     * the chip replays the last fault and clears the count. A crash from the previous run counts
     * as one, so the chip is also how a black screen introduces itself on the next launch.
     */
    private val _faults = MutableStateFlow(0)
    val faults: StateFlow<Int> = _faults.asStateFlow()

    private val _lastFault = MutableStateFlow("")

    private fun recordFault(what: String) {
        _faults.value += 1
        _lastFault.value = what
        // The chip counter is the quiet, persistent half; this is the loud, consented half.
        // Trouble dedupes per message per hour and keeps the first failure of a cascade, and its
        // collector in the activity raises the standard light-common "SEND ERROR?" chip — the
        // shake-to-report pipeline, offered by the app instead of waiting to be shaken about.
        Trouble.record(what)
    }

    fun readFaults() {
        if (_lastFault.value.isNotEmpty()) showNotice(_lastFault.value)
        _faults.value = 0
        // Reading the chip acknowledges the crash it may be carrying, so it does not return at
        // the next launch. The first 120 characters are version, time and thread — a fingerprint
        // that survives the file being appended to and distinguishes a genuinely new crash.
        CrashLog.last(getApplication())?.let { prefs.setCrashSeen(it.take(120)) }
    }

    private sealed class Darkwork

    /**
     * A photograph already on disk, waiting for its filter.
     *
     * **This is the fix for three quick shots killing the app.** The first darkroom queued the
     * capture's *bytes* — a 12-megapixel JPEG per shot, held in a heap this phone caps at 128MB,
     * beside a worker whose decode needs ~48MB and whose filtered copy needs ~48MB more. Three
     * shots in the queue was the whole heap: the "lag" was the garbage collector fighting for
     * scraps, and the black screen was the low-memory killer taking the process with no stack
     * trace to leave behind — the crash report said "Heap 9 MB of 128 MB" and "the app did not
     * die", which is what an LMK kill looks like from the inside.
     *
     * So the buffer is the disk now, the way a camera body's buffer is the card. The untouched
     * frame is *saved immediately* at capture — it appears on the roll unfiltered, which also
     * means a process death costs the filter and never the photograph — and what queues is this:
     * a Uri, a stem and the settings, a few hundred bytes. The worker reads the file back,
     * develops it alone (one decode alive at a time, by construction), and rewrites it in place —
     * the same shape the negative's JPEG has used since v2.62. "As many shots as it can" is now
     * bounded by free space, which the same report put at 68.9 GB.
     */
    private class FileJob(
        val uri: Uri,
        val stem: String,
        val rotationDegrees: Int,
        val mirrored: Boolean,
        val filter: Filters.Filter,
        val aspect: FrameAspect,
        val seed: Float,
        val stampAt: Long?,
        val stampStyle: StampStyle,
        val wantPng: Boolean,
        val takenAt: Long,
    ) : Darkwork()

    private class DevelopJob(
        val frame: CapturedFrame,
        val filter: Filters.Filter,
        val aspect: FrameAspect,
        val seed: Float,
        val stampAt: Long?,
        val stampStyle: StampStyle,
        val wantPng: Boolean,
        /** The moment of the press, not of the develop — a queued photograph keeps its time. */
        val takenAt: Long,
    ) : Darkwork()

    /**
     * A panel frame waiting to be made into a photograph.
     *
     * The other half of the pipeline: Simple and every coarse filter shoot the panel, and the
     * rotate-shade-encode used to run inline at the press. It is a develop like any other, so it
     * queues like any other, and the press keeps only the readback — which is the whole cost a
     * panel shot has any business charging a finger. The worker recycles [bitmap]; a queued panel
     * frame is ~10MB, which is why [DARKROOM_DEPTH] is the small number it is.
     */
    private class PanelJob(
        val bitmap: Bitmap,
        val turn: Int,
        val filter: Filters.Filter,
        val aspect: FrameAspect,
        val seed: Float,
        val stampAt: Long?,
        val stampStyle: StampStyle,
        val faces: List<FaceQuad>,
        val overlay: ((android.graphics.Canvas, Int, Int, List<FaceQuad>) -> Unit)?,
        val tune: FaceTune,
        val wantPng: Boolean,
        val takenAt: Long,
    ) : Darkwork()

    /**
     * Send one capture to the darkroom. Suspends only when the buffer is full.
     *
     * The count is incremented *here*, before the send, so the indicator covers the job during a
     * full-buffer wait as well — a shutter that pauses with nothing on screen saying why is the
     * exact experience this queue exists to end.
     */
    private suspend fun develop(job: Darkwork) {
        _developing.value += 1
        darkroom.send(job)
    }

    /**
     * A panel frame enters the queue, at whatever fidelity the heap can afford right now.
     *
     * **Nothing here waits, because the press must not.** The previous shape suspended the third
     * panel job until a slot freed — which put the wait back at the finger, the exact thing the
     * darkroom exists to remove. Instead the frame degrades: the first few queue at full panel
     * resolution, the next several at half (a quarter of the bytes — the moment preserved, some
     * pixels traded), and past a dozen the shot is refused *out loud*. A refusal with a named
     * reason is a camera being honest about its limits; a wait is a camera lying about whose time
     * it is spending.
     */
    private suspend fun enqueuePanel(job: PanelJob) {
        val depth = _panelQueued.value
        val queued = when {
            depth < PANEL_FULL_DEPTH -> job
            depth < PANEL_MAX_DEPTH -> {
                val half = runCatching {
                    Bitmap.createScaledBitmap(
                        job.bitmap,
                        (job.bitmap.width / 2).coerceAtLeast(1),
                        (job.bitmap.height / 2).coerceAtLeast(1),
                        true,
                    )
                }.getOrNull()
                if (half != null && half != job.bitmap) {
                    runCatching { job.bitmap.recycle() }
                    PanelJob(
                        bitmap = half,
                        turn = job.turn,
                        filter = job.filter,
                        aspect = job.aspect,
                        seed = job.seed,
                        stampAt = job.stampAt,
                        stampStyle = job.stampStyle,
                        faces = job.faces,
                        overlay = job.overlay,
                        tune = job.tune,
                        wantPng = job.wantPng,
                        takenAt = job.takenAt,
                    )
                } else {
                    job
                }
            }
            else -> {
                runCatching { job.bitmap.recycle() }
                recordFault("Buffer full — a shot was dropped")
                showNotice("Buffer full")
                return
            }
        }
        _panelQueued.value += 1
        develop(queued)
    }

    /**
     * The darkroom's own thread, at background priority.
     *
     * `Dispatchers.Default` shares a small pool with everything else that computes, and on this
     * SoC a 12-megapixel decode on it is jank you can see in the viewfinder — the "lag after
     * three shots" was half garbage collector and half this. A single dedicated thread at
     * `MIN_PRIORITY` develops just as fast when the phone is idle and yields the moment the
     * camera needs the cores, which is the correct order of importance for a camera.
     */
    private val darkroomThread =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            // Android schedules by its own nice values, not Java's: MIN_PRIORITY alone still
            // competed with the viewfinder. THREAD_PRIORITY_BACKGROUND is what actually yields
            // the cores — set on the thread itself, first thing it does.
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "darkroom").apply { priority = Thread.MIN_PRIORITY }
        }.asCoroutineDispatcher()

    private fun startDarkroom() {
        viewModelScope.launch {
            for (job in darkroom) {
                val startedAt = SystemClock.elapsedRealtime()
                _developingSince.value = startedAt
                try {
                    when (job) {
                        is DevelopJob -> {
                            val processed = withContext(darkroomThread) {
                                Frames.process(
                                    job.frame,
                                    job.filter,
                                    job.aspect,
                                    job.seed,
                                    job.stampAt,
                                    job.stampStyle,
                                    wantPng = job.wantPng,
                                )
                            }
                            finish(processed, job.filter.id, job.takenAt)
                        }
                        is FileJob -> developFile(job)
                        is PanelJob -> {
                            val processed = withContext(darkroomThread) {
                                Frames.fromPreview(
                                    preview = job.bitmap,
                                    rotationDegrees = job.turn,
                                    filter = job.filter,
                                    aspect = job.aspect,
                                    seed = job.seed,
                                    stampAt = job.stampAt,
                                    stampStyle = job.stampStyle,
                                    faces = job.faces,
                                    overlay = job.overlay,
                                    tune = job.tune,
                                    wantPng = job.wantPng,
                                )
                            }
                            runCatching { job.bitmap.recycle() }
                            finish(processed, job.filter.id, job.takenAt)
                        }
                    }
                    // The estimate feeds the gauge's fill, averaged over four like the shutter's
                    // own readout, so one slow Purikura does not stretch every bar after it.
                    val took = SystemClock.elapsedRealtime() - startedAt
                    _developEstMs.value = (_developEstMs.value * 3 + took) / 4
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // Named per job: a failed develop is one photograph, not a dead queue.
                    reportShutterFailure(failure)
                    if (job is PanelJob) runCatching { job.bitmap.recycle() }
                } finally {
                    if (job is PanelJob) _panelQueued.value -= 1
                    _developing.value -= 1
                    if (_developing.value <= 0) _developingSince.value = 0L
                }
            }
        }
    }

    /**
     * Everything that happens to a sensor frame once it exists, shared by the first attempt and
     * the post-recovery retry so the two cannot drift.
     *
     * Film-roll exposures queue whole — they are not files and arrive at a person's pace. Anything
     * else is **saved untouched immediately** (the disk is the buffer) and either finished on the
     * spot when nothing wants developing, or queued as a [FileJob] to be rewritten in place.
     */
    private suspend fun handleQuickFrame(
        frame: CapturedFrame,
        activeFilter: Filters.Filter,
        aspect: FrameAspect,
        seed: Float,
        stampAt: Long?,
        stampStyle: StampStyle,
        wantPng: Boolean,
        pressedAt: Long,
    ) {
        if (roll.value != null) {
            develop(
                DevelopJob(
                    frame = frame,
                    filter = activeFilter,
                    aspect = aspect,
                    seed = seed,
                    stampAt = stampAt,
                    stampStyle = stampStyle,
                    wantPng = wantPng,
                    takenAt = pressedAt,
                ),
            )
            return
        }
        val stem = repo.stemFor(pressedAt)
        val size = Frames.sizeOf(frame.jpeg)
        val uri = repo.save(
            jpeg = frame.jpeg,
            takenAt = pressedAt,
            width = size.first,
            height = size.second,
            stem = stem,
        )
        if (uri == null) {
            recordFault("Couldn't save")
            showNotice("Couldn't save")
            return
        }
        val needsWork = activeFilter.agsl != null ||
            aspect != FrameAspect.Full ||
            stampAt != null ||
            wantPng
        if (!needsWork) {
            stampLocation(uri)
            if (prefs.sounds.value) beeps.saved()
        } else {
            develop(
                FileJob(
                    uri = uri,
                    stem = stem,
                    rotationDegrees = frame.rotationDegrees,
                    mirrored = frame.mirrored,
                    filter = activeFilter,
                    aspect = aspect,
                    seed = seed,
                    stampAt = stampAt,
                    stampStyle = stampStyle,
                    wantPng = wantPng,
                    takenAt = pressedAt,
                ),
            )
        }
    }

    /**
     * Develop a photograph that is already a file: read, filter, rewrite in place.
     *
     * The same shape the negative's JPEG has used since v2.62, generalised. Everything here may
     * fail alone: the untouched photograph was saved at the press and is already real, so the
     * worst any line below can do is leave it unfiltered — which is a lesser photograph, never a
     * lost one.
     */
    private suspend fun developFile(job: FileJob) {
        val resolver = getApplication<Application>().contentResolver
        val original = withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(job.uri)?.use { it.readBytes() } }.getOrNull()
        }
        if (original == null) {
            recordFault("Couldn't read a shot back to develop it")
            return
        }
        val processed = withContext(darkroomThread) {
            Frames.process(
                CapturedFrame(original, job.rotationDegrees, job.mirrored),
                job.filter,
                job.aspect,
                job.seed,
                job.stampAt,
                job.stampStyle,
                wantPng = job.wantPng,
            )
        }
        // Identity means the fast path fired inside process() — nothing to rewrite, and rewriting
        // the same bytes would only churn the file's dates.
        if (processed.jpeg !== original) {
            if (!repo.rewrite(job.uri, processed.jpeg)) recordFault("Couldn't develop a shot")
        }
        if (job.wantPng) {
            val png = processed.png
            if (png == null) {
                recordFault("Lossless copy failed")
            } else if (
                repo.save(
                    jpeg = png,
                    takenAt = job.takenAt,
                    width = processed.width,
                    height = processed.height,
                    stem = job.stem,
                    format = CaptureFormat.Png,
                ) == null
            ) {
                recordFault("Couldn't save the lossless copy")
            }
        }
        // Last, as ever: everything above rewrites bytes, and a coordinate lives in them.
        stampLocation(job.uri)
        if (prefs.sounds.value) beeps.saved()
    }

    /**
     * The RAW toggle, from the band.
     *
     * The settings row can read "Unavailable"; a band slot has three letters and no room to
     * explain, so the explanation happens at the tap — once, in a notice, rather than as a control
     * that lights up and then writes nothing.
     */
    fun toggleRaw() {
        if (!engine.negativeSupported.value) {
            showNotice("No RAW on this camera")
            return
        }
        prefs.toggleFormat(CaptureFormat.Dng)
    }

    /** Re-read the roll's coordinates — called when a permission has just been granted. */
    fun relocateRoll() = locateRoll()

    /**
     * The frames already in hand when the button is pressed.
     *
     * **Declared up here with the rest of the state, and that is not tidiness.** `init` starts a
     * collector on `prefs.preRollMs`, `viewModelScope` runs on `Dispatchers.Main.immediate`, and a
     * `StateFlow` hands over its current value the moment it is collected — so that collector runs
     * *during construction*, synchronously, before any property declared below `init` has been
     * initialised. This ring lived next to the shutter code that uses it, four hundred lines further
     * down, and reading it from `updatePreRoll` gave back a field that was still null: an immediate
     * crash on launch, in a build where every test passed.
     *
     * Anything `init` can reach has to be declared above `init`. The same note is at the top of
     * that block for [photos].
     *
     * **Filled from the panel, not from a second stream off the ISP.** `CameraEngine` binds an
     * `ImageAnalysis` only in QR mode, deliberately: a second full-rate consumer costs power on
     * every frame whether or not anything reads it. The panel is a frame source that is already
     * running, so the ring reads back from it and the camera pipeline is untouched.
     *
     * Every frame that leaves this ring without being used is recycled by the ring itself. Eight
     * panel bitmaps is most of a hundred megabytes, and leaving that to the collector is an
     * out-of-memory two photographs later.
     */
    private val preRollRing = Ring<Bitmap>(Ring.DEFAULT_CAPACITY) { frame ->
        runCatching { frame.recycle() }
    }

    private var preRollJob: Job? = null

    private fun locateRoll() {
        locateJob?.cancel()
        if (prefs.scope.value != RollScope.Map) return
        locateJob = viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val found = ArrayList<Pair<Photo, Point>>()
            // Newest first, published as it goes: a map that fills in over a second or two is far
            // better than a blank one that appears all at once a minute later.
            photos.value.forEach { photo ->
                if (!isActive) return@launch
                val point = Locations.read(resolver, photo.uri) ?: return@forEach
                found += photo to point
                _located.value = found.toList()
            }
            _located.value = found
        }
    }

    /** The other formats of the photograph on screen, or null when there is only the one file. */
    fun groupOf(photo: Photo): CaptureGroup? =
        groups.value.firstOrNull { group -> group.members.any { it.photo.id == photo.id } }
            ?.takeIf { it.hasAlternatives }

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

    /**
     * The filter the photograph in flight is being made with, held from the press until the file
     * lands. Null when nothing is in flight.
     *
     * **This is what lets the dial move while the shutter is open.** In Pro the filter is applied
     * to the bytes about 1.8 seconds after your finger, so reading the dial at *that* moment meant
     * a notch turned inside the window baked a look you were not framing into the file — and the
     * dial was simply closed for those 1.8 seconds to prevent it. Which is the wrong trade: the
     * one moment you most want to set up the next shot is the moment you have just taken one, and
     * a camera that ignores the wheel for two seconds after every press reads as a camera that
     * missed the input.
     *
     * Pinning at the press answers both. The photograph keeps the look it was framed with, and
     * the wheel is free the whole time.
     *
     * Declared above `init` with [filterLocked], for the reason documented there.
     */
    private var shootingWith: Filters.Filter? = null

    /**
     * The look this photograph belongs to: pinned if one is in flight, the dial otherwise.
     *
     * Every read inside a shooting coroutine goes through here rather than through `filter.value`,
     * and that is the whole of the mechanism — a stray `filter.value` in the capture path is a
     * photograph that changes its mind halfway through.
     */
    private fun lookForShot(): Filters.Filter = shootingWith ?: filter.value

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
        viewModelScope.launch { prefs.preRollMs.collect { updatePreRoll(it) } }
        // The chosen camera state, replayed into the engine at every launch — these are session
        // options on the capture request, so nothing else remembers them. settleChannel afterwards,
        // because a restored mode changes which channels the wheel may hold.
        viewModelScope.launch {
            prefs.flat.collect { engine.setFlat(it) }
        }
        viewModelScope.launch {
            prefs.lensCorrection.collect { engine.setLensCorrection(it) }
        }
        viewModelScope.launch {
            prefs.zoneFocus.collect {
                engine.setZoneFocus(it)
                channelForZone(it)
            }
        }
        viewModelScope.launch {
            prefs.exposureMode.collect {
                engine.setExposureMode(it)
                settleChannel()
            }
        }
        viewModelScope.launch {
            prefs.mode.collect { channelForMode(it) }
        }
        startDarkroom()
        // **The preview's watchdog.** The engine stamps every capture result; this asks, twice a
        // second, whether the stamps stopped while the camera claims to be bound — which is the
        // black viewfinder as a number. Recovery is a rebind, named out loud and marked as a
        // fault, because a camera that silently heals is a camera whose disease nobody reports.
        viewModelScope.launch {
            while (isActive) {
                delay(1_500)
                if (engine.recoverIfDead()) {
                    showNotice("Camera restarted")
                    recordFault("Preview went dark — camera restarted")
                }
            }
        }
        // The activity already offers a report dialog for a crash log on disk; the chip is the
        // quieter half — a black screen that killed the process silently gets a mark on the next
        // viewfinder either way. Announced once per distinct crash: the file survives until a
        // report is sent, and a chip that re-raised it every launch was a permanent !1 nobody
        // could clear.
        val crash = CrashLog.last(app)
        if (crash != null && crash.take(120) != prefs.crashSeen()) {
            recordFault("Crashed last run — shake to report")
        }
        viewModelScope.launch { prefs.scope.collect { locateRoll() } }
        viewModelScope.launch { photos.collect { locateRoll() } }
        // So is the output format. Asking for a negative changes what the `ImageCapture` is, not
        // what the shutter does with it, so it has to be settled before the press rather than at it.
        viewModelScope.launch {
            prefs.formats.collect {
                engine.setNegative(CaptureFormat.Dng in it, prefs.flash.value)
            }
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

    private var refreshWanted = MutableStateFlow(0L)

    fun startObservingMedia() {
        if (observer != null) return
        // Debounced: MediaStore raises one change per row touched, and a burst touches a row per
        // shot plus one per develop rewrite. Re-querying the whole roll for each was a second
        // main-thread tax the viewfinder paid during exactly the moments it could least afford
        // one. The last change wins; the roll is never more than a third of a second stale.
        observer = repo.observe { refreshWanted.value = SystemClock.elapsedRealtime() }
        viewModelScope.launch {
            var seen = 0L
            while (isActive) {
                delay(300)
                val wanted = refreshWanted.value
                if (wanted != seen) {
                    seen = wanted
                    refreshRoll()
                }
            }
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
        // **The dial stays open while the shutter is.** It used to be closed for the 1.8 seconds a
        // Pro capture takes, because the filter was read off the dial when the sensor answered and
        // a notch inside that window baked a look you were not framing into the file. The fix for
        // that belongs at the press, not on the wheel: [shootingWith] pins the look at your finger,
        // so the photograph keeps what it was framed with and the dial is yours again immediately.
        //
        // Which matters more than it sounds. The moment you have just taken a photograph is the
        // moment you are most likely to be setting up the next one, and a wheel that ignores two
        // seconds of turning after every press reads as a wheel that dropped the input rather than
        // as a camera being careful.
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
        //
        // **The two ends of the track stay closed mid-capture**, which is not an oversight left
        // over from the old rule. These two notches change the *mode*, which rebinds the camera and
        // moves the next shot onto a different capture path. A filter the shot in flight is no
        // longer reading is one thing; the camera being rebound underneath it is another.
        if (prefs.mode.value.isSimple) {
            if (by <= 0) return
            if (shotInFlight()) {
                showNotice("Taking the photograph")
                return
            }
            setMode(CaptureMode.Photo)
            prefs.setFilter(Filters.none.id)
            dialHeldUntil = now + Filters.NONE_DWELL_MS
            return
        }
        // Only walks into Simple when Simple is switched on; otherwise None is the end of the track.
        if (by < 0 && filter.value.id == Filters.none.id && prefs.simpleMode.value) {
            if (shotInFlight()) {
                showNotice("Taking the photograph")
                return
            }
            setMode(CaptureMode.Simple)
            dialHeldUntil = now + Filters.NONE_DWELL_MS
            return
        }

        val next = Filters.step(filter.value, by, prefs.dial())
        prefs.setFilter(next.id)
        dialHeldUntil = now + Filters.dwellMs(next)
        // **No name flashed on screen.** The viewfinder is already showing you the filter — a
        // label naming what you can plainly see is a label in the way of it. The buzz says the
        // dial moved; the picture says where to.
    }

    fun setFilter(id: String) {
        // Open during a capture, like the wheel above and for the same reason — the shot in flight
        // is holding its own look in [shootingWith] and cannot be changed out from under itself.
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
        // to its binding on the very next click rather than at the next launch — and so does
        // waking the dial, which is the fix for a click that was claimed all session.
        dialAsleep = prefs.dialLock.value && _dialLocked.value,
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
            PressAction.Channel -> toggleChannelPicking()
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
    fun setMode(next: CaptureMode, keepFilter: Boolean = false) {
        if (engine.recording.value) {
            engine.stopRecording()
            viewModelScope.launch {
                engine.awaitIdle()
                applyMode(next, keepFilter)
            }
            return
        }
        applyMode(next, keepFilter)
    }

    private fun applyMode(next: CaptureMode, keepFilter: Boolean = false) {
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
        val changed = prefs.mode.value != next
        prefs.setMode(next)
        // **A mode starts clean.** The filter is a decision about a photograph, and a mode is a
        // decision about which camera you are holding — so carrying one across the other meant
        // arriving in Video, or back in Pro from QR, still wearing a look chosen for a different
        // shot several minutes ago. Worse, the modes with no filter track hide the dial without
        // clearing it, so the filter went invisible rather than off and the only way to find it
        // was to go back and walk the dial to None by hand. Reported twice, from both ends: as a
        // filter that "remains enabled after changing camera modes" and as a request for a mode
        // switch to "begin with a blank slate".
        //
        // The lens flip is the exception, and it is the reason this is a parameter rather than
        // two lines in `setMode`. Photo and Selfie are one mode wearing two lenses — the codebase
        // says so directly in [flipLens] — and turning the camera around mid-shoot to get the
        // other side of the same thing is not a new decision about the photograph.
        //
        // The grade is deliberately untouched. It is persisted on purpose: which adjustments you
        // shoot with is a property of your camera, not of this frame, and clearing it here would
        // throw away a setting on a gesture nobody thinks of as destructive.
        if (changed && !keepFilter) prefs.setFilter(Filters.none.id)
        showNotice(next.bandLabel)
    }

    /** The lens switch, which in Photo and Selfie is the same thing as switching mode. */
    fun flipLens() {
        when (prefs.mode.value) {
            // Turning the camera around keeps the filter. See [applyMode].
            CaptureMode.Simple, CaptureMode.Photo -> setMode(CaptureMode.Selfie, keepFilter = true)
            CaptureMode.Selfie -> setMode(CaptureMode.Photo, keepFilter = true)
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
        shootingWith = filter.value
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
                    lookForShot().lowRes ||
                    lookForShot().facesAware
                ) {
                    if (!shootPanelFrame(click = true)) showNotice("Nothing on the viewfinder yet")
                    return@launch
                }

                // **The click goes here, at the press.** It used to fire when the capture *returned*, a second
                // and a half after your finger — which is the wrong end of the event. A shutter sound is
                // feedback for the press; the file landing has its own sound now.
                _shutterTick.tryEmit(Unit)

                // **The viewfinder is no longer frozen while a photograph is taken.**
                //
                // It used to hold the panel frame from the moment of the press, on the reasoning
                // that it is roughly what the photograph will look like. Roughly was the problem.
                // The grab happens *before* the capture completes, so the held frame is always a
                // little ahead of the one the sensor returns — and with Reach back on it is
                // provably a different moment, because that feature exists to save a frame from
                // before the press. A still picture of the wrong instant, sat over the viewfinder
                // for a second and a half, reads as the photograph you got. It is not.
                //
                // The progress bar was already tied to `shooting` rather than to the held frame,
                // for the flash case where nothing could honestly be frozen. So it covers this on
                // its own: the viewfinder stays live, the bar says the camera is working, and
                // nothing on screen claims to be a photograph that has not been taken yet.

                // **The negative takes a different route out of here entirely.** Every other path
                // in this app captures into memory and decides afterwards; a DNG cannot, because
                // there is no bitmap behind it and the file can only be built by the thing holding
                // the capture metadata. So when a negative is wanted the destinations are made
                // first and CameraX writes both files itself. See [shootNegative].
                if (prefs.wantsNegative() && engine.negativeSupported.value) {
                    shootNegative()
                    return@launch
                }

                // **The press ends here, and that is the entire speed fix.** The old shape
                // awaited the sensor before unlatching, which billed the whole capture round trip
                // to the finger — even zero shutter lag was being *waited for*, which misses its
                // point. A press now snapshots its settings, launches the capture, and returns;
                // `_shooting` clears in the finally below within a frame or two of the press.
                //
                // CameraX explicitly permits overlapping `takePicture` calls — it queues them —
                // and [captureGate] bounds how many this app lets pile up at the sensor. Presses
                // past the permits suspend in their own coroutines, oldest first: a held shutter
                // becomes a burst, which is what a held shutter has always meant on a real body.
                // The darkroom's depth then bounds the develops behind that, so the pipeline as a
                // whole cannot outgrow memory: two frames at the sensor, six in the queue, and the
                // shutter honestly pauses only when both are full.
                //
                // The external-capture path (another app asked for a photo) keeps the old awaited
                // shape further below: it is a conversation, not a burst.
                if (captureRequestOutput == null && prefs.flash.value == FlashMode.Off) {
                    val activeFilter = lookForShot()
                    val aspect = prefs.aspect.value
                    val seed = Random.nextFloat() * 1000f
                    val stampAt = stampTime(activeFilter)
                    val stampStyle = prefs.stampStyle.value
                    val wantPng = prefs.wantsLossless()
                    val pressedAt = System.currentTimeMillis()
                    // **The moment is seized at the press, always.** The old shape queued the
                    // *press* behind the sensor permit, so a queued press exposed whenever its
                    // turn came — a photograph of the wrong moment, seconds late in a burst,
                    // which is the one thing a camera must never do. Now: sensor free, the
                    // sensor takes it; sensor busy, the live viewfinder frame is grabbed within
                    // milliseconds of the finger and queued for developing. A burst's tail
                    // trades resolution for the moment, which is the trade every body's buffer
                    // makes — the moment is the photograph, the pixels are only how many.
                    if (!captureGate.tryAcquire()) {
                        viewModelScope.launch {
                            if (!shootPanelFrame(click = false)) {
                                showNotice("Nothing on the viewfinder yet")
                            }
                        }
                        return@launch
                    }
                    viewModelScope.launch {
                        try {
                            _inFlight.value += 1
                            try {
                                val startedAt = System.nanoTime()
                                val frame = withTimeout(CAPTURE_DEADLINE_MS) { engine.capture() }
                                val took = (System.nanoTime() - startedAt) / 1_000_000
                                _stillMs.value = (_stillMs.value * 3 + took) / 4
                                Log.i(TAG, "pro: shot ${took}ms")
                                if (prefs.timings.value) showNotice("${took}ms shot")
                                handleQuickFrame(
                                    frame, activeFilter, aspect, seed, stampAt,
                                    stampStyle, wantPng, pressedAt,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                // **A failed zero-lag capture earns one quiet retry.** The engine
                                // answers that failure by rebinding without the ring, so the
                                // second attempt goes to a camera that can actually take it —
                                // and a burst that drained the ring costs nothing visible. Only
                                // when the retry fails too does this become a fault.
                                if (engine.consumeZslRetry()) {
                                    val retried = runCatching {
                                        // The recovery rebind was posted to the main queue by the
                                        // engine; wait for the fresh bind rather than racing it.
                                        withTimeoutOrNull(2_000) { engine.ready.first { it } }
                                        withTimeout(CAPTURE_DEADLINE_MS) { engine.capture() }
                                    }.getOrNull()
                                    if (retried != null) {
                                        handleQuickFrame(
                                            retried, activeFilter, aspect, seed, stampAt,
                                            stampStyle, wantPng, pressedAt,
                                        )
                                        return@launch
                                    }
                                }
                                // The panel frame rather than no photograph — the same rescue as
                                // ever, now per shot instead of per shutter, so one refused frame
                                // in a burst costs that frame alone.
                                Log.e(TAG, "capture failed", failure)
                                val rescued = shootPanelFrame(click = false)
                                val why = failure.message?.take(48)
                                val said = when {
                                    rescued -> "Sensor didn't answer — saved the viewfinder frame"
                                    why.isNullOrBlank() -> "Shutter failed"
                                    else -> "Shutter: $why"
                                }
                                showNotice(said)
                                recordFault(said)
                            } finally {
                                _inFlight.value -= 1
                            }
                        } finally {
                            captureGate.release()
                        }
                    }
                    return@launch
                }

                // Flash, and captures another app asked for: the deliberate, awaited path. A flash
                // exposure is a conversation with the scene and an external capture is one with
                // another app; neither is a burst, and both want the shutter to mean "done".
                val startedAt = System.nanoTime()
                val attempt = runCatching { withTimeout(CAPTURE_DEADLINE_MS) { engine.capture() } }
                    .onFailure { Log.e(TAG, "capture failed", it) }
                val took = (System.nanoTime() - startedAt) / 1_000_000
                _stillMs.value = (_stillMs.value * 3 + took) / 4
                if (prefs.timings.value) showNotice("${took}ms shot")
                val frame = attempt.getOrNull()
                if (frame == null) {
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
                val processed = withContext(Dispatchers.Default) {
                    Frames.process(
                        frame,
                        lookForShot(),
                        prefs.aspect.value,
                        Random.nextFloat() * 1000f,
                        stampTime(lookForShot()),
                        prefs.stampStyle.value,
                        wantPng = prefs.wantsLossless(),
                    )
                }
                finish(processed, lookForShot().id)
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
                shootingWith = null
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
        val activeFilter = lookForShot()
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
        // Into the darkroom with everything it needs, and the press is over. The rotate, the
        // shader, both encodes and the save all happen behind the viewfinder; what a panel shot
        // charges the finger is the readback above and nothing else.
        enqueuePanel(
            PanelJob(
                bitmap = grabbed,
                turn = turn,
                filter = activeFilter,
                aspect = aspect,
                seed = seed,
                stampAt = if (puri != null) null else stampAt,
                stampStyle = prefs.stampStyle.value,
                faces = faces,
                overlay = puri,
                tune = prefs.puriTune(),
                wantPng = prefs.wantsLossless(),
                takenAt = System.currentTimeMillis(),
            ),
        )
        // A fresh arrangement for the next one, so two shots in a row are not the same
        // print with a different face in it. At the press, not the develop: the next shot can be
        // taken before this one is finished, and it must not inherit the same arrangement.
        if (puri != null) reshufflePuri()
        return true
    }

    /**
     * One press that writes a negative.
     *
     * **The order matters and it is not the obvious one.** The rows are described *before* the
     * capture, because CameraX needs somewhere to put two files and because both of them have to
     * carry the same stem — that shared name is the only thing in the system recording that they
     * are one photograph. Asking the clock twice, once per file, would put them in different
     * groups whenever a millisecond fell between the two inserts.
     *
     * **The JPEG is developed afterwards rather than instead.** With a filter on, or a lossless
     * copy wanted, the JPEG CameraX just wrote is read back, put through the shader and written
     * over in place. That costs a decode that the ordinary Pro path does not — but the alternative
     * is two exposures a moment apart, and a negative that does not match the print it came with
     * is not a negative, it is a different photograph.
     *
     * A failure anywhere past the capture leaves the files that did land. A DNG on disk with no
     * filter applied to its JPEG is a worse photograph than intended; no photograph is worse still.
     */
    private suspend fun shootNegative() {
        val takenAt = System.currentTimeMillis()
        val stem = repo.stemFor(takenAt)
        val resolver = getApplication<Application>().contentResolver
        val collection = repo.imagesCollection()

        val rawOptions = ImageCapture.OutputFileOptions.Builder(
            resolver,
            collection,
            repo.valuesFor(takenAt, CaptureFormat.Dng, stem),
        ).build()
        val jpegOptions = ImageCapture.OutputFileOptions.Builder(
            resolver,
            collection,
            repo.valuesFor(takenAt, CaptureFormat.Jpeg, stem),
        ).build()

        val startedAt = System.nanoTime()
        // The same deadline as every other capture, and for the same reason: two callbacks that
        // never arrive would leave `_shooting` latched and every press after it silently dropped.
        val attempt = runCatching {
            withTimeout(CAPTURE_DEADLINE_MS) { engine.captureNegative(rawOptions, jpegOptions) }
        }.onFailure { Log.e(TAG, "negative capture failed", it) }
        val took = (System.nanoTime() - startedAt) / 1_000_000
        _shutterTick.tryEmit(Unit)

        val pair = attempt.getOrNull()
        if (pair == null) {
            val why = attempt.exceptionOrNull()?.message?.take(48)
            showNotice(if (why.isNullOrBlank()) "Shutter failed" else "Shutter: $why")
            return
        }
        if (prefs.timings.value) showNotice("${took}ms shot · RAW")

        val jpegUri = pair.jpeg
        if (pair.raw == null) showNotice("No negative — the JPEG saved")
        if (jpegUri == null) {
            // The negative alone is still a photograph, and the roll opens it.
            if (pair.raw != null && prefs.sounds.value) beeps.saved()
            return
        }
        // This path never tagged at all — found in review, not on a phone: every RAW capture was
        // absent from the map and nothing said so. Stamped here only when nothing below is going
        // to rewrite the file, and again after the rewrite when something is; a stamp before a
        // rewrite is replaced along with the bytes it was written into.
        val willRewrite = lookForShot().agsl != null ||
            prefs.aspect.value != FrameAspect.Full ||
            stampTime(lookForShot()) != null ||
            prefs.wantsLossless()
        if (!willRewrite) stampLocation(jpegUri)

        val activeFilter = lookForShot()
        val wantPng = prefs.wantsLossless()
        val aspect = prefs.aspect.value
        val stampAt = stampTime(activeFilter)
        val needsDeveloping = activeFilter.agsl != null ||
            aspect != FrameAspect.Full ||
            stampAt != null ||
            wantPng
        if (!needsDeveloping) {
            if (prefs.sounds.value) beeps.saved()
            return
        }

        val original = withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(jpegUri)?.use { it.readBytes() } }.getOrNull()
        }
        if (original == null) {
            showNotice("Saved, but couldn't develop it")
            // Undeveloped, but saved — so it still gets its coordinate. Nothing below runs.
            stampLocation(jpegUri)
            return
        }

        val processed = withContext(Dispatchers.Default) {
            Frames.process(
                // Rotation is left to the EXIF the camera wrote, which is why this is zero rather
                // than the panel's idea of which way up the phone is: CameraX has already applied
                // `targetRotation` to the file, and turning it a second time is how a photograph
                // ends up on its side.
                CapturedFrame(jpeg = original, rotationDegrees = 0, mirrored = false),
                activeFilter,
                aspect,
                Random.nextFloat() * 1000f,
                stampAt,
                prefs.stampStyle.value,
                wantPng = wantPng,
            )
        }

        val rewritten = repo.rewrite(jpegUri, processed.jpeg)
        if (!rewritten) showNotice("Couldn't develop the JPEG")
        // After the rewrite, which replaces the whole file: a coordinate written before it would
        // have gone with the bytes it lived in.
        stampLocation(jpegUri)

        if (wantPng) {
            val png = processed.png
            if (png == null) {
                showNotice("Lossless copy failed")
            } else if (
                repo.save(
                    jpeg = png,
                    takenAt = takenAt,
                    width = processed.width,
                    height = processed.height,
                    stem = stem,
                    format = CaptureFormat.Png,
                ) == null
            ) {
                showNotice("Couldn't save the lossless copy")
            }
        }
        if (prefs.sounds.value) beeps.saved()
    }

    /**
     * Keep the ring filling while it is wanted and the viewfinder is up.
     *
     * Stopped and emptied the moment it is switched off, because a ring nobody is going to read is
     * a hundred megabytes and a readback thirty times a second for nothing.
     */
    private fun updatePreRoll(millis: Int) {
        preRollJob?.cancel()
        preRollJob = null
        preRollRing.clear()
        if (millis <= 0) return
        preRollJob = viewModelScope.launch {
            while (isActive) {
                // **The camera being down empties the ring and slows the loop.** Two reasons, one
                // each. Emptied: frames from before a pause are stale, and "nearest the requested
                // moment" across a gap picks the newest stale frame — a photograph of some earlier
                // scene, saved as though it were now. Slowed: this loop used to spin at 30Hz for
                // as long as the process lived, panel or no panel, which is a battery cost for a
                // viewfinder that is not on screen.
                if (!engine.ready.value) {
                    preRollRing.clear()
                    delay(PRE_ROLL_IDLE_MS)
                    continue
                }
                val frame = engine.previewFrame()
                if (frame == null) {
                    delay(PRE_ROLL_IDLE_MS)
                    continue
                }
                preRollRing.add(frame, SystemClock.elapsedRealtime())
                delay(PRE_ROLL_GAP_MS)
            }
        }
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
        // **The ring first, because it costs nothing at the press.** If frames have been arriving
        // all along there is no reason to go and fetch more: reach back to the moment asked for,
        // or take the sharpest of what is held. This is the version of "sharpest of eight" that
        // does not spend a quarter of a second collecting the eight.
        val preRoll = prefs.preRollMs.value
        if (preRoll > 0 && preRollRing.size > 0) {
            // Both forms remove what they return and release the rest. Not nearest() then
            // clear(): clear() evicts everything it holds, including a frame just handed out,
            // and a recycled bitmap given to the caller is a crash on the next draw.
            val picked = if (prefs.burst.value) {
                preRollRing.takeBest { frame -> sharpnessOf(frame) }
            } else {
                preRollRing.takeNearest(SystemClock.elapsedRealtime(), preRoll.toLong())
            }
            if (picked != null) return picked
        }
        if (!prefs.burst.value) return engine.previewFrame()
        var best: Bitmap? = null
        var bestScore = -1f
        repeat(BURST_FRAMES) { index ->
            // No wait before the first: if the burst is going to be abandoned for any reason, the
            // frame it starts from should still be the one that was on the panel at the press.
            if (index > 0) delay(BURST_GAP_MS)
            val frame = engine.previewFrame() ?: return@repeat
            val score = withContext(Dispatchers.Default) { sharpnessOf(frame) }
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
     * How sharp a frame is, on the scale [Sharpness] defines.
     *
     * Comparable within one press and meaningless across two, which is exactly what picking the
     * best of a ring needs. Scored small: the difference between a sharp frame and a smeared one
     * survives a downscale, and scoring at panel size would cost more than the photograph.
     */
    private fun sharpnessOf(frame: Bitmap): Float = runCatching {
        val small = Bitmap.createScaledBitmap(frame, SCORE_W, SCORE_H, true)
        val pixels = IntArray(SCORE_W * SCORE_H)
        small.getPixels(pixels, 0, SCORE_W, 0, 0, SCORE_W, SCORE_H)
        if (small != frame) small.recycle()
        Sharpness.of(pixels, SCORE_W, SCORE_H)
    }.getOrDefault(-1f)

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
        val said = if (why.isNullOrBlank()) "Shutter failed" else "Shutter: $why"
        showNotice(said)
        // The notice fades; the chip stays until read. A burst that dropped one frame in the
        // middle is otherwise a photograph you only miss at home.
        recordFault(said)
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
        shootingWith = Filters.none
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
                // Through [grabBestFrame], not straight off the panel: the settings text has
                // always said the burst applies to "Simple and every coarse filter", and Reach
                // back's description makes the same promise — but this path read the panel
                // directly, so neither setting did anything in the mode most people shoot in.
                // With both off, grabBestFrame *is* a straight panel read.
                val grabbed = grabBestFrame()
                if (grabbed == null) {
                    showNotice("Nothing on the viewfinder yet")
                    return@launch
                }
                val grabMs = (System.nanoTime() - startedAt) / 1_000_000
                _shutterTick.tryEmit(Unit)
                if (prefs.timings.value) showNotice("${grabMs}ms grab")
                Log.i(TAG, "simple: grab ${grabMs}ms")

                // **Into the darkroom, like everything else now.** The encode used to run inline
                // — cheap, but not free, and a burst pays every cost it is charged. What Simple
                // charges the finger is the panel readback above; the encode, the save, the date
                // back and the coordinate all happen behind the viewfinder, in press order,
                // through the same worker as every other photograph. The date back rides
                // `fromPreview`'s own stamp pass rather than the old rewrite-after-save, which is
                // one encode instead of two and the same pixels either way.
                enqueuePanel(
                    PanelJob(
                        bitmap = grabbed,
                        turn = engine.previewRotationDegrees(),
                        filter = Filters.none,
                        aspect = FrameAspect.Full,
                        seed = 0f,
                        stampAt = if (prefs.stampPlain.value) System.currentTimeMillis() else null,
                        stampStyle = prefs.stampStyle.value,
                        faces = emptyList(),
                        overlay = null,
                        tune = FaceTune(),
                        // Simple never writes the lossless copy: it ships the panel's own frame,
                        // and that policy is stated in the settings.
                        wantPng = false,
                        takenAt = System.currentTimeMillis(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                reportShutterFailure(failure)
            } finally {
                _shooting.value = false
                shootingWith = null
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
        shootingWith = filter.value
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
                    val activeFilter = lookForShot()
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
                shootingWith = null
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
    private suspend fun finish(
        processed: Frames.Processed,
        filterId: String,
        /**
         * When the button was pressed, defaulted to now for the callers that develop inline.
         * A queued photograph must carry the press's clock, not the darkroom's: the develop can
         * run seconds later, and DATE_TAKEN is the moment the photograph is *of*.
         */
        pressedAt: Long = System.currentTimeMillis(),
    ) {
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

        val takenAt = pressedAt
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

        // **One press, one stem, however many files.** The name is made once and handed to every
        // save, because it is the only thing tying them together — MediaStore has no field for
        // "these rows are one photograph", so two saves that each asked the clock for a stem could
        // land a millisecond apart and be read back as two photographs with one file each.
        val stem = repo.stemFor(takenAt)
        val wanted = prefs.formats.value

        val uri = repo.save(
            jpeg = processed.jpeg,
            takenAt = takenAt,
            width = processed.width,
            height = processed.height,
            stem = stem,
        )

        // The lossless copy, when there is one. **Its failure is not the photograph's failure**:
        // the JPEG above is already on disk and already a photograph, so a PNG that could not be
        // encoded or could not be written is a line on the viewfinder, not a lost shot.
        if (CaptureFormat.Png in wanted) {
            val png = processed.png
            if (png == null) {
                showNotice("Lossless copy failed")
            } else {
                val pngUri = repo.save(
                    jpeg = png,
                    takenAt = takenAt,
                    width = processed.width,
                    height = processed.height,
                    stem = stem,
                    format = CaptureFormat.Png,
                )
                if (pngUri == null) showNotice("Couldn't save the lossless copy")
            }
        }

        if (uri == null) {
            showNotice("Couldn't save")
        } else {
            stampLocation(uri)
            if (prefs.sounds.value) {
                // The other end of the bracket: click at the press, this when the file exists.
                beeps.saved()
            }
        }
    }

    /**
     * Put where you were onto a photograph that has just been written.
     *
     * **The last known fix, never a fresh one.** Asking for a live update at the shutter costs
     * seconds and a radio on a press whose whole argument is that it happens now; where the phone
     * has no recent position the photograph simply has none, which is a better outcome than a slow
     * camera.
     *
     * Not applied to the lossless copy: PNG has no dependable place to keep this, and it is always
     * a sibling of a JPEG that does — the map reads a capture, not a file.
     */
    private fun stampLocation(uri: Uri) {
        if (!prefs.tagLocation.value) return
        // **Launched, not awaited.** `saveAttributes` does not poke a tag into a header: to insert
        // an EXIF segment it rewrites the entire JPEG through a temporary copy, several megabytes
        // read and several written on a 12-megapixel file. v2.65 awaited that inline on every save,
        // with location on by default, and turned a shutter people called quick into one that
        // visibly waited. The photograph is on disk before this starts and nothing is waiting for
        // it, so nothing should — the same reasoning already written down in [shootSimple] for the
        // date back.
        //
        // The cost is a window of a few hundred milliseconds where the file exists without its
        // coordinate. A photograph sent inside that window goes unlocated, which is a far better
        // outcome than a camera that pauses after every press.
        //
        // **Call it last.** Anything that rewrites the file afterwards — a date back, a developed
        // JPEG — replaces the bytes wholesale and takes the coordinate with them.
        //
        // Its own coroutine with its own catch, because it outlives the shutter that started it: a
        // throw here would otherwise reach `viewModelScope` unhandled and take the process down
        // long after the press it belonged to was over.
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>()
                val where = Locations.lastKnown(context) ?: return@launch
                Locations.stamp(context.contentResolver, uri, where)
            }.onFailure { Log.e(TAG, "could not tag a location", it) }
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

    /**
     * Trash a photograph — all of it.
     *
     * **The roll shows captures; the trash must act on captures.** One press can have written a
     * JPEG, a PNG and a negative, and the grid collapses them to one tile. Trashing only the tile's
     * photo deletes the JPEG, at which point the PNG becomes the group's best remaining file and
     * the "deleted" photograph reappears on the roll — same picture, new file, a haunting. So every
     * member of the group goes into the one system dialog, which also means the count the dialog
     * shows is the count of files, which is honest.
     */
    fun trashRequest(photo: Photo) = trashRequest(listOf(photo))

    fun trashRequest(photos: List<Photo>): android.content.IntentSender? {
        val all = groups.value
        val uris = photos
            .flatMap { photo ->
                all.firstOrNull { group -> group.members.any { it.photo.id == photo.id } }
                    ?.members?.map { it.photo }
                    ?: listOf(photo)
            }
            .distinctBy { it.id }
            .map { it.uri }
        return repo.trashRequest(uris)
    }

    /* ---------------- notices ---------------- */

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

        /**
         * How often the ring takes a frame off the panel.
         *
         * The same interval as the burst, so the reach the setting offers is honest: eight frames
         * at 34ms is about 270ms of buffer, which is why the longest pre-roll on offer is 250.
         * Faster would fill the ring with near-duplicates and read the panel for nothing.
         */
        const val PRE_ROLL_GAP_MS = 34L

        /** The fill loop's pace while the camera is down. Checking, not capturing. */
        const val PRE_ROLL_IDLE_MS = 500L

        /**
         * How many undeveloped captures the darkroom holds before the shutter waits.
         *
         * Memory, not preference: each job carries a full-resolution JPEG, ~5MB at 12MP, and the
         * worker's decode adds a ~48MB bitmap on top of whichever job is current. Six queued is
         * the burst a thumb actually produces; past it, a pause at the shutter is honest.
         */
        const val DARKROOM_DEPTH = 6

        /**
         * Outstanding sensor captures: one.
         *
         * Two overlapped captures was the other suspect in the three-shot fault — a ZSL ring on
         * this HAL is a handful of buffers, and two outstanding acquisitions against it while the
         * preview holds its own is how a HAL wedges with no Java stack to show for it. One at a
         * time still re-arms in milliseconds, because the press stopped waiting for the sensor;
         * the queue of presses behind the permit is what a burst is.
         */
        const val MAX_IN_FLIGHT = 1

        /** Panel frames queued at full resolution before the ladder starts trading pixels. */
        const val PANEL_FULL_DEPTH = 3

        /** The hard ceiling. Half-resolution frames are ~2.5MB; a dozen is a human-proof burst. */
        const val PANEL_MAX_DEPTH = 12

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
