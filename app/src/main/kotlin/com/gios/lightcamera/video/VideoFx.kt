package com.gios.lightcamera.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import com.gios.lightcamera.filter.Adjust
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.time.LocalDateTime
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The looks, between the camera and the encoder.
 *
 * **Why the video was never filtered before, and why this is the only fix.** Every filter in this
 * app is a `RenderEffect` hung on the preview's `TextureView`. That filters what the *view* draws,
 * and the recorder never reads the view — it reads the camera. So Video forced the dial to plain,
 * because a filtered viewfinder over an unfiltered file is a camera lying about what it is
 * recording. The only place a look can reach the file is between the sensor and the encoder, and
 * the only door CameraX has there is a [CameraEffect] carrying a [SurfaceProcessor]: the camera
 * draws into a surface this class owns, and this class draws into the preview's surface and the
 * encoder's. Whatever is drawn is what is recorded. The viewfinder cannot disagree with the file,
 * because they are the same draw.
 *
 * One effect covers both outputs (`PREVIEW or VIDEO_CAPTURE`), which makes CameraX share a single
 * camera stream between them. **That changes what the camera is asked for**, and it is worth
 * saying why that might matter beyond the looks: the HAL now sees one stream going to a GPU
 * texture rather than one going to a video encoder, and it was the encoder-shaped stream set that
 * sent CamX down the EIS usecase with the Morpho node that stalls recordings on this phone (see
 * the FHD note in `CameraEngine`). Whether the vendor's usecase selector reads it that way is a
 * question for a device log, not for this comment.
 *
 * Everything GL happens on one thread, [thread]. CameraX calls the processor on [executor], which
 * posts onto that thread, so the GL context is only ever current in one place.
 *
 * Each frame:
 *
 *  1. The camera frame lands in an external OES texture.
 *  2. For **Preset with nothing set** it is drawn straight to each output — one textured quad per
 *     destination, the same work CameraX's own processor would do.
 *  3. For anything else it is first turned upright into `src` (the geometry is [FxGeometry]),
 *     optionally copied into the history ring, optionally run through the look's pre-pass, and then
 *     run through the look into `fx`. `src` and `fx` are each a pair of textures that swap every
 *     frame, which is how a look reads the last camera frame (`uLast`) and its own last output
 *     (`uPrev`) for nothing.
 *  4. `fx` is drawn to each output through the inverse of the upright turn and CameraX's own
 *     transform, stamped with the camera's timestamp so the encoder keeps sound and picture in step.
 *
 * **Failure is loud and it is survivable.** Anything that throws on the GL thread is reported once
 * through [onFault] and this processor falls back to drawing the camera straight through, so a
 * shader the driver rejects costs the look rather than the recording. The engine hears the fault
 * and stops binding the effect at all for the rest of the process.
 */
class VideoFx(
    /** Clockwise degrees the camera buffer needs to stand upright on the panel. */
    private val sensorRotation: () -> Int,
    /** Quarter turns from the panel to the world, frozen by the caller while a clip records. */
    private val turn: () -> Int,
    /** Called on every camera frame — the preview's heartbeat, for the watchdog. */
    private val onFrame: () -> Unit,
    /** Called once, on the GL thread, when this processor gives up on the looks. */
    private val onFault: (String, Throwable?) -> Unit,
) {

    private val thread = HandlerThread("RollFx").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { task ->
        if (!handler.post(task)) Log.w(TAG, "fx thread gone; dropped a task")
    }
    private val processor = Processor()
    private val released = AtomicBoolean(false)

    /** The effect to bind. One instance for the life of this object. */
    val effect: CameraEffect = RollEffect(executor, processor) { error ->
        Log.e(TAG, "CameraX reported an effect error", error)
        processor.fail("CameraX: ${error.message}", error)
    }

    @Volatile private var wanted: VideoLooks.Look = VideoLooks.plain

    /** Change the look. Takes effect on the next camera frame; never rebinds anything. */
    fun setLook(look: VideoLooks.Look) {
        wanted = look
    }

    /** Let the GL thread go. Safe to call twice and after a fault. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        handler.post {
            processor.teardown()
            thread.quitSafely()
        }
    }

    private class RollEffect(
        executor: Executor,
        processor: SurfaceProcessor,
        errors: Consumer<Throwable>,
    ) : CameraEffect(PREVIEW or VIDEO_CAPTURE, executor, processor, errors)

    /* ------------------------------------------------------------------ */

    private class Out(val surface: EGLSurface, val size: Size)

    private class Program(val id: Int, private val locations: HashMap<String, Int> = HashMap()) {
        fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    }

    private inner class Processor : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var config: EGLConfig? = null
        private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

        private var input: SurfaceTexture? = null
        private var inputTex = 0
        private var inputSize = Size(0, 0)
        private val outputs = LinkedHashMap<SurfaceOutput, Out>()

        private var quad: FloatBuffer? = null
        private var oesProgram: Program? = null
        private var copyProgram: Program? = null
        private val programs = HashMap<String, Program?>()

        private var fbo = 0
        private var workW = 0
        private var workH = 0
        private val srcTex = IntArray(2)
        private val fxTex = IntArray(2)
        private var cur = 0
        private var auxTex = 0
        private var auxW = 0
        private var auxH = 0
        private var histTex = 0
        private var histW = 0
        private var histH = 0
        private var histLayers = 0
        private var histHead = -1
        private var histLen = 0

        private var look: VideoLooks.Look = VideoLooks.plain
        private var lookStartNs = 0L
        private var lastNs = 0L
        private var fresh = true
        private var clock = floatArrayOf(2026f, 1f, 1f, 0f)
        private var clockAtNs = 0L

        /** Once true, only the straight-through draw runs. Never lowered. */
        private var broken = false
        private var faultReported = false

        private val stMatrix = FloatArray(16)
        private val outMatrix = FloatArray(16)

        /* ---------------- CameraX ---------------- */

        override fun onInputSurface(request: SurfaceRequest) {
            if (released.get()) {
                request.willNotProvideSurface()
                return
            }
            if (!ensureEgl()) {
                request.willNotProvideSurface()
                return
            }
            makeCurrent(pbuffer)
            val tex = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(tex[0])
            st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            val surface = Surface(st)
            // A rebind can hand over a new input before the old one's result arrives. Each input
            // owns its own texture so the two can never be attached to one name at once.
            input = st
            inputTex = tex[0]
            inputSize = request.resolution
            fresh = true
            st.setOnFrameAvailableListener(this, handler)
            // One line per bind, for the checklist: the looks assume the input arrives in the
            // sensor's orientation, which CameraX's own transform for this request says outright.
            request.setTransformationInfoListener(executor) { info ->
                Log.i(TAG, "input ${request.resolution} rotation=${info.rotationDegrees} sensor=${sensorRotation()} crop=${info.cropRect}")
            }
            request.provideSurface(surface, executor) {
                st.setOnFrameAvailableListener(null)
                st.release()
                surface.release()
                if (input === st) {
                    input = null
                    inputTex = 0
                }
                if (display != EGL14.EGL_NO_DISPLAY) {
                    makeCurrent(pbuffer)
                    GLES30.glDeleteTextures(1, tex, 0)
                }
            }
        }

        override fun onOutputSurface(output: SurfaceOutput) {
            if (released.get() || !ensureEgl()) {
                output.close()
                return
            }
            val surface = output.getSurface(executor) { _ ->
                // EVENT_REQUEST_CLOSE: CameraX is done with this destination — the recorder has
                // stopped, or the preview was rebound. Stop drawing into it before it goes.
                outputs.remove(output)?.let { destroySurface(it.surface) }
                output.close()
            }
            val egl = runCatching {
                EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            }.getOrNull()
            if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "could not make an EGL surface for ${output.size}: 0x${Integer.toHexString(EGL14.eglGetError())}")
                output.close()
                return
            }
            outputs[output] = Out(egl, output.size)
        }

        /* ---------------- frames ---------------- */

        override fun onFrameAvailable(st: SurfaceTexture) {
            if (released.get() || display == EGL14.EGL_NO_DISPLAY) return
            makeCurrent(pbuffer)
            // A frame from an input that has been replaced still has to be consumed, or its
            // producer stalls waiting for a buffer back.
            runCatching { st.updateTexImage() }.onFailure {
                Log.w(TAG, "updateTexImage failed", it)
                return
            }
            if (st !== input) return
            onFrame()
            if (outputs.isEmpty()) return
            st.getTransformMatrix(stMatrix)
            val ts = st.timestamp
            try {
                val next = wanted
                if (!broken && !next.plain) {
                    if (next !== look) switchTo(next, ts)
                    drawLook(ts)
                } else {
                    look = VideoLooks.plain
                    drawStraight(ts)
                }
            } catch (t: Throwable) {
                fail("render: ${t.message}", t)
                runCatching { drawStraight(ts) }
            }
        }

        private fun switchTo(next: VideoLooks.Look, ts: Long) {
            look = next
            lookStartNs = ts
            fresh = true
            if (next.history == 0) freeHistory()
            if (next.prePass == null) freeAux()
        }

        /** The camera, straight to every output: Preset with nothing set, and the fallback. */
        private fun drawStraight(ts: Long) {
            val prog = oesProgram ?: return
            for ((output, out) in outputs.entries.toList()) {
                if (!makeCurrent(out.surface)) continue
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, out.size.width, out.size.height)
                output.updateTransformMatrix(outMatrix, stMatrix)
                GLES30.glUseProgram(prog.id)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTex)
                GLES30.glUniform1i(prog.loc("uTex"), 0)
                GLES30.glUniformMatrix4fv(prog.loc("uMatrix"), 1, false, outMatrix, 0)
                drawQuad(prog)
                present(out, ts)
            }
            makeCurrent(pbuffer)
        }

        private fun drawLook(ts: Long) {
            val oes = oesProgram ?: return drawStraight(ts)
            val copy = copyProgram ?: return drawStraight(ts)
            val main = program(look.glsl) ?: return fail("look ${look.id} did not compile", null).also { drawStraight(ts) }
            val pre = look.prePass?.let { program(it) ?: return fail("pre-pass of ${look.id} did not compile", null).also { drawStraight(ts) } }

            val rotation = sensorRotation()
            val (w, h) = FxGeometry.workingSize(inputSize.width, inputSize.height, rotation)
            if (w != workW || h != workH) allocateWork(w, h)
            val prev = 1 - cur

            val upright = FxGeometry.Affine.fromGl(stMatrix) * FxGeometry.uprightToBuffer(rotation)

            // 1. The camera frame, upright, into src[cur].
            renderInto(srcTex[cur], w, h)
            GLES30.glUseProgram(oes.id)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTex)
            GLES30.glUniform1i(oes.loc("uTex"), 0)
            GLES30.glUniformMatrix4fv(oes.loc("uMatrix"), 1, false, upright.toGl(), 0)
            drawQuad(oes)

            // 2. The past, for the looks made of it.
            if (look.history > 0) pushHistory(copy, w, h)

            // 3. The look's own first pass, at a fraction of the size.
            if (pre != null) {
                val aw = maxOf(1, w / look.prePassDiv)
                val ah = maxOf(1, h / look.prePassDiv)
                if (aw != auxW || ah != auxH || auxTex == 0) allocateAux(aw, ah)
                renderInto(auxTex, aw, ah)
                GLES30.glUseProgram(pre.id)
                bind2d(0, srcTex[cur], pre.loc("uSrc"))
                bind2d(1, if (fresh) srcTex[cur] else srcTex[prev], pre.loc("uLast"))
                GLES30.glUniform2f(pre.loc("size"), w.toFloat(), h.toFloat())
                GLES30.glUniform2f(pre.loc("auxSize"), aw.toFloat(), ah.toFloat())
                drawQuad(pre)
            }

            // 4. The look.
            renderInto(fxTex[cur], w, h)
            GLES30.glUseProgram(main.id)
            bind2d(0, srcTex[cur], main.loc("uSrc"))
            bind2d(1, if (fresh) srcTex[cur] else srcTex[prev], main.loc("uLast"))
            bind2d(2, if (fresh) srcTex[cur] else fxTex[prev], main.loc("uPrev"))
            bind2d(3, if (auxTex != 0) auxTex else srcTex[cur], main.loc("uAux"))
            GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, histTex)
            main.loc("uHist").let { if (it >= 0) GLES30.glUniform1i(it, 4) }
            setUniforms(main, w, h, ts)
            drawQuad(main)
            fresh = false
            lastNs = ts

            // 5. Out, the right way up for each destination.
            val back = FxGeometry.invert(upright)
            for ((output, out) in outputs.entries.toList()) {
                if (!makeCurrent(out.surface)) continue
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, out.size.width, out.size.height)
                output.updateTransformMatrix(outMatrix, stMatrix)
                val m = (back * FxGeometry.Affine.fromGl(outMatrix)).toGl()
                GLES30.glUseProgram(copy.id)
                bind2d(0, fxTex[cur], copy.loc("uTex"))
                GLES30.glUniformMatrix4fv(copy.loc("uMatrix"), 1, false, m, 0)
                drawQuad(copy)
                present(out, ts)
            }
            makeCurrent(pbuffer)
            cur = prev
        }

        private fun setUniforms(p: Program, w: Int, h: Int, ts: Long) {
            GLES30.glUniform2f(p.loc("size"), w.toFloat(), h.toFloat())
            GLES30.glUniform1f(p.loc("seed"), Random.nextFloat() * 1000f)
            val time = ((ts - lookStartNs).coerceAtLeast(0L)) / 1e9f
            val dt = if (fresh || lastNs == 0L) 1f / 30f else ((ts - lastNs) / 1e9f).coerceIn(0.001f, 0.25f)
            GLES30.glUniform1f(p.loc("time"), time)
            GLES30.glUniform1f(p.loc("dt"), dt)
            GLES30.glUniform1f(p.loc("turn"), (((turn() % 4) + 4) % 4).toFloat())
            GLES30.glUniform1f(p.loc("fresh"), if (fresh) 1f else 0f)
            GLES30.glUniform1f(p.loc("histHead"), histHead.toFloat())
            GLES30.glUniform1f(p.loc("histLen"), histLen.toFloat())
            GLES30.glUniform1f(p.loc("histSize"), maxOf(1, histLayers).toFloat())
            if (ts - clockAtNs > 500_000_000L || clockAtNs == 0L) {
                val now = LocalDateTime.now()
                clock = floatArrayOf(
                    now.year.toFloat(),
                    now.monthValue.toFloat(),
                    now.dayOfMonth.toFloat(),
                    now.toLocalTime().toSecondOfDay().toFloat(),
                )
                clockAtNs = ts
            }
            GLES30.glUniform4f(p.loc("uClock"), clock[0], clock[1], clock[2], clock[3])
            if (look.adjustable) {
                val g = look.grade
                GLES30.glUniform4f(
                    p.loc("gradeA"),
                    g.normalised(Adjust.Exposure), g.normalised(Adjust.Contrast),
                    g.normalised(Adjust.Highlights), g.normalised(Adjust.Shadows),
                )
                GLES30.glUniform4f(
                    p.loc("gradeB"),
                    g.normalised(Adjust.Vibrance), g.normalised(Adjust.Warmth),
                    g.normalised(Adjust.Tint), g.normalised(Adjust.Sharpness),
                )
                GLES30.glUniform4f(p.loc("gradeC"), g.normalised(Adjust.Grain), g.normalised(Adjust.Vignette), 0f, 0f)
            }
        }

        private fun present(out: Out, ts: Long) {
            // The camera's own clock, not the time of the swap: the encoder lines the audio up
            // against these, and a GPU that is a frame late would otherwise be a mouth out of sync.
            EGLExt.eglPresentationTimeANDROID(display, out.surface, ts)
            if (!EGL14.eglSwapBuffers(display, out.surface)) {
                Log.w(TAG, "swap failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            }
        }

        /* ---------------- targets ---------------- */

        private fun allocateWork(w: Int, h: Int) {
            deleteTextures(srcTex)
            deleteTextures(fxTex)
            for (i in 0..1) {
                srcTex[i] = texture2d(w, h, GLES30.GL_LINEAR)
                fxTex[i] = texture2d(w, h, GLES30.GL_LINEAR)
            }
            workW = w
            workH = h
            fresh = true
            // The ring is sized from the frame, so a new frame size means a new ring.
            freeHistory()
            freeAux()
        }

        private fun allocateAux(w: Int, h: Int) {
            freeAux()
            auxTex = texture2d(w, h, GLES30.GL_NEAREST)
            auxW = w
            auxH = h
        }

        private fun freeAux() {
            if (auxTex != 0) GLES30.glDeleteTextures(1, intArrayOf(auxTex), 0)
            auxTex = 0
            auxW = 0
            auxH = 0
        }

        private fun pushHistory(copy: Program, w: Int, h: Int) {
            val layers = look.history
            val (hw, hh) = FxGeometry.historySize(w, h, look.historyEdge)
            if (histTex == 0 || histLayers != layers || histW != hw || histH != hh) {
                freeHistory()
                val t = IntArray(1)
                GLES30.glGenTextures(1, t, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, t[0])
                GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_RGBA8, hw, hh, layers)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                histTex = t[0]
                histLayers = layers
                histW = hw
                histH = hh
                histHead = -1
                histLen = 0
            }
            val (head, len) = FxGeometry.pushHistory(histHead, histLen, histLayers)
            histHead = head
            histLen = len
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, histTex, 0, head)
            GLES30.glViewport(0, 0, hw, hh)
            GLES30.glUseProgram(copy.id)
            bind2d(0, srcTex[cur], copy.loc("uTex"))
            GLES30.glUniformMatrix4fv(copy.loc("uMatrix"), 1, false, FxGeometry.Affine.IDENTITY.toGl(), 0)
            drawQuad(copy)
        }

        private fun freeHistory() {
            if (histTex != 0) GLES30.glDeleteTextures(1, intArrayOf(histTex), 0)
            histTex = 0
            histLayers = 0
            histHead = -1
            histLen = 0
        }

        private fun renderInto(tex: Int, w: Int, h: Int) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
            GLES30.glViewport(0, 0, w, h)
        }

        private fun bind2d(unit: Int, tex: Int, location: Int) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            if (location >= 0) GLES30.glUniform1i(location, unit)
        }

        private fun texture2d(w: Int, h: Int, filter: Int): Int {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return t[0]
        }

        private fun deleteTextures(ids: IntArray) {
            val live = ids.filter { it != 0 }.toIntArray()
            if (live.isNotEmpty()) GLES30.glDeleteTextures(live.size, live, 0)
            ids.fill(0)
        }

        /* ---------------- programs ---------------- */

        private fun drawQuad(p: Program) {
            val buf = quad ?: return
            val aPos = GLES30.glGetAttribLocation(p.id, "aPos")
            val aUv = GLES30.glGetAttribLocation(p.id, "aUv")
            buf.position(0)
            GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, buf)
            GLES30.glEnableVertexAttribArray(aPos)
            if (aUv >= 0) {
                buf.position(2)
                GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 16, buf)
                GLES30.glEnableVertexAttribArray(aUv)
            }
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(aPos)
            if (aUv >= 0) GLES30.glDisableVertexAttribArray(aUv)
        }

        /** Compiled on first use and kept; a failure is kept too, as null, so it is not retried every frame. */
        private fun program(fragment: String): Program? =
            programs.getOrPut(fragment) { link(VERTEX, fragment) }

        private fun link(vertex: String, fragment: String): Program? {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vertex) ?: return null
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment) ?: run {
                GLES30.glDeleteShader(vs)
                return null
            }
            val id = GLES30.glCreateProgram()
            GLES30.glAttachShader(id, vs)
            GLES30.glAttachShader(id, fs)
            GLES30.glLinkProgram(id)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                Log.e(TAG, "link failed: ${GLES30.glGetProgramInfoLog(id)}")
                GLES30.glDeleteProgram(id)
                return null
            }
            return Program(id)
        }

        private fun compile(type: Int, source: String): Int? {
            val id = GLES30.glCreateShader(type)
            GLES30.glShaderSource(id, source)
            GLES30.glCompileShader(id)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                Log.e(TAG, "shader failed to compile: ${GLES30.glGetShaderInfoLog(id)}")
                GLES30.glDeleteShader(id)
                return null
            }
            return id
        }

        /* ---------------- EGL ---------------- */

        private fun ensureEgl(): Boolean {
            if (display != EGL14.EGL_NO_DISPLAY) return true
            return runCatching {
                val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(d != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
                val version = IntArray(2)
                check(EGL14.eglInitialize(d, version, 0, version, 1)) { "eglInitialize failed" }
                val attribs = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                    // The encoder's input surface refuses a config that is not marked recordable.
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                check(EGL14.eglChooseConfig(d, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                    "no recordable ES3 config"
                }
                val cfg = configs[0]!!
                val ctx = EGL14.eglCreateContext(
                    d, cfg, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
                )
                check(ctx != EGL14.EGL_NO_CONTEXT) { "no ES3 context" }
                val pb = EGL14.eglCreatePbufferSurface(
                    d, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
                )
                check(pb != EGL14.EGL_NO_SURFACE) { "no pbuffer" }
                display = d
                config = cfg
                context = ctx
                pbuffer = pb
                check(makeCurrent(pbuffer)) { "could not make the context current" }
                setUpGl()
                true
            }.getOrElse {
                Log.e(TAG, "EGL setup failed", it)
                fail("EGL: ${it.message}", it)
                false
            }
        }

        private fun setUpGl() {
            quad = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(QUAD)
                position(0)
            }
            val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
            oesProgram = if ("GL_OES_EGL_image_external_essl3" in extensions) {
                link(VERTEX, OES_FRAGMENT)
            } else {
                // The older extension only reaches GLSL ES 1.00, so the straight-through draw
                // falls back to that dialect. Every look still needs ES3, and says so if absent.
                link(VERTEX_100, OES_FRAGMENT_100)
            }
            copyProgram = link(VERTEX, COPY_FRAGMENT)
            check(oesProgram != null && copyProgram != null) { "the pass-through shaders did not compile" }
            val f = IntArray(1)
            GLES30.glGenFramebuffers(1, f, 0)
            fbo = f[0]
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        }

        private fun makeCurrent(surface: EGLSurface): Boolean =
            EGL14.eglMakeCurrent(display, surface, surface, context)

        private fun destroySurface(surface: EGLSurface) {
            if (display == EGL14.EGL_NO_DISPLAY) return
            makeCurrent(pbuffer)
            EGL14.eglDestroySurface(display, surface)
        }

        fun fail(why: String, error: Throwable?) {
            broken = true
            if (faultReported) return
            faultReported = true
            Log.e(TAG, "video looks off: $why", error)
            onFault(why, error)
        }

        fun teardown() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            makeCurrent(pbuffer)
            outputs.values.forEach { EGL14.eglDestroySurface(display, it.surface) }
            outputs.keys.forEach { runCatching { it.close() } }
            outputs.clear()
            input?.setOnFrameAvailableListener(null)
            deleteTextures(srcTex)
            deleteTextures(fxTex)
            freeAux()
            freeHistory()
            programs.values.filterNotNull().forEach { GLES30.glDeleteProgram(it.id) }
            programs.clear()
            oesProgram?.let { GLES30.glDeleteProgram(it.id) }
            copyProgram?.let { GLES30.glDeleteProgram(it.id) }
            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
    }

    private companion object {
        const val TAG = "VideoFx"

        /** Not in `EGLExt` on every API level's stubs; the value is fixed by the extension. */
        const val EGL_RECORDABLE_ANDROID = 0x3142

        /** A strip of two triangles over the whole target: position xy, then texture uv. */
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        const val VERTEX = """#version 300 es
in vec4 aPos;
in vec2 aUv;
uniform mat4 uMatrix;
out vec2 vUv;
void main() {
    gl_Position = aPos;
    vUv = (uMatrix * vec4(aUv, 0.0, 1.0)).xy;
}
"""

        const val OES_FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
in vec2 vUv;
out vec4 fragColor;
void main() { fragColor = vec4(texture(uTex, vUv).rgb, 1.0); }
"""

        const val VERTEX_100 = """
attribute vec4 aPos;
attribute vec2 aUv;
uniform mat4 uMatrix;
varying vec2 vUv;
void main() {
    gl_Position = aPos;
    vUv = (uMatrix * vec4(aUv, 0.0, 1.0)).xy;
}
"""

        const val OES_FRAGMENT_100 = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTex;
varying vec2 vUv;
void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0); }
"""

        const val COPY_FRAGMENT = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 fragColor;
void main() { fragColor = vec4(texture(uTex, vUv).rgb, 1.0); }
"""
    }
}
