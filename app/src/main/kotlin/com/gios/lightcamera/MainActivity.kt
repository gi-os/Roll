package com.gios.lightcamera

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.report.ReportOverlay
import com.gios.light.common.hw.WheelBus
import com.gios.lightcamera.hw.LightControls
import com.gios.lightcamera.hw.WheelClickWitness
import com.gios.lightcamera.hw.ShutterRelease
import com.gios.lightcamera.ui.CameraViewModel
import com.gios.lightcamera.ui.ColorMode
import com.gios.lightcamera.ui.Shell
import com.gios.lightcamera.ui.lightInset
import com.gios.lightcamera.ui.theme.LightCameraTheme
import kotlinx.coroutines.launch

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
 * The half of the `IMAGE_CAPTURE` output check that needs nothing but the `Uri`.
 *
 * Split out from [MainActivity.intentCaptureOutput] so it can be tested on the JVM: the rest of
 * that function needs a live `Context` and a real caller on the other end of a binder call.
 */
internal object CaptureTarget {

    /**
     * `file:` is refused because a path is not a grant. A caller naming a filesystem location
     * is asking Roll to reach it under Roll's own identity, which is a different thing from
     * handing over access the caller holds; `content:` at least came through the framework,
     * where a grant can exist and be checked.
     *
     * An authority of ours is refused from the other side of the same argument. The `stars`
     * provider and the LightSync backup provider are Roll's private storage, and a capture
     * pointed at either is an outside app asking Roll to overwrite its own files. Both are
     * declared in the manifest as the application id plus a suffix, so testing the prefix
     * catches both — and catches any provider added later without anyone remembering this.
     */
    fun isWritableTarget(scheme: String?, authority: String?, packageName: String): Boolean {
        if (scheme != "content") return false
        if (authority.isNullOrEmpty()) return false
        return !authority.startsWith(packageName)
    }
}

class MainActivity : ComponentActivity() {

    private val wheel = WheelBus()
    private var controls: LightControls? = null
    private var viewModel: CameraViewModel? = null

    /**
     * The same view model the composition uses — one store, one key, one instance — reachable
     * from the places a composable cannot be, which today is the memory-trim callback.
     */
    private val activityVm: CameraViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        // The wheel-click readout counts from the last time the camera came to the front, because
        // "never, in the two minutes you have been in here" is the useful window and a timestamp
        // from a session yesterday answers nothing. See [WheelClickWitness].
        WheelClickWitness.watchFrom()
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
        // The low-memory killer clears its throat before it speaks; shedding the droppable
        // caches at the warning is how the black-screen class of death gets rarer. Registered
        // rather than overridden so it sits beside the rest of this method's wiring.
        registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    activityVm.shedMemory()
                }
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                activityVm.shedMemory()
            }
        })

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
                        // The map is read through the view model at the moment of the press, so
                        // a binding changed in settings is live on the next press — and so is the
                        // one case where the mapping is overridden by state rather than by
                        // preference. See `CameraViewModel.pressFor`.
                        pressFor = { vm.pressFor(it) },
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

                CompositionLocalProvider(LocalWheelBus provides wheel) {
                    Box(Modifier.fillMaxSize()) {
                        Shell(vm = vm, captureRequest = isCaptureRequest)

                        // **The whole reporting feature is the library's now, as one line.** Roll
                        // ran its own aging copies of the shake detector, the screenshot, the
                        // trouble collector, the chip and the sheet — and the shake path aged
                        // until it silently stopped offering, with nothing on the phone able to
                        // say whether the gesture, the sensor or the wiring had died. The
                        // overlay owns all of it, lifecycle-scoped: sensor on RESUME, off on
                        // PAUSE, crash offered once per process, Trouble collected, queue
                        // flushed. Bottom-start, as before — the shutter and the album live on
                        // the right, and a chip over the shutter is the one place it must never
                        // be.
                        ReportOverlay(inset = lightInset())
                    }
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
     *
     * A caller that names somewhere it cannot itself write is refused for a sharper reason.
     * This activity is exported and `showWhenLocked`, so the `Uri` arrives from an app that
     * needed no permission to send it, and the stream is opened later with *Roll's* identity —
     * which means anything Roll can write, an unprivileged app could otherwise overwrite just
     * by naming it here. The proof we ask for is the write grant the caller attached to the
     * `Intent`: the framework only lets an app pass on access it already holds, so a `Uri` that
     * arrived carrying `FLAG_GRANT_WRITE_URI_PERMISSION` is one the caller could have written
     * itself. Where the flag is absent we ask the framework about the *caller's* uid rather
     * than our own — `Binder.getCallingUid()` outside a binder transaction is this process, and
     * "may Roll write there" is the question that was never worth asking. [CaptureTarget] holds
     * the two shapes rejected before any of that.
     */
    private fun intentCaptureOutput(): Uri? {
        val request = intent ?: return null
        if (!isCaptureAction()) return null
        @Suppress("DEPRECATION")
        val uri = request.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT) ?: return null
        if (!CaptureTarget.isWritableTarget(uri.scheme, uri.authority, packageName)) return null
        if (request.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) return uri

        val caller = callingActivity?.packageName ?: callingPackage ?: return null
        val callerUid = runCatching {
            packageManager.getPackageUid(caller, PackageManager.PackageInfoFlags.of(0L))
        }.getOrNull() ?: return null
        val granted = checkUriPermission(
            uri,
            -1,
            callerUid,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) uri else null
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
