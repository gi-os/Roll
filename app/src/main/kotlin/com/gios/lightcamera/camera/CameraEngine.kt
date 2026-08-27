package com.gios.lightcamera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.Face
import android.hardware.camera2.params.TonemapCurve
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.gios.lightcamera.CaptureMode
import com.gios.lightcamera.PhotoSize
import com.gios.lightcamera.qr.QrAnalyzer
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

enum class AfState { Idle, Scanning, Locked, Failed }

enum class FlashMode { Off, On, Auto }

/** Single locks on the half press and stays put; Continuous keeps the subject sharp. */
enum class AfMode { Single, Continuous }

/** A photo, straight off the sensor. */
class CapturedFrame(val jpeg: ByteArray, val rotationDegrees: Int, val mirrored: Boolean)

/**
 * The camera itself: preview, autofocus, hardware face detection, zoom, exposure, capture.
 *
 * Two things here are worth more than the rest of the file.
 *
 * **Face detection is the camera's, not a library's.** Every camera HAL on Android can
 * detect faces in hardware, publish them in each capture result, and — with
 * `CONTROL_SCENE_MODE_FACE_PRIORITY` — meter and focus on them. Reaching that costs one
 * [Camera2Interop] extender and a capture callback. The usual answer, ML Kit, would add
 * several megabytes to the APK, run a second detector on the CPU over frames the HAL has
 * already analysed, and still not tell the lens where to focus.
 *
 * **The AF state is read, not assumed.** `CONTROL_AF_STATE` in the capture result is what
 * the lens is actually doing, so the focus bracket on screen snaps when the lens snaps
 * rather than when a future completes. That is the difference between a viewfinder you
 * trust and one you second-guess.
 */
// androidx.annotation.OptIn, not Kotlin's: ExperimentalCamera2Interop is a Java-declared
// marker carrying @RequiresOptIn from the annotation-experimental library, which Kotlin's own
// @OptIn does not recognise — it compiles and warns that it has no effect.
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraEngine(private val context: Context) {

    private val _faces = MutableStateFlow<List<FaceBox>>(emptyList())
    val faces: StateFlow<List<FaceBox>> = _faces.asStateFlow()

    private val _afState = MutableStateFlow(AfState.Idle)
    val afState: StateFlow<AfState> = _afState.asStateFlow()

    /** Where the last focus request was aimed, in view pixels, for drawing the bracket. */
    private val _focusPoint = MutableStateFlow<Pair<Float, Float>?>(null)
    val focusPoint: StateFlow<Pair<Float, Float>?> = _focusPoint.asStateFlow()

    /**
     * Fires once per focus run that was actually asked for — true locked, false gave up.
     *
     * Separate from [afState] because that also carries the camera's own passive hunting,
     * which happens continuously and must not beep. Only a half press or a tap opens the
     * window this emits from.
     */
    private val _focusOutcome = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val focusOutcome: SharedFlow<Boolean> = _focusOutcome.asSharedFlow()

    private val _zoom = MutableStateFlow(1f)
    val zoom: StateFlow<Float> = _zoom.asStateFlow()

    private val _maxZoom = MutableStateFlow(1f)
    val maxZoom: StateFlow<Float> = _maxZoom.asStateFlow()

    /** Exposure compensation, in the camera's own index steps. */
    private val _ev = MutableStateFlow(0)
    val ev: StateFlow<Int> = _ev.asStateFlow()

    private val _evRange = MutableStateFlow(0..0)
    val evRange: StateFlow<IntRange> = _evRange.asStateFlow()

    /** EV index to stops, for the readout. Usually a third of a stop per step. */
    private val _evStep = MutableStateFlow(1f / 3f)
    val evStep: StateFlow<Float> = _evStep.asStateFlow()

    private val _torch = MutableStateFlow(false)
    val torch: StateFlow<Boolean> = _torch.asStateFlow()

    private val _facesSupported = MutableStateFlow(false)
    val facesSupported: StateFlow<Boolean> = _facesSupported.asStateFlow()

    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing: StateFlow<Int> = _lensFacing.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var previewView: PreviewView? = null
    private var owner: LifecycleOwner? = null

    /**
     * Which mode the camera is in, and therefore which use cases are bound.
     *
     * `ImageCapture` and `VideoCapture` are bound one at a time, never together. The
     * preview+capture+video triple is only guaranteed on `LEVEL_3` hardware, so binding all
     * three risks a resolution the phone will refuse — and there is nothing to gain: a shutter
     * that also records is two shutters.
     */
    var mode: CaptureMode = CaptureMode.Photo
        private set

    /** Set before binding; changing it rebinds, because it is a use-case configuration. */
    var photoSize: PhotoSize = PhotoSize.Large
        private set

    /**
     * Whether this bind should be able to write a negative.
     *
     * Read at [rebind] rather than at the shutter, because the output format is a property of the
     * `ImageCapture` use case and changing it means rebinding the camera — which is not something
     * to do inside a capture. [setNegative] is the way to change it.
     */
    var negative: Boolean = false
        private set

    private val captureExecutor = Executors.newSingleThreadExecutor()

    /**
     * How the phone is being held, which is not how the interface is drawn.
     *
     * The activity is locked to portrait on purpose — the LPIII's camera does not reflow, and a
     * viewfinder that spins while you turn the phone to frame something is a viewfinder
     * fighting you. But the *photograph* has to come out the way you held the camera, and with
     * the UI locked, CameraX would otherwise bake ROTATION_0 into every file and a horizontal
     * shot would arrive on its side.
     *
     * So the layout stays put and only [ImageCapture.setTargetRotation] follows the
     * accelerometer. The preview's target rotation is deliberately left alone: the face mapper
     * reads it to place its boxes, and the preview genuinely is upright in the window.
     */
    private val orientation = object : OrientationEventListener(context) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            val rotation = when (degrees) {
                in 45..134 -> Surface.ROTATION_270
                in 135..224 -> Surface.ROTATION_180
                in 225..314 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            if (rotation == lastRotation) return
            lastRotation = rotation
            _previewRotation.value = previewRotationDegrees()
            imageCapture?.targetRotation = rotation
            // Not while recording: the rotation is written into the file's metadata when the
            // recording starts, and changing it mid-take is ignored at best.
            if (!_recording.value) videoCapture?.targetRotation = rotation
        }
    }

    @Volatile private var lastRotation = Surface.ROTATION_0

    @Volatile private var imageAnalysis: ImageAnalysis? = null

    /**
     * Where a decoded QR payload goes, set by the view model.
     *
     * Called on [captureExecutor], not the main thread — everything on the other end of it has to
     * be safe to touch from a camera callback. It is a plain volatile field rather than a flow
     * because the analyser is created inside `rebind` and would otherwise need one built per bind.
     */
    @Volatile var onCode: ((String) -> Unit)? = null

    /**
     * The newest frame off the live stream, already in NV21, waiting to be asked for.
     *
     * Replaced thirty times a second and read once per shutter press. `@Volatile` rather than a lock: a
     * torn read is impossible because the reference is swapped whole, and the worst case is taking the
     * frame from a thirtieth of a second ago.
     */
    @Volatile private var latestFrame: LiveFrame? = null

    /** Counts complaints so a broken stream logs three lines rather than thirty a second. */
    @Volatile private var liveComplaints = 0

    /** Cleared for the rest of the process the first time a zero-shutter-lag capture fails. */
    @Volatile private var zslAllowed = true

    @Volatile private var zslActive = false

    @Volatile private var boundAt = 0L

    /**
     * Whether the frame buffer behind zero shutter lag has had time to fill.
     *
     * The whole failure mode from v1.8: ZSL hands back a frame it captured *before* the press, and for the
     * first second after binding there aren't any.
     */
    private fun zslWarm(): Boolean =
        zslActive && System.currentTimeMillis() - boundAt > ZSL_WARM_MS

    /**
     * [previewRotationDegrees] as state, so the viewfinder can follow it.
     *
     * The number itself has been here all along for the shutter's benefit; the overlay needs it too,
     * because it has to be drawn the way up the *photograph* will be rather than the way up the panel
     * is, or the preview stops matching the file the moment you turn the phone on its side.
     */
    private val _previewRotation = MutableStateFlow(0)
    val previewRotation: StateFlow<Int> = _previewRotation.asStateFlow()

    /** Read from the camera callback thread, written from the UI. */
    @Volatile private var viewWidth = 0

    @Volatile private var viewHeight = 0

    @Volatile private var sensorOrientation = 90

    @Volatile private var activeArray: Rect? = null

    @Volatile private var faceDetectMode = CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF

    @Volatile private var lastFacePublish = 0L

    /** True while a half press is holding focus, so continuous AF leaves the lens alone. */
    @Volatile private var focusHeld = false

    /** True between a focus request and the result that settles it. Gates the beep. */
    @Volatile private var awaitingFocus = false

    var afMode: AfMode = AfMode.Single
    var facePriority: Boolean = true

    /* ---------------- binding ---------------- */

    fun onViewSized(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    fun bind(owner: LifecycleOwner, view: PreviewView, flash: FlashMode) {
        this.owner = owner
        this.previewView = view
        orientation.enable()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            this.provider = provider
            rebind(flash)
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Let the camera go, without forgetting how to come back.
     *
     * **The sensor is the most expensive thing this app can leave running**, and a camera is bound the
     * whole time the roll or the viewer is on screen — where there is no viewfinder to feed. Unbinding
     * releases the sensor, the ISP and the preview stream; the provider and the view are kept, so
     * [resume] is a rebind rather than a cold start, which is the difference between a viewfinder that is
     * there when you swipe back and one that fades in.
     *
     * The orientation listener goes too: it is a sensor callback that only exists to keep the capture
     * rotation right, and nothing is being captured.
     */
    fun release() {
        if (_recording.value) return
        runCatching { orientation.disable() }
        runCatching { provider?.unbindAll() }
        // The analyser holds a reference to the view model through its callback and would otherwise
        // keep decoding frames from a stream nobody is watching.
        runCatching { imageAnalysis?.clearAnalyzer() }
        _faces.value = emptyList()
    }

    /** Bind again after [release], using the provider and view already in hand. */
    fun resume(flash: FlashMode) {
        val view = previewView ?: return
        if (owner == null) return
        orientation.enable()
        if (provider == null) {
            // Never bound in the first place — go the long way round.
            owner?.let { bind(it, view, flash) }
            return
        }
        rebind(flash)
    }

    fun setLens(facing: Int, flash: FlashMode) {
        if (_lensFacing.value == facing) return
        if (_recording.value) return
        _lensFacing.value = facing
        _faces.value = emptyList()
        _zoom.value = 1f
        rebind(flash)
    }

    /**
     * Switch mode, which rebinds. Selfie is the front lens and nothing else — that is what it
     * is on the stock camera too.
     */
    fun setMode(next: CaptureMode, flash: FlashMode): Boolean {
        // **Reports whether it took, and that is the crash.** This used to return silently while a
        // recording was live, but the view model committed `prefs.setMode` *before* calling it — so
        // the app would be drawing Pro, with the filter dial live and the shutter wired to
        // `takePicture`, while the camera was still bound to `VideoCapture` and the `ImageCapture`
        // sitting in this class was attached to nothing. The next shutter press threw. Refusing out
        // loud lets the caller keep the two in step.
        if (_recording.value) return false
        val lens = when (next) {
            CaptureMode.Selfie -> CameraSelector.LENS_FACING_FRONT
            // QR and Text are the back lens and cannot be talked out of it: the front camera on
            // this phone is fixed focus and lower resolution, and neither a code nor a page held
            // up to it is one you can read.
            CaptureMode.Simple, CaptureMode.Photo, CaptureMode.Scan, CaptureMode.Text ->
                CameraSelector.LENS_FACING_BACK
            // Video keeps whichever lens you were using; it is a mode, not a camera.
            CaptureMode.Video -> _lensFacing.value
        }
        val lensChanged = lens != _lensFacing.value
        val modeChanged = next != mode
        if (!lensChanged && !modeChanged) return true
        mode = next
        _lensFacing.value = lens
        _faces.value = emptyList()
        _zoom.value = 1f
        rebind(flash)
        return true
    }

    fun setPhotoSize(size: PhotoSize, flash: FlashMode) {
        if (size == photoSize) return
        if (_recording.value) return
        photoSize = size
        rebind(flash)
    }

    /**
     * Ask for, or stop asking for, a RAW file alongside the JPEG.
     *
     * Rebinds, because the output format belongs to the use case. Guarded on equality so that a
     * settings screen re-emitting the same value does not drop the viewfinder.
     */
    fun setNegative(wanted: Boolean, flash: FlashMode) {
        if (wanted == negative) return
        if (_recording.value) return
        negative = wanted
        rebind(flash)
    }

    /**
     * Whether this camera can hand back a DNG at all.
     *
     * **Asked of the camera rather than assumed from the hardware.** RAW is an optional capability
     * and the front camera on a phone very often does not have it while the back one does, so this
     * is re-read on every bind and the setting reads "Unavailable" rather than failing at the
     * shutter — which is where a capability check that never happened always surfaces.
     */
    private val _negativeSupported = MutableStateFlow(false)
    val negativeSupported: StateFlow<Boolean> = _negativeSupported.asStateFlow()

    /**
     * The frame currently on the viewfinder, as a bitmap.
     *
     * This is the whole of `Screen` size: no `takePicture`, no sensor readout, no JPEG from the
     * ISP — just the pixels already on the panel. It is as instant as this app can be.
     *
     * It returns the **unfiltered** frame, because `TextureView.getBitmap` copies the camera's
     * surface and a `RenderEffect` is applied later, when the view is drawn. That is the useful
     * behaviour: the caller runs the same shader over it, at this size, in one small GPU pass —
     * so the photograph matches the viewfinder exactly without the filter being applied twice.
     */
    fun previewFrame(): Bitmap? = runCatching { previewView?.bitmap }.getOrNull()

    /**
     * How far a `Screen` grab has to be turned to come out upright, in degrees clockwise.
     *
     * The preview buffer is upright in the *device's* frame, so a photograph taken with the phone
     * held sideways would be saved with the world on its side. `ImageCapture` solves this with a
     * target rotation and EXIF; here the rotation has to be applied to the pixels.
     *
     * **The one part of this not checked on hardware.** If a `Screen` shot taken sideways comes
     * out rotated the wrong way, these three numbers are where to look.
     */
    fun previewRotationDegrees(): Int = when (lastRotation) {
        Surface.ROTATION_90 -> 270
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 90
        else -> 0
    }

    /**
     * True when the shutter can fire without the camera stopping to meter first.
     *
     * Two things have to be true: the buffer is warm, and the flash is not going to fire. **Auto flash is
     * the hidden second** of a slow shutter — the HAL runs a precapture metering sequence, and on a phone
     * that means a preflash, an exposure measurement and a wait, before the frame you asked for is even
     * begun. With the flash off there is nothing to meter.
     */
    fun canFireInstantly(): Boolean = zslWarm() && imageCapture?.flashMode == ImageCapture.FLASH_MODE_OFF

    fun setFlash(mode: FlashMode) {
        imageCapture?.flashMode = when (mode) {
            FlashMode.Off -> ImageCapture.FLASH_MODE_OFF
            FlashMode.On -> ImageCapture.FLASH_MODE_ON
            FlashMode.Auto -> ImageCapture.FLASH_MODE_AUTO
        }
    }

    /**
     * Build the use cases and bind them.
     *
     * The face-detect mode has to be decided *before* binding, because it is a capture
     * request option baked into the session. So the characteristics are read straight from
     * [CameraManager] for the camera CameraX is about to choose, rather than from the
     * bound camera. Post-bind the sensor orientation and active array are refreshed from
     * the camera actually in use, in case the guess picked a different physical sensor.
     */
    private fun rebind(flash: FlashMode) {
        val provider = provider ?: return
        val owner = owner ?: return
        val view = previewView ?: return

        val hw = readHardware(_lensFacing.value)
        sensorOrientation = hw.sensorOrientation
        activeArray = hw.activeArray
        faceDetectMode = hw.faceDetectMode
        _facesSupported.value = hw.faceDetectMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF

        // 4:3 is the sensor's own shape. Narrower frames are drawn as frame lines and
        // cropped at save time, which is what a camera with a fixed sensor actually does —
        // asking the camera for 16:9 would throw away pixels before you had decided.
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(selector)
            .setTargetRotation(Surface.ROTATION_0)

        // Only the face detector is switched on here, and deliberately nothing else.
        //
        // `CONTROL_SCENE_MODE_FACE_PRIORITY` looks like the right thing — it asks the HAL
        // itself to meter and focus on faces — but it requires `CONTROL_MODE` to be
        // `USE_SCENE_MODE`, which hands 3A to the scene profile and lets the HAL ignore the
        // AF regions CameraX sets. That would trade a working tap-to-focus and half press
        // for an opaque one, so face-priority AF is done here instead, from the boxes the
        // detector publishes, via `startFocusAndMetering`.
        Camera2Interop.Extender(previewBuilder).apply {
            if (_facesSupported.value) {
                setCaptureRequestOption(
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                    hw.faceDetectMode,
                )
            }
            setSessionCaptureCallback(resultCallback)
        }

        val preview = previewBuilder.build()
        this.preview = preview

        // **The single biggest thing between pressing the button and getting a photograph.**
        //
        // The LPIII's sensor is 50 megapixels, and left to itself CameraX asks for the largest
        // JPEG the camera will give. Reading out and encoding 8160 x 6144 costs the ISP the best
        // part of a second or two — which is exactly the "one to three seconds" every review of
        // this phone complains about — and then *this* app has to decode it again for a filter.
        //
        // Twelve megapixels is asked for instead. Nothing is lost that anyone can see: it is
        // still four times the pixels of the largest print you will make from a phone, and about
        // thirty times the panel it will be looked at on. What is gained is a shutter that
        // answers.
        // The size is a setting now, because it is the same question as how fast the shutter is.
        // Screen asks for the smallest the camera will give: that mode never takes a capture, but
        // the use case still has to be bound in case it is switched away from mid-session.
        val longEdge = if (photoSize.isPreviewGrab) 1600 else photoSize.longEdge
        val captureSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(longEdge, longEdge * 3 / 4),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()

        // **Zero shutter lag, second attempt, with the lesson from the first built in.**
        //
        // v1.8 asked for `CAPTURE_MODE_ZERO_SHUTTER_LAG` on the strength of CameraX documenting a silent
        // fallback where the hardware won't do it. The fallback covers *configuration*, not capture: this
        // camera accepted the mode, bound without complaint, and then failed every `takePicture` — a dead
        // shutter. What was missing was the reason: ZSL works by keeping a ring buffer of recent frames
        // and handing one back at the press, and the buffer is empty for the first second or so after
        // binding. Capture into an empty buffer fails.
        //
        // So it is asked for again, and guarded three ways: only in Simple, only after [ZSL_WARM_MS] of
        // the pipeline actually running, and if a capture ever fails the mode is abandoned for the rest of
        // the process and the shot is retried the ordinary way. A dead shutter is unacceptable; a shutter
        // that is early when it can be is worth having.
        // **Asked before the use case is built, because the format is built into it.**
        // `getCameraInfo` answers for a selector without binding anything, which is the only way
        // to know this in time: by the point there is a bound camera to ask, the `ImageCapture`
        // has already been configured. A camera that refuses the query at all is a camera with no
        // negative, which is the safe reading.
        val rawJpegSupported = runCatching {
            val info = provider.getCameraInfo(
                CameraSelector.Builder().requireLensFacing(_lensFacing.value).build(),
            )
            ImageCapture.getImageCaptureCapabilities(info)
                .supportedOutputFormats
                .contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        }.getOrDefault(false)
        _negativeSupported.value = rawJpegSupported

        // **Simple never shoots a negative, so turning one on must not cost Simple anything.**
        // An earlier version gated the ring buffer on `negative` alone, which meant switching RAW
        // on in Pro quietly took zero shutter lag away from a mode that was never going to write a
        // DNG. The format is asked for only where it is actually used.
        val wantsRaw = negative && rawJpegSupported && !mode.isSimple

        val zsl = mode.isSimple && zslAllowed
        val capture = ImageCapture.Builder()
            .setResolutionSelector(captureSelector)
            // **88 in Simple, 92 elsewhere.** JPEG encode is a real slice of the shutter on a 12MP frame
            // and quality is not linear in cost: the difference between 88 and 92 is a few percent of file
            // size and nothing a person can see on a 3.92" screen or a print, while the encoder does
            // measurably less work. Pro keeps 92, where somebody has asked for the best file.
            .setJpegQuality(if (mode.isSimple) 88 else 92)
            .setCaptureMode(
                if (zsl) {
                    ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                } else {
                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                },
            )
            // Whatever the phone's attitude was when the listener last spoke, so a rebind
            // mid-shoot doesn't silently reset the file's orientation to upright.
            .setTargetRotation(lastRotation)
            // **The negative, when the camera will give one.** `OUTPUT_FORMAT_RAW_JPEG` makes one
            // press produce two files from one exposure — the DNG and the ISP's JPEG — rather than
            // two captures a moment apart, which is the only version of this worth having.
            //
            // It is asked for only where it is supported, and support is a property of *this*
            // camera: the selfie sensor on this phone will very likely say no while the main one
            // says yes.
            .also { builder ->
                if (wantsRaw) builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            }
            .also { builder ->
                if (!mode.isSimple) return@also
                // **Measured: 1877 ms inside `takePicture`, 87 ms to save.** The time is entirely the
                // camera's, and this is the only place an app can reach into it. A still on a modern HAL is
                // not one exposure — it is a burst, stacked and denoised and sharpened, and every one of
                // those stages has a HIGH_QUALITY and a FAST setting. `CAPTURE_MODE_MINIMIZE_LATENCY` is
                // CameraX *asking* for the fast ones; these keys are the request itself, which is a
                // stronger statement and reaches HALs that ignore the hint.
                //
                // Pro is left alone: somebody there has asked for the best file the camera can make, and
                // waiting for it is the correct trade.
                Camera2Interop.Extender(builder).apply {
                    setCaptureRequestOption(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        CameraMetadata.NOISE_REDUCTION_MODE_FAST,
                    )
                    setCaptureRequestOption(
                        CaptureRequest.EDGE_MODE,
                        CameraMetadata.EDGE_MODE_FAST,
                    )
                    setCaptureRequestOption(
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                        CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST,
                    )
                    setCaptureRequestOption(
                        CaptureRequest.TONEMAP_MODE,
                        CameraMetadata.TONEMAP_MODE_FAST,
                    )
                    // Preview-intent on the still request is the blunt version of the same idea: it tells
                    // the HAL this frame does not need the treatment a photograph gets.
                    setCaptureRequestOption(
                        CaptureRequest.CONTROL_CAPTURE_INTENT,
                        CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW,
                    )
                }
            }
            .build()
        this.imageCapture = capture
        zslActive = zsl
        boundAt = System.currentTimeMillis()
        setFlash(flash)

        // HD rather than the highest the sensor will give: a 50MP phone will happily offer 4K,
        // and 4K on a 3.92" screen is a minute a gigabyte for a picture nothing here can show.
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .build()
        val video = VideoCapture.withOutput(recorder).also { it.targetRotation = lastRotation }
        this.videoCapture = video

        // **The live-stream experiment is gone, and this is the note it leaves behind.**
        //
        // v2.21 bound a high-resolution `ImageAnalysis` in place of the stills unit, on the reasoning that a
        // continuous stream makes a shutter free. The reasoning was sound and the camera would not do it:
        // no frame ever arrived, at 12MP or at 5MP, with the converter fixed and the failure logged. This
        // phone will not give a usable second stream, and three releases of chasing it was two too many.
        //
        // So Simple uses the stills pipeline like everything else. What survives from the attempt is
        // everything that measured: the fast post-processing keys, zero shutter lag once the buffer is warm,
        // no auto-flash metering, the save off the critical path, and the date printed afterwards. The
        // shutter is 1.8 s on this hardware, and the instant option below is the honest way around it.
        // **QR mode binds an `ImageAnalysis` where the other modes bind a shutter**, and it is built
        // only in that mode: an analysis stream is a second full-rate consumer of the ISP, and
        // leaving one attached in Photo would cost every shot for a feature nobody had switched on.
        //
        // 1280×720 rather than anything larger. A QR code is found from its three finder squares
        // and the modules between them, and at 720p a code filling a third of the frame is still
        // forty pixels across its smallest square — plenty. The cost is not the decode but the
        // copy: [QrAnalyzer] walks the whole Y plane per frame, and 12MP of that twenty times a
        // second would heat the phone to read a poster.
        //
        // `KEEP_ONLY_LATEST` is what makes a slow frame harmless. `TRY_HARDER` occasionally takes
        // longer than a frame interval; with the default blocking strategy that back-pressures the
        // camera and the *preview* stutters, which reads as the viewfinder breaking when you point
        // it at something busy. Dropping the frames nobody will miss costs nothing — the next one
        // is 33 ms away and the code has not moved.
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        val analysis = if (mode.isScan) {
            ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { unit ->
                    unit.setAnalyzer(captureExecutor, QrAnalyzer { text -> onCode?.invoke(text) })
                    imageAnalysis = unit
                }
        } else {
            null
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(_lensFacing.value)
            .build()

        runCatching {
            // Down before anything is torn down, not after it is built. A shutter press that lands
            // between the unbind and the bind would otherwise see a stale `ready` and fire into a
            // use case with no camera behind it.
            _ready.value = false
            // A rebind is a new framing — a flipped lens, a changed mode — and a remembered tap is
            // in the old framing's coordinates. Aimed at nothing now.
            tapPoint = null
            provider.unbindAll()
            preview.setSurfaceProvider(view.surfaceProvider)
            // One use case beside the preview, whichever mode it is. Preview + capture + video is
            // only guaranteed on LEVEL_3 hardware, and the same caution applies to the analyser.
            val second = when {
                mode == CaptureMode.Video -> video
                analysis != null -> analysis
                else -> capture
            }
            val bound = provider.bindToLifecycle(owner, cameraSelector, preview, second)
            camera = bound
            readCameraLimits(bound)
            // A rebind builds a new session, and session capture options do not survive one. Any
            // manual exposure or flat profile in force has to be put back or it silently reverts
            // to auto with the readout still claiming otherwise.
            applyCaptureOptions()
            _ready.value = true
        }.onFailure {
            Log.e(TAG, "bind failed", it)
            _ready.value = false
        }
    }

    private class Hardware(
        val sensorOrientation: Int,
        val activeArray: Rect?,
        val faceDetectMode: Int,
    )

    @SuppressLint("MissingPermission")
    private fun readHardware(facing: Int): Hardware {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return Hardware(90, null, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)
        val want = if (facing == CameraSelector.LENS_FACING_FRONT) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return runCatching {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
            } ?: return@runCatching Hardware(90, null, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)
            val ch = manager.getCameraCharacteristics(id)
            val modes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
                ?.toList().orEmpty()
            // SIMPLE gives rectangles, FULL adds landmarks and ids we don't need. Prefer
            // SIMPLE: it is the cheaper pipeline and far more widely implemented.
            val mode = when {
                modes.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) ->
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
                modes.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) ->
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
                else -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
            }
            // **The lens, so the focus distances are this camera's rather than another phone's.**
            // Hyperfocal falls out of focal length, aperture and sensor size; hard-coding it is
            // right for one device and an assertion everywhere else, and plainly wrong on the
            // selfie camera, which is a different lens.
            focalLengthMm =
                ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            aperture =
                ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f
            ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let {
                sensorWidthMm = it.width
                sensorHeightMm = it.height
            }
            // Minimum focus distance is reported in diopters, so the largest value is the closest
            // the lens will go. Zero means a fixed-focus lens, where there is nothing to set.
            closestFocusM = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?.takeIf { it > 0f }
                ?.let { 1f / it }
                ?: 0.1f
            hyperfocalM = ZoneFocus.hyperfocalMetres(
                focalLengthMm,
                aperture,
                sensorWidthMm,
                sensorHeightMm,
            )
            focusStops = ZoneFocus.stops(hyperfocalM, closestFocusM)
            focusIndex = focusIndex.coerceIn(0, focusStops.lastIndex)

            // The manual ranges, read once per bind. Asked of the camera rather than assumed:
            // a request outside them is refused outright, which on the phone reads as a shutter
            // that did nothing rather than as an exposure that was out of reach.
            ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                shutterRange = it.lower..it.upper
            }
            ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                isoRange = it.lower..it.upper
                sensorMaxIso = it.upper
            }
            // The boosted ceiling, where the hardware offers one: past the sensor's own maximum,
            // sensitivity is gain applied after the raw readout rather than at it.
            ch.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)?.let { boost ->
                isoRange = isoRange.first..(sensorMaxIso.toLong() * boost.upper / 100)
                    .toInt()
                    .coerceAtLeast(sensorMaxIso)
            }
            Hardware(
                sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                faceDetectMode = mode,
            )
        }.getOrElse { Hardware(90, null, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF) }
    }

    private fun readCameraLimits(bound: Camera) {
        val info = bound.cameraInfo
        _maxZoom.value = info.zoomState.value?.maxZoomRatio ?: 1f

        // **1x, said rather than asked.** This used to read the ratio back out of `zoomState`, which
        // is a `LiveData` on a `CameraInfo` that survives the unbind — so for a moment after a rebind
        // it still reports the ratio from before, while the control underneath has already gone back
        // to wide. That was the `3.5x` left sitting in the corner of an unzoomed viewfinder every
        // time you came back from the roll: the lens was at 1x, the label was reading a stale
        // `LiveData`, and nothing ever wrote over it because nothing else touches zoom until you do.
        //
        // Every path through here is one where losing the zoom is expected anyway — a resume, a lens
        // swap, a mode change, a size change — so rather than trying to guess the truth, set it. The
        // ratio is pushed at the control as well as into the state, so the two cannot disagree even
        // if some future device does hold its zoom across a rebind.
        _zoom.value = 1f
        runCatching { bound.cameraControl.setZoomRatio(1f) }
        val exposure = info.exposureState
        if (exposure.isExposureCompensationSupported) {
            val range = exposure.exposureCompensationRange
            _evRange.value = range.lower..range.upper
            _evStep.value = exposure.exposureCompensationStep.toFloat()
            _ev.value = exposure.exposureCompensationIndex
        } else {
            _evRange.value = 0..0
        }
        runCatching {
            val ch = Camera2CameraInfo.from(info)
            ch.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION)
                ?.let { sensorOrientation = it }
            ch.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?.let { activeArray = it }
        }
    }

    /* ---------------- capture results: faces and AF ---------------- */

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            readAf(result)
            readFaces(result)
            readMeter(result)
        }
    }

    /**
     * What the meter settled on, kept for the moment somebody takes hold of the exposure.
     *
     * **This is how a priority mode exists at all.** Camera2 has no half-manual auto-exposure, so
     * holding the shutter while the camera picks the sensitivity means knowing what the camera
     * *would* have picked — and the only place that number exists is the capture result. Read
     * continuously while the meter is in charge and frozen the moment it is not, so the pair being
     * balanced against is the last one the scene actually produced.
     */
    private fun readMeter(result: TotalCaptureResult) {
        if (_exposureMode.value.manualAe) return
        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { meteredShutterNanos = it }
        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { meteredIso = it }
    }

    private fun readAf(result: TotalCaptureResult) {
        val state = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
        val mapped = when (state) {
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
            -> AfState.Scanning

            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            -> AfState.Locked

            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> AfState.Failed
            else -> AfState.Idle
        }
        // A passive lock with nothing requested is the camera idling in continuous AF; it
        // shouldn't light the bracket, or the viewfinder is permanently claiming success.
        if (mapped == AfState.Locked && !focusHeld && _afState.value == AfState.Idle) return
        _afState.value = mapped

        // Only a focus run somebody asked for gets to make a noise.
        if (awaitingFocus && (mapped == AfState.Locked || mapped == AfState.Failed)) {
            awaitingFocus = false
            _focusOutcome.tryEmit(mapped == AfState.Locked)
        }
    }

    private fun readFaces(result: TotalCaptureResult) {
        if (!_facesSupported.value) return
        val now = System.currentTimeMillis()
        // The camera reports at frame rate. Half of that is smoother than the eye needs and
        // spares the overlay two thirds of its recompositions.
        if (now - lastFacePublish < FACE_PUBLISH_MS) return
        lastFacePublish = now

        val detected: Array<Face> = result.get(CaptureResult.STATISTICS_FACES) ?: emptyArray()
        if (detected.isEmpty()) {
            if (_faces.value.isNotEmpty()) _faces.value = emptyList()
            return
        }
        val crop = result.get(CaptureResult.SCALER_CROP_REGION) ?: activeArray ?: return
        val resolution = preview?.resolutionInfo ?: return
        val vw = viewWidth
        val vh = viewHeight
        if (vw == 0 || vh == 0) return
        val mirrored = _lensFacing.value == CameraSelector.LENS_FACING_FRONT

        val boxes = detected.mapNotNull { face ->
            val r = face.bounds
            FaceMapper.toView(
                id = face.id,
                score = face.score,
                sensorRect = intArrayOf(r.left, r.top, r.right, r.bottom),
                cropRect = intArrayOf(crop.left, crop.top, crop.right, crop.bottom),
                rotationDegrees = resolution.rotationDegrees,
                bufferWidth = resolution.resolution.width,
                bufferHeight = resolution.resolution.height,
                mirrored = mirrored,
                viewWidth = vw,
                viewHeight = vh,
            )
        }
        _faces.value = boxes
    }

    /* ---------------- focus ---------------- */

    /** The half press. Focus and meter, and hold it there until the button comes back up. */
    /**
     * The first detent: focus, meter, and **hold both**, which is the preparation the shutter needs.
     *
     * `disableAutoCancel` on the metering action is the load-bearing part. With AF and AE converged and
     * *locked*, `takePicture` has nothing left to do before it can begin the frame it was asked for — no
     * focus sweep, no exposure hunt, no precapture. Half-pressing and waiting for the buzz is the
     * difference between a shutter that answers and one that thinks first, and it is why this camera has
     * two detents at all.
     */
    /**
     * Where the finger last said to focus, and when it said it.
     *
     * **The half press used to override the tap, which is backwards.** Tap the subject at the edge
     * of the frame, half-press to lock — and the lens snapped back to the centre, because
     * [halfPress] only ever knew about faces and the middle. The tap is the most explicit
     * instruction this camera receives; automation must defer to it. So the point is kept, and a
     * half press within [TAP_MEMORY_MS] locks *there*.
     *
     * It expires, because the box on screen does: a tap from three minutes ago points at a scene
     * that has moved on, and honouring it would be a half press that focuses somewhere invisible.
     * And it is cleared on every rebind — a lens flip or a mode change is a new framing, and view
     * coordinates from the old one are aimed at nothing in particular.
     */
    @Volatile private var tapPoint: Triple<Float, Float, Long>? = null

    /** A finger on the viewfinder: focus there, and remember there. */
    fun tapFocus(x: Float, y: Float) {
        tapPoint = Triple(x, y, System.currentTimeMillis())
        focusAt(x, y, lock = false)
    }

    fun halfPress() {
        // The order is the policy: the person's tap, then the person's face-priority setting,
        // then the centre. Most explicit instruction first.
        val tapped = tapPoint?.takeIf { System.currentTimeMillis() - it.third < TAP_MEMORY_MS }
        val target = if (facePriority) {
            FaceMapper.priority(_faces.value, viewWidth, viewHeight)
        } else {
            null
        }
        focusHeld = true
        when {
            tapped != null -> focusAt(tapped.first, tapped.second, lock = true)
            target != null -> focusAt(target.centreX, target.centreY, lock = true)
            else -> focusAt(viewWidth * 0.5f, viewHeight * 0.5f, lock = true)
        }
    }

    /** Tap to focus, or the half press. [lock] disables the camera's own auto-cancel. */
    fun focusAt(x: Float, y: Float, lock: Boolean) {
        val control = camera?.cameraControl ?: return
        val view = previewView ?: return
        val point = runCatching { view.meteringPointFactory.createPoint(x, y) }.getOrNull()
            ?: return
        _focusPoint.value = x to y
        _afState.value = AfState.Scanning
        awaitingFocus = true
        val builder = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
        if (lock) {
            builder.disableAutoCancel()
        } else {
            builder.setAutoCancelDuration(TAP_FOCUS_HOLD_MS, TimeUnit.MILLISECONDS)
        }
        runCatching { control.startFocusAndMetering(builder.build()) }
    }

    /** The button came back up. Let the camera go back to deciding for itself. */
    fun releaseFocus() {
        focusHeld = false
        awaitingFocus = false
        if (afMode == AfMode.Continuous) {
            runCatching { camera?.cameraControl?.cancelFocusAndMetering() }
            _afState.value = AfState.Idle
            _focusPoint.value = null
        }
    }

    /**
     * Continuous AF, driven from the face boxes.
     *
     * Called by the UI on each new face list rather than on a timer, so a still subject
     * costs nothing. [FaceMapper.movedEnoughToRefocus] is the whole policy.
     */
    fun trackFaces(previous: FaceBox?, current: FaceBox?) {
        if (afMode != AfMode.Continuous || focusHeld) return
        if (!FaceMapper.movedEnoughToRefocus(previous, current, viewWidth, viewHeight)) return
        val target = current ?: return
        focusAt(target.centreX, target.centreY, lock = false)
    }

    /* ---------------- manual exposure and the flat profile ---------------- */

    /**
     * What the photographer is holding, and the two halves they are holding it at.
     *
     * Stored as indices into [Exposure.SHUTTER_STOPS] and [Exposure.ISO_STOPS] rather than as
     * values, so a dial cannot land between stops and so a stale index from an older build clamps
     * instead of throwing.
     */
    private val _exposureMode = MutableStateFlow(ExposureMode.Auto)
    val exposureMode: StateFlow<ExposureMode> = _exposureMode.asStateFlow()

    private var shutterIndex = Exposure.SHUTTER_STOPS.indexOf(60L).coerceAtLeast(0)
    private var isoIndex = Exposure.ISO_STOPS.indexOf(400).coerceAtLeast(0)

    /** What the camera is actually exposing at, for the readout. Empty until a frame arrives. */
    private val _exposureLabel = MutableStateFlow("")
    val exposureLabel: StateFlow<String> = _exposureLabel.asStateFlow()

    /** False when the pair asked for cannot be delivered — said out loud rather than taken. */
    private val _exposureReachable = MutableStateFlow(true)
    val exposureReachable: StateFlow<Boolean> = _exposureReachable.asStateFlow()

    /** The last pair the meter settled on, so leaving Auto starts where the meter was. */
    @Volatile private var meteredShutterNanos: Long = Exposure.stopToNanos(60)

    @Volatile private var meteredIso: Int = 400

    private var shutterRange: LongRange = 1_000L..100_000_000L
    private var isoRange: IntRange = 50..3200
    private var sensorMaxIso: Int = 3200

    /**
     * The flat capture profile.
     *
     * **What Zero does to every photograph, offered as a choice and pointed at the file rather
     * than the viewfinder.** Noise reduction, edge enhancement and the tone curve are the ISP
     * deciding what a photograph should look like; turning them off gives back a frame that has
     * been demosaiced and white-balanced and nothing else — flat, low contrast, and far better to
     * grade than something already contrast-stretched.
     *
     * It also makes the eighteen filters better, which is the part no camera without them can
     * have: a shader currently works on an image the ISP has already S-curved, so Film and Dither
     * are grading a grade. Feeding them a linear frame is the difference.
     */
    private val _flat = MutableStateFlow(false)
    val flat: StateFlow<Boolean> = _flat.asStateFlow()

    /**
     * Zone focus: the lens set by distance rather than by pointing it at something.
     *
     * Off by default. Autofocus is right for almost every photograph anyone takes on a phone; this
     * is for the one case it is wrong for, which is a street where the subject arrives after the
     * moment you would have had to focus.
     */
    private val _zoneFocus = MutableStateFlow(false)
    val zoneFocus: StateFlow<Boolean> = _zoneFocus.asStateFlow()

    private val _focusLabel = MutableStateFlow("")
    val focusLabel: StateFlow<String> = _focusLabel.asStateFlow()

    private var focusStops: List<Float> = listOf(ZoneFocus.DEFAULT_HYPERFOCAL_M)
    private var focusIndex = 0

    /** Read off the lens at each bind, and the reason the distances are not a constant. */
    private var focalLengthMm = 0f
    private var aperture = 0f
    private var sensorWidthMm = 0f
    private var sensorHeightMm = 0f
    private var closestFocusM = 0.1f
    private var hyperfocalM = ZoneFocus.DEFAULT_HYPERFOCAL_M

    /** Lens distortion correction, split out of the flat profile and on by default. */
    private val _lensCorrection = MutableStateFlow(true)
    val lensCorrection: StateFlow<Boolean> = _lensCorrection.asStateFlow()

    fun setExposureMode(mode: ExposureMode) {
        if (mode == _exposureMode.value) return
        // Leaving Auto starts at the stop nearest whatever the meter had settled on, so the
        // viewfinder does not jump three stops the moment you take hold of it.
        if (_exposureMode.value == ExposureMode.Auto) {
            shutterIndex = Exposure.nearestShutterIndex(meteredShutterNanos)
            isoIndex = Exposure.nearestIsoIndex(meteredIso)
        }
        _exposureMode.value = mode
        applyCaptureOptions()
    }

    fun stepShutter(notches: Int) {
        shutterIndex = Exposure.stepIndex(Exposure.SHUTTER_STOPS.size, shutterIndex, notches)
        applyCaptureOptions()
    }

    fun stepIso(notches: Int) {
        isoIndex = Exposure.stepIndex(Exposure.ISO_STOPS.size, isoIndex, notches)
        applyCaptureOptions()
    }

    fun setFlat(on: Boolean) {
        if (on == _flat.value) return
        _flat.value = on
        applyCaptureOptions()
    }

    fun setZoneFocus(on: Boolean) {
        if (on == _zoneFocus.value) return
        _zoneFocus.value = on
        if (on) {
            // Start where the lens already was, so switching in does not rack focus across the
            // room before you have touched anything.
            focusIndex = ZoneFocus.nearestStop(focusStops, hyperfocalM)
        }
        applyCaptureOptions()
        updateFocusLabel()
    }

    fun stepFocus(notches: Int) {
        if (!_zoneFocus.value) return
        focusIndex = Exposure.stepIndex(focusStops.size, focusIndex, notches)
        applyCaptureOptions()
        updateFocusLabel()
    }

    private fun updateFocusLabel() {
        if (!_zoneFocus.value) {
            _focusLabel.value = ""
            return
        }
        val metres = focusStops.getOrElse(focusIndex) { hyperfocalM }
        _focusLabel.value = ZoneFocus.label(
            metres,
            ZoneFocus.depthOfField(metres, hyperfocalM, focalLengthMm),
        )
    }

    fun setLensCorrection(on: Boolean) {
        if (on == _lensCorrection.value) return
        _lensCorrection.value = on
        applyCaptureOptions()
    }

    /**
     * Push the manual settings at the running camera.
     *
     * **Through `Camera2CameraControl`, not through the builder, and that is the whole reason this
     * is usable.** Capture request options set on an `ImageCapture.Builder` are fixed at bind
     * time, so a dial that changed them would rebind the camera on every notch — a black
     * viewfinder for a moment, several times a second. `Camera2CameraControl` sets them on the
     * session that is already running, which is what makes the wheel a dial rather than a menu.
     *
     * Everything is set together on every change, because these options *replace* the set rather
     * than merging into it: applying the shutter alone would silently drop the flat profile.
     */
    private fun applyCaptureOptions() {
        val control = camera?.cameraControl ?: return
        val mode = _exposureMode.value
        val builder = CaptureRequestOptions.Builder()

        if (mode.manualAe) {
            val (shutter, iso) = Exposure.rebalance(
                meteredShutterNanos = meteredShutterNanos,
                meteredIso = meteredIso,
                heldShutterNanos = if (mode.holdsShutter) Exposure.shutterAt(shutterIndex) else null,
                heldIso = if (mode.holdsIso) Exposure.isoAt(isoIndex) else null,
                shutterRange = shutterRange,
                isoRange = isoRange,
            )
            val (sensorIso, boost) = Exposure.splitIso(iso, sensorMaxIso)
            builder
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF,
                )
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, sensorIso)
            if (boost > 100) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,
                    boost,
                )
            }
            _exposureReachable.value = Exposure.withinRange(shutter, iso, shutterRange, isoRange)
            _exposureLabel.value = "${Exposure.shutterLabel(shutter)} · ${Exposure.isoLabel(iso)}"
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_ON,
            )
            _exposureReachable.value = true
            _exposureLabel.value = ""
        }

        if (_flat.value) {
            builder
                .setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CameraMetadata.NOISE_REDUCTION_MODE_OFF,
                )
                .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                .setCaptureRequestOption(
                    CaptureRequest.HOT_PIXEL_MODE,
                    CameraMetadata.HOT_PIXEL_MODE_OFF,
                )
                // A straight line from black to white: the tone curve, declined. White balance is
                // deliberately left alone — a frame with no white balance is not flat, it is green.
                .setCaptureRequestOption(
                    CaptureRequest.TONEMAP_MODE,
                    CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE,
                )
                .setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, LINEAR_TONEMAP)
        }

        // **Focus off the meter entirely, at a distance.** `CONTROL_AF_MODE_OFF` is what makes
        // `LENS_FOCUS_DISTANCE` mean anything — with autofocus running the lens simply moves back.
        // Infinity is zero diopters, which is why the conversion is its own function rather than a
        // division sitting here waiting to divide by zero.
        if (_zoneFocus.value) {
            val metres = focusStops.getOrElse(focusIndex) { hyperfocalM }
            builder
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_OFF,
                )
                .setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    ZoneFocus.metresToDiopters(metres),
                )
        }

        // Split out of the flat profile and defaulted on. Turning distortion correction off is a
        // choice about the lens, not about the tone curve, and on a wide phone lens leaving it off
        // is a bowed horizon in every frame.
        if (!_lensCorrection.value) {
            builder.setCaptureRequestOption(
                CaptureRequest.DISTORTION_CORRECTION_MODE,
                CameraMetadata.DISTORTION_CORRECTION_MODE_OFF,
            )
        }

        runCatching {
            Camera2CameraControl.from(control).setCaptureRequestOptions(builder.build())
        }.onFailure { Log.e(TAG, "could not apply capture options", it) }
    }

    /* ---------------- zoom, exposure, torch ---------------- */

    /**
     * One notch of the wheel.
     *
     * Geometric, not linear: a fixed ratio per notch means the framing changes by the same
     * proportion at 1x and at 8x, which is how a zoom ring feels. A fixed *increment* would
     * crawl at the wide end and leap at the long end.
     */
    fun stepZoom(notches: Int) {
        val control = camera?.cameraControl ?: return
        val max = _maxZoom.value
        val next = (_zoom.value * ZOOM_PER_NOTCH.pow(notches)).coerceIn(1f, max)
        _zoom.value = next
        runCatching { control.setZoomRatio(next) }
    }

    fun setZoom(ratio: Float) {
        val control = camera?.cameraControl ?: return
        val next = ratio.coerceIn(1f, _maxZoom.value)
        _zoom.value = next
        runCatching { control.setZoomRatio(next) }
    }

    fun stepEv(notches: Int) {
        val control = camera?.cameraControl ?: return
        val range = _evRange.value
        if (range.first == range.last) return
        val next = (_ev.value + notches).coerceIn(range.first, range.last)
        if (next == _ev.value) return
        _ev.value = next
        runCatching { control.setExposureCompensationIndex(next) }
    }

    /**
     * Exposure straight to an index, for a thumb dragged along the strip.
     *
     * The strip's whole reason for existing is that walking from -2 to +2 EV is twelve notches, and
     * twelve taps on a 3.92" screen is not a control. So this sets rather than accumulates, and
     * clamps to the range the camera reported rather than to a guess.
     */
    fun setEv(index: Int) {
        val control = camera?.cameraControl ?: return
        val range = _evRange.value
        if (range.first == range.last) return
        val next = index.coerceIn(range.first, range.last)
        if (next == _ev.value) return
        _ev.value = next
        runCatching { control.setExposureCompensationIndex(next) }
    }

    fun resetEv() {
        _ev.value = 0
        runCatching { camera?.cameraControl?.setExposureCompensationIndex(0) }
    }

    fun toggleTorch() {
        val control = camera?.cameraControl ?: return
        val has = camera?.cameraInfo?.hasFlashUnit() ?: false
        if (!has) return
        val next = !_torch.value
        _torch.value = next
        runCatching { control.enableTorch(next) }
    }

    fun hasFlash(): Boolean = camera?.cameraInfo?.hasFlashUnit() ?: false

    fun hasFrontCamera(): Boolean = runCatching {
        provider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }.getOrDefault(false)

    /* ---------------- capture ---------------- */

    /**
     * Take the photo.
     *
     * In-memory rather than to a file: the bytes go through a shader, get cropped to the
     * chosen frame, and may end up in a roll that isn't in the gallery yet, so writing them
     * to disk first would only be a file to clean up.
     */
    /**
     * The frame that is already there, as a JPEG.
     *
     * Null when Simple is not bound or no frame has arrived yet. The encode is ours rather than the ISP's —
     * `YuvImage.compressToJpeg` on the NV21 the analyser already produced — which costs a couple of hundred
     * milliseconds of CPU and, crucially, costs it *after* the shutter has returned.
     */
    fun grabLive(quality: Int): CapturedFrame? {
        val frame = latestFrame ?: return null
        val out = java.io.ByteArrayOutputStream(frame.width * frame.height / 4)
        val ok = runCatching {
            YuvImage(frame.nv21, ImageFormat.NV21, frame.width, frame.height, null)
                .compressToJpeg(Rect(0, 0, frame.width, frame.height), quality, out)
        }.getOrDefault(false)
        if (!ok) return null
        return CapturedFrame(
            jpeg = out.toByteArray(),
            rotationDegrees = frame.rotationDegrees,
            mirrored = _lensFacing.value == CameraSelector.LENS_FACING_FRONT,
        )
    }

    /** The size the live stream actually gave us, for the timing readout. Null until a frame arrives. */
    fun liveSize(): Pair<Int, Int>? = latestFrame?.let { it.width to it.height }

    suspend fun capture(): CapturedFrame = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
        // **`imageCapture` being non-null never meant it was bound.** `rebind` builds one on every
        // pass and only *binds* it when the mode is not Video, so in Video — and in the window
        // between `unbindAll` and `bindToLifecycle` on any rebind — this field holds a perfectly
        // good use case attached to no camera at all. `takePicture` on that throws from inside
        // CameraX, off the caller's stack, which is an uncatchable crash rather than a failed shot.
        // The three conditions below are the ones that make it real: a camera, a completed bind,
        // and a mode whose second use case is actually the stills unit.
        if (capture == null || camera == null || !_ready.value || mode == CaptureMode.Video) {
            cont.resumeWithException(IllegalStateException("camera not bound for stills"))
            return@suspendCancellableCoroutine
        }
        capture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val frame = runCatching {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        CapturedFrame(
                            jpeg = bytes,
                            rotationDegrees = image.imageInfo.rotationDegrees,
                            mirrored = _lensFacing.value == CameraSelector.LENS_FACING_FRONT,
                        )
                    }
                    image.close()
                    frame.fold({ if (cont.isActive) cont.resume(it) }, {
                        if (cont.isActive) cont.resumeWithException(it)
                    })
                }

                override fun onError(exception: ImageCaptureException) {
                    // **The ZSL fallback, in the one place that can know.** A failure here with the ring
                    // buffer in play is the v1.8 fault repeating, so the mode is abandoned for the process
                    // and the next bind goes back to minimise-latency. The caller still sees this failure;
                    // it is the following shot that is saved, which is the right trade against silently
                    // swallowing a real error.
                    if (zslActive) {
                        Log.w(TAG, "zero shutter lag capture failed; not asking again", exception)
                        zslAllowed = false
                        zslActive = false
                    }
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            },
        )
    }

    /**
     * What one RAW press produced.
     *
     * Both are nullable because the two saves are independent: the camera can land the negative and
     * fail the JPEG, or the other way round, and a press that produced *one* of the two files still
     * produced a photograph.
     */
    class NegativePair(val raw: Uri?, val jpeg: Uri?)

    /**
     * One exposure, two files, written by CameraX rather than by this app.
     *
     * **This is the one capture path where the bytes never pass through here.** Everything else in
     * Roll takes an in-memory frame and decides what to do with it, because that is what lets a
     * shader run before anything is written. A DNG cannot work that way: it is the sensor's readout
     * before demosaic, there is no bitmap to hand a shader, and building the file means the capture
     * metadata that only CameraX and the HAL hold. So the destinations are handed down and the
     * results come back as `Uri`s.
     *
     * **The two callbacks arrive in an order that is explicitly not guaranteed**, and they are told
     * apart by `OutputFileResults.getImageFormat()` rather than by which came first — the CameraX
     * documentation says so in as many words, and assuming otherwise gives you a DNG named `.jpg`
     * on some devices and not others. The coroutine resumes when both have landed, or when one has
     * errored, and never twice.
     */
    suspend fun captureNegative(
        rawOptions: ImageCapture.OutputFileOptions,
        jpegOptions: ImageCapture.OutputFileOptions,
    ): NegativePair = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
        if (capture == null || camera == null || !_ready.value || mode == CaptureMode.Video) {
            cont.resumeWithException(IllegalStateException("camera not bound for stills"))
            return@suspendCancellableCoroutine
        }

        var raw: Uri? = null
        var jpeg: Uri? = null
        var landed = 0
        val lock = Any()

        capture.takePicture(
            rawOptions,
            jpegOptions,
            captureExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    // The pair is assembled inside the lock and handed out whole. Reading the two
                    // fields afterwards would be a race with the other callback, which runs on the
                    // same executor but is not ordered against this one.
                    val pair = synchronized(lock) {
                        if (results.imageFormat == ImageFormat.RAW_SENSOR) {
                            raw = results.savedUri
                        } else {
                            jpeg = results.savedUri
                        }
                        landed += 1
                        if (landed == 2) NegativePair(raw, jpeg) else null
                    }
                    if (pair != null && cont.isActive) cont.resume(pair)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "negative capture failed", exception)
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            },
        )
    }

    /* ---------------- video ---------------- */

    /**
     * Start recording into `DCIM/Camera`.
     *
     * Audio only if the permission is there — a recording with no sound is worth far more than
     * a permission dialog in front of the thing you were trying to film, so the microphone is
     * asked for when you switch to video and never at the moment you press record.
     *
     * `MediaStoreOutputOptions` rather than a file: the same reasoning as photographs. CameraX
     * takes care of `IS_PENDING`, so a video killed halfway is not left half-visible in every
     * gallery on the phone.
     */
    fun startRecording(withAudio: Boolean): Boolean {
        val video = videoCapture ?: return false
        if (_recording.value) return false
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ROLL_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val options = MediaStoreOutputOptions
            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        return runCatching {
            var pending = video.output.prepareRecording(context, options)
            if (withAudio) pending = pending.withAudioEnabled()
            activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> _recording.value = true
                    is VideoRecordEvent.Finalize -> {
                        _recording.value = false
                        activeRecording = null
                        if (event.hasError()) Log.e(TAG, "recording failed: ${event.error}")
                    }
                }
            }
            true
        }.onFailure {
            Log.e(TAG, "couldn't start recording", it)
            _recording.value = false
        }.getOrDefault(false)
    }

    /**
     * Ask the recorder to stop. **Returns before the file exists.**
     *
     * `Recording.stop()` is a request: the muxer still has to flush, write the moov atom and clear
     * `IS_PENDING`, and only then does `VideoRecordEvent.Finalize` arrive and `recording` go false.
     * Anything that rebinds the camera has to wait for that — see [awaitIdle].
     *
     * The handle is **not** cleared here any more. It used to be, which meant that between the stop
     * and the finalize this class believed nothing was recording while CameraX believed something
     * was, and a `stop()` arriving twice in that window went to a reference nobody held.
     */
    fun stopRecording() {
        runCatching { activeRecording?.stop() }
    }

    /**
     * Wait for the recorder to finish writing, up to [timeoutMs].
     *
     * The timeout is not optional. `Finalize` is the only thing that lowers `recording`, and a
     * muxer that dies without emitting it would otherwise leave the camera pinned in video mode
     * for the rest of the process — a phone that will not go back to taking photographs is worse
     * than a clip that came out short, so after the timeout the flag is forced down and the rebind
     * happens anyway.
     */
    suspend fun awaitIdle(timeoutMs: Long = FINALIZE_TIMEOUT_MS) {
        if (!_recording.value) return
        val settled = withTimeoutOrNull(timeoutMs) {
            _recording.first { !it }
            true
        }
        if (settled == null) {
            Log.w(TAG, "recorder never finalized in ${timeoutMs}ms; forcing idle")
            _recording.value = false
            activeRecording = null
        }
    }

    fun evLabel(): String {
        val stops = _ev.value * _evStep.value
        if (stops == 0f) return "0.0"
        val rounded = (stops * 10).roundToInt() / 10f
        return (if (rounded > 0) "+" else "") + String.format("%.1f", rounded)
    }

    fun zoomLabel(): String {
        val z = _zoom.value
        return if (z < 9.95f) String.format("%.1fx", z) else String.format("%.0fx", z)
    }

    fun shutdown() {
        stopRecording()
        runCatching { orientation.disable() }
        runCatching { provider?.unbindAll() }
        captureExecutor.shutdown()
    }

    private fun Float.pow(n: Int): Float {
        var out = 1f
        repeat(kotlin.math.abs(n)) { out *= this }
        return if (n >= 0) out else 1f / out
    }

    private companion object {
        /**
         * How long a stop is given to become a file.
         *
         * A second of muxing is a very long clip on this hardware; two is the honest ceiling with
         * room for a phone that is busy writing something else at the same time.
         */
        const val FINALIZE_TIMEOUT_MS = 2_000L

        const val TAG = "CameraEngine"

        /** ~11% per notch: nine notches to double, so a full 8x rack is a deliberate spin. */
        const val ZOOM_PER_NOTCH = 1.08f

        /**
         * A straight line from black to white, per channel.
         *
         * Two points is a whole curve: `TonemapCurve` interpolates between them, so (0,0) to (1,1)
         * is the identity — the tone mapping stage present and declining to do anything, which is
         * the only way to switch it off. `TONEMAP_MODE_OFF` does not exist.
         */
        val LINEAR_TONEMAP = TonemapCurve(
            floatArrayOf(0f, 0f, 1f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f),
        )

        const val FACE_PUBLISH_MS = 66L

        /**
         * How long the pipeline must have been running before a zero-shutter-lag capture is attempted.
         *
         * A second and a half, which is a guess with a reason: the buffer is a handful of frames and the
         * preview runs at thirty, so it is full long before this — the margin is for a camera that starts
         * slowly, because the cost of being wrong is a failed shutter.
         */
        const val ZSL_WARM_MS = 1_500L
        const val TAP_FOCUS_HOLD_MS = 4_000L

        /**
         * How long a tap keeps steering the half press.
         *
         * Longer than [TAP_FOCUS_HOLD_MS] on purpose: the *metering* of a tap expires quickly so
         * the viewfinder goes back to being automatic, but the *intent* of the tap — that subject,
         * not the centre — outlives it. Twenty seconds is a person framing the same shot; past
         * that it is a different photograph.
         */
        const val TAP_MEMORY_MS = 20_000L
    }
}
