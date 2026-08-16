package com.gios.lightcamera

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.hw.WheelBus
import com.gios.lightcamera.hw.LightControls
import com.gios.lightcamera.hw.ShutterRelease
import com.gios.lightcamera.report.ReportContext
import com.gios.lightcamera.report.Reports
import com.gios.lightcamera.report.Screenshot
import com.gios.lightcamera.report.ShakeDetector
import com.gios.lightcamera.report.Symptom
import com.gios.lightcamera.report.Trouble
import com.gios.lightcamera.ui.CameraViewModel
import com.gios.lightcamera.ui.ColorMode
import com.gios.lightcamera.ui.ReportChip
import com.gios.lightcamera.ui.ReportReason
import com.gios.lightcamera.ui.ReportSheet
import com.gios.lightcamera.ui.Shell
import com.gios.lightcamera.ui.lightInset
import com.gios.lightcamera.ui.theme.LightCameraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One activity, because a camera is one thing.
 *
 * It owns two responsibilities that can only live here:
 *
 *  - **The physical controls.** [dispatchKeyEvent] is the only place that sees the camera
 *    button and the wheel before the view hierarchy does. The two-stage release is a state
 *    machine ([ShutterRelease]) rather than a pair of key handlers, because the two keys
 *    arrive in an unpredictable order.
 *  - **Being the phone's camera.** Launched with `IMAGE_CAPTURE`, the app has to take one
 *    photograph, write it where the caller asked, and get out of the way. That is what makes
 *    this installable as the default camera rather than merely as an app with a viewfinder.
 */
/**
 * A report waiting to be offered. The screenshot is taken at the moment of the shake rather than
 * when the sheet asks for it — by then the sheet is what is on screen.
 */
private data class ReportRequest(
    val reason: ReportReason,
    val shot: Bitmap?,
    val failure: com.gios.lightcamera.report.Failure? = null,
)

class MainActivity : ComponentActivity() {

    private val wheel = WheelBus()
    private var controls: LightControls? = null
    private var viewModel: CameraViewModel? = null

    /** Raised by a shake, by a failure Roll noticed, or by a crash log from the last run. */
    private val reportRequest = MutableStateFlow<ReportRequest?>(null)

    /** True once the corner chip has been tapped. Ignoring the chip never gets here. */
    private val reportSheetOpen = MutableStateFlow(false)

    /** Null on a phone with no accelerometer, where the whole feature quietly does not exist. */
    private var shake: ShakeDetector? = null

    /**
     * A shake, caught. Take the picture first and ask afterwards — the chip is about to sit on
     * top of whatever it was that looked wrong.
     */
    private fun onShaken() {
        if (reportRequest.value != null) return
        shake?.stop()
        Screenshot.capture(window) { bitmap ->
            reportRequest.value = ReportRequest(ReportReason.Shaken, bitmap)
        }
    }

    /**
     * The accelerometer runs only while Roll is the app you are looking at.
     *
     * More load-bearing here than in the other apps: a camera is carried, pointed and moved for
     * a living, so the gesture has more chances to be misread than anywhere else. That is what
     * the four-second chip is for — being wrong has to be cheap.
     */
    override fun onResume() {
        super.onResume()
        if (reportRequest.value == null) shake?.start()
    }

    override fun onPause() {
        super.onPause()
        shake?.stop()
    }

    /**
     * Launched, or brought forward by the camera key.
     *
     * `singleTop` means the second press lands here rather than in [onCreate], with the activity still
     * showing whatever was on screen when you left. The viewfinder is what a camera button asks for.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel?.onCameraKeyLaunch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shake = ShakeDetector(this, ::onShaken).takeIf { it.available }
        // A crash log still on disk was never sent. RollApp installed the handler; this is the
        // other half, asking about what it caught. Only on a genuinely new launch.
        if (savedInstanceState == null && CrashLog.last(this) != null) {
            reportRequest.value = ReportRequest(ReportReason.Crashed, null)
        }

        // Edge to edge, and never dim while framing a shot: a camera that sleeps on a tripod
        // is a camera you stop using on a tripod.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // The bars are hidden, not just drawn behind. The stock camera's picture starts at the
        // very top edge of the panel, and on a 3.92" screen a status bar is about four percent
        // of the viewfinder spent telling you the time. Swipe from an edge to get them back.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val captureOutput = intentCaptureOutput()
        val isCaptureRequest = captureOutput != null || isCaptureAction()
        val plainCapture = isCaptureRequest && !intent.getBooleanExtra(EXTRA_ALLOW_FILTER, false)

        setContent {
            LightCameraTheme {
                val vm: CameraViewModel = viewModel()
                viewModel = vm

                LaunchedEffect(vm) {
                    vm.captureRequestOutput = captureOutput
                    if (plainCapture) vm.lockFilterPlain()
                    controls = LightControls(
                        activity = this@MainActivity,
                        wheel = wheel,
                        shutter = ShutterRelease(
                            onHalfPress = { vm.halfPress() },
                            onFullPress = { vm.shoot() },
                            onRelease = { vm.engine.releaseFocus() },
                        ),
                        // The map is read through the view model's prefs at the moment of the
                        // press, so a binding changed in settings is live on the next press.
                        pressFor = { vm.prefs.pressFor(it) },
                        onPress = { vm.press(it) },
                    )
                }

                // Somebody else's photograph. Hand it back and leave.
                LaunchedEffect(vm) {
                    vm.captureRequestDone.collect { ok ->
                        finishCaptureRequest(ok, captureOutput)
                    }
                }

                // Anything written while the phone was offline, or while the last build had no
                // key in it, goes out now.
                LaunchedEffect(Unit) { Reports.flush(this@MainActivity) }

                val reports = rememberCoroutineScope()
                val report by reportRequest.collectAsStateWithLifecycle()
                val sheetOpen by reportSheetOpen.collectAsStateWithLifecycle()

                // A failure Roll noticed on its own — a shutter that produced nothing, a filter
                // the GPU refused — offers itself rather than waiting to be shaken about.
                val trouble by Trouble.latest.collectAsStateWithLifecycle()
                LaunchedEffect(trouble) {
                    val failure = trouble ?: return@LaunchedEffect
                    Trouble.clear()
                    if (reportRequest.value != null) return@LaunchedEffect
                    shake?.stop()
                    Screenshot.capture(window) { bitmap ->
                        reportRequest.value = ReportRequest(ReportReason.Failed, bitmap, failure)
                    }
                }

                CompositionLocalProvider(LocalWheelBus provides wheel) {
                    Box(Modifier.fillMaxSize()) {
                        Shell(vm = vm, captureRequest = isCaptureRequest)

                        // Bottom-start, not bottom-end: the shutter and the album live on the
                        // right of the viewfinder chrome, and a chip over the shutter would be
                        // the one place it must never be.
                        report?.takeIf { !sheetOpen }?.let { pending ->
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = lightInset(), bottom = lightInset()),
                            ) {
                                ReportChip(
                                    reason = pending.reason,
                                    onOpen = { reportSheetOpen.value = true },
                                    onExpire = {
                                        // Silence is "not now": an unsent crash log stays on
                                        // disk for the next launch to offer again.
                                        reportRequest.value = null
                                        shake?.start()
                                    },
                                )
                            }
                        }
                    }
                }

                report?.takeIf { sheetOpen }?.let { pending ->
                    ReportSheet(
                        reason = pending.reason,
                        hasScreenshot = pending.shot != null,
                        failure = pending.failure?.what,
                        seedNote = pending.failure?.let { "Could not ${it.what}" }.orEmpty(),
                        onDismiss = {
                            // Cancelling an opened sheet discards a crash log, because you
                            // looked at it and decided. Letting the chip fade does not.
                            if (pending.reason == ReportReason.Crashed) CrashLog.clear(this@MainActivity)
                            reportSheetOpen.value = false
                            reportRequest.value = null
                            shake?.start()
                        },
                        onSend = { symptom, note, includeScreenshot ->
                            reportSheetOpen.value = false
                            reportRequest.value = null
                            shake?.start()
                            reports.launch {
                                withContext(Dispatchers.IO) {
                                    val crash = if (
                                        pending.reason == ReportReason.Crashed ||
                                        symptom == Symptom.Crashed
                                    ) {
                                        CrashLog.last(this@MainActivity)
                                    } else {
                                        null
                                    }
                                    Reports.enqueue(
                                        this@MainActivity,
                                        Reports.compose(
                                            context = this@MainActivity,
                                            symptom = symptom,
                                            note = note,
                                            screen = ReportContext.screen,
                                            crash = crash,
                                            shot = pending.shot
                                                ?.takeIf { includeScreenshot }
                                                ?.let { Screenshot.encode(it) },
                                            failure = pending.failure,
                                        ),
                                    )
                                    if (crash != null) CrashLog.clear(this@MainActivity)
                                }
                                Reports.flush(this@MainActivity)
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Greyscale comes back the moment the app is not in front, and colour returns if it comes
     * back with the viewfinder still open.
     *
     * The daltonizer is a display-wide setting, so leaving it lifted while the user is
     * somewhere else on the phone would quietly turn the whole of LightOS colour — which is
     * their setting to make, not ours. `holders` survives the stop, so this is only about the
     * foreground and not about closing anything.
     */
    override fun onStart() {
        super.onStart()
        ColorMode.onAppVisible(this)
    }

    override fun onStop() {
        super.onStop()
        ColorMode.onAppHidden(this)
    }

    /**
     * The wheel and the camera button.
     *
     * `DecorView` hands the event to the window callback — this — before walking the views,
     * so returning true here beats anything focused. Nothing else in the app listens for
     * keys, which is why there is no arbitration to do.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (controls?.dispatch(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Where an `IMAGE_CAPTURE` caller wants the photograph.
     *
     * `EXTRA_OUTPUT` is the documented way and the one every serious caller uses. A caller
     * that omits it is asking for a thumbnail in the result `Intent`, which this refuses:
     * a bitmap in an `Intent` extra has been a `TransactionTooLargeException` waiting to
     * happen since 2010, and the callers that rely on it are asking for a photograph they can
     * barely see.
     */
    private fun intentCaptureOutput(): Uri? {
        if (!isCaptureAction()) return null
        @Suppress("DEPRECATION")
        return intent?.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
    }

    private fun isCaptureAction(): Boolean = when (intent?.action) {
        MediaStore.ACTION_IMAGE_CAPTURE, "android.media.action.IMAGE_CAPTURE_SECURE" -> true
        else -> false
    }

    companion object {
        /**
         * Set true by an `IMAGE_CAPTURE` caller that wants the filter dial left live.
         *
         * The default is the other way round — a capture request is served plain — because a
         * filter is something the *user* chose for their own roll, and silently applying it to
         * a photograph another app asked for breaks that app in a way neither of them can see.
         * LightNotebook is the case that decided it: it hands a photographed page to Claude to
         * read, and a dithered page is illegible, so "Roll had Game Boy selected three days
         * ago" would surface as "the notebook can't read my handwriting".
         *
         * Opt-in rather than opt-out so a caller written before this existed gets the safe
         * behaviour, and so the interesting case has to say what it wants: attaching a
         * photograph to a note *should* offer the filters, and passes this.
         */
        const val EXTRA_ALLOW_FILTER = "com.gios.lightcamera.extra.ALLOW_FILTER"
    }

    private fun finishCaptureRequest(ok: Boolean, output: Uri?) {
        if (!ok) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val result = Intent()
        if (output != null) {
            result.data = output
            result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
